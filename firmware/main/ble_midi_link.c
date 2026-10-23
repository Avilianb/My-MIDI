/*
 * BLE-MIDI 外设链路（原生 NimBLE 双手机连接 + 单广播实例 + App 端口握手绑定）。
 *
 * - 单广播实例 "ESP32-S3 MIDI"，当已连接手机数 < 2 时自动保持广播，满 2 台自动停止广播（0 广播开销）。
 * - 支持最多 2 台手机同时连接，默认自动分配空闲 USB 端口（Cable 0 / Cable 1）。
 * - 当收到 App 发来的身份握手消息 [0xF4, 0x7D, 0x00|0x01]（或 SysEx [F0 7D 01 00|01 F7]）时，
 *   将该手机连接固定绑定到指定的 USB 虚拟端口（0 = Port 1，1 = Port 2），如另一台手机正占用该端口则自动互换。
 */

#include "ble_midi_link.h"

#include <string.h>

#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "host/ble_att.h"
#include "host/ble_gap.h"
#include "host/ble_gatt.h"
#include "host/ble_hs.h"
#include "host/ble_uuid.h"
#include "host/util/util.h"
#include "nimble/ble.h"
#include "nimble/hci_common.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "os/os_mbuf.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

static const char *TAG = "ble_midi";

#define BLE_DEVICE_NAME       "ESP32-S3 MIDI"
#define BLE_MIDI_MAX_BATCH    8
#define BLE_MIDI_MAX_PKT_SIZE 256
#define BLE_ADV_INSTANCE      0

/* AD 结构：Flags + Complete 128-bit UUID + Complete Local Name。 */
static const uint8_t s_ext_adv_data[] = {
    0x02, 0x01, 0x06, /* Flags: LE General Discoverable, BR/EDR not supported */
    0x11, 0x07,       /* 17 bytes, Complete List of 128-bit Service UUIDs */
    0x00, 0xC7, 0xC4, 0x4E, 0xE3, 0x6C, 0x51, 0xA7,
    0x33, 0x4B, 0xE8, 0xED, 0x5A, 0x0E, 0xB8, 0x03,
    0x0E, 0x09,       /* 14 bytes, Complete Local Name */
    'E', 'S', 'P', '3', '2', '-', 'S', '3', ' ', 'M', 'I', 'D', 'I',
};

typedef struct {
    bool connected;
    bool notify_enabled;
    bool explicit_claim;
    uint16_t conn_handle;
    uint16_t mtu;
    uint8_t cable_num;
} ble_midi_slot_t;

static ble_midi_slot_t s_slots[BLE_MIDI_MAX_PORTS];
static portMUX_TYPE s_slots_lock = portMUX_INITIALIZER_UNLOCKED;
static ble_midi_rx_cb_t s_rx_cb;
static uint16_t s_midi_chr_val_handle;
static uint8_t s_own_addr_type = BLE_OWN_ADDR_PUBLIC;
static bool s_synced;
static bool s_start_requested;

static volatile uint32_t s_rx_unsupported;
static volatile uint32_t s_tx_sent;
static volatile uint32_t s_tx_dropped;

static int ble_midi_gap_event(struct ble_gap_event *event, void *arg);
static void start_advertising_if_needed(void);

static int connected_slot_count_locked(void)
{
    int count = 0;
    for (int i = 0; i < BLE_MIDI_MAX_PORTS; i++) {
        if (s_slots[i].connected) {
            count++;
        }
    }
    return count;
}

static int connected_slot_count(void)
{
    portENTER_CRITICAL(&s_slots_lock);
    const int count = connected_slot_count_locked();
    portEXIT_CRITICAL(&s_slots_lock);
    return count;
}

static int find_slot_by_conn_handle_locked(uint16_t conn_handle)
{
    for (int i = 0; i < BLE_MIDI_MAX_PORTS; i++) {
        if (s_slots[i].connected && s_slots[i].conn_handle == conn_handle) {
            return i;
        }
    }
    return -1;
}

static uint8_t allocate_default_cable_locked(int self_slot)
{
    for (uint8_t cable = 0; cable < BLE_MIDI_MAX_PORTS; cable++) {
        bool used = false;
        for (int i = 0; i < BLE_MIDI_MAX_PORTS; i++) {
            if (i != self_slot && s_slots[i].connected && s_slots[i].cable_num == cable) {
                used = true;
                break;
            }
        }
        if (!used) {
            return cable;
        }
    }
    return (uint8_t)(self_slot % BLE_MIDI_MAX_PORTS);
}

