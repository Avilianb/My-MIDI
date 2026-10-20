/*
 * USB-MIDI 设备层：只负责 TinyUSB 描述符、初始化和 4 字节事件包写入。
 * 它不包含测试音、BLE 或桥接策略。
 */

#include "usb_midi_device.h"

#include "esp_log.h"
#include "tinyusb.h"
#include "tinyusb_default_config.h"

static const char *TAG = "usb_midi";

/** Helper defines **/

// Interface counter
enum interface_count {
#if CFG_TUD_MIDI
    ITF_NUM_MIDI = 0,
    ITF_NUM_MIDI_STREAMING,
#endif
    ITF_COUNT
};

// USB Endpoint numbers
enum usb_endpoints {
    // Available USB Endpoints: 5 IN/OUT EPs and 1 IN EP
    EP_EMPTY = 0,
#if CFG_TUD_MIDI
    EPNUM_MIDI,
#endif
};

/** TinyUSB descriptors **/

#define TUSB_DESCRIPTOR_TOTAL_LEN (TUD_CONFIG_DESC_LEN + CFG_TUD_MIDI * TUD_MIDI_DESC_LEN)

/**
 * @brief String descriptor
 */
static const char *s_str_desc[5] = {
    // array of pointer to string descriptors
    (char[]){0x09, 0x04},   // 0: supported language is English (0x0409)
    "Remote MIDI",          // 1: Manufacturer
    "ESP32-S3 MIDI Bridge", // 2: Product
    "ESP32S3-MIDI-01",      // 3: Serial
    "ESP32-S3 MIDI Bridge", // 4: MIDI interface
};

/**
 * @brief Configuration descriptor
 *
 * This is a simple configuration descriptor that defines 1 configuration and a MIDI interface
 */
static const uint8_t s_midi_cfg_desc[] = {
    // Configuration number, interface count, string index, total length, attribute, power in mA
    TUD_CONFIG_DESCRIPTOR(1, ITF_COUNT, 0, TUSB_DESCRIPTOR_TOTAL_LEN, 0, 100),

    // Interface number, string index, EP Out & EP In address, EP size
    TUD_MIDI_DESCRIPTOR(ITF_NUM_MIDI, 4, EPNUM_MIDI, (0x80 | EPNUM_MIDI), 64),
};

#if (TUD_OPT_HIGH_SPEED)
/**
 * @brief High Speed configuration descriptor
 *
 * This is a simple configuration descriptor that defines 1 configuration and a MIDI interface
 */
static const uint8_t s_midi_hs_cfg_desc[] = {
    // Configuration number, interface count, string index, total length, attribute, power in mA
    TUD_CONFIG_DESCRIPTOR(1, ITF_COUNT, 0, TUSB_DESCRIPTOR_TOTAL_LEN, 0, 100),

    // Interface number, string index, EP Out & EP In address, EP size
    TUD_MIDI_DESCRIPTOR(ITF_NUM_MIDI, 4, EPNUM_MIDI, (0x80 | EPNUM_MIDI), 512),
};
#endif // TUD_OPT_HIGH_SPEED

esp_err_t usb_midi_device_init(void)
{
    ESP_LOGI(TAG, "USB initialization");

    tinyusb_config_t tusb_cfg = TINYUSB_DEFAULT_CONFIG();

    tusb_cfg.descriptor.string = s_str_desc;
    tusb_cfg.descriptor.string_count = sizeof(s_str_desc) / sizeof(s_str_desc[0]);
    tusb_cfg.descriptor.full_speed_config = s_midi_cfg_desc;
#if (TUD_OPT_HIGH_SPEED)
    tusb_cfg.descriptor.high_speed_config = s_midi_hs_cfg_desc;
    tusb_cfg.descriptor.qualifier = NULL;
#endif // TUD_OPT_HIGH_SPEED

    esp_err_t err = tinyusb_driver_install(&tusb_cfg);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "TinyUSB install failed: %s", esp_err_to_name(err));
        return err;
    }

    ESP_LOGI(TAG, "USB initialization DONE");
    return ESP_OK;
}

bool usb_midi_device_mounted(void)
{
    return tud_midi_mounted();
}

uint32_t usb_midi_write(const uint8_t *data, uint32_t len)
{
    if (data == NULL || len == 0 || !tud_midi_mounted()) {
        return 0;
    }
    return tud_midi_stream_write(0, data, len);
}

uint32_t usb_midi_read_packets(uint8_t *packets, uint32_t max_packets)
{
    if (packets == NULL || max_packets == 0 || !tud_midi_mounted()) {
        return 0;
    }
    return tud_midi_packet_read_n(packets, max_packets);
}
