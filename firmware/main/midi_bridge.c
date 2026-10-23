/*
 * BLE-MIDI ↔ USB-MIDI 双向桥接。
 *
 * BLE host task 只把完整消息复制进队列；usb_task 是唯一的 USB 写入者；
 * ble_task 是唯一的 BLE 通知发送者。USB OUT 数据由 usb_task 解析为
 * 4 字节事件包后入队，ble_task 聚合后调用标准 BLE-MIDI 发送。
 */

#include "midi_bridge.h"

#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "esp_timer.h"

#include "ble_midi_link.h"
#include "usb_midi_device.h"

static const char *TAG = "midi_bridge";

#define BLE_TO_USB_DEPTH 256
#define USB_TO_BLE_DEPTH 256
#define MIDI_MSG_MAX_LEN 3
#define MIDI_BATCH_MAX 8
#define USB_READ_BATCH 16
#define USB_WRITE_RETRY_US 5000
#define LAT_BUCKET_US 100
#define LAT_BUCKET_COUNT 64

typedef struct {
    uint8_t cable_num;
    uint8_t len;
    uint8_t data[MIDI_MSG_MAX_LEN];
    int64_t rx_time_us;
} midi_msg_t;

static QueueHandle_t s_ble_to_usb;
static QueueHandle_t s_usb_to_ble[USB_MIDI_CABLE_COUNT];
static TaskHandle_t s_ble_task_handle;

static volatile uint32_t s_ble_rx_dropped;
static volatile uint32_t s_ble_to_usb_sent;
static volatile uint32_t s_ble_to_usb_dropped;
static volatile uint32_t s_usb_unsupported;
static volatile uint32_t s_usb_to_ble_sent;
static volatile uint32_t s_usb_to_ble_dropped;
static volatile uint32_t s_panic_failures;
static volatile uint32_t s_queue_flushes;

/*
 * F-01 / F-07 防卡音状态表：
 * - s_active_notes 记录每个 (cable, channel) 当前已发往 USB 且未释放的音符（128 bit = 2 × uint64_t）
 * - s_pending_note_off 记录任何因队列满或 USB FIFO 满而未能立即发出的 Note Off，在后续循环最优先补发
 */
static uint64_t s_active_notes[USB_MIDI_CABLE_COUNT][16][2];
static uint64_t s_pending_note_off[USB_MIDI_CABLE_COUNT][16][2];
/* 每个 cable 是否有挂起 Note Off；为空时 flush 直接返回，避免每 1 ms 空转 32 次临界区。 */
static volatile bool s_pending_note_off_any[USB_MIDI_CABLE_COUNT];
static portMUX_TYPE s_note_lock = portMUX_INITIALIZER_UNLOCKED;

/*
 * USB 侧影子状态：DAW 通常只在参数变化时回传 CC / Pitch Bend，而它初始化时的
 * 整包 dump 往往发生在手机还没连上 BLE 的时刻，会被直接丢弃。这里保留 DAW 发过的
 * 最后一个值，等端口绑定（Notify 打开）时一次性回放，让手机 UI 对齐 DAW。
 * 音符不回放：连接前就一直按住的音重放会造成意外触发。
 */
#define SHADOW_CCS 128
#define SHADOW_CHANNELS 16
static uint8_t s_shadow_cc[USB_MIDI_CABLE_COUNT][SHADOW_CHANNELS][SHADOW_CCS];
static uint8_t s_shadow_cc_valid[USB_MIDI_CABLE_COUNT][SHADOW_CHANNELS][SHADOW_CCS / 8];
static uint16_t s_shadow_pb[USB_MIDI_CABLE_COUNT][SHADOW_CHANNELS];
static uint16_t s_shadow_pb_valid[USB_MIDI_CABLE_COUNT];
/* usb_task 只置位，由 ble_task（唯一的 BLE 通知发送者）执行回放。 */
static volatile bool s_shadow_replay_pending[USB_MIDI_CABLE_COUNT];

/* 每个 cable 的绑定代次快照；代次变化说明该端口的归属手机变了。只在 usb_task 中读写。 */
static uint32_t s_cable_generation_seen[USB_MIDI_CABLE_COUNT];

/* 只在 usb_task 中更新/读取，无需加锁。 */
static uint32_t s_lat_hist[LAT_BUCKET_COUNT];
static uint32_t s_lat_count;
static uint32_t s_lat_max_us;