/** 向指定连接下发端口绑定回执 [0xF4, 0x7D, actual_cable]，让手机 UI 同步真实分配到的端口。 */
static void notify_port_assignment(uint16_t conn_handle, uint8_t cable_num)
{
    const uint16_t ts13 = (uint16_t)((xTaskGetTickCount() * portTICK_PERIOD_MS) & 0x1FFF);
    const uint8_t pkt[5] = {
        (uint8_t)(0x80 | ((ts13 >> 7) & 0x3F)),
        (uint8_t)(0x80 | (ts13 & 0x7F)),
        0xF4,
        0x7D,
        (uint8_t)(cable_num & 0x01),
    };
    struct os_mbuf *om = ble_hs_mbuf_from_flat(pkt, sizeof(pkt));
    if (om != NULL) {
        (void)ble_gatts_notify_custom(conn_handle, s_midi_chr_val_handle, om);
    }
}

static void bind_slot_to_cable(int slot_idx, uint8_t desired_cable, bool force_claim)
{
    if (slot_idx < 0 || slot_idx >= BLE_MIDI_MAX_PORTS || desired_cable >= BLE_MIDI_MAX_PORTS) {
        return;
    }

    uint16_t self_handle = 0;
    uint8_t self_cable = 0;
    bool self_notify = false;
    uint16_t other_handle = 0;
    uint8_t other_cable = 0;
    bool other_notify = false;
    bool rejected = false;

    portENTER_CRITICAL(&s_slots_lock);
    if (!s_slots[slot_idx].connected) {
        portEXIT_CRITICAL(&s_slots_lock);
        return;
    }

    int other_idx = -1;
    for (int i = 0; i < BLE_MIDI_MAX_PORTS; i++) {
        if (i != slot_idx && s_slots[i].connected && s_slots[i].cable_num == desired_cable) {
            other_idx = i;
            break;
        }
    }

    if (other_idx >= 0 && !force_claim && s_slots[other_idx].explicit_claim) {
        /*
         * F-02 / F-03 防护：目标端口已被另一台正在演出的手机显式认领，
         * 且本次仅是连接/重连时的自动握手（!force_claim），拒绝抢占并分配空闲端口。
         */
        s_slots[slot_idx].cable_num = allocate_default_cable_locked(slot_idx);
        s_slots[slot_idx].explicit_claim = true;
        rejected = true;
    } else {
        const uint8_t fallback_cable = (uint8_t)((desired_cable + 1) % BLE_MIDI_MAX_PORTS);
        if (other_idx >= 0) {
            s_slots[other_idx].cable_num = fallback_cable;
            other_handle = s_slots[other_idx].conn_handle;
            other_cable = fallback_cable;
            other_notify = s_slots[other_idx].notify_enabled;
        }
        s_slots[slot_idx].cable_num = desired_cable;
        s_slots[slot_idx].explicit_claim = true;
    }

    self_handle = s_slots[slot_idx].conn_handle;
    self_cable = s_slots[slot_idx].cable_num;
    self_notify = s_slots[slot_idx].notify_enabled;
    portEXIT_CRITICAL(&s_slots_lock);

    if (rejected) {
        ESP_LOGW(TAG, "slot=%d handle=%u auto-claim Port %u rejected (in use); assigned Port %u",
                 slot_idx, self_handle, desired_cable + 1, self_cable + 1);
    } else {
        ESP_LOGI(TAG, "slot=%d handle=%u bound to USB Port %u (cable=%u, force=%d)",
                 slot_idx, self_handle, self_cable + 1, self_cable, (int)force_claim);
    }

    if (self_notify) {
        notify_port_assignment(self_handle, self_cable);
    }
    if (other_notify) {
        ESP_LOGI(TAG, " handle=%u reassigned to USB Port %u (cable=%u)",
                 other_handle, other_cable + 1, other_cable);
        notify_port_assignment(other_handle, other_cable);
    }
}

