idf_component_register(SRCS "tusb_midi_main.c" "ble_midi_link.c"
                    INCLUDE_DIRS "."
                    PRIV_REQUIRES esp_timer nvs_flash esp_event ble_conn_mgr ble_midi ble_services
                    )