static inline bool is_note_on_msg(const uint8_t *data, uint8_t len)
{
    return len == 3 && (data[0] & 0xF0) == 0x90 && data[2] > 0;
}

static inline bool is_note_off_msg(const uint8_t *data, uint8_t len)
{
    return len == 3 && ((data[0] & 0xF0) == 0x80 || ((data[0] & 0xF0) == 0x90 && data[2] == 0));
}

static void mark_pending_note_off(uint8_t cable_num, uint8_t ch, uint8_t note)
{
    if (cable_num >= USB_MIDI_CABLE_COUNT || ch >= 16 || note >= 128) {
        return;
    }
    portENTER_CRITICAL(&s_note_lock);
    s_pending_note_off[cable_num][ch][note >> 6] |= (1ULL << (note & 63));
    s_pending_note_off_any[cable_num] = true;
    portEXIT_CRITICAL(&s_note_lock);
}

/** 丢弃某个 cable 的全部音符状态（端口换绑时使用：旧端口的音符必须由 panic 释放，不能错发到新手机）。 */
static void clear_cable_note_state(uint8_t cable_num)
{
    if (cable_num >= USB_MIDI_CABLE_COUNT) {
        return;
    }
    portENTER_CRITICAL(&s_note_lock);
    memset(s_active_notes[cable_num], 0, sizeof(s_active_notes[cable_num]));
    memset(s_pending_note_off[cable_num], 0, sizeof(s_pending_note_off[cable_num]));
    s_pending_note_off_any[cable_num] = false;
    portEXIT_CRITICAL(&s_note_lock);
}

static void clear_pending_note_off(uint8_t cable_num, uint8_t ch, uint8_t note)
{
    if (cable_num >= USB_MIDI_CABLE_COUNT || ch >= 16 || note >= 128) {
        return;
    }
    portENTER_CRITICAL(&s_note_lock);
    s_pending_note_off[cable_num][ch][note >> 6] &= ~(1ULL << (note & 63));
    portEXIT_CRITICAL(&s_note_lock);
}
/** 把一条消息完整写入指定 USB 虚拟端口；FIFO 满时最多重试 5 ms。 */
static bool usb_write_all(uint8_t cable_num, const uint8_t *data, uint8_t len)
{
    uint32_t sent = 0;
    const int64_t deadline = esp_timer_get_time() + USB_WRITE_RETRY_US;

    while (sent < len) {
        const uint32_t written = usb_midi_write(cable_num, data + sent, len - sent);
        sent += written;
        if (sent >= len) {
            return true;
        }
        if (esp_timer_get_time() >= deadline) {
            return false;
        }
        vTaskDelay(1);
    }
    return true;
}
/** 记录 BLE 回调入队到 USB 写完的延迟。 */
static void latency_record(int64_t rx_time_us)
{
    int64_t latency = esp_timer_get_time() - rx_time_us;
    if (latency < 0) {
        latency = 0;
    }

    const uint32_t us = (uint32_t)latency;
    uint32_t bucket = us / LAT_BUCKET_US;
    if (bucket >= LAT_BUCKET_COUNT) {
        bucket = LAT_BUCKET_COUNT - 1;
    }

    s_lat_hist[bucket]++;
    s_lat_count++;
    if (us > s_lat_max_us) {
        s_lat_max_us = us;
    }
}

/** 返回百分位所在桶的下界；最高桶报告 6300 µs。 */
static uint32_t latency_percentile(uint32_t percent)
{
    if (s_lat_count == 0) {
        return 0;
    }

    const uint64_t target = ((uint64_t)s_lat_count * percent + 99) / 100;
    uint64_t cumulative = 0;
    for (uint32_t i = 0; i < LAT_BUCKET_COUNT; i++) {
        cumulative += s_lat_hist[i];
        if (cumulative >= target) {
            return i == (LAT_BUCKET_COUNT - 1) ? 6300 : i * LAT_BUCKET_US;
        }
    }
    return 6300;
}

