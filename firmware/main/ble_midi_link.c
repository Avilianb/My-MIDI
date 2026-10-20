/*
 * BLE-MIDI 外设链路。
 *
 * 这一层只负责标准 BLE-MIDI GATT、连接参数和通知收发；它不解析控件，
 * 也不直接操作 USB。BLE host task 中收到的 MIDI 消息通过回调交给桥接层。
 */

#include "ble_midi_link.h"

#include <string.h>

#include "esp_ble_conn_mgr.h"
#include "esp_ble_midi.h"
#include "esp_ble_midi_svc.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_timer.h"

static const char *TAG = "ble_midi";

#define BLE_DEVICE_NAME "ESP32-S3 MIDI"
#define BLE_MIDI_MAX_BATCH 8

/*
 * 扩展广播必须显式带上 128-bit Service UUID。ble_conn_mgr 在
 * CONFIG_BLE_CONN_MGR_EXTENDED_ADV=y 时不会走旧版自动拼接路径；
 * 只给 device_name 会导致广播里没有 UUID，手机按 UUID 过滤就扫不到。
 *
 * AD 结构：Flags + Complete 128-bit UUID + Complete Local Name。
 */
static const uint8_t s_ext_adv_data[] = {
    0x02, 0x01, 0x06, /* Flags: LE General Discoverable, BR/EDR not supported */
    0x11, 0x07,       /* 17 bytes, Complete List of 128-bit Service UUIDs */
    0x00, 0xC7, 0xC4, 0x4E, 0xE3, 0x6C, 0x51, 0xA7,
    0x33, 0x4B, 0xE8, 0xED, 0x5A, 0x0E, 0xB8, 0x03,
    0x0E, 0x09,       /* 14 bytes, Complete Local Name */
    'E', 'S', 'P', '3', '2', '-', 'S', '3', ' ', 'M', 'I', 'D', 'I',
};

static bool s_connected;
static ble_midi_rx_cb_t s_rx_cb;
static volatile uint32_t s_rx_unsupported;
static volatile uint32_t s_tx_sent;
static volatile uint32_t s_tx_dropped;

static void log_if_error(const char *what, esp_err_t err)
{
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "%s failed: %s", what, esp_err_to_name(err));
    }
}

static void midi_event_cb(uint16_t timestamp_ms, esp_ble_midi_event_type_t event_type,
                          const uint8_t *msg, uint16_t msg_len)
{
    (void)timestamp_ms;

    if (event_type == ESP_BLE_MIDI_EVENT_SYSEX_OVERFLOW) {
        s_rx_unsupported++;
        return;
    }

    if (msg == NULL || msg_len == 0 || msg_len > 3) {
        s_rx_unsupported++;
        return;
    }

    const uint8_t status = msg[0];
    const bool channel_voice = (status >= 0x80 && status <= 0xEF);
    const bool realtime = (status >= 0xF8);

    if (channel_voice) {
        /* Note/CC/Pitch Bend 为 3 字节，Program Change/Channel Pressure 为 2 字节。 */
        if (msg_len != 2 && msg_len != 3) {
            s_rx_unsupported++;
            return;
        }
    } else if (realtime) {
        if (msg_len != 1) {
            s_rx_unsupported++;
            return;
        }
    } else {
        /* 首期不转发 SysEx 和 System Common。 */
        s_rx_unsupported++;
        return;
    }

    if (s_rx_cb != NULL) {
        s_rx_cb(msg, (uint8_t)msg_len, esp_timer_get_time());
    }
}

static void conn_event_handler(void *handler_args, esp_event_base_t base, int32_t id, void *event_data)
{
    (void)handler_args;
    if (base != BLE_CONN_MGR_EVENTS || event_data == NULL) {
        return;
    }

    esp_ble_conn_event_data_t *ev = (esp_ble_conn_event_data_t *)event_data;

    switch (id) {
    case ESP_BLE_CONN_EVENT_CONNECTED: {
        s_connected = true;
        esp_ble_midi_set_notify_enabled(false);

        const uint16_t handle = ev->connected.conn_handle;
        ESP_LOGI(TAG, "connected handle=%u", handle);

        const esp_ble_conn_params_t params = {
            .itvl_min = 6,              /* 7.5 ms */
            .itvl_max = 12,             /* 15 ms */
            .latency = 0,
            .supervision_timeout = 400, /* 4 s */
            .min_ce_len = 0,
            .max_ce_len = 0,
        };
        log_if_error("conn update", esp_ble_conn_update_params(handle, &params));
        log_if_error("2M PHY", esp_ble_conn_set_preferred_phy(
                                   handle, ESP_BLE_CONN_PHY_MASK_2M,
                                   ESP_BLE_CONN_PHY_MASK_2M, 0));
        log_if_error("data length", esp_ble_conn_set_data_len(handle, 251, 2120));
        log_if_error("MTU", esp_ble_conn_mtu_update(handle, 247));
        break;
    }

    case ESP_BLE_CONN_EVENT_CCCD_UPDATE: {
        esp_ble_conn_cccd_update_t *update = &ev->cccd_update;
        static const uint8_t midi_char_uuid[16] = BLE_MIDI_CHAR_UUID128;
        const bool is_midi_char =
            (update->uuid_type == BLE_CONN_UUID_TYPE_128) &&
            (memcmp(update->uuid.uuid128, midi_char_uuid, sizeof(midi_char_uuid)) == 0);

        if (is_midi_char) {
            esp_ble_midi_set_notify_enabled(update->notify_enable);
            ESP_LOGI(TAG, "MIDI notify %s", update->notify_enable ? "enabled" : "disabled");
        }
        break;
    }

    case ESP_BLE_CONN_EVENT_DISCONNECTED:
        s_connected = false;
        esp_ble_midi_set_notify_enabled(false);
        ESP_LOGI(TAG, "disconnected handle=%u reason=0x%04x",
                 ev->disconnected.conn_handle, ev->disconnected.reason);
        break;

    case ESP_BLE_CONN_EVENT_MTU:
        ESP_LOGI(TAG, "MTU=%u", ev->mtu_update.mtu);
        break;

    case ESP_BLE_CONN_EVENT_CONN_PARAM_UPDATE: {
        const esp_ble_conn_params_t *p = &ev->conn_param_update.params;
        ESP_LOGI(TAG, "conn params: itvl_min=%u itvl_max=%u latency=%u timeout=%u status=%d",
                 p->itvl_min, p->itvl_max, p->latency, p->supervision_timeout,
                 ev->conn_param_update.status);
        break;
    }

    default:
        break;
    }
}

