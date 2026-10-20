# ESP32-S3 BLE-MIDI ↔ USB-MIDI firmware

手机 App（后续阶段）通过 BLE 连接 ESP32-S3；ESP32-S3 以标准 USB-MIDI 设备接入电脑。
本阶段只做固件，桥接方向为：

```text
Android / iOS (BLE central)
        ⇅  BLE-MIDI (standard GATT service)
ESP32-S3 (BLE peripheral + USB device)
        ⇅  USB-MIDI class
Windows / macOS / Linux + DAW
```

## 硬件与接口

- ESP32-S3 的 **原生 USB 口**接电脑，电脑会枚举出 `ESP32-S3 MIDI Bridge`。
- 板载 UART（示例为 `COM5`）只用于烧录和看日志，不能传 USB-MIDI。
- USB 与 BLE 同时工作；USB 未插时 BLE 仍可连接，但 BLE→USB 消息会被丢弃，不会崩溃。
- 不需要额外 GPIO、按键或屏幕。

## 工具链与依赖

- ESP-IDF **v5.5.2**（ESP32-S3 target）。
- 组件版本（`firmware/dependencies.lock` 锁定）：
  - `espressif/esp_tinyusb` 2.4.0
  - `espressif/tinyusb` 0.21.0~2
  - `espressif/ble_midi` 0.1.0~2
  - `espressif/ble_conn_mgr` 1.3.1
  - `espressif/ble_services` 1.5.0
  - `espressif/cmake_utilities` 0.5.3
- `firmware/sdkconfig.defaults` 关键配置：
  - `CONFIG_TINYUSB_MIDI_COUNT=1`
  - `CONFIG_FREERTOS_HZ=1000`（1 ms tick；默认 100 Hz 会让桥接任务多出约 10 ms 延迟）
  - `CONFIG_BT_NIMBLE_MAX_CONNECTIONS=1`
  - `CONFIG_BLE_CONN_MGR_ROLE_PERIPHERAL=y`
  - `CONFIG_BLE_MIDI_PROFILE=y`
  - `CONFIG_BLE_CONN_MGR_EXTENDED_ADV=y`

## 首次构建（干净 checkout）

在 PowerShell 中执行，不要用 Git Bash（ESP-IDF 的 batch/export 脚本会拒绝 `MSYSTEM`）。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -Command "& 'C:\Espressif\esp-idf-v5.5.2\export.ps1'; idf.py -C firmware set-target esp32s3"
```

`firmware/sdkconfig` 是生成文件且已 gitignore；干净 checkout 必须先执行一次 `set-target`，否则 IDF 默认 target 是 `esp32`，而 USB-MIDI 组件不支持该 target。

## 构建与烧录

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -Command "& 'C:\Espressif\esp-idf-v5.5.2\export.ps1'; idf.py -C firmware build"
powershell -NoProfile -ExecutionPolicy Bypass -Command "& 'C:\Espressif\esp-idf-v5.5.2\export.ps1'; idf.py -C firmware -p COM5 flash monitor"
```

只编译：

```powershell
idf.py -C firmware build
```

烧录并看串口日志（`COM5` 换成实际 UART 端口）：

```powershell
idf.py -C firmware -p COM5 flash monitor
```

## USB 身份

上电后电脑应看到：

- Manufacturer: `Remote MIDI`
- Product: `ESP32-S3 MIDI Bridge`
- Serial: `ESP32S3-MIDI-01`
- VID/PID: `0x303A / 0x4008`
- 1 个 MIDI interface、1 个 IN endpoint、1 个 OUT endpoint

Windows 设备管理器应在 `声音、视频和游戏控制器` 或 `音频设备` 中出现 MIDI 端口；
本机测试时 mido 枚举到的端口名为 `ESP32-S3 MIDI Bridge 2`（输入）和
`ESP32-S3 MIDI Bridge 3`（输出）；Ableton 中选择 `ESP32-S3 MIDI Bridge` 的 Track/Remote 即可。

## BLE 接口

- 广播名：`ESP32-S3 MIDI`
- 标准 BLE-MIDI Service：
  `03b80e5a-ede8-4b33-a751-6ce34ec4c700`