static void dispatch_ble_midi_message(int slot_idx, const uint8_t *msg, uint16_t msg_len)
{
    if (slot_idx < 0 || slot_idx >= BLE_MIDI_MAX_PORTS || msg == NULL || msg_len == 0) {
        return;
    }

    /*
     * App 端口身份握手包：[0xF4, 0x7D, payload]
     * bit 0: desired_cable (0 = Port 1, 1 = Port 2)
     * bit 4: force_claim (0 = 连接自动握手，不抢占已认领端口；1 = 用户手动切换，强制互换)
     */
    if (msg_len == 3 && msg[0] == 0xF4 && msg[1] == 0x7D && (msg[2] & 0x0F) < BLE_MIDI_MAX_PORTS) {
        const uint8_t desired_cable = (uint8_t)(msg[2] & 0x0F);
        const bool force_claim = (msg[2] & 0x10) != 0;
        bind_slot_to_cable(slot_idx, desired_cable, force_claim);
        return;
    }

    if (msg_len > 3) {
        s_rx_unsupported++;
        return;
    }

    const uint8_t status = msg[0];
    const bool channel_voice = (status >= 0x80 && status <= 0xEF);
    const bool realtime = (status >= 0xF8);

    if (channel_voice) {
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
        s_rx_unsupported++;
        return;
    }

    if (s_rx_cb != NULL) {
        uint8_t cable_num = 0;
        bool connected = false;
        portENTER_CRITICAL(&s_slots_lock);
        if (s_slots[slot_idx].connected) {
            cable_num = s_slots[slot_idx].cable_num;
            connected = true;
        }
        portEXIT_CRITICAL(&s_slots_lock);
        if (connected) {
            s_rx_cb(cable_num, msg, (uint8_t)msg_len, esp_timer_get_time());
        }
    }
}

static void parse_ble_midi_packet(int slot_idx, const uint8_t *data, uint16_t len)
{
    if (data == NULL || len < 3 || (data[0] & 0x80) == 0) {
        return;
    }

    uint16_t idx = 1;
    uint8_t running = 0;

    while (idx < len) {
        if ((data[idx] & 0x80) == 0) {
            break;
        }
        idx++; /* timestamp low byte */

        while (idx < len && data[idx] >= 0xF8) {
            uint8_t rt = data[idx++];
            dispatch_ble_midi_message(slot_idx, &rt, 1);
        }
        if (idx >= len) {
            break;
        }

        uint8_t b0 = data[idx];
        if (b0 == 0xF0) {
            /* 检查是否为 App SysEx 端口握手 [F0 7D 01 <port> F7] */
            uint16_t sysex_start = idx;
            idx++;
            while (idx < len && data[idx] != 0xF7) {
                if ((data[idx] & 0x80) != 0 && data[idx] < 0xF8) {
                    break;
                }
                idx++;
            }
            if (idx < len && data[idx] == 0xF7) {
                uint16_t sysex_len = (uint16_t)(idx - sysex_start + 1);
                if (sysex_len == 5 &&
                    data[sysex_start + 1] == 0x7D &&
                    data[sysex_start + 2] == 0x01 &&
                    (data[sysex_start + 3] & 0x0F) < BLE_MIDI_MAX_PORTS) {
                    bind_slot_to_cable(slot_idx,
                                       (uint8_t)(data[sysex_start + 3] & 0x0F),
                                       (data[sysex_start + 3] & 0x10) != 0);
                } else {
                    s_rx_unsupported++;
                }
                idx++;
            } else {
                s_rx_unsupported++;
            }
            continue;
        }

        uint8_t msg[3];
        uint16_t mlen = 0;
        if (b0 & 0x80) {
            uint8_t status = b0;
            idx++;
            if (status >= 0x80 && status <= 0xEF) {
                uint8_t type = status & 0xF0;
                uint8_t need = (type == 0xC0 || type == 0xD0) ? 1 : 2;
                if ((idx + need) > len || (data[idx] & 0x80)) {
                    break;
                }
                msg[0] = status;
                msg[1] = data[idx++];
                if (need == 2) {
                    if (data[idx] & 0x80) {
                        break;
                    }
                    msg[2] = data[idx++];
                }
                mlen = 1 + need;
                running = status;
            } else {
                uint8_t need = 0;
                switch (status) {
                case 0xF1:
                case 0xF3:
                    need = 1;
                    break;
                case 0xF2:
                case 0xF4:
                    need = 2;
                    break;
                default:
                    need = 0;
                    break;
                }
                if ((idx + need) > len) {
                    break;
                }
                if (need >= 1 && (data[idx] & 0x80)) {
                    break;
                }
                msg[0] = status;
                if (need >= 1) {
                    msg[1] = data[idx++];
                }
                if (need == 2) {
                    if (data[idx] & 0x80) {
                        break;
                    }
                    msg[2] = data[idx++];
                }
                mlen = 1 + need;
            }
        } else {
            if (running == 0) {
                break;
            }
            uint8_t status = running;
            uint8_t type = status & 0xF0;
            uint8_t need = (type == 0xC0 || type == 0xD0) ? 1 : 2;
            if ((idx + need) > len || (data[idx] & 0x80)) {
                break;
            }
            msg[0] = status;
            msg[1] = data[idx++];
            if (need == 2) {
                if (data[idx] & 0x80) {
                    break;
                }
                msg[2] = data[idx++];
            }
            mlen = 1 + need;
        }

        if (mlen > 0) {
            dispatch_ble_midi_message(slot_idx, msg, mlen);
        }
    }
}

