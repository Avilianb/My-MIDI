# BLE-MIDI Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the verified USB-MIDI firmware into a bidirectional standard BLE-MIDI peripheral that bridges phone MIDI to USB and USB MIDI back to the phone.

**Architecture:** The ESP32-S3 runs NimBLE with Espressif's `ble_midi` profile and `ble_conn_mgr`; TinyUSB remains a single-cable USB-MIDI device. Two FreeRTOS queues isolate the BLE host callback from USB: `BLE → USB` carries parsed channel-voice messages, `USB → BLE` carries USB-MIDI packets batched into BLE-MIDI notifications. Only one task writes USB and only one task sends BLE notifications.

**Tech Stack:** ESP-IDF 5.5.2, ESP32-S3 native USB, TinyUSB MIDI 1.0, NimBLE, `espressif/ble_midi` 0.1.0~2, `espressif/ble_conn_mgr` ~1.*, `espressif/ble_services` ~1.*, nRF Connect, Ableton Live 12.

**Spec:** `docs/superpowers/specs/2026-10-20-phone-ble-usb-midi-controller-design.md`

## Global Constraints

- Firmware target: ESP32-S3 only; ESP-IDF 5.5.2; native USB port; UART0/COM5 is logs/flashing only.
- USB identity stays exactly: VID/PID `0x303A/0x4008`, Manufacturer `Remote MIDI`, Product `ESP32-S3 MIDI Bridge`, Serial `ESP32S3-MIDI-01`, one MIDI interface, cable 0.
- BLE device name is exactly `ESP32-S3 MIDI`.
- BLE-MIDI service UUID: `03B80E5A-EDE8-4B33-A751-6CE34EC4C700`; IO characteristic UUID: `7772E5DB-3868-4112-A1A9-F2669D106BF3`; Write Without Response + Notify.
- Standard BLE-MIDI only; no custom GATT, no control protocol, no firmware control table.
- Phase 1 does **not** forward SysEx; it forwards channel-voice and real-time messages only. Drop SysEx with a counter.
- No Android app work in this plan; no pairing/bonding; no USB status GATT.
- Queues are depth 256. No per-message UART logging in hot paths; counters and p50/p95 logs only.
- New code comments are Chinese; identifiers and protocol names stay English.
- If `ble_midi`/`ble_conn_mgr`/`ble_services` cannot build or advertise on IDF 5.5.2 + S3, stop and write a separate NimBLE fallback plan. Do not mix two BLE stacks.
- Commit after every task; do not amend earlier commits.

## Review Focus

These are the failure modes a reasonable user would hit; each has a test in the task that owns it.

1. A 14-bit CC pair sent as two messages in one BLE notification must reach USB in order and not collapse to one CC.
2. USB MIDI arriving before the phone subscribes (notify disabled) must be dropped without blocking or crashing.
3. BLE disconnect while a Note On is held must stop the note via CC 123/120, not leave it ringing.
4. USB unplug/replug with queued messages must not replay stale notes after re-enumeration.
5. A 50–100 Hz CC stream must not produce partial USB writes, queue overflow crashes, or a stuck final value.

---

## File Structure

| File | Responsibility |
|---|---|
| `firmware/main/app_main.c` | Boot order: NVS, event loop, USB, bridge, BLE, start tasks. |
| `firmware/main/usb_midi_device.c` | TinyUSB descriptors, driver install, packet read, stream write. No MIDI policy. |
| `firmware/main/usb_midi_device.h` | USB layer API. |
| `firmware/main/ble_midi_link.c` | BLE init, advertising, connection events, MIDI event callback, notification send. |
| `firmware/main/ble_midi_link.h` | BLE layer API and stats struct. |
| `firmware/main/midi_bridge.c` | Queues, USB↔BLE tasks, CIN parsing, panic, latency histogram. |
| `firmware/main/midi_bridge.h` | Bridge init/start and BLE receive hook. |
| `firmware/main/idf_component.yml` | Component versions. |
| `firmware/sdkconfig.defaults` | NimBLE/peripheral/MIDI/extended-advertising switches. |
| `firmware/main/CMakeLists.txt` | Sources and private component requirements. |
| `firmware/README.md` | Stage 3 build, test packets, acceptance, known limits. |

