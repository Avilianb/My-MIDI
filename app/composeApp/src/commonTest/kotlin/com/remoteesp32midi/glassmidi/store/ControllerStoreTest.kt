package com.remoteesp32midi.glassmidi.store

import com.remoteesp32midi.glassmidi.ble.BleDevice
import com.remoteesp32midi.glassmidi.ble.BleMidiTransport
import com.remoteesp32midi.glassmidi.ble.TransportEvent
import com.remoteesp32midi.glassmidi.model.Control
import com.remoteesp32midi.glassmidi.model.ControlKind
import com.remoteesp32midi.glassmidi.model.MidiMessage
import com.remoteesp32midi.glassmidi.model.MidiResolution
import com.remoteesp32midi.glassmidi.model.MidiType
import com.remoteesp32midi.glassmidi.model.PersistedState
import com.remoteesp32midi.glassmidi.storage.LayoutStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ControllerStoreTest {
    @Test
    fun buttonNoteSendsNoteOnAndNoteOff() = runTest {
        val transport = FakeTransport()
        val store = ControllerStore(transport, InMemoryLayoutStore(), backgroundScope)
        runCurrent()

        val error = store.upsertControl(
            Control(
                id = "",
                label = "Pad",
                kind = ControlKind.BUTTON,
                mapping = androidxMidiMapping(MidiType.NOTE, 1, 60, 7),
            )
        )
        assertEquals(null, error)
        val id = store.state.value.controls.single().id

        store.buttonDown(id)
        assertEquals(0x90, transport.sent.last().single().status)
        assertEquals(127, transport.sent.last().single().data2)
        assertEquals(127, store.state.value.controls.single().value)

        store.buttonUp(id)
        assertEquals(0x80, transport.sent.last().single().status)
        assertEquals(0, store.state.value.controls.single().value)
    }

    @Test
    fun buttonCcSends127OnAndZeroOff() = runTest {
        val transport = FakeTransport()
        val store = ControllerStore(transport, InMemoryLayoutStore(), backgroundScope)
        runCurrent()

        store.upsertControl(
            Control(
                id = "",
                label = "Mute",
                kind = ControlKind.BUTTON,
                mapping = androidxMidiMapping(MidiType.CC, 2, 7, 7),
            )
        )
        val id = store.state.value.controls.single().id

        store.buttonDown(id)
        assertEquals(0xB1, transport.sent.last().single().status)
        assertEquals(7, transport.sent.last().single().data1)
        assertEquals(127, transport.sent.last().single().data2)

        store.buttonUp(id)
        assertEquals(0xB1, transport.sent.last().single().status)
        assertEquals(0, transport.sent.last().single().data2)
    }

    @Test
    fun continuousControlIsThrottledThenFlushesFinalValue() = runTest {
        val transport = FakeTransport()
        val store = ControllerStore(transport, InMemoryLayoutStore(), backgroundScope)
        runCurrent()

        store.upsertControl(
            Control(
                id = "",
                label = "Cutoff",
                kind = ControlKind.KNOB,
                mapping = androidxMidiMapping(MidiType.CC, 1, 1, 14),
            )
        )
        val id = store.state.value.controls.single().id

        store.setContinuousValue(id, 8192)
        store.setContinuousValue(id, 9000)
        advanceTimeBy(11)
        runCurrent()

        val sent = transport.sent.last()
        assertEquals(2, sent.size)
        assertEquals(0xB0, sent[0].status)
        assertEquals(1, sent[0].data1)
        assertEquals(70, sent[0].data2) // 9000 >> 7
        assertEquals(2, sent[1].data1)
        assertEquals(40, sent[1].data2) // 9000 & 0x7F

        store.setContinuousValue(id, 100)
        store.flushContinuousValue(id)
        assertEquals(2, transport.sent.last().size)
        assertEquals(0, transport.sent.last()[0].data2)
        assertEquals(100, transport.sent.last()[1].data2)
    }

    @Test
    fun invalidMappingIsRejectedAndValidLayoutIsSaved() = runTest {
        val transport = FakeTransport()
        val layoutStore = InMemoryLayoutStore()
        val store = ControllerStore(transport, layoutStore, backgroundScope)
        runCurrent()

        val invalid = Control(
            id = "",
            label = "Bad",
            kind = ControlKind.KNOB,
            mapping = androidxMidiMapping(MidiType.NOTE, 1, 60, 7),
        )
        assertNotNull(store.upsertControl(invalid))
        assertTrue(store.state.value.controls.isEmpty())

        val valid = Control(
            id = "",
            label = "Good",
            kind = ControlKind.FADER,
            mapping = androidxMidiMapping(MidiType.CC, 2, 10, 14),
        )
        assertEquals(null, store.upsertControl(valid))
        advanceTimeBy(301)
        runCurrent()

        val saved = layoutStore.saved
        assertNotNull(saved)
        assertEquals(1, saved.layout.controls.size)
        assertEquals("Good", saved.layout.controls.single().label)
    }

    @Test
    fun reconnectClearsButtonStateAndResendsContinuousValues() = runTest {
        val transport = FakeTransport()
        val store = ControllerStore(transport, InMemoryLayoutStore(), backgroundScope)
        runCurrent()

        store.upsertControl(
            Control(
                id = "",
                label = "Pad",
                kind = ControlKind.BUTTON,
                mapping = androidxMidiMapping(MidiType.NOTE, 1, 60, 7),
            )
        )
        store.upsertControl(
            Control(
                id = "",
                label = "Cutoff",
                kind = ControlKind.KNOB,
                mapping = androidxMidiMapping(MidiType.CC, 1, 1, 14),
            )
        )
        val buttonId = store.state.value.controls.first { it.kind == ControlKind.BUTTON }.id
        val knob = store.state.value.controls.first { it.kind == ControlKind.KNOB }
        store.buttonDown(buttonId)
        store.setContinuousValue(knob.id, 8192)
        advanceTimeBy(11)
        runCurrent()
        assertEquals(127, store.state.value.controls.first { it.id == buttonId }.value)

        transport.emit(
            TransportEvent.ConnectionChanged(
                com.remoteesp32midi.glassmidi.ble.BleConnection(
                    phase = com.remoteesp32midi.glassmidi.ble.ConnectionPhase.RECONNECTING,
                )
            )
        )
        runCurrent()
        assertEquals(0, store.state.value.controls.first { it.id == buttonId }.value)

        transport.emit(
            TransportEvent.ConnectionChanged(
                com.remoteesp32midi.glassmidi.ble.BleConnection(
                    phase = com.remoteesp32midi.glassmidi.ble.ConnectionPhase.READY,
                    deviceAddress = "AA:BB",
                )
            )
        )
        runCurrent()
        assertTrue(transport.sent.last().size == 2)
        assertEquals(64, transport.sent.last()[0].data2)
        assertEquals(0, transport.sent.last()[1].data2)
    }

    @Test
    fun transportEventsUpdateConnectionAndDevices() = runTest {
        val transport = FakeTransport()
        val store = ControllerStore(transport, InMemoryLayoutStore(), backgroundScope)
        runCurrent()

        transport.emit(
            TransportEvent.Devices(
                listOf(BleDevice("ESP32-S3 MIDI", "AA:BB", -40))
            )
        )
        runCurrent()
        assertEquals(1, store.state.value.devices.size)

        transport.emit(
            TransportEvent.ConnectionChanged(
                com.remoteesp32midi.glassmidi.ble.BleConnection(
                    phase = com.remoteesp32midi.glassmidi.ble.ConnectionPhase.READY,
                    deviceName = "ESP32-S3 MIDI",
                    deviceAddress = "AA:BB",
                )
            )
        )
        runCurrent()
        assertEquals(com.remoteesp32midi.glassmidi.ble.ConnectionPhase.READY, store.state.value.connection.phase)
        assertEquals("AA:BB", store.state.value.lastDeviceAddress)
    }

    private fun androidxMidiMapping(
        type: MidiType,
        channel: Int,
        number: Int,
        bits: Int,
    ) = com.remoteesp32midi.glassmidi.model.MidiMapping(
        type = type,
        channel = channel,
        number = number,
        resolution = if (bits == 14) MidiResolution.FOURTEEN_BIT else MidiResolution.SEVEN_BIT,
    )
}

private class FakeTransport : BleMidiTransport {
    override val events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 32)
    override val incoming = MutableSharedFlow<List<MidiMessage>>(extraBufferCapacity = 32)
    val sent = mutableListOf<List<MidiMessage>>()

    override fun send(messages: List<MidiMessage>) {
        sent += messages
    }

    override fun startScan() = Unit
    override fun stopScan() = Unit
    override fun connect(address: String) = Unit
    override fun disconnect() = Unit

    suspend fun emit(event: TransportEvent) {
        events.emit(event)
    }
}

private class InMemoryLayoutStore : LayoutStore {
    var saved: PersistedState? = null

    override suspend fun load(): PersistedState = PersistedState()

    override suspend fun save(state: PersistedState) {
        saved = state
    }
}
