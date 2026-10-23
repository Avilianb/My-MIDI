package com.remoteesp32midi.glassmidi.model

/**
 * 控件映射校验规则：
 * - Button 允许 Note/CC，且只能是 7-bit；
 * - Knob/Fader 可用 CC（7-bit 或 14-bit），或单条 14-bit Pitch Bend；
 * - 同一通道上，14-bit 的 MSB/LSB 不得与任何其他 CC 占用相同编号；
 * - 同一通道只能有一个 Pitch Bend；
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
            if (candidate.mapping.type == MidiType.PITCH_BEND) {
                return "Button 不支持 Pitch Bend"
            }
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

                MidiType.PITCH_BEND -> return "Button 不支持 Pitch Bend"
            }
        }

        ControlKind.KNOB, ControlKind.FADER -> {
            when (candidate.mapping.type) {
                MidiType.NOTE -> return "Knob/Fader 只能使用 CC 或 Pitch Bend"
                MidiType.PITCH_BEND -> {
                    if (candidate.mapping.resolution != MidiResolution.FOURTEEN_BIT) {
                        return "Pitch Bend 固定为 14-bit"
                    }
                }

                MidiType.CC -> {
                    if (candidate.mapping.resolution == MidiResolution.FOURTEEN_BIT) {
                        if (candidate.mapping.number !in 0..31) return "14-bit CC 编号必须在 0..31"
                    } else {
                        if (candidate.mapping.number !in 0..127) return "7-bit CC 编号必须在 0..127"
                    }
                }
            }
        }
    }

    val others = allControls.filter { it.id != excludeId }
    val candidateChannel = candidate.mapping.channel

    if (candidate.mapping.type == MidiType.PITCH_BEND) {
        val occupied = others.firstOrNull {
            it.mapping.type == MidiType.PITCH_BEND &&
                it.mapping.channel == candidateChannel
        }
        if (occupied != null) {
            return "通道 $candidateChannel 的 Pitch Bend 已被「${occupied.label}」占用"
        }
    }
    val candidateIsFourteenBit =
        candidate.mapping.type == MidiType.CC &&
            candidate.mapping.resolution == MidiResolution.FOURTEEN_BIT
    val candidateNumbers = if (candidate.mapping.type != MidiType.CC) {
        emptySet()
    } else if (candidateIsFourteenBit) {
        setOf(candidate.mapping.number, candidate.mapping.number + 32)
    } else {
        setOf(candidate.mapping.number)
    }

    for (other in others) {
        if (other.mapping.type != MidiType.CC) continue
        if (other.mapping.channel != candidateChannel) continue
        val otherIsFourteenBit = other.mapping.resolution == MidiResolution.FOURTEEN_BIT
        val otherNumbers = if (otherIsFourteenBit) {
            setOf(other.mapping.number, other.mapping.number + 32)
        } else {
            setOf(other.mapping.number)
        }
        // 7-bit 之间允许重复；只要涉及 14-bit 的 MSB/LSB，就不允许占用同一编号。
        if ((candidateIsFourteenBit || otherIsFourteenBit) &&
            candidateNumbers.any { it in otherNumbers }
        ) {
            val occupied = if (otherIsFourteenBit) {
                "CC ${other.mapping.number}/${other.mapping.number + 32}"
            } else {
                "CC ${other.mapping.number}"
            }
            return "通道 ${candidateChannel} 的 $occupied 已被「${other.label}」占用"
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