The old `tusb_midi_main.c` is renamed to `usb_midi_device.c` in Task 2; its periodic test note and timer are deleted. Do not leave a second copy.

---

### Task 1: Add BLE-MIDI stack and advertise

**Files:**
- Modify: `firmware/main/idf_component.yml`
- Modify: `firmware/sdkconfig.defaults`
- Modify: `firmware/main/CMakeLists.txt`
- Create: `firmware/main/ble_midi_link.h`
- Create: `firmware/main/ble_midi_link.c`
- Modify: `firmware/main/tusb_midi_main.c` (temporary init call; split in Task 2)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `typedef void (*ble_midi_rx_cb_t)(const uint8_t *msg, uint8_t len, int64_t rx_time_us);`
  - `esp_err_t ble_midi_link_init(ble_midi_rx_cb_t cb);`
  - `esp_err_t ble_midi_link_start(void);`
  - `bool ble_midi_link_ready(void);`
  - `esp_err_t ble_midi_link_send(const uint8_t *const msgs[], const uint8_t lens[], uint16_t count);`
  - `void ble_midi_link_get_stats(ble_midi_link_stats_t *out);`

- [ ] **Step 1: Write the failing acceptance check**

Before editing, build/flash the current branch and scan with nRF Connect. Expected: no device named `ESP32-S3 MIDI`; no BLE-MIDI service. Record the result in the task note.

- [ ] **Step 2: Add component dependencies**

`firmware/main/idf_component.yml`:

```yaml
dependencies:
  espressif/esp_tinyusb:
    version: "^2.0.0"
  espressif/ble_midi:
    version: "0.1.0~2"
  espressif/ble_conn_mgr:
    version: "~1.*"
  espressif/ble_services:
    version: "~1.*"
  idf: "^5.0"
```

- [ ] **Step 3: Add sdkconfig defaults**

`firmware/sdkconfig.defaults`:

```ini
CONFIG_TINYUSB_MIDI_COUNT=1

# BLE-MIDI peripheral
CONFIG_BT_ENABLED=y
CONFIG_BT_NIMBLE_ENABLED=y
CONFIG_BT_NIMBLE_MAX_CONNECTIONS=1
CONFIG_BLE_CONN_MGR_ROLE_PERIPHERAL=y
CONFIG_BLE_MIDI_PROFILE=y

# 128-bit service UUID needs extended advertising on a 31-byte legacy payload
CONFIG_BLE_CONN_MGR_EXTENDED_ADV=y
```

Delete the generated `firmware/sdkconfig` once so the new defaults take effect; do not commit it.

After the first build, check whether the S3 controller exposes modem sleep:

```powershell
Select-String -Path firmware/sdkconfig -Pattern 'BT_CTRL_MODEM_SLEEP'
```

If the symbol exists and is `=y`, add `CONFIG_BT_CTRL_MODEM_SLEEP=n` to `firmware/sdkconfig.defaults`, delete `firmware/sdkconfig`, and rebuild. If the symbol is absent, record that the S3 controller has no such switch; do not invent one.

- [ ] **Step 4: Create the BLE layer header**

`firmware/main/ble_midi_link.h`:

```c
#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

/** BLE 收到一条完整 MIDI 消息后的回调；在 BLE host task 中调用，必须只入队。 */
typedef void (*ble_midi_rx_cb_t)(const uint8_t *msg, uint8_t len, int64_t rx_time_us);

typedef struct {
    uint32_t rx_unsupported; /* SysEx / 非通道消息丢弃数 */
    uint32_t tx_sent;        /* 成功写入通知的消息数 */
    uint32_t tx_dropped;     /* 通知未启用或发送失败的消息数 */
} ble_midi_link_stats_t;

esp_err_t ble_midi_link_init(ble_midi_rx_cb_t cb);
esp_err_t ble_midi_link_start(void);
bool ble_midi_link_ready(void);

/** msgs 指向最多 8 条完整 MIDI 消息；每条 1..3 字节。 */
esp_err_t ble_midi_link_send(const uint8_t *const msgs[], const uint8_t lens[], uint16_t count);

void ble_midi_link_get_stats(ble_midi_link_stats_t *out);

#ifdef __cplusplus
}
#endif
```

