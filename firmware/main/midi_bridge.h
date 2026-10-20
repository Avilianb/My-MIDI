#pragma once

#include <stdint.h>
#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

esp_err_t midi_bridge_init(void);
esp_err_t midi_bridge_start(void);

/** BLE host task 回调：只复制入队，不写 USB、不打印。 */
void midi_bridge_on_ble_midi(const uint8_t *msg, uint8_t len, int64_t rx_time_us);

#ifdef __cplusplus
}
#endif
