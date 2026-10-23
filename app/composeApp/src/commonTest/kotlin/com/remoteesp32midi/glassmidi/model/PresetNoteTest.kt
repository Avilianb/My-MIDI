package com.remoteesp32midi.glassmidi.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PresetNoteTest {
    /** 造一条 6 控件的通道条。 */
    private fun stripControls(
        strip: Int,
        faderCc: Int,
        knobOffset: Int,
        note: (Int, Int) -> Int,
        buttonCc: (Int, Int) -> Int,
        buttonType: MidiType = MidiType.NOTE,
    ): List<Control> {
        val channel = strip % 16 + 1
        return (0 until CONTROLS_PER_STRIP).map { slot ->
            val kind = when (slot) {
                0 -> ControlKind.FADER
                1, 2, 3 -> ControlKind.KNOB
                else -> ControlKind.BUTTON
            }
            val mapping = when (slot) {
                0 -> MidiMapping(MidiType.CC, channel, faderCc, MidiResolution.SEVEN_BIT)
                1, 2, 3 -> MidiMapping(
                    MidiType.CC,
                    channel,
                    knobOffset + slot,
                    MidiResolution.SEVEN_BIT,
                )
                else -> if (buttonType == MidiType.CC) {
                    MidiMapping(
                        MidiType.CC,
                        channel,
                        if (slot == 4) buttonCc(strip, 0) else buttonCc(strip, 1),
                        MidiResolution.SEVEN_BIT,
                    )
                } else {
                    MidiMapping(
                        MidiType.NOTE,
                        channel,
                        if (slot == 4) note(strip, 0) else note(strip, 1),
                        MidiResolution.SEVEN_BIT,
                    )
                }
            }
            Control(id = "c${strip}_$slot", label = "c${strip}_$slot", kind = kind, mapping = mapping)
        }
    }

    @Test
    fun presetNotesStepPerStripFromC1() {
        assertEquals(36, presetStripNoteNumber(0))
        assertEquals(38, presetStripNoteNumber(1))
        assertEquals(42, presetStripNoteNumber(3))
        assertEquals(37, presetStripNoteNumber(0, 1))
        assertEquals(43, presetStripNoteNumber(3, 1))
        assertEquals(127, presetStripNoteNumber(200))
    }

    @Test
    fun presetMappingKeepsUserNoteNumber() {
        val custom = MidiMapping(MidiType.NOTE, 1, 72, MidiResolution.SEVEN_BIT)
        assertEquals(72, presetStripMapping(0, 4, custom).number)
        assertEquals(72, presetStripMapping(0, 5, custom).number)
        assertEquals(36, presetStripMapping(0, 4).number)
        assertEquals(37, presetStripMapping(0, 5).number)
    }

    @Test
    fun presetMappingKeepsUserCcNumber() {
        val custom = MidiMapping(MidiType.CC, 1, 22, MidiResolution.SEVEN_BIT)
        assertEquals(22, presetStripMapping(0, 4, custom).number)
        assertEquals(22, presetStripMapping(0, 1, custom).number)
        assertEquals(36, presetStripMapping(0, 4).number)
    }

    @Test
    fun presetButtonCcStepsPerStripFrom80() {
        // 每条通道条两个按钮各占一个 CC。
        assertEquals(80, presetStripCcNumber(0, 4))
        assertEquals(81, presetStripCcNumber(0, 5))
        assertEquals(82, presetStripCcNumber(1, 4))
        assertEquals(83, presetStripCcNumber(1, 5))
        assertEquals(112, presetStripCcNumber(16, 4))
        // 旋钮仍按通道条共享 CC 1/2/3;推子是 CC 7(音量),不再是 CC 0(Bank Select)。
        assertEquals(1, presetStripCcNumber(0, 1))
        assertEquals(3, presetStripCcNumber(3, 3))
        assertEquals(7, presetStripCcNumber(0, 0))
        assertEquals(7, presetStripCcNumber(3, 0))
        assertEquals(15, presetStripCcNumber(16, 0))
        assertEquals(9, presetStripCcNumber(16, 1))
    }

    @Test
    fun presetCcNumbersNeverCollideOnTheSameChannel() {
        // 24 条通道条(2 个 bank，144 个控件)内保证 (通道, CC) 唯一。
        val seen = mutableMapOf<Pair<Int, Int>, String>()
        for (strip in 0 until 24) {
            for (slot in 0 until CONTROLS_PER_STRIP) {
                val key = (strip % 16 + 1) to presetStripCcNumber(strip, slot)
                val label = "strip$strip/slot$slot"
                val previous = seen.put(key, label)
                assertEquals(null, previous, "ch${key.first} CC${key.second}: $label vs $previous")
            }
        }
    }

    @Test
    fun legacyNotesMigratePerStripAndCustomNotesStayUntouched() {
        // 旧版(每条只有一个按钮、Note 60)迁移后扩成两个按钮。
        val controls = (0 until 4).flatMap { strip ->
            stripControls(
                strip = strip,
                faderCc = 0,
                knobOffset = strip / 16 * 4,
                note = { s, i -> if (i == 0) legacyPresetStripNoteNumber(s) else presetStripNoteNumber(s, 1) },
                buttonCc = { s, i -> 80 + s * 2 + i },
            )
        }.toMutableList()

        val migrated = migrateLegacyAutoMappings(controls)
        assertEquals(
            listOf(36, 37, 38, 39, 40, 41, 42, 43),
            migrated.filter { it.kind == ControlKind.BUTTON }.map { it.mapping.number },
        )

        // 手改过的编号保持不动。
        val custom = controls.toMutableList().also { list ->
            list[4] = list[4].copy(mapping = list[4].mapping.copy(number = 72))
        }
        val kept = migrateLegacyAutoMappings(custom)
        assertEquals(72, kept[4].mapping.number)
        assertEquals(38, kept[10].mapping.number)

        // 没有旧编号需要迁移时返回原列表实例，调用方可以据此跳过保存。
        assertSame(kept, migrateLegacyAutoMappings(kept))
    }

    @Test
    fun legacyButtonCcMigratesToPerStripCc() {
        val controls = (0 until 2).flatMap { strip ->
            stripControls(
                strip = strip,
                faderCc = 0,
                knobOffset = 0,
                note = { s, i -> presetStripNoteNumber(s, i) },
                buttonCc = { s, i -> if (i == 0) 64 else 80 + s * 2 + 1 },
                buttonType = MidiType.CC,
            )
        }

        val migrated = migrateLegacyAutoMappings(controls)
        assertEquals(
            listOf(80, 81, 82, 83),
            migrated.filter { it.kind == ControlKind.BUTTON }.map { it.mapping.number },
        )

        val custom = controls.mapIndexed { index, control ->
            if (index == 4) control.copy(mapping = control.mapping.copy(number = 22)) else control
        }
        val kept = migrateLegacyAutoMappings(custom)
        assertEquals(22, kept[4].mapping.number)
        assertEquals(82, kept[10].mapping.number)
    }

    @Test
    fun legacyFaderCcMigratesToVolumeAndCustomStays() {
        val controls = (0 until 2).flatMap { strip ->
            stripControls(
                strip = strip,
                faderCc = 0,
                knobOffset = 0,
                note = { s, i -> presetStripNoteNumber(s, i) },
                buttonCc = { s, i -> 80 + s * 2 + i },
            )
        }

        val migrated = migrateLegacyAutoMappings(controls)
        assertEquals(7, migrated[0].mapping.number)
        assertEquals(7, migrated[6].mapping.number)

        val custom = controls.mapIndexed { index, control ->
            if (index == 6) control.copy(mapping = control.mapping.copy(number = 22)) else control
        }
        val kept = migrateLegacyAutoMappings(custom)
        assertEquals(7, kept[0].mapping.number)
        assertEquals(22, kept[6].mapping.number)
    }

    @Test
    fun higherBanksMigrateToWiderCcStride() {
        // 34 条通道条，末尾两条落在 bank 2。
        val controls = (0 until 34).flatMap { strip ->
            val bank = strip / 16
            stripControls(
                strip = strip,
                faderCc = bank * 4,
                knobOffset = bank * 4,
                note = { s, i -> presetStripNoteNumber(s, i) },
                buttonCc = { s, i -> 80 + s * 2 + i },
            )
        }

        val migrated = migrateLegacyAutoMappings(controls)
        // strip 32 (bank 2) 起始于下标 192:推子 8 -> 23，K1/K2/K3 9/10/11 -> 17/18/19。
        assertEquals(23, migrated[192].mapping.number)
        assertEquals(17, migrated[193].mapping.number)
        assertEquals(19, migrated[195].mapping.number)
        // bank 0 的旋钮保持 1/2/3 不变。
        assertEquals(1, migrated[1].mapping.number)
    }
}