- [ ] **Step 5: Implement BLE init, advertising, and callback filtering**

`firmware/main/ble_midi_link.c` must:

- Include `esp_ble_conn_mgr.h`, `esp_ble_midi.h`, `esp_ble_midi_svc.h`, `esp_event.h`, `esp_log.h`, `esp_timer.h`, `string.h`.
- Define `BLE_DEVICE_NAME "ESP32-S3 MIDI"`.
- In `ble_midi_link_init(cb)`:
  - Zero an `esp_ble_conn_config_t`.
  - Copy the name into `config.device_name` with `strlcpy`.
  - Set `config.include_service_uuid = 1`, `config.adv_uuid_type = BLE_CONN_UUID_TYPE_128`.
  - Copy `BLE_MIDI_SERVICE_UUID128` into `config.adv_uuid128`.
  - With `CONFIG_BLE_CONN_MGR_EXTENDED_ADV=y`, `ble_conn_mgr` does **not** auto-append the service UUID. Build an explicit AD payload containing Flags + Complete 128-bit Service UUID + Complete Local Name; put it in `extended_adv_data`. NimBLE rejects non-legacy `connectable + scannable`, so use a connectable-only non-legacy PDU (or a legacy PDU with a payload <=31 bytes; UUID+name does not fit).
  - Call in this order: `esp_ble_conn_init(&config)`, `esp_ble_midi_svc_init()`, `esp_ble_midi_profile_init()`, `esp_ble_midi_register_event_cb()`, `esp_event_handler_register(BLE_CONN_MGR_EVENTS, ESP_EVENT_ANY_ID, conn_event_handler, NULL)`. Do not call `esp_ble_conn_set_mtu()`; it is a legacy no-op and logs a warning. Request MTU with `esp_ble_conn_mtu_update()` after connect.
  - Store `cb`; a NULL callback is allowed and drops parsed MIDI.
- `ble_midi_link_start()` calls `esp_ble_conn_start()`.
- `ble_midi_link_ready()` returns `s_connected && esp_ble_midi_is_notify_enabled()`.
- `ble_midi_link_send()`:
  - Returns `ESP_ERR_INVALID_ARG` if `count == 0 || count > 8`.
  - Returns `ESP_ERR_INVALID_STATE` unless `ble_midi_link_ready()`.
  - Builds a local `uint16_t ts[8]` with `esp_ble_midi_get_timestamp_ms()`.
  - Calls `esp_ble_midi_send_multi(msgs, lens, ts, count, &first_unsent)`.
  - On success, `tx_sent += count`; on error, `sent = first_unsent` (clamp to count), `tx_sent += sent`, `tx_dropped += count - sent`.
- MIDI event callback:
  - On `ESP_BLE_MIDI_EVENT_SYSEX_OVERFLOW`, increment `rx_unsupported` and return.
  - Reject `msg == NULL || msg_len == 0 || msg_len > 3`.
  - Accept channel voice `0x80..0xEF` and real-time `0xF8..0xFF`; channel-voice lengths are 2 or 3, real-time length is 1. Drop everything else (SysEx/system common) with `rx_unsupported++`.
  - Call `s_rx_cb(msg, msg_len, esp_timer_get_time())`; do not log per message.