esp_err_t ble_midi_link_init(ble_midi_rx_cb_t cb)
{
    s_rx_cb = cb;

    esp_ble_conn_config_t config = {0};
    strlcpy((char *)config.device_name, BLE_DEVICE_NAME, sizeof(config.device_name));

    static const uint8_t midi_service_uuid[16] = BLE_MIDI_SERVICE_UUID128;
    config.include_service_uuid = 1;
    config.adv_uuid_type = BLE_CONN_UUID_TYPE_128;
    memcpy(config.adv_uuid128, midi_service_uuid, sizeof(midi_service_uuid));
    config.extended_adv_data = (const char *)s_ext_adv_data;
    config.extended_adv_len = sizeof(s_ext_adv_data);

    esp_err_t err = esp_ble_conn_init(&config);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "conn init failed: %s", esp_err_to_name(err));
        return err;
    }

    /*
     * NimBLE 不允许 non-legacy 同时 connectable + scannable；使用
     * connectable-only 扩展广播，UUID+名称放主广播数据。
     */
    esp_ble_conn_adv_params_t adv_params = {0};
    adv_params.adv_handle = 0;
    adv_params.adv_event_properties = BIT(0); /* connectable */
    adv_params.itvl_min = 0x100;
    adv_params.itvl_max = 0x100;
    adv_params.tx_power = 127;
    log_if_error("adv params", esp_ble_conn_adv_params_set(&adv_params));

    err = esp_ble_midi_svc_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "MIDI service init failed: %s", esp_err_to_name(err));
        return err;
    }

    err = esp_ble_midi_profile_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "MIDI profile init failed: %s", esp_err_to_name(err));
        return err;
    }

    err = esp_ble_midi_register_event_cb(midi_event_cb);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "MIDI callback registration failed: %s", esp_err_to_name(err));
        return err;
    }

    err = esp_event_handler_register(BLE_CONN_MGR_EVENTS, ESP_EVENT_ANY_ID,
                                     conn_event_handler, NULL);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "BLE event handler registration failed: %s", esp_err_to_name(err));
        return err;
    }

    return ESP_OK;
}

esp_err_t ble_midi_link_start(void)
{
    esp_err_t err = esp_ble_conn_start();
    if (err == ESP_OK) {
        ESP_LOGI(TAG, "advertising as %s", BLE_DEVICE_NAME);
    } else {
        ESP_LOGE(TAG, "advertising start failed: %s", esp_err_to_name(err));
    }
    return err;
}

bool ble_midi_link_ready(void)
{
    return s_connected && esp_ble_midi_is_notify_enabled();
}

esp_err_t ble_midi_link_send(const uint8_t *const msgs[], const uint8_t lens[], uint16_t count)
{
    if (msgs == NULL || lens == NULL || count == 0 || count > BLE_MIDI_MAX_BATCH) {
        return ESP_ERR_INVALID_ARG;
    }
    if (!ble_midi_link_ready()) {
        return ESP_ERR_INVALID_STATE;
    }

    uint16_t timestamps[BLE_MIDI_MAX_BATCH];
    const uint16_t now = esp_ble_midi_get_timestamp_ms();
    for (uint16_t i = 0; i < count; i++) {
        timestamps[i] = now;
    }

    size_t first_unsent = 0;
    esp_err_t err = esp_ble_midi_send_multi(msgs, lens, timestamps, count, &first_unsent);
    if (first_unsent > count) {
        first_unsent = count;
    }

    s_tx_sent += (uint32_t)first_unsent;
    s_tx_dropped += (uint32_t)(count - first_unsent);
    return err;
}

void ble_midi_link_get_stats(ble_midi_link_stats_t *out)
{
    if (out == NULL) {
        return;
    }
    out->rx_unsupported = s_rx_unsupported;
    out->tx_sent = s_tx_sent;
    out->tx_dropped = s_tx_dropped;
}
