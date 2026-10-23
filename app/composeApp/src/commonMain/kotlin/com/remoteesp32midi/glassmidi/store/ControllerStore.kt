package com.remoteesp32midi.glassmidi.store

import com.remoteesp32midi.glassmidi.ble.BleConnection
import com.remoteesp32midi.glassmidi.ble.BleDevice
import com.remoteesp32midi.glassmidi.ble.BleMidiTransport
import com.remoteesp32midi.glassmidi.ble.ConnectionPhase
import com.remoteesp32midi.glassmidi.ble.TransportEvent
import com.remoteesp32midi.glassmidi.model.CONTROLS_PER_STRIP
import com.remoteesp32midi.glassmidi.model.ButtonBehavior
import com.remoteesp32midi.glassmidi.model.CURRENT_SCHEMA_VERSION
import com.remoteesp32midi.glassmidi.model.Control
import com.remoteesp32midi.glassmidi.model.ControlKind
import com.remoteesp32midi.glassmidi.model.DEFAULT_STRIP_COUNT
import com.remoteesp32midi.glassmidi.model.Layout
import com.remoteesp32midi.glassmidi.model.MidiMapping
import com.remoteesp32midi.glassmidi.model.MidiMessage
import com.remoteesp32midi.glassmidi.model.MidiResolution
import com.remoteesp32midi.glassmidi.model.MidiType
import com.remoteesp32midi.glassmidi.model.PersistedState
import com.remoteesp32midi.glassmidi.model.isChannelStripLayout
import com.remoteesp32midi.glassmidi.model.hasPresetStripMappings
import com.remoteesp32midi.glassmidi.model.presetStripMapping
import com.remoteesp32midi.glassmidi.model.presetStripSlotDefaultLabel
import com.remoteesp32midi.glassmidi.model.validateControl
import com.remoteesp32midi.glassmidi.model.valueRange
import com.remoteesp32midi.glassmidi.model.withDefaultLabel
import com.remoteesp32midi.glassmidi.storage.LayoutStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

data class UiState(
    val controls: List<Control> = emptyList(),
    val connection: BleConnection = BleConnection(),
    val devices: List<BleDevice> = emptyList(),
    val scanning: Boolean = false,
    val lastDeviceAddress: String? = null,
    val lastDeviceName: String? = null,
    val error: String? = null,
    val loaded: Boolean = false,
)

