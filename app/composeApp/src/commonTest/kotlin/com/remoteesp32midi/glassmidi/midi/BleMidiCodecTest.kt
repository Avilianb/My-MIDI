package com.remoteesp32midi.glassmidi.midi

import com.remoteesp32midi.glassmidi.model.MidiMessage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BleMidiCodecTest {
    private val codec = BleMidiCodec()

    @Test
    fun noteOnUsesStandardBleMidiHeaderAndTimestamp() {
        val packet = codec.encode(
            listOf(MidiMessage.noteOn(channel = 1, note = 60, velocity = 100)),
            timestampMs = 0,
        )
        assertContentEquals(
            byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x90.toByte(), 0x3C, 0x64),
            packet,
        )
    }

    @Test
    fun fourteenBitCcPairLivesInOnePacket() {
        val packet = codec.encode(
            listOf(
                MidiMessage.controlChange(channel = 1, controller = 1, value = 64),
                MidiMessage.controlChange(channel = 1, controller = 2, value = 0),
            ),
            timestampMs = 0,
        )
        assertContentEquals(
            byteArrayOf(
                0x80.toByte(), 0x80.toByte(), 0xB0.toByte(), 0x01, 0x40,
                0x80.toByte(), 0xB0.toByte(), 0x02, 0x00,
            ),
            packet,
        )
    }

    @Test
    fun timestampHighAndLowBitsAreSplitAcrossHeaderAndMessage() {
        val packet = codec.encode(
            listOf(MidiMessage.controlChange(channel = 4, controller = 7, value = 99)),
            timestampMs = 0x1ABC,
        )
        // 0x1ABC = 0b1_1010_1011_1100: header gets 0b110101, low byte gets 0b0111100.
        assertEquals(0x80 or 0x35, packet[0].toInt() and 0xFF)
        assertEquals(0x80 or 0x3C, packet[1].toInt() and 0xFF)
        assertEquals(0xB3, packet[2].toInt() and 0xFF)
    }

    @Test
    fun decodeSupportsMultipleMessagesAndRunningStatus() {
        val decoded = codec.decode(
            byteArrayOf(
                0x80.toByte(), 0x80.toByte(), 0x90.toByte(), 0x3C, 0x64,
                0x80.toByte(), 0x3C, 0x00,
            )
        )
        assertEquals(2, decoded.size)
        assertTrue(decoded[0].isNoteOn)
        assertTrue(decoded[1].isNoteOff)
        assertEquals(60, decoded[1].data1)
    }

    @Test
    fun decodeSupportsPortHandshakeSystemCommonMessage() {
        val encoded = codec.encode(
            listOf(MidiMessage.portSelect(1, force = true)),
            timestampMs = 123,
        )
        val decoded = codec.decode(encoded).single()
        assertTrue(decoded.isPortHandshake)
        assertEquals(1, decoded.handshakePortIndex)
    }

    @Test
    fun clockInsideNotificationDoesNotDiscardControllerFeedback() {
        val packet = byteArrayOf(0x80.toByte(), 0x80.toByte(), 0xF8.toByte(),
            0x80.toByte(), 0xB0.toByte(), 7, 100,
            0x80.toByte(), 0xFE.toByte(), 0x80.toByte(), 7, 0)
        assertEquals(listOf(MidiMessage.controlChange(1, 7, 100),
            MidiMessage.controlChange(1, 7, 0)), codec.decode(packet))
    }

    @Test
    fun malformedDataByteIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            codec.decode(byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x90.toByte(), 0x3C))
        }
        assertFailsWith<IllegalArgumentException> {
            codec.decode(byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x31, 0x3C, 0x64))
        }
    }
}