static int gatt_svr_chr_access_midi(uint16_t conn_handle, uint16_t attr_handle,
                                    struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)attr_handle;
    (void)arg;

    switch (ctxt->op) {
    case BLE_GATT_ACCESS_OP_READ_CHR:
        return 0;

    case BLE_GATT_ACCESS_OP_WRITE_CHR: {
        const uint16_t om_len = OS_MBUF_PKTLEN(ctxt->om);
        if (om_len == 0 || om_len > BLE_MIDI_MAX_PKT_SIZE) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        uint8_t buf[BLE_MIDI_MAX_PKT_SIZE];
        uint16_t flat_len = 0;
        if (ble_hs_mbuf_to_flat(ctxt->om, buf, sizeof(buf), &flat_len) != 0) {
            return BLE_ATT_ERR_UNLIKELY;
        }
        portENTER_CRITICAL(&s_slots_lock);
        const int slot_idx = find_slot_by_conn_handle_locked(conn_handle);
        portEXIT_CRITICAL(&s_slots_lock);
        if (slot_idx >= 0) {
            parse_ble_midi_packet(slot_idx, buf, flat_len);
        }
        return 0;
    }

    default:
        return BLE_ATT_ERR_UNLIKELY;
    }
}

static const ble_uuid128_t s_midi_svc_uuid =
    BLE_UUID128_INIT(0x00, 0xC7, 0xC4, 0x4E, 0xE3, 0x6C, 0x51, 0xA7,
                     0x33, 0x4B, 0xE8, 0xED, 0x5A, 0x0E, 0xB8, 0x03);

static const ble_uuid128_t s_midi_chr_uuid =
    BLE_UUID128_INIT(0xF3, 0x6B, 0x10, 0x9D, 0x66, 0xF2, 0xA9, 0xA1,
                     0x12, 0x41, 0x68, 0x38, 0xDB, 0xE5, 0x72, 0x77);

static const struct ble_gatt_svc_def s_gatt_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &s_midi_svc_uuid.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = &s_midi_chr_uuid.u,
                .access_cb = gatt_svr_chr_access_midi,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE_NO_RSP | BLE_GATT_CHR_F_NOTIFY,
                .val_handle = &s_midi_chr_val_handle,
            },
            { 0 }
        },
    },
    { 0 }
};