- Connection event handler:
  - `ESP_BLE_CONN_EVENT_CONNECTED`: `s_connected = true`; `esp_ble_midi_set_notify_enabled(false)`; log handle; request params `itvl_min=6`, `itvl_max=12`, `latency=0`, `supervision_timeout=400`; call `esp_ble_conn_set_preferred_phy(handle, ESP_BLE_CONN_PHY_MASK_2M, ESP_BLE_CONN_PHY_MASK_2M, 0)`; `esp_ble_conn_set_data_len(handle, 251, 2120)`; `esp_ble_conn_mtu_update(handle, 247)`. Log every non-OK return; do not abort the connection.
  - `ESP_BLE_CONN_EVENT_CCCD_UPDATE`: cast to `esp_ble_conn_cccd_update_t *`; compare `uuid_type == BLE_CONN_UUID_TYPE_128` and `memcmp(uuid.uuid128, BLE_MIDI_CHAR_UUID128, 16) == 0`; call `esp_ble_midi_set_notify_enabled(ev->notify_enable)`; log the new state.
  - `ESP_BLE_CONN_EVENT_DISCONNECTED`: `s_connected = false`; `esp_ble_midi_set_notify_enabled(false)`; log handle/reason.
  - `ESP_BLE_CONN_EVENT_MTU`: log negotiated MTU.
  - `ESP_BLE_CONN_EVENT_CONN_PARAM_UPDATE`: log actual interval, latency, timeout.
- `ble_midi_link_get_stats()` copies the three counters under a short critical section or with plain 32-bit reads; log-only accuracy is acceptable.

- [ ] **Step 6: Wire BLE init into the temporary stage-2 main**

In `firmware/main/tusb_midi_main.c`, after TinyUSB install and before the periodic timer:

```c
esp_err_t nvs_ret = nvs_flash_init();
if (nvs_ret == ESP_ERR_NVS_NO_FREE_PAGES || nvs_ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
    ESP_ERROR_CHECK(nvs_flash_erase());
    nvs_ret = nvs_flash_init();
}
ESP_ERROR_CHECK(nvs_ret);
ESP_ERROR_CHECK(esp_event_loop_create_default());

ESP_ERROR_CHECK(ble_midi_link_init(NULL)); /* Task 2 会替换为桥接回调 */
ESP_ERROR_CHECK(ble_midi_link_start());
```

Add includes `nvs_flash.h`, `esp_event.h`, `ble_midi_link.h`. Keep the USB test note running in this task so USB regression is visible.

- [ ] **Step 7: Update CMake**

`firmware/main/CMakeLists.txt`:

```cmake
idf_component_register(SRCS "tusb_midi_main.c" "ble_midi_link.c"
                    INCLUDE_DIRS "."
                    PRIV_REQUIRES esp_timer nvs_flash esp_event ble_conn_mgr ble_midi ble_services
                    )
```

- [ ] **Step 8: Build**

