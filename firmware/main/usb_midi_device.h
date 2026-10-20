#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

esp_err_t usb_midi_device_init(void);
bool usb_midi_device_mounted(void);

/** 返回实际接受的字节数；USB 未挂载或 FIFO 满时可能小于 len。 */
uint32_t usb_midi_write(const uint8_t *data, uint32_t len);

#ifdef __cplusplus
}
#endif
