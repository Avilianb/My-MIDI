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

/** 控件自动编号方案的版本;0→1 修正旧布局里“所有按钮同一个音 / 同一个 CC”的问题。 */
const val MAPPING_SCHEME_VERSION = 1

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
    val channelNames: List<String> = emptyList(),
)

data class PersistedState(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val layout: Layout = Layout(),
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    /** 0 = 乐手 1 (USB Port 1 / Cable 0)，1 = 乐手 2 (USB Port 2 / Cable 1) */
    val devicePort: Int = 0,
    /** 0 = 旧版按钮音高方案(未迁移)，1 = 每条通道条独立音高。 */
    val mappingSchemeVersion: Int = 0,
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

const val CONTROLS_PER_STRIP = 6
/** 旧布局(每条 1 Fader + 3 Knob + 1 Button)的控件数,只用于一次性迁移。 */
const val LEGACY_CONTROLS_PER_STRIP = 5
const val DEFAULT_STRIP_COUNT = 4
const val MAX_PLAYER_STRIPS = 8

/** Actual channel learned by Live; firmware translates local BLE channels. */
fun usbMidiChannel(devicePort: Int, localChannel: Int): Int =
    devicePort.coerceIn(0, 1) * MAX_PLAYER_STRIPS + localChannel


data class ChannelStrip(
    val index: Int,
    val fader: Control,
    val knobs: List<Control>,
    val buttons: List<Control>,
    val customName: String? = null,
) {
    val number: Int get() = index + 1
    val defaultTitle: String get() = "CH $number"
    val title: String get() = customName?.takeIf { it.isNotBlank() } ?: defaultTitle
    val allControls: List<Control>
        get() = buildList(CONTROLS_PER_STRIP) {
            add(fader)
            addAll(knobs)
            addAll(buttons)
        }
}

fun presetStripCcNumber(stripIndex: Int, slotIndex: Int): Int {
    val bank = stripIndex / 16
    // bank 0 保持 K1..K3 = 1/2/3、推子 = 7；bank ≥ 1 起用 8 步长。
    // 若沿用 4 步长，推子 CC(7 + 4*bank) 会等于下一个 bank 的 K3(4*(bank+1)+3)，
    // 而两者在 (stripIndex % 16) 上共用同一条 MIDI 通道。
    val base = if (bank == 0) 0 else bank * 8
    return when (slotIndex) {
        4, 5 -> {
            // 按钮:每条通道条两个独立 CC(General Purpose 区,80 起)。
            (80 + stripIndex * 2 + (slotIndex - 4)).coerceIn(0, 127)
        }
        0 -> {
            // 推子:CC 7 = Channel Volume;旧预设的 CC 0 是 Bank Select,扫它会改变待切换的音色库。
            (base + 7).coerceIn(0, 127)
        }
        else -> (base + slotIndex.coerceIn(1, 3)).coerceIn(0, 127)
    }
}

/** 旧版按钮 CC 编号(每条通道条都是 CC 64),只用于一次性迁移。 */
fun legacyPresetButtonCcNumber(stripIndex: Int): Int {
    val bank = stripIndex / 16
    return (64 + bank).coerceIn(0, 127)
}

/** 旧版推子 CC 编号(CC 0 = Bank Select),只用于一次性迁移。 */
fun legacyPresetFaderCcNumber(stripIndex: Int): Int {
    val bank = stripIndex / 16
    return (bank * 4).coerceIn(0, 127)
}

/**
 * 每条通道条的按钮默认音高:从 C1(36) 起逐条递增,鼓垫习惯。
 * 超过 92 条通道条后夹在 127,不再增加。
 */
fun presetStripNoteNumber(stripIndex: Int, buttonIndex: Int = 0): Int {
    // 每条通道条两个按钮各占一个音高:36 + 2*strip + (0/1)。
    return (36 + stripIndex * 2 + buttonIndex.coerceIn(0, 1)).coerceIn(0, 127)
}

/** 旧版自动编号(16 条通道条共用一个音高),只用于一次性迁移。 */
fun legacyPresetStripNoteNumber(stripIndex: Int): Int {
    val bank = stripIndex / 16
    return (60 + bank).coerceIn(0, 127)
}

/**
 * 把仍停留在旧版自动编号上的控件迁移到新编号。
 * 按钮:Note(60+bank → 36+strip)与 CC(64+bank → 80+strip);
 * 推子:CC(bank*4 → 7+bank*4)。
 * 只改写“恰好等于旧自动值”的控件;用户手改过的编号保持不动。
 */
fun migrateLegacyAutoMappings(controls: List<Control>): List<Control> {
    var changed = false
    val migrated = controls.mapIndexed { index, control ->
        val stripIndex = index / CONTROLS_PER_STRIP
        val slotIndex = index % CONTROLS_PER_STRIP
        val mapping = control.mapping
        val legacy: Int
        val target: Int
        when {
            // 旧布局每条只有一个按钮(slot 4):Note 60+bank、CC 64+bank。
            control.kind == ControlKind.BUTTON && mapping.type == MidiType.NOTE && slotIndex == 4 -> {
                legacy = legacyPresetStripNoteNumber(stripIndex)
                target = presetStripNoteNumber(stripIndex, 0)
            }

            control.kind == ControlKind.BUTTON && mapping.type == MidiType.CC && slotIndex == 4 -> {
                legacy = legacyPresetButtonCcNumber(stripIndex)
                target = presetStripCcNumber(stripIndex, 4)
            }

            control.kind == ControlKind.KNOB && mapping.type == MidiType.CC -> {
                legacy = (stripIndex / 16) * 4 + slotIndex
                target = presetStripCcNumber(stripIndex, slotIndex)
            }

            control.kind == ControlKind.FADER && mapping.type == MidiType.CC -> {
                legacy = legacyPresetFaderCcNumber(stripIndex)
                target = presetStripCcNumber(stripIndex, 0)
            }

            else -> return@mapIndexed control
        }
        if (mapping.number == legacy && mapping.number != target) {
            changed = true
            control.copy(mapping = mapping.copy(number = target))
        } else {
            control
        }
    }
    return if (changed) migrated else controls
}

fun presetStripMapping(
    stripIndex: Int,
    slotIndex: Int,
    existingMapping: MidiMapping? = null,
): MidiMapping {
    val channel = (stripIndex % 16) + 1
    val bank = stripIndex / 16
    // 归一化时保留用户改过的 CC 编号,只有新建控件/重置布局才回到预设值。
    val keptCcNumber = existingMapping
        ?.takeIf { it.type == MidiType.CC }
        ?.number
        ?.coerceIn(0, 127)
    // 14-bit 只能用 MSB 0..31,越界会让编辑器显示非法值并卡住保存。
    fun ccNumberFor(slot: Int, resolution: MidiResolution): Int {
        val number = keptCcNumber ?: presetStripCcNumber(stripIndex, slot)
        return if (resolution == MidiResolution.FOURTEEN_BIT) number.coerceIn(0, 31) else number
    }
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
                    number = ccNumberFor(0, res),
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
                number = ccNumberFor(slotIndex, res),
                resolution = res,
            )
        }

        else -> {
            // slot 4/5 都是按钮(>=6 的非法 slot 也按按钮兜底)。
            val useCc = existingMapping?.type == MidiType.CC
            if (useCc) {
                MidiMapping(
                    type = MidiType.CC,
                    channel = channel,
                    number = ccNumberFor(slotIndex, MidiResolution.SEVEN_BIT),
                    resolution = MidiResolution.SEVEN_BIT,
                )
            } else {
                // 归一化时保留用户改过的音高,只有新建控件/重置布局才回到预设值。
                val noteNumber = existingMapping
                    ?.takeIf { it.type == MidiType.NOTE }
                    ?.number
                    ?.coerceIn(0, 127)
                    ?: presetStripNoteNumber(stripIndex, slotIndex - 4)
                MidiMapping(
                    type = MidiType.NOTE,
                    channel = channel,
                    number = noteNumber,
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
        4 -> "CH $stripNumber Btn 1"
        5 -> "CH $stripNumber Btn 2"
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
            chunk[4].kind == ControlKind.BUTTON &&
            chunk[5].kind == ControlKind.BUTTON
    }
}

/** 旧布局形态:每条 1 Fader + 3 Knob + 1 Button,只用于一次性迁移。 */
fun List<Control>.isLegacyFiveControlStripLayout(): Boolean {
    if (isEmpty() || size % LEGACY_CONTROLS_PER_STRIP != 0) return false
    return chunked(LEGACY_CONTROLS_PER_STRIP).all { chunk ->
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

fun List<Control>.toChannelStrips(
    channelNames: List<String> = emptyList(),
): List<ChannelStrip> {
    if (!isChannelStripLayout()) return emptyList()
    return chunked(CONTROLS_PER_STRIP).mapIndexed { index, chunk ->
        ChannelStrip(
            index = index,
            fader = chunk[0],
            knobs = chunk.subList(1, 4),
            buttons = chunk.subList(4, 6),
            customName = channelNames.getOrNull(index)?.takeIf { it.isNotBlank() },
        )
    }
}