- 标准 BLE-MIDI Characteristic：
  `7772e5db-3868-4112-a1a9-f2669d106bf3`
- 属性：`Read`、`Write Without Response`、`Notify`
- 连接后实际 MTU 为 247（不同适配器可能不同）；Windows 测试中连接间隔为 15 ms。
- 广播使用 BLE 5 extended advertising，因为 128-bit UUID + 名称放不进 31 字节 legacy payload；
  手机端必须支持 BLE 5 extended scanning（例如 iPhone 8 及以后、带 BLE 5 的 Android 8+
  机型）。只支持 legacy advertising 的旧手机会扫不到该设备；这是当前广播数据长度的取舍。

### BLE-MIDI 包格式

一条 BLE-MIDI 消息以 `80 <timestamp>` 开头，后面是标准 MIDI 字节。例如：

```text
80 80 90 3C 64   → Note On, channel 1, note 60, velocity 100
80 80 80 3C 00   → Note Off, channel 1, note 60
80 80 B0 01 40   → CC1, channel 1, value 64
```

时间戳由 BLE-MIDI 规范定义为低 13 bit 运行时间；具体数值每次不同，测试时只比较后面的
MIDI 字节即可。一个 BLE 写入可以携带多个 MIDI 消息，固件会顺序解析。

### nRF Connect 测试表

在 nRF Connect 中连接 `ESP32-S3 MIDI`，展开 BLE-MIDI service，点 characteristic 右侧的
Notify（三个箭头），然后以 `Write Without Response` 写入下列十六进制：

| 动作 | 写入的十六进制 | 预期 USB/DAW |
|---|---|---|
| Note On ch1 C4 vel100 | `80 80 90 3C 64` | C4 Note On，velocity 100 |
| Note Off ch1 C4 | `80 80 80 3C 00` | C4 Note Off |
| CC1 ch1 = 64 | `80 80 B0 01 40` | CC1 到 64（Ableton 用 MIDI Map 绑定参数） |
| 14-bit CC1，值 8192 | `80 80 B0 01 40 80 B0 21 00` | 先 CC1=64，再 CC33=0，顺序不能反 |

通道可改：Note On/Off 用 `9n`/`8n`，CC 用 `Bn`，`n = 0..F` 对应 MIDI 通道 1..16。
例如通道 4 的 CC7=100：`80 80 B3 07 64`。

### Ableton Live 验证

1. `Preferences → Link, Tempo & MIDI → MIDI Ports`，把 `ESP32-S3 MIDI Bridge` 的
   **Track** 打开；要做 CC 映射时同时打开 **Remote**。
2. 新建 MIDI 轨，输入选 `ESP32-S3 MIDI Bridge`，加载任意乐器并点亮监听/录音。
3. 写入上表的 Note On/Off，Ableton 应显示音符并发声。
4. CC 映射：点 Ableton 右上角 `MIDI`（Map）→ 点一个旋钮/参数 → 从 nRF Connect 写
   `80 80 B0 01 40`；映射应显示 CC1。再映射第二个参数到 `80 80 B0 21 00`（CC33），
   验证 14-bit 对的 MSB/LSB 都到达。
5. 不同 DAW 对 Middle C 的八度命名不同（60 可能显示 C3 或 C4），以 MIDI 监视器/音符编号为准。

## 双向反馈：USB → BLE

1. 手机/nRF Connect 先连接并打开 Notify。
2. 在 Ableton 中播放一个包含 CC 自动化（例如 CC1）的 MIDI clip，输出到
   `ESP32-S3 MIDI Bridge`。
3. nRF Connect 的 characteristic 会收到通知，去掉时间戳后的尾部应为 `B0 01 xx`；
   14-bit 对会收到 `B0 01 40`、`B0 21 00` 两条，顺序与 USB 输入一致。
4. 关闭 Notify 后再播放，固件不会崩溃；`BRIDGE stats` 的 `USB->BLE drop` 会增加。
   重新打开 Notify 后恢复正常。