/** 尝试补发所有挂起的 Note Off；返回是否全部排空。 */
static void flush_pending_note_offs(uint8_t cable_num)
{
    if (cable_num >= USB_MIDI_CABLE_COUNT) {
        return;
    }
    if (!s_pending_note_off_any[cable_num] || !usb_midi_device_mounted()) {
        return;
    }

    /* 先落旗标再扫描：期间新置位的挂起位会在下一轮重新触发，不会漏掉。 */
    s_pending_note_off_any[cable_num] = false;

    for (uint8_t ch = 0; ch < 16; ch++) {
        for (uint8_t word = 0; word < 2; word++) {
            portENTER_CRITICAL(&s_note_lock);
            uint64_t mask = s_pending_note_off[cable_num][ch][word];
            portEXIT_CRITICAL(&s_note_lock);
            while (mask != 0) {
                const uint8_t bit = (uint8_t)__builtin_ctzll(mask);
                const uint8_t note = (uint8_t)((word << 6) | bit);
                const uint8_t msg[3] = { (uint8_t)(0x80 | ch), note, 0 };
                if (!usb_write_all(cable_num, msg, sizeof(msg))) {
                    s_pending_note_off_any[cable_num] = true;
                    return;
                }
                s_active_notes[cable_num][ch][word] &= ~(1ULL << bit);
                portENTER_CRITICAL(&s_note_lock);
                s_pending_note_off[cable_num][ch][word] &= ~(1ULL << bit);
                portEXIT_CRITICAL(&s_note_lock);
                s_ble_to_usb_sent++;
                mask &= (mask - 1);
            }
        }
    }
}

/** 向指定 USB 虚拟端口发送逐音 Note Off + 16 通道 All Notes Off / All Sound Off / Sustain Off。 */
static void usb_send_panic_cable(uint8_t cable_num)
{
    if (cable_num >= USB_MIDI_CABLE_COUNT) {
        return;
    }

    /* 先将所有活跃音符转为 pending_note_off，确保即使当前 FIFO 拥塞也会在后续补发 */
    portENTER_CRITICAL(&s_note_lock);
    for (uint8_t ch = 0; ch < 16; ch++) {
        s_pending_note_off[cable_num][ch][0] |= s_active_notes[cable_num][ch][0];
        s_pending_note_off[cable_num][ch][1] |= s_active_notes[cable_num][ch][1];
        s_active_notes[cable_num][ch][0] = 0;
        s_active_notes[cable_num][ch][1] = 0;
        if (s_pending_note_off[cable_num][ch][0] | s_pending_note_off[cable_num][ch][1]) {
            s_pending_note_off_any[cable_num] = true;
        }
    }
    portEXIT_CRITICAL(&s_note_lock);

    if (!usb_midi_device_mounted()) {
        return;
    }

    flush_pending_note_offs(cable_num);

    for (uint8_t ch = 0; ch < 16; ch++) {
        const uint8_t messages[3][3] = {
            { (uint8_t)(0xB0 | ch), 123, 0 }, /* All Notes Off */
            { (uint8_t)(0xB0 | ch), 120, 0 }, /* All Sound Off */
            { (uint8_t)(0xB0 | ch), 64, 0 },  /* Sustain Off */
        };
        for (size_t i = 0; i < 3; i++) {
            if (!usb_midi_device_mounted()) {
                return;
            }
            if (!usb_write_all(cable_num, messages[i], sizeof(messages[i]))) {
                s_panic_failures++;
            }
        }
    }
}

static void usb_send_panic_all(void)
{
    for (uint8_t c = 0; c < USB_MIDI_CABLE_COUNT; c++) {
        usb_send_panic_cable(c);
    }
}

/** 丢弃断线/挂载边界上的旧消息，避免重连后重放。 */
static void clear_queues(void)
{
    xQueueReset(s_ble_to_usb);
    for (uint8_t c = 0; c < USB_MIDI_CABLE_COUNT; c++) {
        xQueueReset(s_usb_to_ble[c]);
    }
    portENTER_CRITICAL(&s_note_lock);
    memset(s_active_notes, 0, sizeof(s_active_notes));
    memset(s_pending_note_off, 0, sizeof(s_pending_note_off));
    for (uint8_t c = 0; c < USB_MIDI_CABLE_COUNT; c++) {
        s_pending_note_off_any[c] = false;
    }
    portEXIT_CRITICAL(&s_note_lock);
    s_queue_flushes++;
}

