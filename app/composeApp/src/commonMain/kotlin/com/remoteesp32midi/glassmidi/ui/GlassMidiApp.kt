package com.remoteesp32midi.glassmidi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.remoteesp32midi.glassmidi.ble.BleDevice
import com.remoteesp32midi.glassmidi.ble.ConnectionPhase
import com.remoteesp32midi.glassmidi.model.Control
import com.remoteesp32midi.glassmidi.model.ControlKind
import com.remoteesp32midi.glassmidi.model.defaultControlLabel
import com.remoteesp32midi.glassmidi.model.defaultMapping
import com.remoteesp32midi.glassmidi.model.defaultValue
import com.remoteesp32midi.glassmidi.store.ControllerStore
import com.remoteesp32midi.glassmidi.store.UiState

@Composable
fun GlassMidiApp(
    store: ControllerStore,
    permissionsReady: Boolean,
    onRequestPermissions: () -> Unit,
    onConnect: (BleDevice) -> Unit,
    onDisconnect: () -> Unit,
) {
    val state by store.state.collectAsState()
    val backdrop = rememberLayerBackdrop()
    var editor by remember { mutableStateOf<Control?>(null) }

    Box(Modifier.fillMaxSize()) {
        BackgroundLayer(backdrop)

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TopBar(
                state = state,
                permissionsReady = permissionsReady,
                backdrop = backdrop,
                onRequestPermissions = onRequestPermissions,
                onScan = { store.startScan() },
                onStopScan = { store.stopScan() },
                onDisconnect = onDisconnect,
            )

            if (!permissionsReady) {
                PermissionBanner(backdrop, onRequestPermissions)
            }

            state.error?.let { error ->
                GlassSurface(
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    tint = GlassDanger.copy(alpha = 0.12f),
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            error,
                            Modifier.weight(1f),
                            style = TextStyle(GlassDanger, 13.sp),
                        )
                        BasicText(
                            "关闭",
                            Modifier
                                .padding(start = 12.dp)
                                .clickable { store.clearError() },
                            style = TextStyle(GlassText, 13.sp, FontWeight.SemiBold),
                        )
                    }
                }
            }

            if (permissionsReady) {
                if (state.devices.isEmpty() &&
                    state.connection.phase == ConnectionPhase.IDLE &&
                    state.lastDeviceAddress != null
                ) {
                    LastDeviceRow(
                        state = state,
                        backdrop = backdrop,
                        onConnect = onConnect,
                    )
                }
                DevicePanel(
                    state = state,
                    backdrop = backdrop,
                    onConnect = onConnect,
                )
            }

            if (state.controls.isEmpty()) {
                EmptyControls(
                    backdrop = backdrop,
                    onAdd = { editor = newControl(state) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.controls, key = { it.id }) { control ->
                        ControlCard(
                            control = control,
                            backdrop = backdrop,
                            onPressStart = { store.buttonDown(control.id) },
                            onPressEnd = { store.buttonUp(control.id) },
                            onContinuousChange = { store.setContinuousValue(control.id, it) },
                            onContinuousFinished = { store.flushContinuousValue(control.id) },
                            onEdit = { editor = control },
                        )
                    }
                }
            }

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                GlassPill(
                    text = "＋  添加控件",
                    onClick = { editor = newControl(state) },
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    selected = true,
                )
            }
        }

        editor?.let { control ->
            ControlEditor(
                backdrop = backdrop,
                initial = control,
                allControls = state.controls,
                onDismiss = { editor = null },
                onSave = { candidate -> store.upsertControl(candidate) },
                onDelete = { store.deleteControl(control.id) },
            )
        }
    }
}

@Composable
private fun BackgroundLayer(backdrop: com.kyant.backdrop.backdrops.LayerBackdrop) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF07111F),
                        Color(0xFF0E1D33),
                        Color(0xFF1A1228),
                    )
                )
            )
            .layerBackdrop(backdrop)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                color = Color(0xFF1B6E9B).copy(alpha = 0.35f),
                radius = size.minDimension * 0.48f,
                center = Offset(size.width * 0.85f, size.height * 0.12f),
            )
            drawCircle(
                color = Color(0xFF7448C7).copy(alpha = 0.24f),
                radius = size.minDimension * 0.55f,
                center = Offset(size.width * 0.08f, size.height * 0.72f),
            )
            drawCircle(
                color = Color(0xFF148F91).copy(alpha = 0.16f),
                radius = size.minDimension * 0.40f,
                center = Offset(size.width * 0.70f, size.height * 0.92f),
            )
        }
    }
}

