package com.remoteesp32midi.glassmidi.model

enum class ControlKind(val displayName: String) {
    BUTTON("Button"),
    KNOB("Knob"),
    FADER("Fader"),
}

enum class MidiType(val displayName: String) {
    NOTE("Note"),
    CC("CC"),
}

enum class MidiResolution {
    SEVEN_BIT,
    FOURTEEN_BIT,
}

/**
 * channel 是界面使用的 1..16；number 是 Note 编号或 CC 的 MSB 编号。
 * 标准 14-bit CC 占用 number（MSB）和 number + 32（LSB）两个编号。
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
)

data class Layout(
    val schemaVersion: Int = 1,
    val controls: List<Control> = emptyList(),
)

data class PersistedState(
    val schemaVersion: Int = 1,
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