/** USB-MIDI 4 字节事件包 → 完整 MIDI 消息；支持 Cable 0 与 Cable 1。 */
static bool usb_packet_to_message(const uint8_t pkt[4], midi_msg_t *out)
{
    const uint8_t cable_num = (uint8_t)(pkt[0] >> 4);
    if (cable_num >= USB_MIDI_CABLE_COUNT) {
        return false;
    }
    uint8_t len;
    switch (pkt[0] & 0x0F) {
    case 0x08: /* Note Off */
    case 0x09: /* Note On */
    case 0x0A: /* Poly Pressure */
    case 0x0B: /* Control Change */
    case 0x0E: /* Pitch Bend */
    case 0x03: /* System Common, 3 bytes */
        len = 3;
        break;
    case 0x0C: /* Program Change */
    case 0x0D: /* Channel Pressure */
    case 0x02: /* System Common, 2 bytes */
        len = 2;
        break;
    case 0x0F: /* Single byte, including real-time */
        len = 1;
        break;
    default:
        /* 0x00/0x01 保留，0x04..0x07 为 SysEx。 */
        return false;
    }

    out->cable_num = cable_num;
    out->len = len;
    memcpy(out->data, &pkt[1], len);
    out->rx_time_us = esp_timer_get_time();
    return true;
}

static void shadow_record(const midi_msg_t *msg)
{
    if (msg->len != 3 || msg->cable_num >= USB_MIDI_CABLE_COUNT) {
        return;
    }
    const uint8_t cable = msg->cable_num;
    const uint8_t status = msg->data[0];
    const uint8_t ch = status & 0x0F;
    if ((status & 0xF0) == 0xB0) {
        const uint8_t cc = msg->data[1] & 0x7F;
        s_shadow_cc[cable][ch][cc] = msg->data[2] & 0x7F;
        s_shadow_cc_valid[cable][ch][cc >> 3] |= (uint8_t)(1U << (cc & 7));
    } else if ((status & 0xF0) == 0xE0) {
        s_shadow_pb[cable][ch] =
            (uint16_t)((msg->data[1] & 0x7F) | ((msg->data[2] & 0x7F) << 7));
        s_shadow_pb_valid[cable] |= (uint16_t)(1U << ch);
    }
}

/** 只清有效性：USB 卸载意味着 DAW 可能重启，重枚举后它会重新 dump。 */
static void shadow_clear(void)
{
    memset(s_shadow_cc_valid, 0, sizeof(s_shadow_cc_valid));
    memset(s_shadow_pb_valid, 0, sizeof(s_shadow_pb_valid));
}

/** 按 (channel, CC) 升序回放某个 cable 的影子状态；14-bit 的 MSB/LSB 天然成对且顺序正确。 */
static void shadow_replay(uint8_t cable)
{
    if (cable >= USB_MIDI_CABLE_COUNT || !usb_midi_device_mounted()) {
        return;
    }
    uint8_t bufs[MIDI_BATCH_MAX][3];
    const uint8_t *msgs[MIDI_BATCH_MAX];
    uint8_t lens[MIDI_BATCH_MAX];
    uint8_t n = 0;
    uint32_t replayed = 0;

    for (uint8_t ch = 0; ch < SHADOW_CHANNELS; ch++) {
        for (uint8_t cc = 0; cc < SHADOW_CCS; cc++) {
            if ((s_shadow_cc_valid[cable][ch][cc >> 3] & (uint8_t)(1U << (cc & 7))) == 0) {
                continue;
            }
            bufs[n][0] = (uint8_t)(0xB0 | ch);
            bufs[n][1] = cc;
            bufs[n][2] = s_shadow_cc[cable][ch][cc];
            msgs[n] = bufs[n];
            lens[n] = 3;
            n++;
            if (n == MIDI_BATCH_MAX) {
                if (ble_midi_link_send(cable, msgs, lens, n) == ESP_OK) {
                    replayed += n;
                }
                n = 0;
                if (!usb_midi_device_mounted()) {
                    return;
                }
                vTaskDelay(pdMS_TO_TICKS(1));
            }
        }
        if ((s_shadow_pb_valid[cable] & (uint16_t)(1U << ch)) != 0) {
            const uint16_t value = s_shadow_pb[cable][ch];
            bufs[n][0] = (uint8_t)(0xE0 | ch);
            bufs[n][1] = (uint8_t)(value & 0x7F);
            bufs[n][2] = (uint8_t)((value >> 7) & 0x7F);
            msgs[n] = bufs[n];
            lens[n] = 3;
            n++;
            if (n == MIDI_BATCH_MAX) {
                if (ble_midi_link_send(cable, msgs, lens, n) == ESP_OK) {
                    replayed += n;
                }
                n = 0;
                vTaskDelay(pdMS_TO_TICKS(1));
            }
        }
    }
    if (n > 0 && ble_midi_link_send(cable, msgs, lens, n) == ESP_OK) {
        replayed += n;
    }
    ESP_LOGI(TAG, "shadow replay cable %u: %lu msgs", cable, (unsigned long)replayed);
}

