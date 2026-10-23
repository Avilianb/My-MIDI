#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

#define BLE_MIDI_MAX_PORTS 2

/**
 * BLE 收到一条完整 MIDI 消息后的回调；在 BLE host task 中调用，必须只入队。
 * cable_num 为 0（Port 1 / 乐手 1）或 1（Port 2 / 乐手 2）。
 */
typedef void (*ble_midi_rx_cb_t)(uint8_t cable_num, const uint8_t *msg, uint8_t len, int64_t rx_time_us);

typedef struct {
    uint32_t rx_unsupported; /* SysEx / 非通道消息丢弃数 */
    uint32_t tx_sent;        /* 成功写入通知的消息数 */
    uint32_t tx_dropped;     /* 通知未启用或发送失败的消息数 */
} ble_midi_link_stats_t;

esp_err_t ble_midi_link_init(ble_midi_rx_cb_t cb);
esp_err_t ble_midi_link_start(void);
bool ble_midi_link_port_ready(uint8_t cable_num);

/** 向指定 cable_num (0..1) 绑定的手机发送最多 8 条完整 MIDI 消息；每条 1..3 字节。 */
esp_err_t ble_midi_link_send(uint8_t cable_num, const uint8_t *const msgs[], const uint8_t lens[], uint16_t count);

void ble_midi_link_get_stats(ble_midi_link_stats_t *out);
#ifdef __cplusplus
}
#endif
