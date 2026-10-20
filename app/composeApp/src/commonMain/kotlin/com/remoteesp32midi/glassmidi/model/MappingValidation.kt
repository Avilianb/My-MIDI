package com.remoteesp32midi.glassmidi.model

/**
 * 控件映射校验规则：
 * - Button 允许 Note/CC，且只能是 7-bit；
 * - Knob/Fader 只能是 CC 14-bit，number 范围 0..126；
 * - 同一通道上，14-bit 的 MSB/LSB 不得与任何其他 CC 占用相同编号；
 * - 普通 7-bit CC 之间、Note 之间允许重复映射。
 */
fun validateControl(
    candidate: Control,
    allControls: List<Control>,
    excludeId: String? = candidate.id,
): String? {
    if (candidate.label.isBlank()) return "名称不能为空"
    if (candidate.label.length > 24) return "名称最多 24 个字符"
    if (candidate.mapping.channel !in 1..16) return "通道必须在 1..16"

    when (candidate.kind) {
        ControlKind.BUTTON -> {
            if (candidate.mapping.resolution != MidiResolution.SEVEN_BIT) {
                return "Button 不支持 14-bit"
            }
            when (candidate.mapping.type) {
                MidiType.NOTE -> if (candidate.mapping.number !in 0..127) {
                    return "Note 编号必须在 0..127"
                }

                MidiType.CC -> if (candidate.mapping.number !in 0..127) {
                    return "CC 编号必须在 0..127"
                }
            }
        }

        ControlKind.KNOB, ControlKind.FADER -> {
            if (candidate.mapping.type != MidiType.CC) return "Knob/Fader 只能使用 CC"
            if (candidate.mapping.resolution != MidiResolution.FOURTEEN_BIT) {
                return "Knob/Fader 必须是 14-bit CC"
            }
            if (candidate.mapping.number !in 0..126) return "14-bit CC 编号必须在 0..126"
        }
    }

    val others = allControls.filter { it.id != excludeId }
    val candidateChannel = candidate.mapping.channel
    val candidateIsFourteenBit =
        candidate.mapping.type == MidiType.CC &&
            candidate.mapping.resolution == MidiResolution.FOURTEEN_BIT
    val candidateNumbers = if (candidateIsFourteenBit) {
        setOf(candidate.mapping.number, candidate.mapping.number + 1)
    } else if (candidate.mapping.type == MidiType.CC && candidate.kind == ControlKind.BUTTON) {
        setOf(candidate.mapping.number)
    } else {
        emptySet()
    }

    for (other in others) {
        if (other.mapping.type != MidiType.CC) continue
        if (other.mapping.channel != candidateChannel) continue
        if (other.kind == ControlKind.KNOB || other.kind == ControlKind.FADER) {
            val occupied = setOf(other.mapping.number, other.mapping.number + 1)
            if (candidateNumbers.any { it in occupied }) {
                return "通道 ${candidateChannel} 的 CC ${other.mapping.number}/${other.mapping.number + 1} 已被「${other.label}」占用"
            }
        } else if (candidateIsFourteenBit && other.mapping.number in candidateNumbers) {
            return "通道 ${candidateChannel} 的 CC ${other.mapping.number} 已被「${other.label}」占用"
        }
    }

    return null
}

/**
 * 为新控件生成不重复的默认名称。已有自定义名称不参与编号计算。
 */
fun defaultControlLabel(
    kind: ControlKind,
    controls: List<Control>,
    excludeId: String? = null,
): String {
    val prefix = "${kind.displayName} "
    val others = controls.filter { it.id != excludeId }
    val used = others.map { it.label }.toSet()
    val maxSuffix = others.mapNotNull { control ->
        control.label
            .takeIf { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.toIntOrNull()
    }.maxOrNull() ?: 0
    var index = maxSuffix + 1
    while ("$prefix$index" in used) index++
    return "$prefix$index"
}

fun Control.withDefaultLabel(existing: List<Control> = emptyList()): Control =
    if (label.isBlank()) {
        copy(label = defaultControlLabel(kind, existing, id))
    } else {
        this
    }
