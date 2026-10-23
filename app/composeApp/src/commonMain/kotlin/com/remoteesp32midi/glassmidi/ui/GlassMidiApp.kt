package com.remoteesp32midi.glassmidi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.remoteesp32midi.glassmidi.ble.BleDevice
import com.remoteesp32midi.glassmidi.ble.ConnectionPhase
import com.remoteesp32midi.glassmidi.model.ChannelStrip
import com.remoteesp32midi.glassmidi.model.Control
import com.remoteesp32midi.glassmidi.model.toChannelStrips
import com.remoteesp32midi.glassmidi.store.ControllerStore
import com.remoteesp32midi.glassmidi.store.UiState

private val DockBottomInset = 82.dp

private enum class DockTab(
    val label: String,
) {
    CONTROL("控制"),
    CUSTOMIZE("自定义"),
    CONNECTION("连接设置"),
}

@Composable
fun GlassMidiApp(
    store: ControllerStore,
    permissionsReady: Boolean,
    onRequestPermissions: () -> Unit,
    onConnect: (BleDevice) -> Unit,
    onDisconnect: () -> Unit,
    landscapeDockRevealed: Boolean = false,
    landscapeDockTick: Int = 0,
    onLandscapeDockInteraction: () -> Unit = {},
    onDismissLandscapeDock: () -> Unit = {},
) {
    val state by store.state.collectAsState()
    val backdrop = rememberLayerBackdrop()
    var editor by remember { mutableStateOf<Control?>(null) }
    var editingChannelStrip by remember { mutableStateOf<ChannelStrip?>(null) }
    var deletingChannelStrip by remember { mutableStateOf<ChannelStrip?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedBankPage by remember { mutableIntStateOf(0) }
    val activeTab = DockTab.entries[selectedTab.coerceIn(0, DockTab.entries.lastIndex)]
    val bottomContentColor = GlassText

    LaunchedEffect(state.loaded) {
        if (state.loaded) {
            store.ensureDefaultStrips()
        }
    }

    val strips = remember(state.controls, state.channelNames) {
        state.controls.toChannelStrips(state.channelNames)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val isLandscape = maxWidth > maxHeight
        val dockVisible = !isLandscape || landscapeDockRevealed
        val bottomInset = if (isLandscape) 0.dp else DockBottomInset

        LaunchedEffect(isLandscape, landscapeDockRevealed, selectedTab, landscapeDockTick) {
            if (isLandscape && landscapeDockRevealed) {
                delay(3500L)
                onDismissLandscapeDock()
            }
        }

        BackgroundLayer(backdrop)

        Box(
            Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (activeTab) {
                    DockTab.CONTROL, DockTab.CUSTOMIZE -> {
                        val editMode = activeTab == DockTab.CUSTOMIZE
                        val stripsPerPage = 4
                        val pageCount = ((strips.size + stripsPerPage - 1) / stripsPerPage)
                            .coerceAtLeast(1)
                        val currentPage = selectedBankPage.coerceIn(0, pageCount - 1)
                        LaunchedEffect(currentPage, pageCount) {
                            if (selectedBankPage != currentPage) {
                                selectedBankPage = currentPage
                            }
                        }
                        val pageStart = currentPage * stripsPerPage
                        val pageStrips = strips.drop(pageStart).take(stripsPerPage)

                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(bottom = bottomInset),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                    pageStrips.forEachIndexed { idx, strip ->
                                        if (idx > 0) {
                                            Box(
                                                Modifier
                                                    .width(0.5.dp)
                                                    .fillMaxHeight()
                                                    .padding(vertical = 8.dp)
                                                    .background(Color.Black.copy(alpha = 0.08f)),
                                            )
                                        }
                                        key(strip.index) {
                                            ChannelStripCard(
                                                strip = strip,
                                                totalStrips = strips.size,
                                                backdrop = backdrop,
                                                editMode = editMode,
                                                landscape = isLandscape,
                                                onPressStart = {
                                                    if (isLandscape && landscapeDockRevealed) {
                                                        onDismissLandscapeDock()
                                                    }
                                                    store.buttonDown(it)
                                                },
                                                onPressEnd = { store.buttonUp(it) },
                                                onContinuousChange = { id, value ->
                                                    if (isLandscape && landscapeDockRevealed) {
                                                        onDismissLandscapeDock()
                                                    }
                                                    store.setContinuousValue(id, value)
                                                },
                                                onContinuousFinished = { id ->
                                                    store.flushContinuousValue(id)
                                                },
                                                onEditControl = { control ->
                                                    if (editMode) editor = control
                                                },
                                                onMoveLeft = {
                                                    if (strip.index > 0) {
                                                        store.moveChannelStrip(strip.index, strip.index - 1)
                                                    }
                                                },
                                                onMoveRight = {
                                                    if (strip.index < strips.size - 1) {
                                                        store.moveChannelStrip(strip.index, strip.index + 1)
                                                    }
                                                },
                                                onDeleteStrip = {
                                                    if (editMode) deletingChannelStrip = strip
                                                },
                                                onRenameStrip = {
                                                    if (editMode) editingChannelStrip = strip
                                                },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .fillMaxHeight(),
                                            )
                                        }
                                    }
                                    val emptySlots = (stripsPerPage - pageStrips.size).coerceAtLeast(0)
                                    repeat(emptySlots) {
                                        Box(
                                            Modifier
                                                .width(0.5.dp)
                                                .fillMaxHeight()
                                                .padding(vertical = 8.dp)
                                                .background(Color.Black.copy(alpha = 0.05f)),
                                        )
                                        Spacer(
                                            Modifier
                                                .weight(1f)
                                                .fillMaxHeight(),
                                        )
                                    }
                            }
                            if (pageCount > 1 || editMode) {
                                MixerPageBar(
                                    stripsCount = strips.size,
                                    stripsPerPage = stripsPerPage,
                                    pageCount = pageCount,
                                    currentPage = currentPage,
                                    editMode = editMode,
                                    onSelectPage = { selectedBankPage = it },
                                    onAddStrip = {
                                        val newIndex = store.addChannelStrip()
                                        selectedBankPage = newIndex / stripsPerPage
                                    },
                                )
                            }
                        }
                    }

                    DockTab.CONNECTION -> {
                        ConnectionSettingsScreen(
                            state = state,
                            backdrop = backdrop,
                            permissionsReady = permissionsReady,
                            onRequestPermissions = onRequestPermissions,
                            onScan = { store.startScan() },
                            onStopScan = { store.stopScan() },
                            onDisconnect = onDisconnect,
                            onConnect = onConnect,
                            onSelectDevicePort = { store.setDevicePort(it) },
                            onClearError = { store.clearError() },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(bottom = bottomInset),
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = dockVisible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp)
                .padding(bottom = 10.dp),
        ) {
            LiquidBottomTabs(
                selectedTabIndex = selectedTab,
                onTabSelected = {
                    selectedTab = it
                    if (isLandscape) onLandscapeDockInteraction()
                },
                backdrop = backdrop,
                tabsCount = DockTab.entries.size,
                modifier = Modifier.fillMaxWidth(),
            ) {
                DockTab.entries.forEachIndexed { index, tab ->
                    val tabContentColor =
                        if (index == selectedTab) GlassAccent else bottomContentColor
                    LiquidBottomTab(
                        selected = index == selectedTab,
                        onClick = {
                            selectedTab = index
                            if (isLandscape) onLandscapeDockInteraction()
                        },
                    ) {
                        Box {
                            TabIcon(
                                tab = tab,
                                color = tabContentColor,
                                modifier = Modifier.size(26.dp),
                            )
                            // 状态只用一个点表示:已连接不显示，其它状态按严重程度着色。
                            val statusDot = when (state.connection.phase) {
                                ConnectionPhase.READY -> null
                                ConnectionPhase.ERROR -> GlassDanger
                                ConnectionPhase.CONNECTING,
                                ConnectionPhase.RECONNECTING,
                                ConnectionPhase.SCANNING -> GlassAccent
                                ConnectionPhase.IDLE -> GlassMuted
                            }
                            if (statusDot != null && tab == DockTab.CONNECTION) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 1.dp, y = (-1).dp)
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(statusDot),
                                )
                            }
                        }
                        BasicText(
                            tab.label,
                            style = TextStyle(
                                color = tabContentColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                }
            }
        }

        editor?.let { control ->
            ControlEditor(
                backdrop = backdrop,
                initial = control,
                allControls = state.controls,
                onDismiss = { editor = null },
                onSave = { candidate ->
                    store.upsertControl(candidate)
                },
                onDelete = null,
                allowKindChange = false,
            )
        }

        editingChannelStrip?.let { strip ->
            ChannelNameEditor(
                backdrop = backdrop,
                stripNumber = strip.number,
                initialName = strip.customName.orEmpty(),
                onDismiss = { editingChannelStrip = null },
                onSave = { newName ->
                    store.renameChannelStrip(strip.index, newName)
                },
            )
        }

        deletingChannelStrip?.let { strip ->
            DeleteChannelConfirmDialog(
                backdrop = backdrop,
                stripNumber = strip.number,
                customName = strip.customName,
                onDismiss = { deletingChannelStrip = null },
                onConfirm = {
                    store.deleteChannelStrip(strip.index)
                },
            )
        }
    }
}

@Composable
private fun BackgroundLayer(backdrop: LayerBackdrop) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.White)
            .layerBackdrop(backdrop),
    )
}


