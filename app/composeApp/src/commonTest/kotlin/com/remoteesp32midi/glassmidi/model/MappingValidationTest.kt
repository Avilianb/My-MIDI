package com.remoteesp32midi.glassmidi.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class MappingValidationTest {
    private fun control(
        id: String,
        kind: ControlKind,
        type: MidiType,
        channel: Int,
        number: Int,
        resolution: MidiResolution,
    ) = Control(
        id = id,
        label = id,
        kind = kind,
        mapping = MidiMapping(type, channel, number, resolution),
    )

    @Test
    fun buttonAcceptsNoteOrSevenBitCc() {
        val note = control("n", ControlKind.BUTTON, MidiType.NOTE, 1, 60, MidiResolution.SEVEN_BIT)
        val cc = control("c", ControlKind.BUTTON, MidiType.CC, 1, 20, MidiResolution.SEVEN_BIT)
        assertNull(validateControl(note, emptyList()))
        assertNull(validateControl(cc, emptyList()))
    }

    @Test
    fun knobAndFaderOnlyAcceptFourteenBitCc() {
        val note = control("n", ControlKind.KNOB, MidiType.NOTE, 1, 60, MidiResolution.SEVEN_BIT)
        val sevenBit = control("s", ControlKind.FADER, MidiType.CC, 1, 1, MidiResolution.SEVEN_BIT)
        val fourteenBit = control("f", ControlKind.FADER, MidiType.CC, 1, 1, MidiResolution.FOURTEEN_BIT)
        assertNotNull(validateControl(note, emptyList()))
        assertNotNull(validateControl(sevenBit, emptyList()))
        assertNull(validateControl(fourteenBit, emptyList()))
    }

    @Test
    fun fourteenBitNumberMustLeaveRoomForLsb() {
        val invalid = control("k", ControlKind.KNOB, MidiType.CC, 1, 127, MidiResolution.FOURTEEN_BIT)
        assertNotNull(validateControl(invalid, emptyList()))
    }

    @Test
    fun fourteenBitPairCannotOverlapSevenBitCc() {
        val existing = control("c", ControlKind.BUTTON, MidiType.CC, 1, 2, MidiResolution.SEVEN_BIT)
        val candidate = control("k", ControlKind.KNOB, MidiType.CC, 1, 1, MidiResolution.FOURTEEN_BIT)
        val error = validateControl(candidate, listOf(existing))
        assertNotNull(error)
        assertEquals(true, error.contains("CC 2"))
    }

    @Test
    fun twoFourteenBitPairsCannotShareNumbers() {
        val existing = control("k1", ControlKind.KNOB, MidiType.CC, 1, 1, MidiResolution.FOURTEEN_BIT)
        val candidate = control("k2", ControlKind.KNOB, MidiType.CC, 1, 2, MidiResolution.FOURTEEN_BIT)
        assertNotNull(validateControl(candidate, listOf(existing)))
    }

    @Test
    fun sevenBitCcsMayShareTheSameNumber() {
        val existing = control("c1", ControlKind.BUTTON, MidiType.CC, 1, 20, MidiResolution.SEVEN_BIT)
        val candidate = control("c2", ControlKind.BUTTON, MidiType.CC, 1, 20, MidiResolution.SEVEN_BIT)
        assertNull(validateControl(candidate, listOf(existing)))
    }

    @Test
    fun defaultLabelsAvoidDuplicatesPerKind() {
        val controls = listOf(
            control("b1", ControlKind.BUTTON, MidiType.NOTE, 1, 60, MidiResolution.SEVEN_BIT)
                .copy(label = "Button 1"),
            control("b2", ControlKind.BUTTON, MidiType.NOTE, 1, 61, MidiResolution.SEVEN_BIT)
                .copy(label = "Button 2"),
            control("k1", ControlKind.KNOB, MidiType.CC, 1, 1, MidiResolution.FOURTEEN_BIT)
                .copy(label = "Knob 1"),
        )
        assertEquals(
            "Button 3",
            Control(
                id = "",
                label = "",
                kind = ControlKind.BUTTON,
                mapping = MidiMapping(MidiType.NOTE, 1, 62, MidiResolution.SEVEN_BIT),
            ).withDefaultLabel(controls).label,
        )
        assertEquals(
            "Knob 2",
            Control(
                id = "",
                label = "",
                kind = ControlKind.KNOB,
                mapping = MidiMapping(MidiType.CC, 1, 2, MidiResolution.FOURTEEN_BIT),
            ).withDefaultLabel(controls).label,
        )
    }

    @Test
    fun sameNumberOnDifferentChannelsIsAllowed() {
        val existing = control("k1", ControlKind.KNOB, MidiType.CC, 1, 1, MidiResolution.FOURTEEN_BIT)
        val candidate = control("k2", ControlKind.KNOB, MidiType.CC, 2, 1, MidiResolution.FOURTEEN_BIT)
        assertNull(validateControl(candidate, listOf(existing)))
    }
}
