package com.remoteesp32midi.glassmidi.store

import com.remoteesp32midi.glassmidi.ble.BleDevice
import com.remoteesp32midi.glassmidi.ble.BleMidiTransport
import com.remoteesp32midi.glassmidi.ble.TransportEvent
import com.remoteesp32midi.glassmidi.model.Control
import com.remoteesp32midi.glassmidi.model.ControlKind
import com.remoteesp32midi.glassmidi.model.Layout
import com.remoteesp32midi.glassmidi.model.MidiMessage
import com.remoteesp32midi.glassmidi.model.MidiResolution
import com.remoteesp32midi.glassmidi.model.MidiType
import com.remoteesp32midi.glassmidi.model.PersistedState
import com.remoteesp32midi.glassmidi.model.validateControl
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
    fun incomingNoteAndCcUpdateControlsWithoutSending() = runTest {
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
                label = "Mute",
                kind = ControlKind.BUTTON,
                mapping = androidxMidiMapping(MidiType.CC, 1, 20, 7),
            )
        )
        val noteId = store.state.value.controls.first { it.mapping.type == MidiType.NOTE }.id
        val ccId = store.state.value.controls.first { it.mapping.type == MidiType.CC }.id
        val sentBefore = transport.sent.size

        transport.emitIncoming(listOf(MidiMessage.noteOn(1, 60, 99)))
        runCurrent()
        assertEquals(99, store.state.value.controls.first { it.id == noteId }.value)

        transport.emitIncoming(listOf(MidiMessage.noteOff(1, 60)))
        runCurrent()
        assertEquals(0, store.state.value.controls.first { it.id == noteId }.value)

        transport.emitIncoming(listOf(MidiMessage.controlChange(1, 20, 64)))
        runCurrent()
        assertEquals(64, store.state.value.controls.first { it.id == ccId }.value)
        assertEquals(sentBefore, transport.sent.size)
    }

    @Test
    fun incomingFourteenBitPairUpdatesKnobAndIgnoresOrphanLsb() = runTest {
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

        transport.emitIncoming(listOf(MidiMessage.controlChange(1, 33, 127)))
        runCurrent()
        assertEquals(0, store.state.value.controls.single().value)

        transport.emitIncoming(
            listOf(
                MidiMessage.controlChange(1, 1, 64),
                MidiMessage.controlChange(1, 33, 127),
            )
        )
        runCurrent()
        assertEquals(8319, store.state.value.controls.single().value)
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
        assertEquals(33, sent[1].data1)
        assertEquals(40, sent[1].data2) // 9000 & 0x7F

        store.setContinuousValue(id, 100)
        store.flushContinuousValue(id)
        assertEquals(2, transport.sent.last().size)
        assertEquals(0, transport.sent.last()[0].data2)
        assertEquals(100, transport.sent.last()[1].data2)
    }

    @Test
    fun sevenBitContinuousSendsSingleCcAndAcceptsFeedback() = runTest {
        val transport = FakeTransport()
        val store = ControllerStore(transport, InMemoryLayoutStore(), backgroundScope)
        runCurrent()

        store.upsertControl(
            Control(
                id = "",
                label = "Fader 2",
                kind = ControlKind.FADER,
                mapping = androidxMidiMapping(MidiType.CC, 1, 2, 7),
            )
        )
        val id = store.state.value.controls.single().id

        store.setContinuousValue(id, 16383)
        advanceTimeBy(11)
        runCurrent()

        val sent = transport.sent.last()
        assertEquals(1, sent.size)
        assertEquals(0xB0, sent[0].status)
        assertEquals(2, sent[0].data1)
        assertEquals(127, sent[0].data2)
        // 拖动期间及松手后 200ms 回声窗口内忽略旧的 DAW 回传，避免拉扯滑块；
        // 窗口结束后正常接受 DAW 反馈。
        transport.emitIncoming(listOf(MidiMessage.controlChange(1, 2, 32)))
        runCurrent()
        assertEquals(16383, store.state.value.controls.single().value)

        advanceTimeBy(200)
        runCurrent()
        transport.emitIncoming(listOf(MidiMessage.controlChange(1, 2, 64)))
        runCurrent()
        assertEquals(64 shl 7, store.state.value.controls.single().value)
    }

    @Test
    fun pitchBendSendsSingleMessageAndAcceptsFeedback() = runTest {
        val transport = FakeTransport()
        val store = ControllerStore(transport, InMemoryLayoutStore(), backgroundScope)
        runCurrent()

        store.upsertControl(
            Control(
                id = "",
                label = "Fader 2",
                kind = ControlKind.FADER,
                mapping = androidxMidiMapping(MidiType.PITCH_BEND, 1, 0, 14),
            )
        )
        val id = store.state.value.controls.single().id

        store.setContinuousValue(id, 9000)
        advanceTimeBy(11)
        runCurrent()

        val sent = transport.sent.last()
        assertEquals(1, sent.size)
        assertEquals(0xE0, sent[0].status)
        assertEquals(9000 and 0x7F, sent[0].data1)
        assertEquals(9000 shr 7, sent[0].data2)
        advanceTimeBy(200)
        runCurrent()
        transport.emitIncoming(listOf(MidiMessage(0xE0, 4096 and 0x7F, 4096 shr 7)))
        runCurrent()
        assertEquals(4096, store.state.value.controls.single().value)
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
    fun moveControlTakesTargetSlotAndPersists() = runTest {
        val transport = FakeTransport()
        val layoutStore = InMemoryLayoutStore()
        val store = ControllerStore(transport, layoutStore, backgroundScope)
        runCurrent()

        repeat(3) { index ->
            assertEquals(
                null,
                store.upsertControl(
                    Control(
                        id = "",
                        label = "Pad $index",
                        kind = ControlKind.BUTTON,
                        mapping = androidxMidiMapping(MidiType.NOTE, 1, 60 + index, 7),
                    )
                )
            )
        }
        val controls = store.state.value.controls
        val first = controls[0]
        val second = controls[1]
        val third = controls[2]

        store.moveControl(third.id, first.id)
        assertEquals(
            listOf(third.label, first.label, second.label),
            store.state.value.controls.map { it.label },
        )

        advanceTimeBy(301)
        runCurrent()
        assertEquals(
            listOf(third.label, first.label, second.label),
            layoutStore.saved?.layout?.controls?.map { it.label },
        )
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
    fun legacyFourteenBitMappingIsMigratedToStandardMsb() = runTest {
        val legacy = Control(
            id = "legacy",
            label = "Old Cutoff",
            kind = ControlKind.KNOB,
            mapping = androidxMidiMapping(MidiType.CC, 1, 60, 14),
        )
        val store = ControllerStore(
            FakeTransport(),
            InMemoryLayoutStore(PersistedState(layout = Layout(controls = listOf(legacy)))),
            backgroundScope,
        )
        runCurrent()

        val migrated = store.state.value.controls.single()
        assertEquals("Old Cutoff", migrated.label)
        assertTrue(migrated.mapping.number in 0..31)
        assertEquals(null, validateControl(migrated, emptyList(), migrated.id))
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

    @Test
    fun channelStripsAreInitializedAndCanAddMoveAndDelete() = runTest {
        val transport = FakeTransport()
        val store = ControllerStore(transport, InMemoryLayoutStore(), backgroundScope)
        runCurrent()

        store.ensureDefaultStrips()
        val initialControls = store.state.value.controls
        assertEquals(20, initialControls.size)
        initialControls.forEach { control ->
            assertEquals(null, validateControl(control, initialControls, control.id))
        }
        assertEquals(1, initialControls[0].mapping.channel)
        assertEquals(MidiType.PITCH_BEND, initialControls[0].mapping.type)
        assertEquals(MidiResolution.SEVEN_BIT, initialControls[1].mapping.resolution)
        assertEquals(1, initialControls[1].mapping.number)
        assertEquals(2, initialControls[5].mapping.channel)
        assertEquals(MidiType.PITCH_BEND, initialControls[5].mapping.type)

        val newIndex = store.addChannelStrip()
        assertEquals(4, newIndex)
        assertEquals(25, store.state.value.controls.size)
        assertEquals(5, store.state.value.controls[20].mapping.channel)

        val firstFaderId = store.state.value.controls[0].id
        val secondFaderId = store.state.value.controls[5].id
        store.moveChannelStrip(0, 1)
        assertEquals(secondFaderId, store.state.value.controls[0].id)
        assertEquals(firstFaderId, store.state.value.controls[5].id)
        // 移动通道条后，位置 0 始终保持预设的 MIDI Channel 1，位置 1 保持 MIDI Channel 2
        assertEquals(1, store.state.value.controls[0].mapping.channel)
        assertEquals(2, store.state.value.controls[5].mapping.channel)

        store.deleteChannelStrip(4)
        assertEquals(20, store.state.value.controls.size)
    }

    @Test
    fun devicePortHandshakeIsSentOnReadyAndWhenSwitched() = runTest {
        val transport = FakeTransport()
        val layoutStore = InMemoryLayoutStore(PersistedState(devicePort = 1))
        val store = ControllerStore(transport, layoutStore, backgroundScope)
        runCurrent()
        assertEquals(1, store.state.value.devicePort)

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
        assertEquals(listOf(MidiMessage.portSelect(1)), transport.sent.first())

        store.setDevicePort(0)
        assertEquals(0, store.state.value.devicePort)
        assertEquals(listOf(MidiMessage.portSelect(0)), transport.sent.last())
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

    suspend fun emitIncoming(messages: List<MidiMessage>) {
        incoming.emit(messages)
    }
}

private class InMemoryLayoutStore(
    private val initial: PersistedState = PersistedState(),
) : LayoutStore {
    var saved: PersistedState? = null

    override suspend fun load(): PersistedState = initial

    override suspend fun save(state: PersistedState) {
        saved = state
    }
}
