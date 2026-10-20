# Glass MIDI Android App

手机端通过标准 BLE-MIDI（GATT）连接 ESP32-S3，向固件发送 Note/CC，并接收固件回传的
USB→BLE 消息。界面使用 Compose Multiplatform + Backdrop 液态玻璃效果。

当前阶段：**阶段 5（双向反馈、14-bit 配对、布局保存）代码完成；DAW 回传真机验收待做。**

## 技术栈

| 项目 | 版本/选择 |
|---|---|
| 语言/UI | Kotlin 2.4.10、Compose Multiplatform 1.12.0 |
| 液态玻璃 | `io.github.kyant0:backdrop:2.0.1` |
| Android Gradle Plugin | 9.3.2 |
| Gradle | 9.5.0（wrapper） |
| JDK | 21（编译目标 JVM 17） |
| compileSdk / targetSdk | 37 |
| minSdk | 26（Android 8.0） |
| BLE | Android 原生 `BluetoothLeScanner` / `BluetoothGatt`，无第三方 BLE 库 |
| 测试 | `kotlin.test` + `kotlinx-coroutines-test`，JVM host test |

`composeApp` 的 `commonMain` 只依赖 Compose 和公共模型；Android BLE、权限、前台服务和
文件存储放在 `androidApp`。以后加 iOS 时，替换 transport/存储实现即可，不依赖 Android API
的 UI 和协议代码可以继续复用。

## 目录

```text
app/
├── composeApp/src/commonMain/kotlin/.../
│   ├── ble/BleMidiTransport.kt          # BLE 传输接口与事件模型
│   ├── midi/BleMidiCodec.kt             # 标准 BLE-MIDI 编解码（含 running status）
│   ├── model/                           # Control / MIDI 映射 / 校验
│   ├── storage/LayoutStore.kt           # 布局持久化接口
│   ├── store/ControllerStore.kt         # 控件状态、10 ms 节流、重连恢复
│   └── ui/                              # Compose 界面与 Backdrop 组件
├── composeApp/src/commonTest/            # 编解码、校验、状态机测试
└── androidApp/src/main/kotlin/.../
    ├── ble/AndroidBleMidiTransport.kt   # 扫描/连接/MTU/Notify/单写队列/重连
    ├── service/MidiConnectionService.kt # 连接期间的前台服务
    ├── storage/AndroidLayoutStore.kt    # AtomicFile JSON 持久化
    └── MainActivity.kt                  # 权限请求、前台服务启动
```

## 构建与安装

前置：JDK 21、Android SDK Platform 37、Android 设备或模拟器。`app/local.properties`
里的 `sdk.dir` 指向本机 SDK，已被 gitignore。

PowerShell：

```powershell
cd app
$env:JAVA_HOME = "C:\HyperV\Bin\jdk21\jdk-21.0.12+8"
.\gradlew.bat :androidApp:assembleDebug
.\gradlew.bat :composeApp:jvmTest
adb install -r androidApp\build\outputs\apk\debug\androidApp-debug.apk
```

APK 路径：`app/androidApp/build/outputs/apk/debug/androidApp-debug.apk`。

首次同步需要访问 Maven Central/Google；`settings.gradle.kts` 配置了阿里云镜像。当前没有
iOS target；`composeApp` 的公共代码未使用 Android API，后续可按 KMP 方式加入 iOS。

## 使用

1. 打开 App，允许蓝牙扫描/连接权限；Android 13+ 还会请求通知权限（前台服务通知）。
2. 点右上角“扫描”，选择 `ESP32-S3 MIDI`。连接成功后状态变为“已连接”。
3. 点“添加控件”，选择 Button / Knob / Fader：
   - Button：Note 或 7-bit CC，按下 127，松开 0 / Note Off。
   - Knob/Fader：固定 14-bit CC；MSB 为编号（0–31），LSB 自动使用 `MSB + 32`。
   - 通道 1–16 可调；名称、MIDI 类型、编号可在编辑器中修改。
4. 旋钮上下拖动，推子上下拖动；值范围 0–16383。松手立即发送最终值，拖动期间每 10 ms
   最多发送一帧，避免 BLE 写队列被淹没。