Run from PowerShell:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -Command "Remove-Item Env:MSYSTEM -ErrorAction SilentlyContinue; & 'C:\Espressif\esp-idf-v5.5.2\export.ps1'; Set-Location 'C:\Users\woo\Desktop\remoteesp32midi'; Remove-Item firmware\build -Recurse -Force -ErrorAction SilentlyContinue; Remove-Item firmware\sdkconfig -Force -ErrorAction SilentlyContinue; idf.py -C firmware set-target esp32s3; idf.py -C firmware build"
```

Expected: `Project build complete`. If component resolution fails, capture the failing component/version and stop; do not hand-edit downloaded components.

- [ ] **Step 9: Flash and verify advertising**

Flash `idf.py -C firmware -p COM5 flash monitor`. In nRF Connect:

1. Scan; expect `ESP32-S3 MIDI`.
2. Connect; expect service `03B80E5A-EDE8-4B33-A751-6CE34EC4C700`.
3. Expect characteristic `7772E5DB-3868-4112-A1A9-F2669D106BF3` with Write Without Response and Notify.
4. Enable notifications; UART log shows connected, MTU, and actual connection interval.
5. Ableton still receives the 500 ms test Note On/Off (USB regression).

- [ ] **Step 10: Commit**

```bash
git add firmware/main/idf_component.yml firmware/sdkconfig.defaults firmware/main/CMakeLists.txt firmware/main/ble_midi_link.c firmware/main/ble_midi_link.h firmware/main/tusb_midi_main.c firmware/dependencies.lock
git commit -m "feat: add BLE MIDI peripheral advertising"
```

---

### Task 2: Split USB layer and bridge BLE → USB

**Files:**
- Rename: `firmware/main/tusb_midi_main.c` → `firmware/main/usb_midi_device.c`
- Create: `firmware/main/usb_midi_device.h`
- Create: `firmware/main/app_main.c`
- Create: `firmware/main/midi_bridge.h`
- Create: `firmware/main/midi_bridge.c`
- Modify: `firmware/main/CMakeLists.txt`

**Interfaces:**
- Consumes: `ble_midi_link_init`, `ble_midi_link_start`, `ble_midi_link_ready` from Task 1.
- Produces:
  - `esp_err_t usb_midi_device_init(void);`
  - `bool usb_midi_device_mounted(void);`
  - `uint32_t usb_midi_write(const uint8_t *data, uint32_t len);`
  - `esp_err_t midi_bridge_init(void);`
  - `esp_err_t midi_bridge_start(void);`
  - `void midi_bridge_on_ble_midi(const uint8_t *msg, uint8_t len, int64_t rx_time_us);`

- [ ] **Step 1: Write the failing acceptance check**

With Task 1 flashed and notifications enabled, write Note On `80 80 90 3C 64` in nRF Connect. Expected: nothing in Ableton, because no bridge exists yet.

- [ ] **Step 2: Split USB device code**

`git mv firmware/main/tusb_midi_main.c firmware/main/usb_midi_device.c`. In the renamed file:

- Keep descriptors, string descriptors, `usb_midi_device_init()`, and TinyUSB install.
- Delete `NOTE_OFF`, `NOTE_ON`, `periodic_midi_write_example_cb`, the periodic timer, and `midi_task_read_example`.
- Change the log tag to `usb_midi`.
- Add `usb_midi_device_mounted()` returning `tud_midi_mounted()`.
- Add `usb_midi_write()` returning `tud_midi_stream_write(0, data, len)` when mounted, otherwise `0`.
- Do not add read/task code here yet; Task 3 adds packet read.

`firmware/main/usb_midi_device.h`:

```c
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
```

- [ ] **Step 3: Implement the bridge queue for BLE → USB**

`firmware/main/midi_bridge.h`:

```c
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
```

`firmware/main/midi_bridge.c`:

- `midi_msg_t { uint8_t len; uint8_t data[3]; int64_t rx_time_us; }`.
- `QueueHandle_t s_ble_to_usb` depth 256.
- `midi_bridge_init()` creates the queue; returns `ESP_ERR_NO_MEM` on failure.
- `midi_bridge_on_ble_midi()` rejects NULL, `len < 1`, `len > 3`; copies into a local `midi_msg_t`; `xQueueSend(s_ble_to_usb, &msg, pdMS_TO_TICKS(5))`. This is deliberate backpressure on overflow, not an unbounded block. If it still times out, increment `s_ble_rx_dropped`; acceptance must show zero drops at 100 Hz.
- `usb_task` priority 10, stack 4096:
  - Loop every 1 ms.
  - Drain `s_ble_to_usb`; for each message call `usb_midi_write()` in a retry helper until all bytes are accepted or 5 ms elapse.
  - If USB is not mounted or `ble_midi_link_ready()` is false, drain and count as dropped; do not write.
  - Count `s_ble_to_usb_sent` and `s_ble_to_usb_dropped`.
  - Every 5 s log cumulative `BLE->USB sent/dropped`.
- `midi_bridge_start()` creates `usb_task` and returns `ESP_ERR_NO_MEM` if `xTaskCreate` fails.

- [ ] **Step 4: Create the new app entry point**

`firmware/main/app_main.c`:

```c
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
}
```

- [ ] **Step 5: Update CMake**

```cmake
idf_component_register(SRCS "app_main.c" "usb_midi_device.c" "ble_midi_link.c" "midi_bridge.c"
                    INCLUDE_DIRS "."
                    PRIV_REQUIRES esp_timer nvs_flash esp_event ble_conn_mgr ble_midi ble_services
                    )