也可以在 Windows 用 mido 发一条 CC 做快速验证（不作为固件依赖）：

```python
import mido
with mido.open_output("ESP32-S3 MIDI Bridge 3") as out:
    out.send(mido.Message("control_change", channel=0, control=1, value=64))
```

## 断线清音与重连

### 断开 BLE 时防止卡音

1. 连接并开启 Notify。
2. 写 Note On `80 80 90 3C 7F`，不要写 Note Off。
3. 在 nRF Connect 里断开。
4. 预期：USB 侧收到 16 个通道 × 3 条清音消息，共 48 条：

   ```text
   B0 7B 00, B0 78 00, B0 40 00
   B1 7B 00, B1 78 00, B1 40 00
   ...
   BF 7B 00, BF 78 00, BF 40 00
   ```

   `7B` = All Notes Off，`78` = All Sound Off，`40` = Sustain Off。
5. UART 应出现 `BLE ready -> false; panic`，且 `panic_fail=0`；Ableton 音符应立即停止。

### 重连

断开后设备会重新广播。再次扫描应仍看到 `ESP32-S3 MIDI` 和同一个
service UUID；连接、Notify、Note/CC 都应正常。重连后 `flushes` 增加，旧队列不会重放。

### USB 重新插拔

USB 新挂载时固件会等 50 ms 让主机枚举完成，然后同样发 48 条清音，并清空队列。
测试步骤：BLE 保持连接并按住一个 Note On → 拔掉 ESP32-S3 原生 USB → 重新插上 →
USB 设备重新枚举，BLE 保持连接，不应重放旧音符。该步骤需要人工拔插，属于硬件验收项。

## 延迟统计与验收

`midi_bridge` 每 5 秒打印一次累计统计：

```text
BRIDGE stats: BLE->USB sent=500 drop=0 p50=500us p95=800us max=942us;
USB->BLE sent=0 drop=0; unsupported=0 tx_drop=0 usb_unsupported=0 flushes=14 panic_fail=0
```

字段含义：

| 字段 | 含义 |
|---|---|
| `BLE->USB sent` | 已完整写入 USB FIFO 的 BLE 消息数（不含 panic） |
| `drop` | BLE 回调/队列丢弃 + USB 未挂载或未就绪时丢弃的 BLE 消息 |
| `p50` / `p95` | 从 BLE 回调入队到 USB 写完的延迟，100 µs 分桶的桶下界 |
| `max` | 观察到的最大延迟（µs，精确值） |
| `USB->BLE sent` / `drop` | 已成功发出 / 因未订阅或发送失败丢弃的 USB 消息数 |
| `unsupported` | BLE-MIDI 解析器拒绝的消息（例如 SysEx/非法包） |
| `tx_drop` | `ble_midi` 通知发送层报告的丢包数 |
| `usb_unsupported` | USB OUT 中不转发的包（cable 非 0、SysEx 等） |
| `flushes` | 因连接/断开/USB 挂载边界清空队列的次数 |
| `panic_fail` | 清音消息在 5 ms 重试窗口内未写完的次数 |

延迟验收结果（本机 Windows BLE 适配器，`CONFIG_FREERTOS_HZ=1000`）：

- 500 条消息、约 100 Hz 连续写入：`sent=500 drop=0 p50=500us p95=800us max=942us`。
- 10 s CC1 扫动（约 94 Hz）：942 条全部到达 USB，顺序完整且最终值 127；
  `p95=600us max=3793us drop=0`。
- 5 分钟持续 Note/CC：连接保持，约 6134 条消息，`p95=600us max=1552us drop=0`。
- 目标 `p95 < 5000 µs`，实测远低于目标；MTU 247，连接间隔 15 ms。
- p50/p95 是桶下界：桶 63 显示 `6300us` 表示实际值 ≥ 6300 µs；`max` 给出精确上界。
- 该延迟只覆盖 ESP32 内部入队到 USB FIFO 接受；不包含 BLE 空口、Windows USB 驱动和 DAW 缓冲。
- 空闲链路上的单次测量会受手机/适配器调度影响；高负载或远距离时应看 `drop`、`tx_drop` 和 `max`。
- 100 Hz CC 连续扫动、5 分钟持续 Note/CC、断线重连的实测记录见下方“阶段 3 验收记录”。