class ControllerStore(
    private val transport: BleMidiTransport,
    private val layoutStore: LayoutStore,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val pendingContinuous = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val pendingFourteenBit = mutableMapOf<Pair<Int, Int>, Int>()
    private val suppressedContinuousIds = mutableSetOf<String>()
    private val echoSuppressionJobs = mutableMapOf<String, Job>()
    private var saveJob: Job? = null

    init {
        scope.launch {
            val persisted = runCatching { layoutStore.load() }.getOrElse {
                PersistedState()
            }
            val rawControls = persisted.layout.controls
            val resetToDefaultModes = persisted.schemaVersion < CURRENT_SCHEMA_VERSION ||
                persisted.layout.schemaVersion < CURRENT_SCHEMA_VERSION
            val migrated = migrateLoadedControls(rawControls)
            val controls = if (resetToDefaultModes && migrated.isChannelStripLayout()) {
                applyPresetMappings(migrated, preserveMode = false)
            } else {
                migrated
            }
            _state.update {
                it.copy(
                    controls = controls,
                    lastDeviceAddress = persisted.deviceAddress,
                    lastDeviceName = persisted.deviceName,
                    loaded = true,
                )
            }
            if (controls != rawControls || resetToDefaultModes) {
                scheduleSave()
            }
        }

        scope.launch {
            transport.events.collect { event ->
                when (event) {
                    is TransportEvent.Devices -> _state.update {
                        it.copy(devices = event.devices, scanning = it.connection.phase == ConnectionPhase.SCANNING)
                    }

                    is TransportEvent.ConnectionChanged -> {
                        val connection = event.connection
                        _state.update { current ->
                            val controls = if (connection.phase == ConnectionPhase.READY) {
                                current.controls
                            } else {
                                current.controls.map {
                                    if (it.kind == ControlKind.BUTTON) it.copy(value = 0) else it
                                }
                            }
                            current.copy(
                                controls = controls,
                                connection = connection,
                                scanning = connection.phase == ConnectionPhase.SCANNING,
                                lastDeviceAddress = connection.deviceAddress ?: current.lastDeviceAddress,
                                lastDeviceName = connection.deviceName ?: current.lastDeviceName,
                                error = if (connection.phase == ConnectionPhase.READY) null else current.error,
                            )
                        }
                        if (connection.phase == ConnectionPhase.READY) {
                            // 重连/首次 Ready 后恢复连续控件在 DAW 侧的当前值。
                            _state.value.controls
                                .filter { it.kind != ControlKind.BUTTON }
                                .forEach { sendContinuous(it, it.value) }
                        } else {
                            pendingContinuous.value = emptyMap()
                            pendingFourteenBit.clear()
                            clearEchoSuppression()
                        }
                        if (connection.phase == ConnectionPhase.READY ||
                            connection.phase == ConnectionPhase.ERROR
                        ) {
                            scheduleSave()
                        }
                    }

                    is TransportEvent.Error -> _state.update { it.copy(error = event.message) }
                }
            }
        }

        // USB/DAW → ESP32 → 手机：只更新 UI，不回写到 BLE。
        scope.launch {
            transport.incoming.collect { messages -> applyIncoming(messages) }
        }

        scope.launch {
            while (isActive) {
                delay(CONTINUOUS_INTERVAL_MS)
                flushPendingContinuous()
            }
        }
    }

    fun startScan() {
        _state.update {
            it.copy(
                scanning = true,
                devices = emptyList(),
                connection = BleConnection(phase = ConnectionPhase.SCANNING),
                error = null,
            )
        }
        transport.startScan()
    }

    fun stopScan() {
        transport.stopScan()
        _state.update {
            if (it.connection.phase == ConnectionPhase.SCANNING) {
                it.copy(scanning = false, connection = BleConnection())
            } else {
                it.copy(scanning = false)
            }
        }
    }

    fun connect(device: BleDevice) {
        transport.stopScan()
        _state.update {
            it.copy(
                scanning = false,
                devices = emptyList(),
                lastDeviceAddress = device.address,
                lastDeviceName = device.name,
                error = null,
                connection = BleConnection(
                    phase = ConnectionPhase.CONNECTING,
                    deviceName = device.name,
                    deviceAddress = device.address,
                ),
            )
        }
        scheduleSave()
        transport.connect(device.address)
    }

    fun disconnect() {
        transport.disconnect()
        _state.update {
            it.copy(
                scanning = false,
                devices = emptyList(),
                connection = BleConnection(
                    phase = ConnectionPhase.IDLE,
                    deviceName = it.lastDeviceName,
                    deviceAddress = it.lastDeviceAddress,
                ),
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** 返回 null 表示保存成功，否则返回给界面展示的校验错误。 */
    fun upsertControl(candidate: Control): String? {
        val normalized = candidate.withDefaultLabel(_state.value.controls).let {
            if (it.id.isBlank()) it.copy(id = newId()) else it
        }
        val value = normalized.value.coerceIn(normalized.kind.valueRange())
        val control = normalized.copy(value = value)
        val error = validateControl(control, _state.value.controls)
        if (error != null) return error

        _state.update { current ->
            val existingIndex = current.controls.indexOfFirst { it.id == control.id }
            val controls = if (existingIndex >= 0) {
                current.controls.toMutableList().also { it[existingIndex] = control }
            } else {
                current.controls + control
            }
            current.copy(controls = controls, error = null)
        }
        scheduleSave()
        return null
    }

    fun deleteControl(id: String) {
        _state.update { current ->
            current.copy(controls = current.controls.filterNot { it.id == id })
        }
        pendingContinuous.update { it - id }
        clearEchoSuppression(id)
        scheduleSave()
    }

    /**
     * 拖拽换位：把 [sourceId] 放到 [targetId] 原来的位置。
     * 全局列表顺序也是网格顺序，因此换位后立即持久化。
     */
    fun moveControl(sourceId: String, targetId: String) {
        if (sourceId == targetId) return
        var moved = false
        _state.update { current ->
            val from = current.controls.indexOfFirst { it.id == sourceId }
            val to = current.controls.indexOfFirst { it.id == targetId }
            if (from < 0 || to < 0) return@update current
            val reordered = current.controls.toMutableList()
            val control = reordered.removeAt(from)
            reordered.add(to.coerceIn(0, reordered.size), control)
            moved = true
            current.copy(controls = reordered)
        }
        if (moved) scheduleSave()
    }

    /**
     * 确保当前布局为标准调音台通道条结构（每条 1 Fader + 3 Knob + 1 Button）。
     * 若已有控件不是通道条结构，则保留原有控件并补齐为至少 [minStrips] 条通道。
     */
    fun ensureDefaultStrips(minStrips: Int = DEFAULT_STRIP_COUNT) {
        val current = _state.value.controls
        if (current.hasPresetStripMappings() && current.size >= minStrips * CONTROLS_PER_STRIP) return

        if (current.isChannelStripLayout() && current.size >= minStrips * CONTROLS_PER_STRIP) {
            val normalized = applyPresetMappings(current)
            _state.update { it.copy(controls = normalized, error = null) }
            scheduleSave()
            return
        }

        val faders = ArrayDeque(current.filter { it.kind == ControlKind.FADER })
        val knobs = ArrayDeque(current.filter { it.kind == ControlKind.KNOB })
        val buttons = ArrayDeque(current.filter { it.kind == ControlKind.BUTTON })
        val neededByExisting = maxOf(
            faders.size,
            (knobs.size + 2) / 3,
            buttons.size,
        )
        val stripCount = maxOf(minStrips, neededByExisting)
        val built = ArrayList<Control>(stripCount * CONTROLS_PER_STRIP)

        for (stripIdx in 0 until stripCount) {
            built += faders.removeFirstOrNull()
                ?: createPresetStripControl(stripIdx, 0)
            for (slotIdx in 1..3) {
                built += knobs.removeFirstOrNull()
                    ?: createPresetStripControl(stripIdx, slotIdx)
            }
            built += buttons.removeFirstOrNull()
                ?: createPresetStripControl(stripIdx, 4)
        }

        val normalized = applyPresetMappings(built, preserveMode = false)
        _state.update { it.copy(controls = normalized, error = null) }
        scheduleSave()
    }

    fun addChannelStrip(): Int {
        ensureDefaultStrips()
        var newStripIndex = 0
        _state.update { current ->
            val stripIndex = current.controls.size / CONTROLS_PER_STRIP
            val built = current.controls.toMutableList()
            for (slotIdx in 0 until CONTROLS_PER_STRIP) {
                built += createPresetStripControl(stripIndex, slotIdx)
            }
            newStripIndex = stripIndex
            current.copy(controls = applyPresetMappings(built), error = null)
        }
        scheduleSave()
        return newStripIndex
    }

    fun deleteChannelStrip(stripIndex: Int) {
        var removedIds: List<String> = emptyList()
        _state.update { current ->
            val stripCount = current.controls.size / CONTROLS_PER_STRIP
            if (stripCount <= 1 || stripIndex !in 0 until stripCount) return@update current
            val start = stripIndex * CONTROLS_PER_STRIP
            val end = start + CONTROLS_PER_STRIP
            removedIds = current.controls.subList(start, end).map { it.id }
            val updated = current.controls.toMutableList().apply {
                subList(start, end).clear()
            }
            current.copy(controls = applyPresetMappings(updated), error = null)
        }
        if (removedIds.isNotEmpty()) {
            pendingContinuous.update { it - removedIds.toSet() }
            removedIds.forEach { clearEchoSuppression(it) }
            scheduleSave()
        }
    }

    fun moveChannelStrip(fromStripIndex: Int, toStripIndex: Int) {
        if (fromStripIndex == toStripIndex) return
        var moved = false
        _state.update { current ->
            val stripCount = current.controls.size / CONTROLS_PER_STRIP
            if (fromStripIndex !in 0 until stripCount || toStripIndex !in 0 until stripCount) {
                return@update current
            }
            val chunks = current.controls.chunked(CONTROLS_PER_STRIP).toMutableList()
            val strip = chunks.removeAt(fromStripIndex)
            chunks.add(toStripIndex, strip)
            moved = true
            current.copy(controls = applyPresetMappings(chunks.flatten()), error = null)
        }
        if (moved) scheduleSave()
    }

    private fun createPresetStripControl(
        stripIndex: Int,
        slotIndex: Int,
    ): Control {
        val kind = when (slotIndex) {
            0 -> ControlKind.FADER
            1, 2, 3 -> ControlKind.KNOB
            else -> ControlKind.BUTTON
        }
        return Control(
            id = newId(),
            label = presetStripSlotDefaultLabel(stripIndex, slotIndex),
            kind = kind,
            mapping = presetStripMapping(stripIndex, slotIndex),
            value = 0,
        )
    }

    private fun applyPresetMappings(
        controls: List<Control>,
        preserveMode: Boolean = true,
    ): List<Control> {
        val usedLabels = HashSet<String>(controls.size)
        val result = ArrayList<Control>(controls.size)
        val defaultPattern = Regex("^CH\\s+\\d+\\s+(Fader|K[1-3]|Btn)$")
        controls.chunked(CONTROLS_PER_STRIP).forEachIndexed { stripIdx, chunk ->
            chunk.forEachIndexed { slotIdx, control ->
                val defaultLabel = presetStripSlotDefaultLabel(stripIdx, slotIdx)
                val candidateLabel = if (
                    control.label.isBlank() ||
                    defaultPattern.matches(control.label) ||
                    control.label in usedLabels
                ) {
                    defaultLabel
                } else {
                    control.label
                }
                val finalLabel = if (candidateLabel !in usedLabels) {
                    candidateLabel
                } else {
                    "$candidateLabel (${stripIdx + 1})"
                }
                usedLabels += finalLabel
                result += control.copy(
                    label = finalLabel,
                    mapping = presetStripMapping(
                        stripIndex = stripIdx,
                        slotIndex = slotIdx,
                        existingMapping = if (preserveMode) control.mapping else null,
                    ),
                )
            }
        }
        return result
    }

    fun buttonDown(id: String) {
        val control = _state.value.controls.find { it.id == id } ?: return
        if (control.kind != ControlKind.BUTTON) return
        if (control.buttonBehavior == ButtonBehavior.TOGGLE) {
            val nextValue = if (control.value > 0) 0 else 127
            val message = when (control.mapping.type) {
                MidiType.NOTE -> if (nextValue > 0) {
                    MidiMessage.noteOn(control.mapping.channel, control.mapping.number, 127)
                } else {
                    MidiMessage.noteOff(control.mapping.channel, control.mapping.number)
                }
                MidiType.CC -> MidiMessage.controlChange(
                    control.mapping.channel,
                    control.mapping.number,
                    nextValue,
                )
                MidiType.PITCH_BEND -> return
            }
            updateValue(id, nextValue)
            transport.send(listOf(message))
            scheduleSave()
            return
        }
        val message = when (control.mapping.type) {
            MidiType.NOTE -> MidiMessage.noteOn(control.mapping.channel, control.mapping.number, 127)
            MidiType.CC -> MidiMessage.controlChange(control.mapping.channel, control.mapping.number, 127)
            MidiType.PITCH_BEND -> return
        }
        updateValue(id, 127)
        transport.send(listOf(message))
    }

    fun buttonUp(id: String) {
        val control = _state.value.controls.find { it.id == id } ?: return
        if (control.kind != ControlKind.BUTTON) return
        if (control.buttonBehavior == ButtonBehavior.TOGGLE) return
        val message = when (control.mapping.type) {
            MidiType.NOTE -> MidiMessage.noteOff(control.mapping.channel, control.mapping.number)
            MidiType.CC -> MidiMessage.controlChange(control.mapping.channel, control.mapping.number, 0)
            MidiType.PITCH_BEND -> return
        }
        updateValue(id, 0)
        transport.send(listOf(message))
        scheduleSave()
    }

    /** 连续控件只更新 UI 和待发值；10 ms 循环负责合并/节流。 */
    fun setContinuousValue(id: String, value: Int) {
        val control = _state.value.controls.find { it.id == id } ?: return
        if (control.kind == ControlKind.BUTTON) return
        if (control.mapping.type == MidiType.NOTE) return
        val clamped = value.coerceIn(0, 16383)
        suppressEcho(id)
        if (control.value == clamped && pendingContinuous.value[id] == clamped) return
        updateValue(id, clamped)
        pendingContinuous.update { it + (id to clamped) }
    }

    /** 拖动结束时强制发送最终值，不等待下一轮节流。 */
    fun flushContinuousValue(id: String) {
        val control = _state.value.controls.find { it.id == id } ?: return
        suppressEcho(id)
        pendingContinuous.update { it - id }
        sendContinuous(control, control.value)
        scheduleSave()
    }

    private fun suppressEcho(id: String) {
        suppressedContinuousIds += id
        echoSuppressionJobs[id]?.cancel()
        echoSuppressionJobs[id] = scope.launch {
            delay(ECHO_SUPPRESSION_MS)
            suppressedContinuousIds -= id
            echoSuppressionJobs.remove(id)
        }
    }

    private fun clearEchoSuppression(id: String? = null) {
        if (id == null) {
            echoSuppressionJobs.values.forEach { it.cancel() }
            echoSuppressionJobs.clear()
            suppressedContinuousIds.clear()
        } else {
            echoSuppressionJobs.remove(id)?.cancel()
            suppressedContinuousIds -= id
        }
    }

    private fun updateValue(id: String, value: Int) {
        _state.update { current ->
            val index = current.controls.indexOfFirst { it.id == id }
            if (index < 0 || current.controls[index].value == value) return@update current
            val updated = current.controls.toMutableList()
            updated[index] = updated[index].copy(value = value)
            current.copy(controls = updated)
        }
    }

    /**
     * 处理 DAW 回传：Note/7-bit CC 更新 Button 或 7-bit 连续控件；14-bit CC
     * 先缓存 MSB，收到 LSB 后合并；Pitch Bend 直接按 LSB/MSB 合并。
     * 这里只改 UI 状态，绝不调用 transport.send，避免回环。
     */
    private fun applyIncoming(messages: List<MidiMessage>) {
        if (messages.isEmpty()) return
        var changed = false
        val controls = _state.value.controls

        for (message in messages) {
            if (!message.isChannelVoice) continue
            val channel = message.channel + 1
            when {
                message.isNoteOn || message.isNoteOff -> {
                    val velocity = if (message.isNoteOn) message.data2 ?: 0 else 0
                    controls.forEach { control ->
                        if (control.kind == ControlKind.BUTTON &&
                            control.mapping.type == MidiType.NOTE &&
                            control.mapping.channel == channel &&
                            control.mapping.number == message.data1
                        ) {
                            changed = setFeedbackValue(control.id, velocity) || changed
                        }
                    }
                }

                message.command == 0xB0 -> {
                    val controller = message.data1
                    val value = message.data2 ?: continue

                    controls.forEach { control ->
                        if (control.kind == ControlKind.BUTTON &&
                            control.mapping.type == MidiType.CC &&
                            control.mapping.resolution == MidiResolution.SEVEN_BIT &&
                            control.mapping.channel == channel &&
                            control.mapping.number == controller
                        ) {
                            changed = setFeedbackValue(control.id, value) || changed
                        }
                    }

                    controls.forEach { control ->
                        if (control.kind == ControlKind.BUTTON ||
                            control.mapping.type != MidiType.CC ||
                            control.mapping.resolution != MidiResolution.SEVEN_BIT ||
                            control.mapping.channel != channel ||
                            control.mapping.number != controller
                        ) {
                            return@forEach
                        }
                        changed = setFeedbackValue(control.id, value shl 7) || changed
                    }

                    controls.forEach { control ->
                        if (control.kind == ControlKind.BUTTON ||
                            control.mapping.type != MidiType.CC ||
                            control.mapping.resolution != MidiResolution.FOURTEEN_BIT ||
                            control.mapping.channel != channel
                        ) {
                            return@forEach
                        }
                        when (controller) {
                            control.mapping.number -> {
                                pendingFourteenBit[channel to controller] = value
                            }

                            control.mapping.number + 32 -> {
                                val high = pendingFourteenBit.remove(channel to control.mapping.number)
                                if (high != null) {
                                    changed = setFeedbackValue(
                                        control.id,
                                        (high shl 7) or value,
                                    ) || changed
                                }
                            }
                        }
                    }
                }

                message.command == 0xE0 -> {
                    val value = ((message.data2 ?: 0) shl 7) or message.data1
                    controls.forEach { control ->
                        if (control.kind != ControlKind.BUTTON &&
                            control.mapping.type == MidiType.PITCH_BEND &&
                            control.mapping.channel == channel
                        ) {
                            changed = setFeedbackValue(control.id, value) || changed
                        }
                    }
                }
            }
        }

        if (changed) scheduleSave()
    }

    private fun setFeedbackValue(id: String, value: Int): Boolean {
        val control = _state.value.controls.find { it.id == id } ?: return false
        if (control.kind != ControlKind.BUTTON && id in suppressedContinuousIds) {
            return false
        }
        val clamped = value.coerceIn(control.kind.valueRange())
        if (control.value == clamped) return false
        updateValue(id, clamped)
        return true
    }

    /**
     * 旧版本允许 14-bit 控件使用任意 CC 编号。迁移到标准的 MSB 0..31，
     * 按原顺序为冲突/越界控件选择同通道内第一个空闲 MSB，避免静默丢失布局。
     */
    private fun migrateLoadedControls(raw: List<Control>): List<Control> {
        val accepted = ArrayList<Control>(raw.size)
        for (control in raw) {
            if (validateControl(control, accepted, control.id) == null) {
                accepted += control
                continue
            }
            val needsStandardMsb = control.kind != ControlKind.BUTTON &&
                control.mapping.type == MidiType.CC &&
                control.mapping.resolution == MidiResolution.FOURTEEN_BIT
            if (!needsStandardMsb) continue

            val remapped = (1..31).toList().plus(0).firstNotNullOfOrNull { msb ->
                control.copy(mapping = control.mapping.copy(number = msb))
                    .takeIf { validateControl(it, accepted, it.id) == null }
            }
            if (remapped != null) accepted += remapped
        }
        return accepted
    }

    private fun flushPendingContinuous() {
        val snapshot = pendingContinuous.value
        if (snapshot.isEmpty()) return
        pendingContinuous.value = emptyMap()
        val byId = _state.value.controls.associateBy { it.id }
        snapshot.forEach { (id, value) ->
            byId[id]?.let { sendContinuous(it, value) }
        }
    }

    private fun sendContinuous(control: Control, value: Int) {
        val channel = control.mapping.channel
        when (control.mapping.type) {
            MidiType.NOTE -> return
            MidiType.PITCH_BEND -> {
                transport.send(listOf(MidiMessage.pitchBend(channel, value.coerceIn(0, 16383))))
                return
            }

            MidiType.CC -> Unit
        }
        val number = control.mapping.number
        if (control.mapping.resolution == MidiResolution.SEVEN_BIT) {
            // DAW 兼容模式：只发一个 7-bit CC。Ableton 等 DAW 的 MIDI Learn
            // 不会把 14-bit 的 LSB 当成独立控制器，避免发送旋钮来回跳。
            transport.send(
                listOf(MidiMessage.controlChange(channel, number, (value shr 7) and 0x7F))
            )
            return
        }
        val msb = (value shr 7) and 0x7F
        val lsb = value and 0x7F
        transport.send(
            listOf(
                MidiMessage.controlChange(channel, number, msb),
                // 标准 14-bit CC：MSB 0..31，LSB 固定为 MSB + 32。
                MidiMessage.controlChange(channel, number + 32, lsb),
            )
        )
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            persist()
        }
    }

    private suspend fun persist() {
        val current = _state.value
        runCatching {
            layoutStore.save(
                PersistedState(
                    layout = Layout(controls = current.controls),
                    deviceAddress = current.lastDeviceAddress,
                    deviceName = current.lastDeviceName,
                )
            )
        }
    }

    private fun newId(): String = "c${Random.nextLong().toULong().toString(16)}"

    companion object {
        private const val CONTINUOUS_INTERVAL_MS = 10L
        private const val ECHO_SUPPRESSION_MS = 200L
        private const val SAVE_DEBOUNCE_MS = 300L
    }
}