```

- [ ] **Step 6: Build and flash**

Run the Task 1 build command. Expected: `Project build complete`; no reference to `tusb_midi_main.c` or the periodic note.

- [ ] **Step 7: Verify BLE → USB**

In nRF Connect, connect and enable notify, then write:

| Action | BLE-MIDI hex | Expected Ableton |
|---|---|---|
| Note On ch1 C4 vel100 | `80 80 90 3C 64` | Note C4 on |
| Note Off ch1 C4 | `80 80 80 3C 00` | Note C4 off |
| CC1 ch1 = 64 | `80 80 B0 01 40` | CC1 maps to a parameter at 64/127 |
| 14-bit CC1 pair, value 8192 | `80 80 B0 01 40 80 B0 21 00` | CC1=64 and CC33=0 both arrive, in this order |

For CC verification, use Ableton MIDI Map mode on two parameters (CC1 and CC33) or a MIDI monitor. Expected UART: `BLE->USB sent` increments; no per-message logs.

- [ ] **Step 8: Commit**

```bash
git add firmware/main/app_main.c firmware/main/usb_midi_device.c firmware/main/usb_midi_device.h firmware/main/midi_bridge.c firmware/main/midi_bridge.h firmware/main/CMakeLists.txt
git commit -m "feat: bridge BLE MIDI messages to USB"
```

---

### Task 3: Bridge USB → BLE

**Files:**
- Modify: `firmware/main/usb_midi_device.c`
- Modify: `firmware/main/usb_midi_device.h`
- Modify: `firmware/main/midi_bridge.c`

**Interfaces:**
- Consumes: `ble_midi_link_send`, `ble_midi_link_ready`, `ble_midi_link_get_stats` from Task 1; `usb_midi_write` from Task 2.
- Produces:
  - `uint32_t usb_midi_read_packets(uint8_t *packets, uint32_t max_packets);`
  - USB→BLE task behavior inside `midi_bridge.c`.

- [ ] **Step 1: Write the failing acceptance check**

With Task 2 flashed, enable MIDI Map mode in Ableton and send CC1 from a MIDI clip routed to `ESP32-S3 MIDI Bridge`. Expected: nRF Connect shows no notification, because USB→BLE is not implemented.

- [ ] **Step 2: Add USB packet read**

In `usb_midi_device.h`:

```c
/** packets 必须是 max_packets * 4 字节；返回读取的 4 字节 packet 数。 */
uint32_t usb_midi_read_packets(uint8_t *packets, uint32_t max_packets);
```

In `usb_midi_device.c`, return `tud_midi_packet_read_n(packets, max_packets)`. In the installed TinyUSB 0.21.0~2 single-interface wrapper, this function has no `itf` parameter; the multi-interface variant is `tud_midi_n_packet_read_n(itf, ...)`.

- [ ] **Step 3: Add USB → BLE queue and task**

In `midi_bridge.c`:

- Add `QueueHandle_t s_usb_to_ble` depth 256.
- Add `usb_packet_to_message(const uint8_t pkt[4], midi_msg_t *out)`:
  - Reject cable number other than 0 (`pkt[0] >> 4 != 0`).
  - CIN `0x08,0x09,0x0A,0x0B,0x0E`: length 3.
  - CIN `0x0C,0x0D,0x02`: length 2.
  - CIN `0x03`: length 3.
  - CIN `0x0F`: length 1.
  - Everything else (reserved, SysEx `0x04..0x07`, cable events): reject; increment `s_usb_unsupported`.
  - Copy `pkt[1..len]`; timestamp `esp_timer_get_time()`.
- In `usb_task`, after draining BLE→USB, read up to 16 packets per loop with `usb_midi_read_packets`. For each valid message, `xQueueSend(s_usb_to_ble, &msg, pdMS_TO_TICKS(5))`; on timeout increment `s_usb_to_ble_dropped`. The USB task may briefly block on a full queue; this is the intended backpressure and keeps Note Off from being silently lost.
- Add `ble_task` priority 8, stack 4096:
  - Block up to 10 ms for the first message from `s_usb_to_ble`.
  - Drain up to 7 more without blocking into a local `midi_msg_t batch[8]`.
  - If `!ble_midi_link_ready()`, count all as dropped and continue.
  - Build `const uint8_t *msgs[8]` and `uint8_t lens[8]`, call `ble_midi_link_send(msgs, lens, count)`.
  - On OK, count sent; on error, count dropped.
- `midi_bridge_start()` creates both tasks and returns `ESP_ERR_NO_MEM` if either fails.
- Every 5 s log both directions.

- [ ] **Step 4: Build and flash**

Run the Task 1 build command. Expected: `Project build complete`.

- [ ] **Step 5: Verify USB → BLE, including notify-disabled behavior**

1. In nRF Connect, connect and enable notify.
2. In Ableton, route a MIDI clip with CC1 values to `ESP32-S3 MIDI Bridge`; play it.
3. Expect notifications containing `B0 01 xx`; e.g. value 65 is `80 80 B0 01 41` (timestamp bytes may differ).
4. Disable notify in nRF Connect; play the CC clip again. Expected: no notification, no crash, `USB->BLE dropped` increases.
5. Re-enable notify; play again; notifications resume.

- [ ] **Step 6: Commit**

```bash
git add firmware/main/usb_midi_device.c firmware/main/usb_midi_device.h firmware/main/midi_bridge.c
git commit -m "feat: bridge USB MIDI back to BLE notifications"
```

---

### Task 4: Disconnect safety and reconnect

**Files:**
- Modify: `firmware/main/midi_bridge.c`
- Modify: `firmware/README.md` (temporary notes; final docs Task 5)

**Interfaces:**
- Consumes: `usb_midi_device_mounted`, `usb_midi_write`, `ble_midi_link_ready` from Tasks 1–3.
- Produces: internal `usb_send_panic()`, queue clearing on state transitions.

- [ ] **Step 1: Write the failing acceptance check**

In nRF Connect, write Note On `80 80 90 3C 7F`, then disconnect without writing Note Off. Expected now: the note continues ringing because no panic exists.

- [ ] **Step 2: Implement the panic**

In `midi_bridge.c`:

- Add `usb_send_panic()`:
  - If `!usb_midi_device_mounted()`, return.
  - For channel 0..15, send `B0|ch, 123, 0` (All Notes Off), `B0|ch, 120, 0` (All Sound Off), `B0|ch, 64, 0` (Sustain Off).
  - Use the same retry helper as normal writes; count failures.
- Add `clear_queues()`: `xQueueReset(s_ble_to_usb)` and `xQueueReset(s_usb_to_ble)`; increment a `s_queue_flushes` counter.

- [ ] **Step 3: Add state-transition handling to `usb_task`**

Track `bool was_ready` and `bool was_mounted`:

- On BLE `ready` true → false: log, `usb_send_panic()`, `clear_queues()`.
- On USB mounted false → true: wait 50 ms, `usb_send_panic()`, `clear_queues()`.
- On USB mounted true → false: `clear_queues()`; do not call USB writes.
- On BLE false → true: clear queues so stale messages from the disconnected period cannot replay.
- `was_ready`/`was_mounted` update every loop.

- [ ] **Step 4: Build and flash**

Run the Task 1 build command. Expected: `Project build complete`.

- [ ] **Step 5: Verify stuck-note protection**

1. Connect and enable notify.
2. Write Note On `80 80 90 3C 7F`; do not send Note Off.
3. Disconnect in nRF Connect. Expected: Ableton note stops quickly; UART logs one panic; no crash.
4. Scan again; device advertises. Connect, enable notify, write Note On/Off; works.
5. With BLE connected, unplug the native USB cable and replug. Expected: USB re-enumerates, BLE stays connected, panic runs on mount, no stale notes.

- [ ] **Step 6: Commit**

```bash
git add firmware/main/midi_bridge.c firmware/README.md
git commit -m "fix: send all-notes-off on BLE disconnect and USB mount"
```

---

### Task 5: Latency statistics, docs, and final acceptance

**Files:**
- Modify: `firmware/main/midi_bridge.c`
- Modify: `firmware/README.md`

**Interfaces:**
- Consumes: all earlier APIs.
- Produces: `BRIDGE stats` log with p50/p95/max and final documentation.

- [ ] **Step 1: Write the failing acceptance check**

Run Task 4 firmware and send 20 Note On/Off pairs from nRF Connect. Expected: only cumulative sent/dropped logs; no p50/p95 latency.

- [ ] **Step 2: Add latency histogram**

In `midi_bridge.c`:

- Record `rx_time_us` in each `midi_msg_t` when the BLE callback enqueues.
- When the USB task finishes writing a BLE→USB message, compute `lat_us = esp_timer_get_time() - rx_time_us`.
- Use 64 buckets of 100 µs (`0..6300+ µs`); clamp overflow into bucket 63.
- Track `lat_count`, `lat_max_us`, and the histogram.
- Compute p50/p95 from the histogram by cumulative count; report the bucket lower bound (`bucket * 100`), with `>6300` for bucket 63.
- Call `ble_midi_link_get_stats(&link_stats)`; include `rx_unsupported` as `unsupported` and `tx_dropped` as `tx_drop`.
- Log every 5 s:

```text
BRIDGE stats: BLE->USB sent=... drop=... p50=...us p95=...us max=...us; USB->BLE sent=... drop=...; unsupported=... tx_drop=... flushes=...
```

- Use plain 32-bit counters; log-only race tolerance is acceptable. Do not add per-message logs.

- [ ] **Step 3: Build and flash**

Run the Task 1 build command. Expected: `Project build complete`.

- [ ] **Step 4: Verify latency and sustained CC**

1. Connect and enable notify.
2. Send at least 100 Note On/Off pairs or CC changes as fast as nRF Connect allows. If a Windows BLE central is available, use a temporary `bleak` script to write 200 pairs at 10 ms; do not commit it.
3. Read `BRIDGE stats`; expected p95 < 5000 µs on a quiet link. If the sample count is below 100 or the adapter refuses 2M/7.5 ms, report actual interval and sample count instead of claiming the target.
4. Send a 50–100 Hz CC sweep for 10 s; expected no crash, no partial CC, final value present.
5. Run a 5-minute BLE session with periodic Note/CC; expected no disconnect, no queue overflow, no stuck note.
6. Disconnect/reconnect once; expected advertising resumes and UUIDs are unchanged.

- [ ] **Step 5: Update documentation**

`firmware/README.md` must include:

- BLE name `ESP32-S3 MIDI`, service/characteristic UUIDs, Write Without Response + Notify.
- Build/flash commands (same as stage 2), native USB port vs COM5.
- nRF Connect test table with the exact hex packets from Task 2.
- Ableton verification for Note, CC map, and 14-bit pair.
- USB→BLE test: Ableton CC clip → nRF Connect notification.
- Stuck-note test and reconnect test.
- `BRIDGE stats` field meanings; p95 target and caveat.
- Known limits: no SysEx forwarding, no USB-status GATT, no app yet, no bonding.

- [ ] **Step 6: Commit**

```bash
git add firmware/main/midi_bridge.c firmware/README.md
git commit -m "feat: add BLE bridge latency stats and stage 3 docs"
```

---

## Final Verification

Run the full stage 3 acceptance on the branch:

- [ ] `idf.py -C firmware build` completes.
- [ ] nRF Connect sees `ESP32-S3 MIDI` and the standard BLE-MIDI service/characteristic.
- [ ] BLE Note On/Off and CC arrive in Ableton on the correct channel/number.
- [ ] 14-bit CC MSB+LSB arrive in order.
- [ ] Ableton CC arrives as a BLE notification.
- [ ] Disconnect/reconnect works; device re-advertises.
- [ ] BLE disconnect during Note On sends panic; no stuck note.
- [ ] USB replug does not replay stale notes.
- [ ] 50–100 Hz CC stream has no crash/partial message; final value arrives.
- [ ] `BRIDGE stats` p95 is below 5 ms, or the actual interval/sample limitation is documented.
- [ ] No per-message UART logs in the hot path.
- [ ] README matches the tested behavior.

## Contingency

If `ble_midi` 0.1.0~2 fails to build, initialize, or advertise on IDF 5.5.2 + S3:

1. Stop; keep the stage-2 branch intact.
2. Capture the exact failing component/version and log.
3. Write a separate fallback plan using NimBLE GATT + a BEP codec; do not enable both the component and a hand-written GATT service.
