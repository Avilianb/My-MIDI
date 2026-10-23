idf_component_register(SRCS "app_main.c" "usb_midi_device.c" "ble_midi_link.c" "midi_bridge.c"
                    INCLUDE_DIRS "."
                    PRIV_REQUIRES esp_timer nvs_flash esp_event bt
                    )