5. 布局、控件映射和最后一次连接地址自动保存；杀掉 App 后重开仍会恢复。
6. 连接断开后自动按 0.5/1/2/5 秒退避重连；再次 Ready 会重发所有连续控件当前值，
   Button 显示状态清零。

### 权限

| Android | 权限 |
|---|---|
| 12+（API 31+） | `BLUETOOTH_SCAN`（neverForLocation）、`BLUETOOTH_CONNECT` |
| 8–11（API 26–30） | `BLUETOOTH`、`BLUETOOTH_ADMIN`、`ACCESS_FINE_LOCATION` |
| 13+（API 33+） | `POST_NOTIFICATIONS`（拒绝只影响前台服务通知展示） |

扫描在 Android 12+ 不需要定位权限；旧系统需要打开系统定位开关才能收到 BLE 扫描结果。

## BLE 协议

App 严格使用标准 BLE-MIDI：

- Service：`03b80e5a-ede8-4b33-a751-6ce34ec4c700`
- Characteristic：`7772e5db-3868-4112-a1a9-f2669d106bf3`
- 写：`Write Without Response`；读回：CCCD Notify。
- 固件使用 **BLE 5 extended advertising**（128-bit UUID + 名称超过 31 字节）；App 的
  `ScanSettings` 显式设置 `setLegacy(false)` + `PHY_LE_ALL_SUPPORTED`。不要改回只扫描
  legacy 广播，否则会表现为“系统蓝牙能看到、App 扫描不到”。
- 每个通知/写入由 `80 <timestamp>` 头开始，后接原始 MIDI 字节。编码器在同一个写入包内
  携带一条消息；14-bit 旋钮/推子把 MSB、LSB 两条 CC 放在同一个包里，顺序固定为
  MSB→LSB，保证固件能用同一时间戳配对。

例：Knob 映射 CH1 CC1，值 8192（0x2000）：

```text
80 <ts> B0 01 40 B0 21 00
```

### 14-bit 规则

- 编号范围 0–31；LSB 固定 `编号 + 32`（例如 CC1 → CC33）。
- 不允许两个 14-bit 对共享同一通道的任一 CC 编号；也不允许 14-bit 对与 7-bit CC 控件
  使用相同的 CC 编号（编辑器会实时提示）。
- 7-bit Button CC 可以与另一个 7-bit CC 共用编号，但回传时两个控件都会响应，建议避免。
- 收到 `CC MSB` 时先记下高 7 位并按粗值更新 UI；收到 `CC MSB+32` 时合并为 0–16383。

## 阶段 4 验收

### 已在本机验证（Android 模拟器，无 ESP32）

| 项目 | 结果 |
|---|---|
| Debug 构建 | `BUILD SUCCESSFUL`，APK 约 9.2 MB |
| commonTest | 编解码、映射校验、Store 状态机全部通过（`gradlew :composeApp:jvmTest`） |
| 启动/权限 | 模拟器启动、扫描入口可点，进入“正在扫描 ESP32-S3 MIDI”状态 |
| 添加/编辑控件 | Button、Knob、Fader 可创建；名称/通道/编号/类型可编辑 |
| 校验 | 14-bit CC 冲突实时提示并禁用保存；改到空闲编号后可保存 |
| 手势 | Knob 拖动值变化，松手发送最终值；Fader 拖动值变化 |
| 持久化 | 杀掉进程重开，控件布局从 `files/layout.json` 恢复 |
| 返回/重连 UI | 未连接、扫描中、错误状态可切换；未做硬件重连验收 |

模拟器的 Bluetooth 扫描在宿主上会让 `android.hardware.bluetooth` 崩溃（模拟器限制），
所以“扫描到设备并连接”必须在真机验收。

### 手机 + ESP32 实测步骤

1. 烧录 `firmware/` 当前版本，USB 接电脑，确认 `ESP32-S3 MIDI Bridge` 出现在 DAW。
2. 手机装 APK，打开蓝牙，点“扫描”，连接 `ESP32-S3 MIDI`，状态应为“已连接”。
3. 新建 Button+Note 60，按下/松开；DAW MIDI 监视器应看到 Note On/Off。
4. 新建 Button+CC 20，按下/松开；应看到 CC20=127/0。
5. 新建 Knob+CC1、Fader+CC2，拖动；应看到 `CC1/CC33`、`CC2/CC34` 成对到达，
   DAW 的 MIDI 映射旋钮应连续变化。
