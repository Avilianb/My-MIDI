# Glass MIDI — BLE MIDI 控制器

```text
Android App (Compose Multiplatform + Backdrop)
        ⇅  BLE-MIDI (standard GATT service)
ESP32-S3 (BLE peripheral + USB-MIDI device)
        ⇅  USB-MIDI class
Windows / macOS / Linux + DAW
```

- `firmware/`：ESP-IDF 5.5.2 固件；BLE↔USB 双向桥、断线清音、延迟统计。
  构建/烧录/验收见 [firmware/README.md](firmware/README.md)。
- `app/`:Android App;控件编辑、BLE 连接/重连、14-bit CC / Pitch Bend、布局持久化。
  构建/测试/阶段 4 验收见 [app/README.md](app/README.md)。

## 当前阶段

| 阶段 | 状态 |
|---|---|
| 1 技术选型 | 已确认：ESP-IDF + Compose Multiplatform(Android) + 标准 BLE-MIDI |
| 2 USB-MIDI 单独跑通 | 已完成 |
| 3 BLE 桥接 + nRF Connect 验收 | 已完成（固件 README 有实测记录） |
| 4 Android App 连接 + 基础控件 | 真机扫描/连接已验证；控件与持久化完成 |
| 5 双向反馈、配置/布局保存 | 回传 UI、14-bit 配对、布局保存已实现；DAW 回传实测待做 |

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