static void start_advertising_if_needed(void)
{
    if (!s_synced || !s_start_requested) {
        return;
    }
    if (connected_slot_count() >= BLE_MIDI_MAX_PORTS) {
        /* 两台手机均已连上，不开启广播（0 广播开销） */
        if (ble_gap_ext_adv_active(BLE_ADV_INSTANCE)) {
            ble_gap_ext_adv_stop(BLE_ADV_INSTANCE);
        }
        return;
    }
    if (ble_gap_ext_adv_active(BLE_ADV_INSTANCE)) {
        return;
    }

    struct ble_gap_ext_adv_params adv_params;
    memset(&adv_params, 0, sizeof(adv_params));
    adv_params.connectable = 1;
    adv_params.own_addr_type = s_own_addr_type;
    adv_params.primary_phy = BLE_HCI_LE_PHY_1M;
    adv_params.secondary_phy = BLE_HCI_LE_PHY_1M;
    adv_params.sid = 0;
    adv_params.itvl_min = 0x100; /* 160 ms */
    adv_params.itvl_max = 0x100;
    adv_params.tx_power = 127;

    int rc = ble_gap_ext_adv_configure(BLE_ADV_INSTANCE, &adv_params, NULL,
                                       ble_midi_gap_event, NULL);
    if (rc == BLE_HS_EBUSY) {
        ble_gap_ext_adv_stop(BLE_ADV_INSTANCE);
        rc = ble_gap_ext_adv_configure(BLE_ADV_INSTANCE, &adv_params, NULL,
                                       ble_midi_gap_event, NULL);
    }
    if (rc != 0) {
        ESP_LOGE(TAG, "ext_adv_configure failed rc=%d", rc);
        return;
    }

    struct os_mbuf *data = os_msys_get_pkthdr(sizeof(s_ext_adv_data), 0);
    if (data == NULL) {
        ESP_LOGE(TAG, "ext_adv mbuf alloc failed");
        return;
    }
    rc = os_mbuf_append(data, s_ext_adv_data, sizeof(s_ext_adv_data));
    if (rc != 0) {
        os_mbuf_free_chain(data);
        ESP_LOGE(TAG, "ext_adv mbuf append failed rc=%d", rc);
        return;
    }
    rc = ble_gap_ext_adv_set_data(BLE_ADV_INSTANCE, data);
    if (rc != 0) {
        ESP_LOGE(TAG, "ext_adv_set_data failed rc=%d", rc);
        return;
    }

    rc = ble_gap_ext_adv_start(BLE_ADV_INSTANCE, 0, 0);
    if (rc != 0 && rc != BLE_HS_EALREADY) {
        ESP_LOGE(TAG, "ext_adv_start failed rc=%d", rc);
        return;
    }
    ESP_LOGI(TAG, "advertising as %s (connected=%d/%d)",
             BLE_DEVICE_NAME, connected_slot_count(), BLE_MIDI_MAX_PORTS);
}