@Composable
private fun TopBar(
    state: UiState,
    permissionsReady: Boolean,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onRequestPermissions: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val actionText = when {
        !permissionsReady -> "授权"
        state.connection.phase == ConnectionPhase.READY -> "断开"
        state.connection.phase == ConnectionPhase.CONNECTING ||
            state.connection.phase == ConnectionPhase.RECONNECTING -> "取消"
        state.scanning -> "停止"
        else -> "扫描"
    }
    val action = when {
        !permissionsReady -> onRequestPermissions
        state.connection.phase == ConnectionPhase.READY -> onDisconnect
        state.connection.phase == ConnectionPhase.CONNECTING ||
            state.connection.phase == ConnectionPhase.RECONNECTING -> onDisconnect
        state.scanning -> onStopScan
        else -> onScan
    }

    GlassSurface(
        backdrop = backdrop,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                BasicText(
                    "Glass MIDI",
                    style = TextStyle(GlassText, 25.sp, FontWeight.SemiBold),
                )
                Spacer(Modifier.height(2.dp))
                BasicText(
                    state.statusText(),
                    style = TextStyle(
                        color = if (state.connection.phase == ConnectionPhase.READY) GlassAccent else GlassMuted,
                        fontSize = 12.sp,
                    ),
                )
            }
            GlassPill(
                text = actionText,
                onClick = action,
                backdrop = backdrop,
                selected = state.connection.phase == ConnectionPhase.READY,
            )
        }
    }
}

@Composable
private fun PermissionBanner(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onRequestPermissions: () -> Unit,
) {
    GlassSurface(
        backdrop = backdrop,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        tint = GlassDanger.copy(alpha = 0.10f),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "需要蓝牙权限才能扫描设备",
                Modifier.weight(1f),
                style = TextStyle(GlassDanger, 13.sp),
            )
            GlassPill(
                text = "授权",
                onClick = onRequestPermissions,
                backdrop = backdrop,
            )
        }
    }
}

@Composable
private fun LastDeviceRow(
    state: UiState,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onConnect: (BleDevice) -> Unit,
) {
    val address = state.lastDeviceAddress ?: return
    val name = state.lastDeviceName ?: "ESP32-S3 MIDI"
    GlassSurface(
        backdrop = backdrop,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                BasicText("上次设备", style = TextStyle(GlassMuted, 11.sp))
                BasicText(name, style = TextStyle(GlassText, 15.sp, FontWeight.Medium))
            }
            GlassPill(
                text = "重连",
                onClick = { onConnect(BleDevice(name, address, -100)) },
                backdrop = backdrop,
                selected = true,
            )
        }
    }
}

@Composable
private fun DevicePanel(
    state: UiState,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onConnect: (BleDevice) -> Unit,
) {
    if (state.devices.isEmpty()) return
    GlassSurface(
        backdrop = backdrop,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(
                if (state.scanning) "正在扫描 BLE-MIDI 设备" else "发现 ${state.devices.size} 台设备",
                style = TextStyle(GlassMuted, 12.sp),
            )
            state.devices.take(4).forEach { device ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        BasicText(device.name, style = TextStyle(GlassText, 15.sp, FontWeight.Medium))
                        BasicText(
                            "${device.address}  ${device.rssi} dBm",
                            style = TextStyle(GlassMuted, 10.sp),
                        )
                    }
                    GlassPill(
                        text = "连接",
                        onClick = { onConnect(device) },
                        backdrop = backdrop,
                        selected = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyControls(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        backdrop = backdrop,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText("还没有控件", style = TextStyle(GlassText, 22.sp, FontWeight.SemiBold))
            Spacer(Modifier.height(6.dp))
            BasicText(
                "先连接 ESP32-S3，再添加 Button / Knob / Fader",
                style = TextStyle(
                    color = GlassMuted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                ),
            )
            Spacer(Modifier.height(20.dp))
            GlassPill(text = "添加第一个控件", onClick = onAdd, backdrop = backdrop, selected = true)
        }
    }
}

private fun UiState.statusText(): String = when (connection.phase) {
    ConnectionPhase.READY -> "已连接 ${connection.deviceName ?: "BLE-MIDI"}"
    ConnectionPhase.SCANNING -> "正在扫描 ESP32-S3 MIDI"
    ConnectionPhase.CONNECTING -> "连接中 ${connection.deviceName ?: ""}".trim()
    ConnectionPhase.RECONNECTING -> "连接断开，自动重连中"
    ConnectionPhase.ERROR -> connection.detail ?: "连接异常"
    ConnectionPhase.IDLE -> lastDeviceName?.let { "上次设备：$it" } ?: "未连接"
}

private fun newControl(state: UiState): Control {
    return Control(
        id = "",
        label = defaultControlLabel(ControlKind.BUTTON, state.controls),
        kind = ControlKind.BUTTON,
        mapping = ControlKind.BUTTON.defaultMapping(),
        value = ControlKind.BUTTON.defaultValue(),
    )
}
