# My MIDI — 双手机 BLE-MIDI ↔ 双虚拟端口 USB-MIDI 控制器

```text
手机 A / 乐手 1 (My MIDI App) ──┐
                                 ⇅  BLE-MIDI (NimBLE 双连接 + 端口握手回执)
手机 B / 乐手 2 (My MIDI App) ──┴─► ESP32-S3 (BLE peripheral + TinyUSB 双虚拟线缆)
                                 ⇅  USB-MIDI (Cable 0: Port 1 / Cable 1: Port 2)
                             Windows / macOS / Linux + DAW (Ableton Live 等)
```

- `firmware/`：ESP-IDF 5.5.2 固件；原生 NimBLE 双手机连接、单广播实例、TinyUSB 双虚拟线缆（Port 1 / Port 2）、活跃音符状态表防卡音、按端口独立断线清音与延迟统计。
  构建/烧录/验收见 [firmware/README.md](firmware/README.md)。
- `app/`：Android App（`My MIDI`）；4 通道整体台面、Ableton Live 标准 dB 刻度推子、预设锁定通道映射、双乐手端口选择与自动握手回执、双向反馈与布局持久化。
  构建/测试/验收见 [app/README.md](app/README.md)。

## 当前阶段

| 阶段 | 状态 |
|---|---|
| 1 技术选型 | 已完成：ESP-IDF 5.5.2 (NimBLE + TinyUSB) + Compose Multiplatform (Android) |
| 2 USB-MIDI 双虚拟线缆 | 已完成：单接口枚举 `ESP32-S3 MIDI 1` (Cable 0) 与 `ESP32-S3 MIDI 2` (Cable 1) |
| 3 BLE 双手机桥接 + 防卡音 | 已完成：支持 2 台手机同时连接、端口认领与 Notify 回执、Note Off 挂起补发 |
| 4 Android App 调音台控件 | 已完成：整块玻璃台面、Ableton dB 推子、预设 MIDI 通道映射、常亮防息屏 |
| 5 双向反馈与现场稳定性 | 已完成：200ms 触控回声抑制、14-bit CC 配对、高优 Note 发送通道与连接看门狗 |
## 快速构建

```powershell
# 固件（PowerShell，不要用 Git Bash）
powershell -NoProfile -ExecutionPolicy Bypass -Command "& 'C:\Espressif\esp-idf-v5.5.2\export.ps1'; idf.py -C firmware build"

# Android App
cd app
$env:JAVA_HOME = "C:\HyperV\Bin\jdk21\jdk-21.0.12+8"
.\gradlew.bat :androidApp:assembleDebug
.\gradlew.bat :composeApp:jvmTest
```
