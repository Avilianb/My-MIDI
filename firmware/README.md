# Stage 2 USB-MIDI firmware

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