static int ble_midi_gap_event(struct ble_gap_event *event, void *arg)
{
    (void)arg;

    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT: {
        if (event->connect.status != 0) {
            ESP_LOGW(TAG, "connect failed status=%d", event->connect.status);
            start_advertising_if_needed();
            return 0;
        }

        const uint16_t handle = event->connect.conn_handle;
        int slot_idx = -1;
        uint8_t default_cable = 0;
        int total_connected = 0;
        portENTER_CRITICAL(&s_slots_lock);
        for (int i = 0; i < BLE_MIDI_MAX_PORTS; i++) {
            if (!s_slots[i].connected) {
                slot_idx = i;
                break;
            }
        }
        if (slot_idx >= 0) {
            default_cable = allocate_default_cable_locked(slot_idx);
            s_slots[slot_idx].connected = true;
            s_slots[slot_idx].notify_enabled = false;
            s_slots[slot_idx].explicit_claim = false;
            s_slots[slot_idx].conn_handle = handle;
            s_slots[slot_idx].mtu = 23;
            s_slots[slot_idx].cable_num = default_cable;
            total_connected = connected_slot_count_locked();
        }
        portEXIT_CRITICAL(&s_slots_lock);

        if (slot_idx < 0) {
            ESP_LOGW(TAG, "no free slot for handle=%u; disconnecting", handle);
            ble_gap_terminate(handle, BLE_ERR_REM_USER_CONN_TERM);
            return 0;
        }

        ESP_LOGI(TAG, "connected slot=%d handle=%u default_cable=%u (connected=%d/%d)",
                 slot_idx, handle, default_cable, total_connected, BLE_MIDI_MAX_PORTS);

        struct ble_gap_upd_params params = {
            .itvl_min = 6,              /* 7.5 ms */
            .itvl_max = 12,             /* 15 ms */
            .latency = 0,
            .supervision_timeout = 400, /* 4 s */
            .min_ce_len = 0,
            .max_ce_len = 0,
        };
        ble_gap_update_params(handle, &params);
        ble_gap_set_prefered_le_phy(handle, BLE_GAP_LE_PHY_2M_MASK, BLE_GAP_LE_PHY_2M_MASK, 0);
        ble_hs_hci_util_set_data_len(handle, 251, 2120);
        ble_gattc_exchange_mtu(handle, NULL, NULL);

        /* 若还没连满 2 台手机，立即重新开启广播等待第 2 台手机连入 */
        start_advertising_if_needed();
        return 0;
    }

    case BLE_GAP_EVENT_DISCONNECT: {
        const uint16_t handle = event->disconnect.conn.conn_handle;
        int slot_idx = -1;
        uint8_t old_cable = 0;
        portENTER_CRITICAL(&s_slots_lock);
        slot_idx = find_slot_by_conn_handle_locked(handle);
        if (slot_idx >= 0) {
            old_cable = s_slots[slot_idx].cable_num;
            memset(&s_slots[slot_idx], 0, sizeof(s_slots[slot_idx]));
        }
        portEXIT_CRITICAL(&s_slots_lock);
        if (slot_idx >= 0) {
            ESP_LOGI(TAG, "disconnected slot=%d handle=%u cable=%u reason=0x%04x",
                     slot_idx, handle, old_cable, event->disconnect.reason);
        }
        start_advertising_if_needed();
        return 0;
    }

    case BLE_GAP_EVENT_SUBSCRIBE: {
        if (event->subscribe.attr_handle == s_midi_chr_val_handle) {
            const bool enabled = (event->subscribe.cur_notify != 0);
            int slot_idx = -1;
            uint8_t cable_num = 0;
            portENTER_CRITICAL(&s_slots_lock);
            slot_idx = find_slot_by_conn_handle_locked(event->subscribe.conn_handle);
            if (slot_idx >= 0) {
                s_slots[slot_idx].notify_enabled = enabled;
                cable_num = s_slots[slot_idx].cable_num;
            }
            portEXIT_CRITICAL(&s_slots_lock);
            if (slot_idx >= 0) {
                ESP_LOGI(TAG, "slot=%d handle=%u cable=%u MIDI notify %s",
                         slot_idx, event->subscribe.conn_handle, cable_num,
                         enabled ? "enabled" : "disabled");
                if (enabled) {
                    notify_port_assignment(event->subscribe.conn_handle, cable_num);
                }
            }
        }
        return 0;
    }

    case BLE_GAP_EVENT_MTU: {
        int slot_idx = -1;
        portENTER_CRITICAL(&s_slots_lock);
        slot_idx = find_slot_by_conn_handle_locked(event->mtu.conn_handle);
        if (slot_idx >= 0) {
            s_slots[slot_idx].mtu = event->mtu.value;
        }
        portEXIT_CRITICAL(&s_slots_lock);
        if (slot_idx >= 0) {
            ESP_LOGI(TAG, "slot=%d handle=%u MTU=%u", slot_idx, event->mtu.conn_handle, event->mtu.value);
        }
        return 0;
    }

    case BLE_GAP_EVENT_ADV_COMPLETE:
        start_advertising_if_needed();
        return 0;

    default:
        return 0;
    }
}

static void ble_midi_on_reset(int reason)
{
    ESP_LOGW(TAG, "NimBLE reset reason=%d", reason);
    s_synced = false;
}

static void ble_midi_on_sync(void)
{
    int rc = ble_hs_util_ensure_addr(0);
    if (rc != 0) {
        ESP_LOGE(TAG, "ensure_addr failed rc=%d", rc);
        return;
    }
    rc = ble_hs_id_infer_auto(0, &s_own_addr_type);
    if (rc != 0) {
        ESP_LOGE(TAG, "infer_auto failed rc=%d", rc);
        return;
    }
    s_synced = true;
    start_advertising_if_needed();
}

static void ble_midi_host_task(void *param)
{
    (void)param;
    nimble_port_run();
    nimble_port_freertos_deinit();
}

