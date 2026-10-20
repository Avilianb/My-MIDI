# Stage 2 USB-MIDI Firmware Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an ESP32-S3 firmware that enumerates as a standard USB-MIDI device on Windows 11 and continuously sends a deterministic Note On/Off test pattern that Ableton Live 12 can record.

**Architecture:** ESP-IDF 5.5.2 project derived from the official `tusb_midi` example. TinyUSB exposes one MIDI interface with one IN and one OUT endpoint. A periodic timer sends Note On C4 velocity 100 and Note Off C4 on channel 1 every 500 ms. No BLE, no CDC, no app code in this stage.

**Tech Stack:** ESP-IDF 5.5.2, TinyUSB MIDI device class, C, Windows 11, Ableton Live 12, CH340 UART on COM5 for flashing/logs, ESP32-S3 native USB for MIDI.

**Spec:** `docs/superpowers/specs/2026-10-20-phone-ble-usb-midi-controller-design.md`

## Global Constraints

- ESP-IDF version: **5.5.2**; target: **esp32s3**.
- USB MIDI only: **no CDC composite**, no BLE, no Wi-Fi in this stage.
- Product string: **`ESP32-S3 MIDI Bridge`**.
- Manufacturer string: **`Remote MIDI`**.
- Serial string: **`ESP32S3-MIDI-01`**.
- Prototype VID/PID: **0x303A / 0x4008** (TinyUSB default; do not hard-code a different pair).
- MIDI test pattern: **channel 1 (wire channel 0), note 60 (C4), velocity 100, Note On then Note Off, 500 ms apart**.
- Flashing/log port: **COM5** (CH340 UART). Do not use COM3/COM4 (Bluetooth).
- USB-MIDI test port: ESP32-S3 **native USB** port; it is a separate physical port/cable on most S3 dev boards.
- Windows host: Windows 11; DAW: Ableton Live 12 Suite.
- Do not commit `firmware/build/`, `firmware/sdkconfig`, or `firmware/sdkconfig.old`.
- Commit after each task; do not amend a previous commit unless the task explicitly says so.

## Review Focus

These are the failure modes most likely to make the device look broken to a user. Each is pinned to Task 2's verification steps.

1. **Windows never sees a MIDI port** because the USB descriptor, native USB cable, or target is wrong. Expected: a device with `VID_303A` appears, and Ableton lists `ESP32-S3 MIDI Bridge`.
2. **A note hangs** because Note On is sent without a matching Note Off. Expected: a 10-second recording contains about 10 complete note pairs and no ringing note after stopping.
3. **Wrong MIDI channel/note/velocity** because the status nibble or data bytes are off by one. Expected: channel 1, note 60, velocity 100 in Ableton.
4. **USB unplug/replug leaves the device dead** because TinyUSB does not recover or the firmware assumes a permanent mount. Expected: notes resume after replugging the native USB cable.
5. **The 5-minute run drops or stalls** because the periodic timer/task starves or the device disconnects. Expected: notes continue for 5 minutes, and a fresh 10-second recording still contains about 10 note pairs.

---

### Task 1: Install ESP-IDF 5.5.2 and make the example build

**Files:**
- Create: `firmware/` (copy of the ESP-IDF `tusb_midi` example)
- Create: `.gitignore`
- Modify: `firmware/sdkconfig.defaults` only if the copied example does not already contain `CONFIG_TINYUSB_MIDI_COUNT=1`
- Test: build output of `idf.py -C firmware build`

**Interfaces:**
- Consumes: nothing.
- Produces: a buildable `firmware/` project targeting `esp32s3`; later tasks modify `firmware/main/tusb_midi_main.c`.

- [ ] **Step 1: Install ESP-IDF 5.5.2 and the ESP32-S3 tools**

Use PowerShell. The install location is outside the repo so build tools are not committed.

```powershell
New-Item -ItemType Directory -Force C:\Espressif | Out-Null
git clone --depth 1 --branch v5.5.2 --recursive https://github.com/espressif/esp-idf.git C:\Espressif\esp-idf-v5.5.2
Set-Location C:\Espressif\esp-idf-v5.5.2
.\install.bat esp32s3
```

Expected: the installer finishes without an error and reports the ESP32-S3 toolchain installed. If `C:\Espressif\esp-idf-v5.5.2` already exists with the correct version, skip the clone but still run `install.bat esp32s3`.