static void usb_task(void *arg)
{
    (void)arg;
    int64_t next_log_us = esp_timer_get_time() + 5000000;
    bool was_port_ready[USB_MIDI_CABLE_COUNT] = { false, false };
    bool was_mounted = false;

    for (;;) {
        const bool mounted = usb_midi_device_mounted();

        /* 端口换绑：旧端口上不属于当前手机的余音必须就地释放，不能把 Note Off 错发到新手机。 */
        for (uint8_t c = 0; c < USB_MIDI_CABLE_COUNT; c++) {
            const uint32_t generation = ble_midi_link_cable_generation(c);
            if (generation != s_cable_generation_seen[c]) {
                s_cable_generation_seen[c] = generation;
                ESP_LOGW(TAG, "cable %u rebound; releasing notes on that port", c);
                /* 先把活跃音符转成逐音 Note Off 发出去，再清状态；顺序反了逐音 Off 会全部丢失。 */
                usb_send_panic_cable(c);
                clear_cable_note_state(c);
                /* 新归属的手机需要对齐该端口在 DAW 里的当前值。 */
                s_shadow_replay_pending[c] = true;
                if (s_ble_task_handle != NULL) {
                    xTaskNotifyGive(s_ble_task_handle);
                }
            }
        }

        /* 某一路 BLE 断开/通知关闭：只对该乐手的 USB 虚拟端口发 panic，不影响另一位乐手。 */
        for (uint8_t c = 0; c < USB_MIDI_CABLE_COUNT; c++) {
            const bool port_ready = ble_midi_link_port_ready(c);
            if (was_port_ready[c] && !port_ready) {
                ESP_LOGW(TAG, "BLE port %u ready -> false; panic cable %u", c + 1, c);
                usb_send_panic_cable(c);
            }
            if (!was_port_ready[c] && port_ready) {
                ESP_LOGI(TAG, "BLE port %u ready; scheduling shadow replay", c + 1);
                s_shadow_replay_pending[c] = true;
                if (s_ble_task_handle != NULL) {
                    xTaskNotifyGive(s_ble_task_handle);
                }
            }
            was_port_ready[c] = port_ready;
        }

        /* USB 卸载：清掉不能在重枚举后重放的消息。 */
        if (was_mounted && !mounted) {
            ESP_LOGW(TAG, "USB unmounted; clearing queues");
            clear_queues();
            shadow_clear();
        }

        /* USB 新挂载：等主机枚举完成，再对两个端口发一次 panic。 */
        if (!was_mounted && mounted) {
            vTaskDelay(pdMS_TO_TICKS(50));
            if (usb_midi_device_mounted()) {
                ESP_LOGI(TAG, "USB mounted; panic all cables");
                usb_send_panic_all();
            }
            clear_queues();
        }

        was_mounted = mounted;

        midi_msg_t msg;

        /* BLE → USB：先排空，按 msg.cable_num 写入对应的 USB 虚拟线缆（Port 1 / Port 2）。
         * 挂起的 Note Off 必须留到本轮排空之后再补发：否则它会抢在仍排在队列里的同音 Note On
         * 之前发出，DAW 收到 Off→On，音符被永久按住。 */
        while (xQueueReceive(s_ble_to_usb, &msg, 0) == pdTRUE) {
            const uint8_t ch = (uint8_t)(msg.data[0] & 0x0F);
            const uint8_t note = (uint8_t)(msg.data[1] & 0x7F);
            const bool note_on = is_note_on_msg(msg.data, msg.len);
            const bool note_off = is_note_off_msg(msg.data, msg.len);

            if (!usb_midi_device_mounted() || !ble_midi_link_port_ready(msg.cable_num)) {
                if (note_off && msg.cable_num < USB_MIDI_CABLE_COUNT) {
                    s_active_notes[msg.cable_num][ch][note >> 6] &= ~(1ULL << (note & 63));
                    mark_pending_note_off(msg.cable_num, ch, note);
                } else {
                    s_ble_to_usb_dropped++;
                }
                continue;
            }

            if (usb_write_all(msg.cable_num, msg.data, msg.len)) {
                if (note_on) {
                    /*
                     * 只置活跃位，不能在这里清除挂起的 Note Off：
                     * 这条 Note On 早于那条被丢弃的 Note Off 入队，若在这里清除，
                     * 队列满时丢掉的 Note Off 就永远补不回来了（DAW 卡音）。
                     * 真正的"重按取消旧 Off"发生在 midi_bridge_on_ble_midi 入队成功后。
                     */
                    s_active_notes[msg.cable_num][ch][note >> 6] |= (1ULL << (note & 63));
                } else if (note_off) {
                    s_active_notes[msg.cable_num][ch][note >> 6] &= ~(1ULL << (note & 63));
                    clear_pending_note_off(msg.cable_num, ch, note);
                }
                s_ble_to_usb_sent++;
                latency_record(msg.rx_time_us);
            } else {
                if (note_off) {
                    s_active_notes[msg.cable_num][ch][note >> 6] &= ~(1ULL << (note & 63));
                    mark_pending_note_off(msg.cable_num, ch, note);
                } else {
                    s_ble_to_usb_dropped++;
                }
            }
        }

        /* 队列已排空：此时补发挂起的 Note Off 不会被同音的 Note On 反超。 */
        if (mounted) {
            for (uint8_t c = 0; c < USB_MIDI_CABLE_COUNT; c++) {
                flush_pending_note_offs(c);
            }
        }

        /* USB → BLE：一次最多读取 16 个 4 字节事件包，按 cable_num 投入独立队列，互不阻塞。 */
        uint8_t packets[USB_READ_BATCH * 4];
        const uint32_t count = usb_midi_read_packets(packets, USB_READ_BATCH);
        bool enqueued_any = false;
        for (uint32_t i = 0; i < count; i++) {
            midi_msg_t incoming;
            if (!usb_packet_to_message(&packets[i * 4], &incoming)) {
                s_usb_unsupported++;
                continue;
            }
            shadow_record(&incoming);
            if (xQueueSend(s_usb_to_ble[incoming.cable_num], &incoming, 0) == pdTRUE) {
                enqueued_any = true;
            } else {
                s_usb_to_ble_dropped++;
            }
        }
        if (enqueued_any && s_ble_task_handle != NULL) {
            xTaskNotifyGive(s_ble_task_handle);
        }

        const int64_t now = esp_timer_get_time();
        if (now >= next_log_us) {
            ble_midi_link_stats_t link_stats;
            ble_midi_link_get_stats(&link_stats);

            ESP_LOGI(TAG,
                     "BRIDGE stats: BLE->USB sent=%lu drop=%lu p50=%luus p95=%luus max=%luus; "
                     "USB->BLE sent=%lu drop=%lu; unsupported=%lu tx_drop=%lu usb_unsupported=%lu "
                     "flushes=%lu panic_fail=%lu",
                     (unsigned long)s_ble_to_usb_sent,
                     (unsigned long)(s_ble_to_usb_dropped + s_ble_rx_dropped),
                     (unsigned long)latency_percentile(50),
                     (unsigned long)latency_percentile(95),
                     (unsigned long)s_lat_max_us,
                     (unsigned long)s_usb_to_ble_sent,
                     (unsigned long)s_usb_to_ble_dropped,
                     (unsigned long)link_stats.rx_unsupported,
                     (unsigned long)link_stats.tx_dropped,
                     (unsigned long)s_usb_unsupported,
                     (unsigned long)s_queue_flushes,
                     (unsigned long)s_panic_failures);
            next_log_us = now + 5000000;
        }

        vTaskDelay(1);
    }
}

