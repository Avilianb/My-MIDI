/*
 * BLE-MIDI ↔ USB-MIDI 双向桥接。
 *
 * BLE host task 只把完整消息复制进队列；usb_task 是唯一的 USB 写入者；
 * ble_task 是唯一的 BLE 通知发送者。USB OUT 数据由 usb_task 解析为
 * 4 字节事件包后入队，ble_task 聚合后调用标准 BLE-MIDI 发送。
 */

#include "midi_bridge.h"

#include <string.h>
#include <stdatomic.h>

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
#define PLAYER_CHANNELS 8
#define MIDI_BATCH_MAX 8
#define USB_READ_BATCH 16
#define USB_DRAIN_BATCH 32
#define PANIC_BATCH 8
#define LAT_BUCKET_US 100
#define LAT_BUCKET_COUNT 64

typedef struct {
    uint8_t cable_num;
    uint8_t len;
    uint8_t data[MIDI_MSG_MAX_LEN];
    int64_t rx_time_us;
    uint32_t generation;
    uint32_t recovery_epoch;
} midi_msg_t;

static QueueHandle_t s_ble_to_usb;
static QueueHandle_t s_usb_to_ble[USB_MIDI_CABLE_COUNT];
static TaskHandle_t s_ble_task_handle;

static _Atomic uint32_t s_ble_rx_dropped;
static _Atomic uint32_t s_ble_to_usb_sent;
static _Atomic uint32_t s_ble_to_usb_dropped;
static _Atomic uint32_t s_usb_unsupported;
static _Atomic uint32_t s_usb_to_ble_sent;
static _Atomic uint32_t s_usb_to_ble_dropped;
static _Atomic uint32_t s_panic_failures;
static _Atomic uint32_t s_queue_flushes;

/*
 * F-01 / F-07 防卡音状态表：
 * - s_active_notes 记录每个 (cable, channel) 当前已发往 USB 且未释放的音符（128 bit = 2 × uint64_t）
 * - s_pending_note_off 记录任何因队列满或 USB FIFO 满而未能立即发出的 Note Off，在后续循环最优先补发
 */
static uint64_t s_active_notes[USB_MIDI_CABLE_COUNT][PLAYER_CHANNELS][2];
static uint64_t s_pending_note_off[USB_MIDI_CABLE_COUNT][PLAYER_CHANNELS][2];
/* 每个 cable 是否有挂起 Note Off；为空时 flush 直接返回，避免每 1 ms 空转 32 次临界区。 */
static volatile bool s_pending_note_off_any[USB_MIDI_CABLE_COUNT];
static portMUX_TYPE s_note_lock = portMUX_INITIALIZER_UNLOCKED;
/* A lost release invalidates this cable's backlog, then retries a full panic.
 * Do not cancel an earlier dropped release when a newer Note On is enqueued. */
static uint32_t s_recovery_epoch[USB_MIDI_CABLE_COUNT];
static bool s_recovery_requested[USB_MIDI_CABLE_COUNT];
/* usb_task owns these masks. Each bit is a CC still awaiting FIFO space. */
static uint8_t s_panic_cc[USB_MIDI_CABLE_COUNT][PLAYER_CHANNELS];
static portMUX_TYPE s_shadow_lock = portMUX_INITIALIZER_UNLOCKED;

/*
 * USB 侧影子状态：DAW 通常只在参数变化时回传 CC / Pitch Bend，而它初始化时的
 * 整包 dump 往往发生在手机还没连上 BLE 的时刻，会被直接丢弃。这里保留 DAW 发过的
 * 最后一个值，等端口绑定（Notify 打开）时一次性回放，让手机 UI 对齐 DAW。
 * 音符不回放：连接前就一直按住的音重放会造成意外触发。
 */
