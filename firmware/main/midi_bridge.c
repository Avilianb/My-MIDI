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

typedef struct {
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

/** 把一条消息完整写入 USB；FIFO 满时最多重试 5 ms。 */
static bool usb_write_all(const uint8_t *data, uint8_t len)
{
    uint32_t sent = 0;
    const int64_t deadline = esp_timer_get_time() + USB_WRITE_RETRY_US;

    while (sent < len) {
        const uint32_t written = usb_midi_write(data + sent, len - sent);
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

/** USB-MIDI 4 字节事件包 → 完整 MIDI 消息；SysEx 首期不转发。 */
static bool usb_packet_to_message(const uint8_t pkt[4], midi_msg_t *out)
{
    if ((pkt[0] >> 4) != 0) {
        return false; /* 只支持 cable 0 */
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

    out->len = len;
    memcpy(out->data, &pkt[1], len);
    out->rx_time_us = esp_timer_get_time();
    return true;
}

static void usb_task(void *arg)
{
    (void)arg;
    int64_t next_log_us = esp_timer_get_time() + 5000000;

    for (;;) {
        midi_msg_t msg;

        /* BLE → USB：先排空，保证 Note Off 不被 USB OUT 读取拖住。 */
        while (xQueueReceive(s_ble_to_usb, &msg, 0) == pdTRUE) {
            if (!usb_midi_device_mounted() || !ble_midi_link_ready()) {
                s_ble_to_usb_dropped++;
                continue;
            }
            if (usb_write_all(msg.data, msg.len)) {
                s_ble_to_usb_sent++;
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
            ESP_LOGI(TAG,
                     "BLE->USB sent=%lu drop=%lu; USB->BLE sent=%lu drop=%lu unsupported=%lu",
                     (unsigned long)s_ble_to_usb_sent,
                     (unsigned long)(s_ble_to_usb_dropped + s_ble_rx_dropped),
                     (unsigned long)s_usb_to_ble_sent,
                     (unsigned long)s_usb_to_ble_dropped,
                     (unsigned long)s_usb_unsupported);
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

        size_t count = 1;
        while (count < MIDI_BATCH_MAX &&
               xQueueReceive(s_usb_to_ble, &batch[count], 0) == pdTRUE) {
            count++;
        }

        if (!ble_midi_link_ready()) {
            s_usb_to_ble_dropped += count;
            continue;
        }

        const uint8_t *msgs[MIDI_BATCH_MAX];
        uint8_t lens[MIDI_BATCH_MAX];
        for (size_t i = 0; i < count; i++) {
            msgs[i] = batch[i].data;
            lens[i] = batch[i].len;
        }

        if (ble_midi_link_send(msgs, lens, (uint16_t)count) == ESP_OK) {
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

void midi_bridge_on_ble_midi(const uint8_t *msg, uint8_t len, int64_t rx_time_us)
{
    if (msg == NULL || len == 0 || len > MIDI_MSG_MAX_LEN) {
        s_ble_rx_dropped++;
        return;
    }

    midi_msg_t item = {
        .len = len,
        .rx_time_us = rx_time_us,
    };
    memcpy(item.data, msg, len);

    if (xQueueSend(s_ble_to_usb, &item, pdMS_TO_TICKS(5)) != pdTRUE) {
        s_ble_rx_dropped++;
    }
}