static void ble_task(void *arg)
{
    (void)arg;
    midi_msg_t batch[MIDI_BATCH_MAX];
    uint8_t next_start_cable = 0;

    for (;;) {
        bool sent_in_round = false;

        /* 影子回放由唯一的 BLE 发送者执行：先对齐快照，后面的实时数据再覆盖它。 */
        for (uint8_t c = 0; c < USB_MIDI_CABLE_COUNT; c++) {
            if (!s_shadow_replay_pending[c]) {
                continue;
            }
            s_shadow_replay_pending[c] = false;
            if (ble_midi_link_port_ready(c) && usb_midi_device_mounted()) {
                shadow_replay(c);
            }
        }

        for (uint8_t offset = 0; offset < USB_MIDI_CABLE_COUNT; offset++) {
            const uint8_t target_cable = (uint8_t)((next_start_cable + offset) % USB_MIDI_CABLE_COUNT);
            size_t count = 0;
            while (count < MIDI_BATCH_MAX &&
                   xQueueReceive(s_usb_to_ble[target_cable], &batch[count], 0) == pdTRUE) {
                count++;
            }
            if (count == 0) {
                continue;
            }
            sent_in_round = true;

            if (!ble_midi_link_port_ready(target_cable)) {
                s_usb_to_ble_dropped += count;
                continue;
            }

            const uint8_t *msgs[MIDI_BATCH_MAX];
            uint8_t lens[MIDI_BATCH_MAX];
            for (size_t i = 0; i < count; i++) {
                msgs[i] = batch[i].data;
                lens[i] = batch[i].len;
            }

            if (ble_midi_link_send(target_cable, msgs, lens, (uint16_t)count) == ESP_OK) {
                s_usb_to_ble_sent += count;
            } else {
                s_usb_to_ble_dropped += count;
            }
        }

        next_start_cable = (uint8_t)((next_start_cable + 1) % USB_MIDI_CABLE_COUNT);
        if (!sent_in_round) {
            (void)ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(10));
        }
    }
}