@Composable
private fun MixerPageBar(
    stripsCount: Int,
    stripsPerPage: Int,
    pageCount: Int,
    currentPage: Int,
    editMode: Boolean,
    onSelectPage: (Int) -> Unit,
    onAddStrip: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (page in 0 until pageCount) {
                val firstCh = page * stripsPerPage + 1
                val lastCh = ((page + 1) * stripsPerPage).coerceAtMost(stripsCount)
                val label = if (firstCh == lastCh) {
                    "CH $firstCh"
                } else {
                    "CH $firstCh-$lastCh"
                }
                BankChip(
                    text = label,
                    selected = page == currentPage,
                    onClick = { onSelectPage(page) },
                )
            }
        }
        if (editMode) {
            BankChip(
                text = "+ 通道",
                selected = true,
                onClick = onAddStrip,
            )
        }
    }
}

@Composable
private fun BankChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(
                if (selected) GlassAccent else Color.Black.copy(alpha = 0.05f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            style = TextStyle(
                color = if (selected) Color.White else GlassText,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun ConnectionSettingsScreen(
    state: UiState,
    backdrop: Backdrop,
    permissionsReady: Boolean,
    onRequestPermissions: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onDisconnect: () -> Unit,
    onConnect: (BleDevice) -> Unit,
    onSelectDevicePort: (Int) -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            BasicText(
                "连接设置",
                style = TextStyle(GlassText, 22.sp, FontWeight.SemiBold),
            )
            BasicText(
                "管理 ESP32-S3 BLE-MIDI 无线桥接器连接",
                style = TextStyle(GlassMuted, 11.sp),
            )
        }

        GlassSurface(
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ConnectionGlyph(
                        connected = state.connection.phase == ConnectionPhase.READY,
                        error = state.connection.phase == ConnectionPhase.ERROR,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        BasicText(
                            state.connectionTitle(),
                            style = TextStyle(
                                color = if (state.connection.phase == ConnectionPhase.READY) {
                                    GlassAccent
                                } else {
                                    GlassText
                                },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                        BasicText(
                            state.connectionDetail(),
                            style = TextStyle(GlassMuted, 11.sp),
                        )
                    }

                    val actionText = when {
                        !permissionsReady -> "授权蓝牙"
                        state.connection.phase == ConnectionPhase.READY -> "断开连接"
                        state.connection.phase == ConnectionPhase.CONNECTING ||
                            state.connection.phase == ConnectionPhase.RECONNECTING -> "取消连接"
                        state.scanning || state.connection.phase == ConnectionPhase.SCANNING -> "停止扫描"
                        else -> "扫描设备"
                    }
                    val action = when {
                        !permissionsReady -> onRequestPermissions
                        state.connection.phase == ConnectionPhase.READY -> onDisconnect
                        state.connection.phase == ConnectionPhase.CONNECTING ||
                            state.connection.phase == ConnectionPhase.RECONNECTING -> onDisconnect
                        state.scanning || state.connection.phase == ConnectionPhase.SCANNING -> onStopScan
                        else -> onScan
                    }
                    GlassPill(
                        text = actionText,
                        onClick = action,
                        backdrop = backdrop,
                        selected = state.connection.phase != ConnectionPhase.READY,
                    )
                }

                state.error?.let { error ->
                    Hairline()
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            error,
                            Modifier.weight(1f),
                            style = TextStyle(GlassDanger, 11.sp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        BasicText(
                            "关闭",
                            Modifier
                                .clickable(onClick = onClearError)
                                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                            style = TextStyle(GlassMuted, 11.sp),
                        )
                    }
                }

                if (state.devices.isEmpty() &&
                    state.connection.phase == ConnectionPhase.IDLE &&
                    state.lastDeviceAddress != null
                ) {
                    val address = state.lastDeviceAddress
                    val name = state.lastDeviceName ?: "ESP32-S3 MIDI"
                    Hairline()
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            BasicText("上次连接设备", style = TextStyle(GlassMuted, 10.sp))
                            BasicText(name, style = TextStyle(GlassText, 13.sp, FontWeight.Medium))
                            BasicText(address, style = TextStyle(GlassMuted, 9.sp))
                        }
                        GlassPill(
                            text = "快速重连",
                            onClick = { onConnect(BleDevice(name, address, -100)) },
                            backdrop = backdrop,
                            selected = false,
                        )
                    }
                }
            }
        }

        GlassSurface(
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        BasicText(
                            "乐手身份与电脑端口（一拖二模式）",
                            style = TextStyle(GlassText, 14.sp, FontWeight.SemiBold),
                        )
                        BasicText(
                            "两台手机同时连同一个 ESP32 时，按此选择分别路由到电脑的 MIDI 端口 1 或端口 2",
                            style = TextStyle(GlassMuted, 10.sp),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GlassPill(
                        text = "乐手 1 · Port 1",
                        onClick = { onSelectDevicePort(0) },
                        backdrop = backdrop,
                        selected = state.devicePort == 0,
                        modifier = Modifier.weight(1f),
                    )
                    GlassPill(
                        text = "乐手 2 · Port 2",
                        onClick = { onSelectDevicePort(1) },
                        backdrop = backdrop,
                        selected = state.devicePort == 1,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        GlassSurface(
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicText(
                    if (state.scanning) {
                        "正在扫描 BLE-MIDI 设备..."
                    } else if (state.devices.isNotEmpty()) {
                        "发现 ${state.devices.size} 台可用设备"
                    } else {
                        "可用设备列表"
                    },
                    style = TextStyle(GlassText, 13.sp, FontWeight.SemiBold),
                )
                if (state.devices.isEmpty()) {
                    BasicText(
                        if (state.scanning) {
                            "请确保 ESP32-S3 已上电并处于广播状态"
                        } else {
                            "点击上方「扫描设备」搜索附近的 ESP32-S3 MIDI"
                        },
                        style = TextStyle(GlassMuted, 11.sp),
                    )
                } else {
                    state.devices.forEachIndexed { index, device ->
                        if (index > 0) Hairline()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                BasicText(
                                    device.name,
                                    style = TextStyle(GlassText, 13.sp, FontWeight.Medium),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                BasicText(
                                    "${device.address}  ·  ${device.rssi} dBm",
                                    style = TextStyle(GlassMuted, 10.sp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
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
    }
}

@Composable
private fun ConnectionGlyph(
    connected: Boolean,
    error: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val stroke = 1.8.dp.toPx()
        val color = when {
            connected -> GlassAccent
            error -> GlassDanger
            else -> GlassMuted
        }
        if (connected) {
            drawCircle(
                color = color,
                radius = size.minDimension / 2f - stroke / 2f,
                style = Stroke(stroke),
            )
        } else {
            val inset = size.minDimension * 0.28f
            drawLine(
                color = color,
                start = Offset(inset, inset),
                end = Offset(size.width - inset, size.height - inset),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = Offset(size.width - inset, inset),
                end = Offset(inset, size.height - inset),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun Hairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.Black.copy(alpha = 0.08f)),
    )
}

private fun UiState.connectionTitle(): String = when (connection.phase) {
    ConnectionPhase.READY -> "已连接 · Port ${devicePort + 1}"
    ConnectionPhase.SCANNING -> "正在扫描"
    ConnectionPhase.CONNECTING -> "连接中"
    ConnectionPhase.RECONNECTING -> "正在重连"
    ConnectionPhase.ERROR -> "连接异常"
    ConnectionPhase.IDLE -> "未连接 · Port ${devicePort + 1}"
}

private fun UiState.connectionDetail(): String = when (connection.phase) {
    ConnectionPhase.READY -> connection.deviceName ?: "BLE-MIDI"
    ConnectionPhase.SCANNING -> "查找 ESP32-S3 MIDI"
    ConnectionPhase.CONNECTING -> connection.deviceName ?: "正在建立连接"
    ConnectionPhase.RECONNECTING -> "连接断开，自动重试"
    ConnectionPhase.ERROR -> connection.detail ?: error ?: "请重试"
    ConnectionPhase.IDLE -> lastDeviceName?.let { "上次：$it" } ?: "点此进入连接设置"
}

@Composable
private fun TabIcon(
    tab: DockTab,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val stroke = 1.6.dp.toPx()
        when (tab) {
            DockTab.CONTROL -> {
                val xs = listOf(0.22f, 0.5f, 0.78f).map { it * size.width }
                val knobs = listOf(0.35f, 0.62f, 0.45f).map { it * size.height }
                xs.forEachIndexed { index, x ->
                    drawLine(
                        color = color.copy(alpha = 0.55f),
                        start = Offset(x, size.height * 0.15f),
                        end = Offset(x, size.height * 0.85f),
                        strokeWidth = stroke,
                    )
                    drawCircle(
                        color = color,
                        radius = size.width * 0.12f,
                        center = Offset(x, knobs[index]),
                    )
                }
            }

            DockTab.CUSTOMIZE -> {
                val width = size.width * 0.70f
                val height = size.height * 0.70f
                drawRoundRect(
                    color = color,
                    topLeft = Offset(
                        (size.width - width) / 2f,
                        (size.height - height) / 2f,
                    ),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(width * 0.24f, height * 0.24f),
                    style = Stroke(stroke),
                )
                val plus = size.width * 0.14f
                drawLine(
                    color = color,
                    start = Offset(center.x - plus, center.y),
                    end = Offset(center.x + plus, center.y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(center.x, center.y - plus),
                    end = Offset(center.x, center.y + plus),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }

            DockTab.CONNECTION -> {
                drawCircle(
                    color = color,
                    radius = size.minDimension * 0.34f,
                    style = Stroke(stroke),
                )
                drawCircle(
                    color = color,
                    radius = size.minDimension * 0.11f,
                )
            }
        }
    }
}
