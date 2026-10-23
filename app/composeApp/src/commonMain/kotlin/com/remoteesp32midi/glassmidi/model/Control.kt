package com.remoteesp32midi.glassmidi.model

enum class ControlKind(val displayName: String) {
    BUTTON("Button"),
    KNOB("Knob"),
    FADER("Fader"),
}

enum class MidiType(val displayName: String) {
    NOTE("Note"),
    CC("CC"),
    PITCH_BEND("Pitch Bend"),
}

enum class MidiResolution {
    SEVEN_BIT,
    FOURTEEN_BIT,
}

enum class ButtonBehavior(val displayName: String) {
    MOMENTARY("按住触发"),
    TOGGLE("点击开关"),
}

const val CURRENT_SCHEMA_VERSION = 2

/**
 * channel 是界面使用的 1..16;number 是 Note 编号或 CC 的 MSB 编号。
 * 标准 14-bit CC 占用 number(MSB)和 number + 32(LSB)两个编号。
 * Pitch Bend 不使用 number,固定为单条 14-bit 消息。
 */
data class MidiMapping(
    val type: MidiType,
    val channel: Int,
    val number: Int,
    val resolution: MidiResolution,
)

data class Control(
    val id: String,
    val label: String,
    val kind: ControlKind,
    val mapping: MidiMapping,
    val value: Int = 0,
    val buttonBehavior: ButtonBehavior = ButtonBehavior.MOMENTARY,
)

data class Layout(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val controls: List<Control> = emptyList(),
)

data class PersistedState(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val layout: Layout = Layout(),
    val deviceAddress: String? = null,
    val deviceName: String? = null,
)

fun ControlKind.supportsNote(): Boolean = this == ControlKind.BUTTON

fun ControlKind.defaultResolution(): MidiResolution =
    if (this == ControlKind.BUTTON) MidiResolution.SEVEN_BIT else MidiResolution.FOURTEEN_BIT

fun ControlKind.defaultValue(): Int = if (this == ControlKind.BUTTON) 0 else 0

fun ControlKind.valueRange(): IntRange =
    if (this == ControlKind.BUTTON) 0..127 else 0..16383

fun ControlKind.defaultMapping(): MidiMapping = MidiMapping(
    type = if (this == ControlKind.BUTTON) MidiType.NOTE else MidiType.CC,
    channel = 1,
    number = if (this == ControlKind.BUTTON) 60 else 1,
    resolution = defaultResolution(),
)

const val CONTROLS_PER_STRIP = 5
const val DEFAULT_STRIP_COUNT = 4

data class ChannelStrip(
    val index: Int,
    val fader: Control,
    val knobs: List<Control>,
    val button: Control,
) {
    val number: Int get() = index + 1
    val title: String get() = "CH $number"
    val allControls: List<Control>
        get() = buildList(CONTROLS_PER_STRIP) {
            add(fader)
            addAll(knobs)
            add(button)
        }
}

fun presetStripCcNumber(stripIndex: Int, slotIndex: Int): Int {
    val bank = stripIndex / 16
    return if (slotIndex == 4) {
        (64 + bank).coerceIn(0, 127)
    } else {
        (bank * 4 + slotIndex.coerceIn(0, 3)).coerceIn(0, 31)
    }
}

fun presetStripNoteNumber(stripIndex: Int): Int {
    val bank = stripIndex / 16
    return (60 + bank).coerceIn(0, 127)
}

fun presetStripMapping(
    stripIndex: Int,
    slotIndex: Int,
    existingMapping: MidiMapping? = null,
): MidiMapping {
    val channel = (stripIndex % 16) + 1
    val bank = stripIndex / 16
    return when (slotIndex) {
        0 -> {
            val useCc = existingMapping?.type == MidiType.CC || bank > 0
            if (!useCc) {
                MidiMapping(
                    type = MidiType.PITCH_BEND,
                    channel = channel,
                    number = 0,
                    resolution = MidiResolution.FOURTEEN_BIT,
                )
            } else {
                val res = if (existingMapping?.type == MidiType.CC) {
                    existingMapping.resolution
                } else {
                    MidiResolution.SEVEN_BIT
                }
                MidiMapping(
                    type = MidiType.CC,
                    channel = channel,
                    number = presetStripCcNumber(stripIndex, 0),
                    resolution = res,
                )
            }
        }

        1, 2, 3 -> {
            val res = existingMapping
                ?.takeIf { it.type == MidiType.CC }
                ?.resolution
                ?: MidiResolution.SEVEN_BIT
            MidiMapping(
                type = MidiType.CC,
                channel = channel,
                number = presetStripCcNumber(stripIndex, slotIndex),
                resolution = res,
            )
        }

        else -> {
            val useCc = existingMapping?.type == MidiType.CC
            if (useCc) {
                MidiMapping(
                    type = MidiType.CC,
                    channel = channel,
                    number = presetStripCcNumber(stripIndex, 4),
                    resolution = MidiResolution.SEVEN_BIT,
                )
            } else {
                MidiMapping(
                    type = MidiType.NOTE,
                    channel = channel,
                    number = presetStripNoteNumber(stripIndex),
                    resolution = MidiResolution.SEVEN_BIT,
                )
            }
        }
    }
}

fun presetStripSlotDefaultLabel(stripIndex: Int, slotIndex: Int): String {
    val stripNumber = stripIndex + 1
    return when (slotIndex) {
        0 -> "CH $stripNumber Fader"
        1 -> "CH $stripNumber K1"
        2 -> "CH $stripNumber K2"
        3 -> "CH $stripNumber K3"
        else -> "CH $stripNumber Btn"
    }
}

fun List<Control>.isChannelStripLayout(): Boolean {
    if (isEmpty() || size % CONTROLS_PER_STRIP != 0) return false
    return chunked(CONTROLS_PER_STRIP).all { chunk ->
        chunk[0].kind == ControlKind.FADER &&
            chunk[1].kind == ControlKind.KNOB &&
            chunk[2].kind == ControlKind.KNOB &&
            chunk[3].kind == ControlKind.KNOB &&
            chunk[4].kind == ControlKind.BUTTON
    }
}

fun List<Control>.hasPresetStripMappings(): Boolean {
    if (!isChannelStripLayout()) return false
    return chunked(CONTROLS_PER_STRIP).withIndex().all { (stripIdx, chunk) ->
        chunk.withIndex().all { (slotIdx, control) ->
            control.mapping == presetStripMapping(stripIdx, slotIdx, control.mapping)
        }
    }
}

fun List<Control>.toChannelStrips(): List<ChannelStrip> {
    if (!isChannelStripLayout()) return emptyList()
    return chunked(CONTROLS_PER_STRIP).mapIndexed { index, chunk ->
        ChannelStrip(
            index = index,
            fader = chunk[0],
            knobs = chunk.subList(1, 4),
            button = chunk[4],
        )
    }
}