- [ ] **Step 2: Verify the toolchain version**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -Command "& 'C:\Espressif\esp-idf-v5.5.2\export.ps1'; idf.py --version"
```

Expected output contains `ESP-IDF v5.5.2`.

- [ ] **Step 3: Copy the official USB-MIDI example into the repo**

From the repo root `C:\Users\woo\Desktop\remoteesp32midi`:

```powershell
Copy-Item -Recurse "C:\Espressif\esp-idf-v5.5.2\examples\peripherals\usb\device\tusb_midi" "firmware"
```

Expected: `firmware/CMakeLists.txt`, `firmware/main/tusb_midi_main.c`, and `firmware/sdkconfig.defaults` exist.

- [ ] **Step 4: Set the target and build**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -Command "& 'C:\Espressif\esp-idf-v5.5.2\export.ps1'; Set-Location 'C:\Users\woo\Desktop\remoteesp32midi'; idf.py -C firmware set-target esp32s3; idf.py -C firmware build"
```

Expected: `Project build complete.` and a `firmware/build/esp32s3/` output tree.

- [ ] **Step 5: Add `.gitignore` and make the first commit**

Create `.gitignore` at the repo root:

```gitignore
firmware/build/
firmware/managed_components/
firmware/sdkconfig
firmware/sdkconfig.old
```

Then initialize git if needed and commit the design, plan, and buildable scaffold:

```powershell
git init
git add docs firmware .gitignore
git commit -m "chore: scaffold ESP-IDF USB MIDI firmware"
```

Expected: commit succeeds; `git status --short` does not list `firmware/build/` or `firmware/sdkconfig`.

---

### Task 2: Replace the example melody with the deterministic Note On/Off test and verify on hardware

**Files:**
- Modify: `firmware/main/tusb_midi_main.c`
- Create: `firmware/README.md` (build/flash/test notes for this stage)
- Test: hardware observation in Windows Device Manager/PnP, Ableton Live 12, and a 10-second MIDI recording

**Interfaces:**
- Consumes: the buildable `firmware/` project from Task 1.
- Produces: a firmware that sends `0x90 0x3C 0x64` then `0x80 0x3C 0x00` every 500 ms on channel 1, with USB descriptors `Remote MIDI` / `ESP32-S3 MIDI Bridge` / `ESP32S3-MIDI-01`.

- [ ] **Step 1: Write the hardware acceptance check before changing the firmware**

Create `firmware/README.md` with the exact expected results:

````markdown
# Stage 2 USB-MIDI firmware

## Build and flash

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -Command "& 'C:\Espressif\esp-idf-v5.5.2\export.ps1'; idf.py -C firmware build; idf.py -C firmware -p COM5 flash monitor"
```

## Expected USB identity

- Manufacturer: `Remote MIDI`
- Product: `ESP32-S3 MIDI Bridge`
- VID/PID: `0x303A / 0x4008`
- One MIDI interface, one IN endpoint, one OUT endpoint.

## Expected MIDI

- Channel 1
- Note 60 (C4)
- Velocity 100
- Note On, then Note Off 500 ms later, repeating.

## Acceptance

1. Windows PnP shows a device with `VID_303A`.
2. Ableton Live 12 lists `ESP32-S3 MIDI Bridge` as a MIDI input.
3. A 10-second recording contains about 10 note-on and 10 note-off events.
4. No note rings after stopping playback.
5. Unplug/replug the native USB cable; notes resume.
6. Run for 5 minutes; notes continue; a fresh 10-second recording still has about 10 note pairs.
````

This is intentionally a hardware acceptance test: there is no pure host-side logic in this stage whose unit test would catch a descriptor or TinyUSB wiring failure.

- [ ] **Step 2: Change the string descriptors**

In `firmware/main/tusb_midi_main.c`, replace the string descriptor block with:

```c
static const char* s_str_desc[5] = {
    // array of pointer to string descriptors
    (char[]){0x09, 0x04},       // 0: supported language is English (0x0409)
    "Remote MIDI",              // 1: Manufacturer
    "ESP32-S3 MIDI Bridge",     // 2: Product
    "ESP32S3-MIDI-01",          // 3: Serial
    "ESP32-S3 MIDI Bridge",     // 4: MIDI interface
};
```

- [ ] **Step 3: Replace the melody timer callback with the deterministic note test**

Replace the `NOTE_OFF`/`NOTE_ON` defines and `periodic_midi_write_example_cb()` with:

```c
// Basic MIDI Messages
#define NOTE_OFF 0x80
#define NOTE_ON  0x90

