#include "esp_err.h"
#include "esp_event.h"
#include "esp_log.h"
#include "nvs_flash.h"

#include "ble_midi_link.h"
#include "midi_bridge.h"
#include "usb_midi_device.h"

static const char *TAG = "app";

void app_main(void)
{
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);
    ESP_ERROR_CHECK(esp_event_loop_create_default());

    ESP_ERROR_CHECK(usb_midi_device_init());
    ESP_ERROR_CHECK(midi_bridge_init());
    ESP_ERROR_CHECK(ble_midi_link_init(midi_bridge_on_ble_midi));
    ESP_ERROR_CHECK(midi_bridge_start());
    ESP_ERROR_CHECK(ble_midi_link_start());

    ESP_LOGI(TAG, "USB-MIDI and BLE-MIDI bridge ready");
}