esp_err_t ble_midi_link_init(ble_midi_rx_cb_t cb)
{
    s_rx_cb = cb;
    memset(s_slots, 0, sizeof(s_slots));

    esp_err_t err = nimble_port_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "nimble_port_init failed: %s", esp_err_to_name(err));
        return err;
    }

    ble_hs_cfg.reset_cb = ble_midi_on_reset;
    ble_hs_cfg.sync_cb = ble_midi_on_sync;
    ble_hs_cfg.store_status_cb = ble_store_util_status_rr;
    ble_hs_cfg.sm_io_cap = BLE_SM_IO_CAP_NO_IO;
    ble_hs_cfg.sm_sc = 1;

    ble_svc_gap_init();
    ble_svc_gatt_init();

    int rc = ble_gatts_count_cfg(s_gatt_svcs);
    if (rc != 0) {
        ESP_LOGE(TAG, "ble_gatts_count_cfg failed rc=%d", rc);
        return ESP_FAIL;
    }
    rc = ble_gatts_add_svcs(s_gatt_svcs);
    if (rc != 0) {
        ESP_LOGE(TAG, "ble_gatts_add_svcs failed rc=%d", rc);
        return ESP_FAIL;
    }

    ble_svc_gap_device_name_set(BLE_DEVICE_NAME);
    nimble_port_freertos_init(ble_midi_host_task);
    return ESP_OK;
}

esp_err_t ble_midi_link_start(void)
{
    s_start_requested = true;
    start_advertising_if_needed();
    return ESP_OK;
}

bool ble_midi_link_port_ready(uint8_t cable_num)
{
    bool ready = false;
    portENTER_CRITICAL(&s_slots_lock);
    for (int i = 0; i < BLE_MIDI_MAX_PORTS; i++) {
        if (s_slots[i].connected && s_slots[i].cable_num == cable_num) {
            ready = s_slots[i].notify_enabled;
            break;
        }
    }
    portEXIT_CRITICAL(&s_slots_lock);
    return ready;
}

esp_err_t ble_midi_link_send(uint8_t cable_num, const uint8_t *const msgs[], const uint8_t lens[], uint16_t count)
{
    if (msgs == NULL || lens == NULL || count == 0 || count > BLE_MIDI_MAX_BATCH || cable_num >= BLE_MIDI_MAX_PORTS) {
        return ESP_ERR_INVALID_ARG;
    }

    bool ready = false;
    uint16_t conn_handle = 0;
    uint16_t mtu = 23;
    portENTER_CRITICAL(&s_slots_lock);
    for (int i = 0; i < BLE_MIDI_MAX_PORTS; i++) {
        if (s_slots[i].connected && s_slots[i].cable_num == cable_num) {
            ready = s_slots[i].notify_enabled;
            conn_handle = s_slots[i].conn_handle;
            mtu = s_slots[i].mtu >= 23 ? s_slots[i].mtu : 23;
            break;
        }
    }
    portEXIT_CRITICAL(&s_slots_lock);

    if (!ready) {
        s_tx_dropped += count;
        return ESP_ERR_INVALID_STATE;
    }

    const uint16_t max_payload = (mtu - 3 <= BLE_MIDI_MAX_PKT_SIZE) ? (mtu - 3) : BLE_MIDI_MAX_PKT_SIZE;
    const uint16_t ts13 = (uint16_t)((xTaskGetTickCount() * portTICK_PERIOD_MS) & 0x1FFF);

    uint8_t pkt[BLE_MIDI_MAX_PKT_SIZE];
    uint16_t pos = 0;
    pkt[pos++] = (uint8_t)(0x80 | ((ts13 >> 7) & 0x3F));
    const uint8_t ts_low = (uint8_t)(0x80 | (ts13 & 0x7F));

    uint16_t sent_msgs = 0;
    for (uint16_t i = 0; i < count; i++) {
        const uint8_t mlen = lens[i];
        if (msgs[i] == NULL || mlen == 0 || mlen > 3) {
            continue;
        }
        if (pos + 1 + mlen > max_payload) {
            break;
        }
        pkt[pos++] = ts_low;
        memcpy(&pkt[pos], msgs[i], mlen);
        pos += mlen;
        sent_msgs++;
    }

    if (sent_msgs == 0) {
        s_tx_dropped += count;
        return ESP_ERR_INVALID_SIZE;
    }

    struct os_mbuf *om = ble_hs_mbuf_from_flat(pkt, pos);
    if (om == NULL) {
        s_tx_dropped += count;
        return ESP_ERR_NO_MEM;
    }

    const int rc = ble_gatts_notify_custom(conn_handle, s_midi_chr_val_handle, om);
    if (rc != 0) {
        s_tx_dropped += count;
        return ESP_FAIL;
    }

    s_tx_sent += sent_msgs;
    if (count > sent_msgs) {
        s_tx_dropped += (uint32_t)(count - sent_msgs);
    }
    return ESP_OK;
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