6. 保持拖动 10 秒，确认不卡顿、不丢最终值；断开 ESP32 电源再上电，App 应自动重连，
   重连后 Knob/Fader 的值应重新发送，Button 不应保持按下。
7. 杀掉 App 再开，布局应恢复；再次连接后功能正常。

### 验收标准对应

| 验收标准 | 现状 |
|---|---|
| DAW 收到手机操作对应通道数据 | 代码路径完成；待真机 + USB MIDI 监视器确认 |
| 旋钮连续转动流畅、不丢包 | 10 ms 合并节流 + 单写队列 + 松手强制最终值；待真机手感/丢包验收 |
| 断开重连后功能正常 | 自动退避重连、Ready 重发连续值、Button 清零；待真机验收 |

## 阶段 5：双向反馈

### 已实现

- `ControllerStore` 消费 `transport.incoming`，按 `(通道, Note/CC 编号, 分辨率)` 匹配控件。
- Button + Note：Note On 更新为 velocity，Note Off/velocity 0 归零。
- Button + 7-bit CC：直接更新 0–127。
- Knob/Fader + 14-bit CC：缓存 MSB（按粗值更新），收到 `MSB + 32` 后合并为 0–16383。
- 回传只更新 UI/持久化，不调用 `transport.send`，不会形成 BLE 回环。
- 断开/重连清除未完成的 14-bit MSB；UI 值在 300 ms 防抖后写回 `layout.json`。
- Knob/Fader 的本地拖动状态会在外部回传到达时同步；拖动期间不被回传打断。

### 测试步骤

1. 连接 ESP32-S3，在 DAW 中向 `ESP32-S3 MIDI Bridge` 发送 CC1=64、CC33=0；
   App 的 Knob CC1 应显示 8192。
2. 再发送 CC1=64、CC33=127；应显示 8319。
3. 播放包含 Note 60/CC20 的 MIDI clip；对应 Button 应点亮/熄灭或显示 CC 值。
4. 确认 App 不会把回传值再次发回 BLE（DAW 不会出现回环 CC）。
5. 外部回传后杀掉 App 重开，最后一次控件值应恢复。

### 状态

| 项目 | 结果 |
|---|---|
| 回传映射 | 单元测试覆盖 Note/CC/14-bit/orphan LSB，全部通过 |
| 回传不发送 | 单元测试断言 `transport.sent` 不增加 |
| 持久化 | 沿用布局保存和 300 ms 防抖 |
| 真机 DAW 回传 | 待用户按上面步骤验证 |

### 扫描不到设备

1. ESP32-S3 同一时间只允许一个 BLE central。先断开系统蓝牙、nRF Connect、LightBlue
   等所有已连接客户端，再给 ESP 断电重启。
2. Android 11 及以下，App 扫描还要求系统定位服务开启；Android 12+ 只需附近设备权限。
3. 固件广播是 BLE 5 extended advertising；确认手机支持 BLE 5，且安装的是包含
   `setLegacy(false)` 修复的 APK（本仓库当前版本）。
4. 系统蓝牙里能看到设备但 App 扫描不到时，用 nRF Connect 对照：
   - nRF Connect 也看不到：ESP 没有广播，通常是被其他 central 占用或未上电。
   - nRF Connect 能看到、App 看不到：抓 `adb logcat`，过滤 `GlassMidiBle` 后反馈。

## 已知限制

- 布局保存已实现，但还没有拖拽排序、删除确认、页面切换/多页布局。
- 回传只更新当前页控件；不保存 DAW 的自动化历史。
- 没有 USB 挂载状态查询；固件当前不提供该 GATT 状态。
- 没有 BLE bonding/加密；与固件当前开放外设一致。
- 只支持单 BLE 连接；前台服务在连接期间保持，系统可能因省电策略杀进程。
- iOS 未配置 target；公共层已避免 Android 依赖，但 BLE/存储/权限需另写 actual 实现。