static void periodic_midi_write_example_cb(void *arg)
{
    static bool note_is_on = false;
    static uint8_t const cable_num = 0; // MIDI jack associated with USB endpoint
    static uint8_t const channel = 0;   // 0 for channel 1
    static uint8_t const note = 60;     // C4

    if (!tud_midi_mounted()) {
        return;
    }

    uint8_t message[3];
    if (!note_is_on) {
        message[0] = NOTE_ON | channel;
        message[1] = note;
        message[2] = 100;
        ESP_LOGI(TAG, "Note On  ch=%d note=%d vel=%d", channel + 1, note, message[2]);
    } else {
        message[0] = NOTE_OFF | channel;
        message[1] = note;
        message[2] = 0;
        ESP_LOGI(TAG, "Note Off ch=%d note=%d", channel + 1, note);
    }

    tud_midi_stream_write(cable_num, message, sizeof(message));
    note_is_on = !note_is_on;
}
```

In `app_main()`, replace the `tempo` variable and timer period with a 500 ms period:

```c
    // Send Note On/Off every 500 ms.
    int const period_ms = 500;
    const esp_timer_create_args_t periodic_midi_args = {
        .callback = &periodic_midi_write_example_cb,
        .name = "periodic_midi"
    };

    ESP_LOGI(TAG, "MIDI write task init");
    esp_timer_handle_t periodic_midi_timer;
    ESP_ERROR_CHECK(esp_timer_create(&periodic_midi_args, &periodic_midi_timer));
    ESP_ERROR_CHECK(esp_timer_start_periodic(periodic_midi_timer, period_ms * 1000));
```

Leave the existing `midi_task_read_example()` and its task creation in place so the USB OUT endpoint is drained; this is needed for the later BLE bridge.

- [ ] **Step 4: Build the modified firmware**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -Command "& 'C:\Espressif\esp-idf-v5.5.2\export.ps1'; Set-Location 'C:\Users\woo\Desktop\remoteesp32midi'; idf.py -C firmware build"
```

Expected: build succeeds with no warnings about missing `bool` or `sizeof`. If `bool` is not available, add `#include <stdbool.h>`.

- [ ] **Step 5: Flash and open the UART monitor**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -Command "& 'C:\Espressif\esp-idf-v5.5.2\export.ps1'; Set-Location 'C:\Users\woo\Desktop\remoteesp32midi'; idf.py -C firmware -p COM5 flash monitor"
```

Expected: flash succeeds; the monitor prints USB initialization logs and `Note On`/`Note Off` lines at 500 ms intervals.

- [ ] **Step 6: Connect the native USB port and verify enumeration**

Connect the ESP32-S3 native USB port to the PC with a data cable (keep COM5 connected for logs). In a separate PowerShell:

```powershell
Get-PnpDevice -PresentOnly | Where-Object { $_.InstanceId -match "VID_303A" } | Select-Object Status,Class,FriendlyName,InstanceId | Format-List
```

Expected: a present device with `VID_303A`; the friendly name or MIDI interface identifies `ESP32-S3 MIDI Bridge`. If nothing appears, check that the cable is a data cable and that it is connected to the S3 native USB port, not the UART port.

- [ ] **Step 7: Verify MIDI in Ableton Live 12**

1. Open Ableton Live 12 → Settings → Link, Tempo & MIDI → MIDI Ports.
2. Find the `ESP32-S3 MIDI Bridge` input; enable **Track** and **Remote**.
3. Create a MIDI track, arm it, and enable monitoring.
4. Confirm the incoming activity indicator flashes at 500 ms intervals.
5. Record 10 seconds into a MIDI clip.

Expected: the clip contains about 10 note-on and 10 note-off events, all channel 1, note 60, velocity 100. Stop playback and confirm no note rings.

- [ ] **Step 8: Verify unplug/replug and the 5-minute soak**

1. Unplug the native USB cable, wait 2 seconds, replug it.
2. Confirm the notes resume without reflashing.
3. Let it run for 5 minutes.
4. Record another 10 seconds and count about 10 note pairs.

Expected: notes resume after replug; the 5-minute run does not stall or disconnect; the fresh recording still has about 10 note pairs.

- [ ] **Step 9: Commit the tested firmware**

```powershell
git add firmware/main/tusb_midi_main.c firmware/README.md
git commit -m "feat: send deterministic USB MIDI note test"
```

Expected: commit succeeds.

---

## Stage 2 Completion Criteria

- `idf.py -C firmware build` succeeds.
- COM5 flash succeeds and UART logs show 500 ms Note On/Off.
- Windows PnP shows `VID_303A`; Ableton lists `ESP32-S3 MIDI Bridge`.
- 10-second recording: about 10 Note On + 10 Note Off, channel 1, note 60, velocity 100.
- No stuck note after stopping.
- Native USB replug and 5-minute soak pass.
- Commits exist for Task 1 and Task 2.

Stop here and hand the result to the user for hardware verification. Do not start BLE work until the user confirms Stage 2.
