package com.remoteesp32midi.glassmidi.store

import com.remoteesp32midi.glassmidi.ble.BleConnection
import com.remoteesp32midi.glassmidi.ble.BleDevice
import com.remoteesp32midi.glassmidi.ble.BleMidiTransport
import com.remoteesp32midi.glassmidi.ble.ConnectionPhase
import com.remoteesp32midi.glassmidi.ble.TransportEvent
import com.remoteesp32midi.glassmidi.model.Control
import com.remoteesp32midi.glassmidi.model.ControlKind
import com.remoteesp32midi.glassmidi.model.Layout
import com.remoteesp32midi.glassmidi.model.MidiMessage
import com.remoteesp32midi.glassmidi.model.MidiResolution
import com.remoteesp32midi.glassmidi.model.MidiType
import com.remoteesp32midi.glassmidi.model.PersistedState
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
    private var saveJob: Job? = null

    init {
        scope.launch {
            val persisted = runCatching { layoutStore.load() }.getOrElse {
                PersistedState()
            }
            val controls = persisted.layout.controls.filter { validateControl(it, emptyList(), it.id) == null }
            _state.update {
                it.copy(
                    controls = controls,
                    lastDeviceAddress = persisted.deviceAddress,
                    lastDeviceName = persisted.deviceName,
                    loaded = true,
                )
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
        scheduleSave()
    }

    fun buttonDown(id: String) {
        val control = _state.value.controls.find { it.id == id } ?: return
        if (control.kind != ControlKind.BUTTON) return
        val message = when (control.mapping.type) {
            MidiType.NOTE -> MidiMessage.noteOn(control.mapping.channel, control.mapping.number, 127)
            MidiType.CC -> MidiMessage.controlChange(control.mapping.channel, control.mapping.number, 127)
        }
        updateValue(id, 127)
        transport.send(listOf(message))
    }

    fun buttonUp(id: String) {
        val control = _state.value.controls.find { it.id == id } ?: return
        if (control.kind != ControlKind.BUTTON) return
        val message = when (control.mapping.type) {
            MidiType.NOTE -> MidiMessage.noteOff(control.mapping.channel, control.mapping.number)
            MidiType.CC -> MidiMessage.controlChange(control.mapping.channel, control.mapping.number, 0)
        }
        updateValue(id, 0)
        transport.send(listOf(message))
        scheduleSave()
    }

    /** 连续控件只更新 UI 和待发值；10 ms 循环负责合并/节流。 */
    fun setContinuousValue(id: String, value: Int) {
        val control = _state.value.controls.find { it.id == id } ?: return
        if (control.kind == ControlKind.BUTTON) return
        if (control.mapping.type != MidiType.CC ||
            control.mapping.resolution != MidiResolution.FOURTEEN_BIT
        ) {
            return
        }
        val clamped = value.coerceIn(0, 16383)
        updateValue(id, clamped)
        pendingContinuous.update { it + (id to clamped) }
    }

    /** 拖动结束时强制发送最终值，不等待下一轮节流。 */
    fun flushContinuousValue(id: String) {
        val control = _state.value.controls.find { it.id == id } ?: return
        pendingContinuous.update { it - id }
        sendContinuous(control, control.value)
        scheduleSave()
    }

    private fun updateValue(id: String, value: Int) {
        _state.update { current ->
            current.copy(
                controls = current.controls.map {
                    if (it.id == id) it.copy(value = value) else it
                }
            )
        }
    }

    /**
     * 处理 DAW 回传：Note/7-bit CC 直接更新 Button；14-bit CC 先缓存 MSB，
     * 收到 LSB 后合并。这里只改 UI 状态，绝不调用 transport.send，避免回环。
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
                            control.mapping.resolution != MidiResolution.FOURTEEN_BIT ||
                            control.mapping.channel != channel
                        ) {
                            return@forEach
                        }
                        when (controller) {
                            control.mapping.number -> {
                                pendingFourteenBit[channel to controller] = value
                                // 先用 MSB 粗调，LSB 到达后再补齐低 7 位。
                                changed = setFeedbackValue(control.id, value shl 7) || changed
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
            }
        }

        if (changed) scheduleSave()
    }

    private fun setFeedbackValue(id: String, value: Int): Boolean {
        val control = _state.value.controls.find { it.id == id } ?: return false
        val clamped = value.coerceIn(control.kind.valueRange())
        if (control.value == clamped) return false
        updateValue(id, clamped)
        return true
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
        if (control.mapping.type != MidiType.CC ||
            control.mapping.resolution != MidiResolution.FOURTEEN_BIT
        ) {
            return
        }
        val msb = (value shr 7) and 0x7F
        val lsb = value and 0x7F
        val channel = control.mapping.channel
        val number = control.mapping.number
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
        private const val SAVE_DEBOUNCE_MS = 300L
    }
}