#define SHADOW_CCS 128
#define SHADOW_CHANNELS PLAYER_CHANNELS
static uint8_t s_shadow_cc[USB_MIDI_CABLE_COUNT][SHADOW_CHANNELS][SHADOW_CCS];
static uint8_t s_shadow_cc_valid[USB_MIDI_CABLE_COUNT][SHADOW_CHANNELS][SHADOW_CCS / 8];
static uint16_t s_shadow_pb[USB_MIDI_CABLE_COUNT][SHADOW_CHANNELS];
static uint16_t s_shadow_pb_valid[USB_MIDI_CABLE_COUNT];
/* usb_task 只置位，由 ble_task（唯一的 BLE 通知发送者）执行回放。 */
static _Atomic uint32_t s_shadow_replay_request[USB_MIDI_CABLE_COUNT];
/* Only ble_task writes these; a newer request cannot be lost at completion. */
static uint32_t s_shadow_replay_completed[USB_MIDI_CABLE_COUNT];
static uint32_t s_shadow_replay_in_progress[USB_MIDI_CABLE_COUNT];

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

static void request_recovery(uint8_t cable)
{
    portENTER_CRITICAL(&s_note_lock);
    if (!s_recovery_requested[cable]) {
        s_recovery_requested[cable] = true;
        s_recovery_epoch[cable]++;
    }
    portEXIT_CRITICAL(&s_note_lock);
}

