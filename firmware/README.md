# ESP32-S3 BLE-MIDI ↔ USB-MIDI firmware

## First-time setup (clean checkout)

Run from PowerShell, not Git Bash (the ESP-IDF batch wrappers reject `MSYSTEM`).

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -Command "& 'C:\Espressif\esp-idf-v5.5.2\export.ps1'; idf.py -C firmware set-target esp32s3"
```

`firmware/sdkconfig` is generated and gitignored, so this step is required once per clean checkout. Without it IDF defaults to `esp32`, which the USB MIDI component does not support.

## Build and flash

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -Command "& 'C:\Espressif\esp-idf-v5.5.2\export.ps1'; idf.py -C firmware build; idf.py -C firmware -p COM5 flash monitor"
```

Connect the ESP32-S3 **native USB** port (the `USB` port, not `UART`) to the computer with a data cable. COM5 is the CH340 UART used for flashing and logs; it cannot carry USB-MIDI.

## Expected USB identity

- Manufacturer: `Remote MIDI`
- Product: `ESP32-S3 MIDI Bridge`
- Serial: `ESP32S3-MIDI-01`
- VID/PID: `0x303A / 0x4008`
- One MIDI interface, one IN endpoint, one OUT endpoint.

## Expected MIDI

上电后不再周期发送测试音。只有 BLE 写入 Note/CC 时，USB 才会出现对应消息。

## Stage 3 behavior (in progress)

- 上电不再发送周期测试音；USB MIDI 只来自 BLE 写入。
- BLE 名称 `ESP32-S3 MIDI`，标准 BLE-MIDI Service/Characteristic。
- BLE 断开或 USB 新挂载时，向 16 个通道发送 CC 123（All Notes Off）、CC 120（All Sound Off）、CC 64=0（Sustain Off）。
- 完整 nRF Connect/双向验收步骤在阶段 3 收尾时补齐。

## Stage 3 acceptance

1. Windows PnP still shows `VID_303A` / `PID_4008`.
2. nRF Connect sees `ESP32-S3 MIDI`, the BLE-MIDI service, and enables notify.
3. BLE Note/CC and 14-bit CC pairs arrive on the USB MIDI port.
4. USB MIDI CC arrives as a BLE notification; disabling notify drops messages without crashing.
5. BLE disconnect during a held Note On sends CC 123/120/64; reconnect works.
6. Full packet table and latency acceptance are filled in at stage 3 closeout.
