package com.remoteesp32midi.glassmidi.ble

import com.remoteesp32midi.glassmidi.model.MidiMessage
import kotlinx.coroutines.flow.Flow

data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
)

enum class ConnectionPhase {
    IDLE,
    SCANNING,
    CONNECTING,
    READY,
    RECONNECTING,
    ERROR,
}

data class BleConnection(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val detail: String? = null,
)

sealed interface TransportEvent {
    data class Devices(val devices: List<BleDevice>) : TransportEvent

    data class ConnectionChanged(val connection: BleConnection) : TransportEvent

    data class Error(val message: String) : TransportEvent
}

interface MidiSender {
    fun send(messages: List<MidiMessage>)
}

interface BleMidiTransport : MidiSender {
    val events: Flow<TransportEvent>

    /** 电脑 → ESP32 → 手机方向的 MIDI；阶段 5 用来更新控件。 */
    val incoming: Flow<List<MidiMessage>>

    fun startScan()

    fun stopScan()

    fun connect(address: String)

    fun disconnect()
}