/* Atomic USB event write, without blocking either performer's bridge loop. */
static bool usb_write_all(uint8_t cable, const uint8_t *data, uint8_t len)
{
    uint8_t wire[MIDI_MSG_MAX_LEN];
    memcpy(wire, data, len);
    if (data[0] >= 0x80 && data[0] < 0xF0) {
        uint8_t local_channel = data[0] & 15;
        if (local_channel >= PLAYER_CHANNELS) return false;
        wire[0] = (data[0] & 0xF0) | (cable * PLAYER_CHANNELS + local_channel);
    }
    return usb_midi_write(cable, wire, len) == len;
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

/** Retry at most PANIC_BATCH packets per cable per turn, retaining failures. */
static void flush_pending_note_offs(uint8_t cable)
{
    if (!s_pending_note_off_any[cable] || !usb_midi_device_mounted()) return;
    unsigned budget = PANIC_BATCH;
    const uint8_t controllers[] = {64, 120, 123};
    for (uint8_t ch = 0; ch < PLAYER_CHANNELS; ch++) {
        for (uint8_t i = 0; i < 3; i++) {
            if (!(s_panic_cc[cable][ch] & (1U << i))) continue;
            uint8_t msg[] = {(uint8_t)(0xB0 | ch), controllers[i], 0};
            if (!usb_write_all(cable, msg, 3)) { s_panic_failures++; return; }
            s_panic_cc[cable][ch] &= ~(1U << i);
            if (--budget == 0) return;
        }
        for (uint8_t word = 0; word < 2; word++) {
            uint64_t mask = s_pending_note_off[cable][ch][word];
            while (mask) {
                uint8_t bit = (uint8_t)__builtin_ctzll(mask);
                uint8_t msg[] = {(uint8_t)(0x80 | ch), (uint8_t)(word * 64 + bit), 0};
                if (!usb_write_all(cable, msg, 3)) { s_panic_failures++; return; }
                s_pending_note_off[cable][ch][word] &= ~(1ULL << bit);
                mask &= mask - 1;
                if (--budget == 0) return;
            }
        }
    }
    s_pending_note_off_any[cable] = false;
}

static void usb_send_panic_cable(uint8_t cable)
{
    for (uint8_t ch = 0; ch < PLAYER_CHANNELS; ch++) {
        for (uint8_t w = 0; w < 2; w++) {
            s_pending_note_off[cable][ch][w] |= s_active_notes[cable][ch][w];
            s_active_notes[cable][ch][w] = 0;
        }
        s_panic_cc[cable][ch] = 7;
    }
    s_pending_note_off_any[cable] = true;
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
    memset(s_panic_cc, 0, sizeof(s_panic_cc));
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

    out->generation = ble_midi_link_cable_generation(cable_num);
    out->cable_num = cable_num;
    out->len = len;
    memcpy(out->data, &pkt[1], len);
    if (out->data[0] >= 0x80 && out->data[0] < 0xF0) {
        uint8_t channel = out->data[0] & 15;
        /* Live can echo the same Remote message on both output ports.
         * Keep only this performer's namespace, then restore local channels. */
        if (channel / PLAYER_CHANNELS != cable_num) return false;
        out->data[0] = (out->data[0] & 0xF0) | (channel % PLAYER_CHANNELS);
    }
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
    portENTER_CRITICAL(&s_shadow_lock);
    if ((status & 0xF0) == 0xB0) {
        const uint8_t cc = msg->data[1] & 0x7F;
        s_shadow_cc[cable][ch][cc] = msg->data[2] & 0x7F;
        s_shadow_cc_valid[cable][ch][cc >> 3] |= (uint8_t)(1U << (cc & 7));
    } else if ((status & 0xF0) == 0xE0) {
        s_shadow_pb[cable][ch] =
            (uint16_t)((msg->data[1] & 0x7F) | ((msg->data[2] & 0x7F) << 7));
        s_shadow_pb_valid[cable] |= (uint16_t)(1U << ch);
    }
    portEXIT_CRITICAL(&s_shadow_lock);
}

/** 只清有效性：USB 卸载意味着 DAW 可能重启，重枚举后它会重新 dump。 */
static void shadow_clear(void)
{
    portENTER_CRITICAL(&s_shadow_lock);
    memset(s_shadow_cc_valid, 0, sizeof(s_shadow_cc_valid));
    memset(s_shadow_pb_valid, 0, sizeof(s_shadow_pb_valid));
    portEXIT_CRITICAL(&s_shadow_lock);
}

/* One small replay batch per turn keeps the other phone's live feedback moving. */
static uint16_t s_replay_cursor[USB_MIDI_CABLE_COUNT];
static uint32_t s_replay_generation[USB_MIDI_CABLE_COUNT];
static void shadow_replay(uint8_t cable)
{
    uint32_t generation = ble_midi_link_cable_generation(cable);
    uint32_t request = s_shadow_replay_request[cable];
    if (s_replay_generation[cable] != generation || s_shadow_replay_in_progress[cable] != request) {
        s_shadow_replay_in_progress[cable] = request;
        s_replay_generation[cable] = generation;
        s_replay_cursor[cable] = 0;
    }
    uint8_t bufs[MIDI_BATCH_MAX][3], lens[MIDI_BATCH_MAX];
    const uint8_t *msgs[MIDI_BATCH_MAX];
    uint8_t n = 0;
    uint16_t cursor = s_replay_cursor[cable];
    while (cursor < PLAYER_CHANNELS * 129 && n < MIDI_BATCH_MAX) {
        uint8_t ch = cursor / 129, cc = cursor % 129;
        cursor++;
        portENTER_CRITICAL(&s_shadow_lock);
        bool valid = cc < 128 ? (s_shadow_cc_valid[cable][ch][cc >> 3] & (1U << (cc & 7))) != 0
                               : (s_shadow_pb_valid[cable] & (1U << ch)) != 0;
        uint16_t value = cc < 128 ? s_shadow_cc[cable][ch][cc] : s_shadow_pb[cable][ch];
        portEXIT_CRITICAL(&s_shadow_lock);
        if (!valid) continue;
        bufs[n][0] = (cc < 128 ? 0xB0 : 0xE0) | ch;
        bufs[n][1] = cc < 128 ? cc : value & 127;
        bufs[n][2] = cc < 128 ? value : value >> 7;
        msgs[n] = bufs[n]; lens[n++] = 3;
    }
    if (n && ble_midi_link_send(cable, msgs, lens, n) != ESP_OK) return;
    s_replay_cursor[cable] = cursor;
    if (cursor == PLAYER_CHANNELS * 129) {
        s_shadow_replay_completed[cable] = request;
        s_replay_cursor[cable] = 0;
    }
}

static void process_ble_message(const midi_msg_t *msg)
{
    uint8_t c = msg->cable_num;
    if (msg->data[0] >= 0x80 && msg->data[0] < 0xF0 &&
        (msg->data[0] & 15) >= PLAYER_CHANNELS) {
        s_ble_to_usb_dropped++;
        return;
    }
    portENTER_CRITICAL(&s_note_lock);
    bool stale = msg->recovery_epoch != s_recovery_epoch[c] || s_recovery_requested[c];
    portEXIT_CRITICAL(&s_note_lock);
    if (stale || msg->generation != ble_midi_link_cable_generation(c) ||
        msg->generation != s_cable_generation_seen[c] ||
        s_pending_note_off_any[c] || !usb_midi_device_mounted() ||
        !ble_midi_link_port_ready(c)) {
        s_ble_to_usb_dropped++;
        return;
    }
    if (usb_write_all(c, msg->data, msg->len)) {
        uint8_t ch = msg->data[0] & 15;
        uint8_t note = msg->data[1] & 127;
        if (is_note_on_msg(msg->data, msg->len))
            s_active_notes[c][ch][note >> 6] |= 1ULL << (note & 63);
        else if (is_note_off_msg(msg->data, msg->len))
            s_active_notes[c][ch][note >> 6] &= ~(1ULL << (note & 63));
        s_ble_to_usb_sent++;
        latency_record(msg->rx_time_us);
    } else {
        s_ble_to_usb_dropped++;
        /* Sustain/button CC releases are safety-critical too. */
        request_recovery(c);
    }
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
                /* Keep failed panic packets pending until USB accepts them. */
                /* 新归属的手机需要对齐该端口在 DAW 里的当前值。 */
                s_shadow_replay_request[c]++;
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
                s_shadow_replay_request[c]++;
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
                clear_queues();
                usb_send_panic_all();
            }
        }

        was_mounted = mounted;

        midi_msg_t msg;

        for (uint8_t c = 0; c < USB_MIDI_CABLE_COUNT; c++) {
            portENTER_CRITICAL(&s_note_lock);
            bool recover = s_recovery_requested[c];
            s_recovery_requested[c] = false;
            portEXIT_CRITICAL(&s_note_lock);
            if (recover) usb_send_panic_cable(c);
            flush_pending_note_offs(c);
        }

        /* Bound work so a continuous BLE flood cannot starve USB OUT or GAP
         * boundary checks. Never route an old owner's queued event to a new one. */
        for (unsigned n = 0; n < USB_DRAIN_BATCH &&
             xQueueReceive(s_ble_to_usb, &msg, 0) == pdTRUE; n++) {
            process_ble_message(&msg);
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
            if (!ble_midi_link_port_ready(incoming.cable_num)) continue;
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
            if (s_shadow_replay_request[c] == s_shadow_replay_completed[c]) {
                continue;
            }
            if (ble_midi_link_port_ready(c) && usb_midi_device_mounted()) {
                shadow_replay(c);
            }
        }

        for (uint8_t offset = 0; offset < USB_MIDI_CABLE_COUNT; offset++) {
            const uint8_t target_cable = (uint8_t)((next_start_cable + offset) % USB_MIDI_CABLE_COUNT);
            size_t count = 0;
            while (count < MIDI_BATCH_MAX &&
                   xQueueReceive(s_usb_to_ble[target_cable], &batch[count], 0) == pdTRUE) {
                if (batch[count].generation == ble_midi_link_cable_generation(target_cable)) count++;
                else s_usb_to_ble_dropped++;
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

    if (msg[0] >= 0x80 && msg[0] < 0xF0 && (msg[0] & 15) >= PLAYER_CHANNELS) {
        s_ble_rx_dropped++;
        return;
    }

    midi_msg_t item = {
        .cable_num = cable_num,
        .len = len,
        .rx_time_us = rx_time_us,
    };
    memcpy(item.data, msg, len);

    item.generation = ble_midi_link_cable_generation(cable_num);
    portENTER_CRITICAL(&s_note_lock);
    item.recovery_epoch = s_recovery_epoch[cable_num];
    bool recovering = s_recovery_requested[cable_num];
    portEXIT_CRITICAL(&s_note_lock);
    /* BLE host callbacks must never wait on the USB consumer. */
    if (recovering || xQueueSend(s_ble_to_usb, &item, 0) != pdTRUE) {
        s_ble_rx_dropped++;
        request_recovery(cable_num);
    }
}