## 阶段 3 验收记录

| 项目 | 结果 |
|---|---|
| 构建 | `Project build complete`，app 约 0x8d660，1 MB 分区剩余 45% |
| 广播/服务 | `ESP32-S3 MIDI`，service UUID 可见，characteristic 可写/通知 |
| BLE→USB Note/CC | nRF Connect/bleak 写入，USB MIDI 收到 |
| 14-bit CC 对 | `B0 01 40` 后 `B0 21 00`，顺序正确 |
| USB→BLE | USB MIDI CC 到达 BLE notification；关闭 Notify 时 drop 增加且不崩溃 |
| Ableton 识别 | Live 12.4.6 的 MIDI 设备表中出现 `ESP32-S3 MIDI Bridge`（输入/输出，Track 默认开）；音符/CC 映射需在 Live 内人工确认 |
| 断线清音 | 48 条 CC 123/120/64，`panic_fail=0` |
| 重连 | 连续 3 次连接/断开，重新广播，service UUID 不变，消息计数递增，`drop=0` |
| 100 Hz 突发 | 500 条，`drop=0`，`p95=800us` |
| 100 Hz CC 扫动 | 942 条全部到达 USB，顺序正确，最终值 127；`drop=0`，`p95=600us`，`max=3793us` |
| 5 分钟持续会话 | 2921 个 Note On/Off 周期 + 292 条 CC（约 6134 条），连接保持 300 s，`drop=0`，`p95=600us`，`max=1552us` |
| USB 物理拔插 | 需要人工拔插验收；挂载清音路径已在启动日志验证 |

## 已知限制

- **不转发 SysEx**：BLE 侧解析器拒绝/忽略 SysEx；USB OUT 的 CIN `0x04..0x07` 也会被丢弃。
  需要时再扩展（注意 BLE-MIDI 的 SysEx 分包与 USB 的 3 字节事件包不同）。
- **没有 USB 状态 GATT**：手机 App 目前无法查询 USB 是否已挂载；只能通过 BLE 连接状态和
  数据是否被丢弃间接判断。
- **Android App 在 `app/`**：连接、基础控件和布局持久化已实现，见 `app/README.md`；
  USB→BLE 回传写入控件 UI 属于阶段 5。
- **没有绑定/加密**：BLE 连接当前是开放外设，未启用 bonding；后续按 App 需求加。
- **单连接**：`CONFIG_BT_NIMBLE_MAX_CONNECTIONS=1`，只支持一个 BLE central。
- **只处理 cable 0**：USB-MIDI 多 cable 尚未使用。
- **没有持久化配置**：通道、控件布局等由后续 App 负责。
- BLE-MIDI 时间戳仅用于协议封装，未做主机时钟同步；当前控制消息不依赖精确时间戳。

## 故障排查

- **电脑没有 MIDI 设备**：确认插的是 ESP32-S3 原生 USB 口，数据线支持数据传输；换 USB 口；
  看 UART 是否有 `USB initialization DONE` 和 `USB mounted`。
- **能看到 BLE 但连不上**：确认手机支持 BLE 5 扫描；先关闭再打开蓝牙；设备只允许一个连接。
- **nRF Connect 写入后 USB 没反应**：确认已打开 Notify 只是为了回传，写入本身不需要 Notify；
  确认写的是 `7772e5db-...` characteristic，且使用 Write Without Response。
- **Ableton 没输入**：在 MIDI Ports 中打开 Track；Windows 可能显示端口编号（如
  `ESP32-S3 MIDI Bridge 2`），以实际枚举名为准。
- **延迟偏高**：确认 `sdkconfig` 中 `CONFIG_FREERTOS_HZ=1000`；默认 100 Hz 会把 1 ms
  轮询变成 10 ms。执行干净构建后重新烧录。
- **COM5 打不开**：关闭其他串口监视器/Arduino IDE；`idf.py monitor` 与烧录不能同时占用 UART。
