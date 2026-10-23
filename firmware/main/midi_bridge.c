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
static QueueHandle_t s_usb_to_ble;
static volatile uint32_t s_ble_rx_dropped;
static volatile uint32_t s_ble_to_usb_sent;
static volatile uint32_t s_ble_to_usb_dropped;
static volatile uint32_t s_usb_unsupported;
static volatile uint32_t s_usb_to_ble_sent;
static volatile uint32_t s_usb_to_ble_dropped;
static volatile uint32_t s_panic_failures;
static volatile uint32_t s_queue_flushes;

/* 只在 usb_task 中更新/读取，无需加锁。 */
static uint32_t s_lat_hist[LAT_BUCKET_COUNT];
static uint32_t s_lat_count;
static uint32_t s_lat_max_us;

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

/** 向指定 USB 虚拟端口发送 16 通道 All Notes Off / All Sound Off / Sustain Off。 */
static void usb_send_panic_cable(uint8_t cable_num)
{
    if (!usb_midi_device_mounted() || cable_num >= USB_MIDI_CABLE_COUNT) {
        return;
    }

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
    xQueueReset(s_usb_to_ble);
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

static void usb_task(void *arg)
{
    (void)arg;
    int64_t next_log_us = esp_timer_get_time() + 5000000;
    bool was_port_ready[USB_MIDI_CABLE_COUNT] = { false, false };
    bool was_mounted = false;

    for (;;) {
        const bool mounted = usb_midi_device_mounted();

        /* 某一路 BLE 断开/通知关闭：只对该乐手的 USB 虚拟端口发 panic，不影响另一位乐手。 */
        for (uint8_t c = 0; c < USB_MIDI_CABLE_COUNT; c++) {
            const bool port_ready = ble_midi_link_port_ready(c);
            if (was_port_ready[c] && !port_ready) {
                ESP_LOGW(TAG, "BLE port %u ready -> false; panic cable %u", c + 1, c);
                usb_send_panic_cable(c);
            }
            was_port_ready[c] = port_ready;
        }

        /* USB 卸载：清掉不能在重枚举后重放的消息。 */
        if (was_mounted && !mounted) {
            ESP_LOGW(TAG, "USB unmounted; clearing queues");
            clear_queues();
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

        /* BLE → USB：先排空，按 msg.cable_num 写入对应的 USB 虚拟线缆（Port 1 / Port 2）。 */
        while (xQueueReceive(s_ble_to_usb, &msg, 0) == pdTRUE) {
            if (!usb_midi_device_mounted() || !ble_midi_link_port_ready(msg.cable_num)) {
                s_ble_to_usb_dropped++;
                continue;
            }
            if (usb_write_all(msg.cable_num, msg.data, msg.len)) {
                s_ble_to_usb_sent++;
                latency_record(msg.rx_time_us);
            } else {
                s_ble_to_usb_dropped++;
            }
        }
        /* USB → BLE：一次最多读取 16 个 4 字节事件包。 */
        uint8_t packets[USB_READ_BATCH * 4];
        const uint32_t count = usb_midi_read_packets(packets, USB_READ_BATCH);
        for (uint32_t i = 0; i < count; i++) {
            midi_msg_t incoming;
            if (!usb_packet_to_message(&packets[i * 4], &incoming)) {
                s_usb_unsupported++;
                continue;
            }
            if (xQueueSend(s_usb_to_ble, &incoming, pdMS_TO_TICKS(5)) != pdTRUE) {
                s_usb_to_ble_dropped++;
            }
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

    for (;;) {
        if (xQueueReceive(s_usb_to_ble, &batch[0], pdMS_TO_TICKS(10)) != pdTRUE) {
            continue;
        }
        const uint8_t target_cable = batch[0].cable_num;
        size_t count = 1;
        while (count < MIDI_BATCH_MAX) {
            midi_msg_t peeked;
            if (xQueuePeek(s_usb_to_ble, &peeked, 0) != pdTRUE ||
                peeked.cable_num != target_cable) {
                break;
            }
            if (xQueueReceive(s_usb_to_ble, &batch[count], 0) == pdTRUE) {
                count++;
            } else {
                break;
            }
        }

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
}

esp_err_t midi_bridge_init(void)
{
    s_ble_to_usb = xQueueCreate(BLE_TO_USB_DEPTH, sizeof(midi_msg_t));
    if (s_ble_to_usb == NULL) {
        return ESP_ERR_NO_MEM;
    }

    s_usb_to_ble = xQueueCreate(USB_TO_BLE_DEPTH, sizeof(midi_msg_t));
    if (s_usb_to_ble == NULL) {
        vQueueDelete(s_ble_to_usb);
        s_ble_to_usb = NULL;
        return ESP_ERR_NO_MEM;
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

    if (xTaskCreate(ble_task, "ble_bridge", 4096, NULL, 8, NULL) != pdPASS) {
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

    if (xQueueSend(s_ble_to_usb, &item, pdMS_TO_TICKS(5)) != pdTRUE) {
        s_ble_rx_dropped++;
    }
}