esp_err_t midi_bridge_init(void)
{
    s_ble_to_usb = xQueueCreate(BLE_TO_USB_DEPTH, sizeof(midi_msg_t));
    if (s_ble_to_usb == NULL) {
        return ESP_ERR_NO_MEM;
    }

    for (uint8_t c = 0; c < USB_MIDI_CABLE_COUNT; c++) {
        s_usb_to_ble[c] = xQueueCreate(USB_TO_BLE_DEPTH, sizeof(midi_msg_t));
        if (s_usb_to_ble[c] == NULL) {
            for (uint8_t j = 0; j < c; j++) {
                vQueueDelete(s_usb_to_ble[j]);
                s_usb_to_ble[j] = NULL;
            }
            vQueueDelete(s_ble_to_usb);
            s_ble_to_usb = NULL;
            return ESP_ERR_NO_MEM;
        }
    }

    return ESP_OK;
}

esp_err_t midi_bridge_start(void)
{
    TaskHandle_t usb_handle = NULL;
    if (xTaskCreate(usb_task, "usb_bridge", 4096, NULL, 10, &usb_handle) != pdPASS) {
        ESP_LOGE(TAG, "failed to create usb_task");
        return ESP_ERR_NO_MEM;
    }

    if (xTaskCreate(ble_task, "ble_bridge", 4096, NULL, 8, &s_ble_task_handle) != pdPASS) {
        ESP_LOGE(TAG, "failed to create ble_task");
        vTaskDelete(usb_handle);
        return ESP_ERR_NO_MEM;
    }

    return ESP_OK;
}

void midi_bridge_on_ble_midi(uint8_t cable_num, const uint8_t *msg, uint8_t len, int64_t rx_time_us)
{
    if (msg == NULL || len == 0 || len > MIDI_MSG_MAX_LEN || cable_num >= USB_MIDI_CABLE_COUNT) {
        s_ble_rx_dropped++;
        return;
    }

    midi_msg_t item = {
        .cable_num = cable_num,
        .len = len,
        .rx_time_us = rx_time_us,
    };
    memcpy(item.data, msg, len);

    /* 只有 3 字节消息才有 (channel, note) 数据字节；0xF8 时钟等 1 字节消息不能读 msg[1]。 */
    const bool note_on = is_note_on_msg(msg, len);
    const bool note_off = is_note_off_msg(msg, len);
    const uint8_t ch = note_on || note_off ? (uint8_t)(msg[0] & 0x0F) : 0;
    const uint8_t note = note_on || note_off ? (uint8_t)(msg[1] & 0x7F) : 0;

    if (xQueueSend(s_ble_to_usb, &item, pdMS_TO_TICKS(5)) != pdTRUE) {
        if (note_off) {
            /* F-01 防卡音：即使队列满，Note Off 也绝不丢弃，写入挂起位图由 usb_task 补发 */
            mark_pending_note_off(cable_num, ch, note);
        } else {
            s_ble_rx_dropped++;
        }
    } else if (note_on) {
        /* 入队成功后才撤销挂起的 Note Off：本机重按该音说明旧状态已被覆盖。
         * 若在入队前撤销，队列满时这条 Note On 被丢弃会让之前的音永久卡住。 */
        clear_pending_note_off(cable_num, ch, note);
    }
}
