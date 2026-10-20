/*
 * BLE-MIDI → USB-MIDI 桥接。
 *
 * BLE host task 只把完整消息复制进队列；usb_task 是唯一的 USB 写入者。
 * 这样 BLE 回调和 TinyUSB FIFO 不会互相阻塞。
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
#define USB_WRITE_RETRY_US 5000

typedef struct {
    uint8_t len;
    uint8_t data[3];
    int64_t rx_time_us;
} midi_msg_t;

static QueueHandle_t s_ble_to_usb;
static volatile uint32_t s_ble_rx_dropped;
static volatile uint32_t s_ble_to_usb_sent;
static volatile uint32_t s_ble_to_usb_dropped;

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

static void usb_task(void *arg)
{
    (void)arg;
    int64_t next_log_us = esp_timer_get_time() + 5000000;

    for (;;) {
        midi_msg_t msg;
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

        const int64_t now = esp_timer_get_time();
        if (now >= next_log_us) {
            ESP_LOGI(TAG, "BLE->USB sent=%lu drop=%lu",
                     (unsigned long)s_ble_to_usb_sent,
                     (unsigned long)(s_ble_to_usb_dropped + s_ble_rx_dropped));
            next_log_us = now + 5000000;
        }

        vTaskDelay(1);
    }
}

esp_err_t midi_bridge_init(void)
{
    s_ble_to_usb = xQueueCreate(BLE_TO_USB_DEPTH, sizeof(midi_msg_t));
    return s_ble_to_usb != NULL ? ESP_OK : ESP_ERR_NO_MEM;
}

esp_err_t midi_bridge_start(void)
{
    if (xTaskCreate(usb_task, "usb_bridge", 4096, NULL, 10, NULL) != pdPASS) {
        ESP_LOGE(TAG, "failed to create usb_task");
        return ESP_ERR_NO_MEM;
    }
    return ESP_OK;
}

void midi_bridge_on_ble_midi(const uint8_t *msg, uint8_t len, int64_t rx_time_us)
{
    if (msg == NULL || len == 0 || len > sizeof(((midi_msg_t *)0)->data)) {
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
