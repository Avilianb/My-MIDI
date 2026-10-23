package com.remoteesp32midi.glassmidi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.remoteesp32midi.glassmidi.model.Control
import com.remoteesp32midi.glassmidi.model.ButtonBehavior
import com.remoteesp32midi.glassmidi.model.CONTROLS_PER_STRIP
import com.remoteesp32midi.glassmidi.model.presetStripCcNumber
import com.remoteesp32midi.glassmidi.model.presetStripNoteNumber
import com.remoteesp32midi.glassmidi.model.ControlKind
import com.remoteesp32midi.glassmidi.model.MidiMapping
import com.remoteesp32midi.glassmidi.model.MidiResolution
import com.remoteesp32midi.glassmidi.model.MidiType
import com.remoteesp32midi.glassmidi.model.defaultControlLabel
import com.remoteesp32midi.glassmidi.model.validateControl
import com.remoteesp32midi.glassmidi.model.withDefaultLabel

@Composable
fun ControlEditor(
    backdrop: Backdrop,
    initial: Control,
    allControls: List<Control>,
    onDismiss: () -> Unit,
    onSave: (Control) -> String?,
    onDelete: (() -> Unit)? = null,
    allowKindChange: Boolean = false,
) {
    val controlIndex = remember(initial.id, allControls) {
        allControls.indexOfFirst { it.id == initial.id }.coerceAtLeast(0)
    }
    val stripIndex = controlIndex / CONTROLS_PER_STRIP
    val slotIndex = controlIndex % CONTROLS_PER_STRIP
    val lockedChannel = initial.mapping.channel
    val defaultCcNumber = presetStripCcNumber(stripIndex, slotIndex)
    val defaultNoteNumber = presetStripNoteNumber(stripIndex)
    val canUsePitchBend = initial.kind == ControlKind.FADER && (stripIndex / 16 == 0)

    var labelText by remember(initial.id) { mutableStateOf(initial.label) }
    var mapping by remember(initial.id) {
        mutableStateOf(
            if (initial.mapping.type == MidiType.CC &&
                initial.mapping.resolution == MidiResolution.FOURTEEN_BIT
            ) {
                initial.mapping.copy(number = initial.mapping.number.coerceIn(0, 31))
            } else {
                initial.mapping
            },
        )
    }
    // 切换信号类型时记住上次的音符/CC 编号,免得来回切换丢设置。
    var noteNumber by remember(initial.id) {
        mutableStateOf(
            if (initial.mapping.type == MidiType.NOTE) initial.mapping.number else defaultNoteNumber,
        )
    }
    var ccNumber by remember(initial.id) {
        mutableStateOf(
            if (initial.mapping.type == MidiType.CC) initial.mapping.number else defaultCcNumber,
        )
    }
    var buttonBehavior by remember(initial.id) { mutableStateOf(initial.buttonBehavior) }
    var saveError by remember(initial.id) { mutableStateOf<String?>(null) }

    // 映射冲突校验仅依赖 mapping，输入名称时不触发全表扫描校验
    val mappingValidation = remember(initial.id, initial.kind, mapping, allControls) {
        validateControl(
            candidate = initial.copy(label = "x", mapping = mapping),
            allControls = allControls,
        )
    }
    val labelValidation = if (labelText.trim().length > 24) "名称最多 24 个字符" else null
    val validation = labelValidation ?: mappingValidation

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .imePadding()
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            backdrop = backdrop,
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .clickable {},
            shape = RoundedCornerShape(28.dp),
            tint = Color.White,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        ) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        BasicText(
                            "控件设置",
                            style = TextStyle(GlassText, 20.sp, FontWeight.SemiBold),
                        )
                        BasicText(
                            "通道已锁定；名称、信号类型、音符与 CC 编号可修改",
                            style = TextStyle(GlassMuted, 11.sp),
                        )
                    }
                    BasicText(
                        "×",
                        Modifier
                            .clickable(onClick = onDismiss)
                            .padding(8.dp),
                        style = TextStyle(GlassMuted, 24.sp),
                    )
                }

                FieldLabel("显示名称")
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.04f), RoundedCornerShape(14.dp)),
                ) {
                    GlassTextField(
                        value = labelText,
                        onValueChange = {
                            labelText = it
                            saveError = null
                        },
                        placeholder = "例如 Vocal / Cutoff / Mute",
                    )
                }

                when (initial.kind) {
                    ControlKind.FADER -> {
                        FieldLabel("推子信号模式（通道 CH $lockedChannel 自动锁定）")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (canUsePitchBend) {
                                GlassPill(
                                    text = "Pitch Bend 14-bit",
                                    onClick = {
                                        mapping = MidiMapping(
                                            type = MidiType.PITCH_BEND,
                                            channel = lockedChannel,
                                            number = 0,
                                            resolution = MidiResolution.FOURTEEN_BIT,
                                        )
                                        saveError = null
                                    },
                                    backdrop = backdrop,
                                    selected = mapping.type == MidiType.PITCH_BEND,
                                )
                            }
                            GlassPill(
                                text = "7-bit CC",
                                onClick = {
                                    mapping = MidiMapping(
                                        type = MidiType.CC,
                                        channel = lockedChannel,
                                        number = ccNumber,
                                        resolution = MidiResolution.SEVEN_BIT,
                                    )
                                    saveError = null
                                },
                                backdrop = backdrop,
                                selected = mapping.type == MidiType.CC &&
                                    mapping.resolution == MidiResolution.SEVEN_BIT,
                            )
                            GlassPill(
                                text = "14-bit CC",
                                onClick = {
                                    mapping = MidiMapping(
                                        type = MidiType.CC,
                                        channel = lockedChannel,
                                        number = ccNumber.coerceIn(0, 31),
                                        resolution = MidiResolution.FOURTEEN_BIT,
                                    )
                                    saveError = null
                                },
                                backdrop = backdrop,
                                selected = mapping.type == MidiType.CC &&
                                    mapping.resolution == MidiResolution.FOURTEEN_BIT,
                            )
                        }
                    }

                    ControlKind.KNOB -> {
                        FieldLabel("旋钮精度模式（CH $lockedChannel）")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassPill(
                                text = "7-bit CC（通用推荐）",
                                onClick = {
                                    mapping = MidiMapping(
                                        type = MidiType.CC,
                                        channel = lockedChannel,
                                        number = ccNumber,
                                        resolution = MidiResolution.SEVEN_BIT,
                                    )
                                    saveError = null
                                },
                                backdrop = backdrop,
                                selected = mapping.type == MidiType.CC &&
                                    mapping.resolution == MidiResolution.SEVEN_BIT,
                            )
                            GlassPill(
                                text = "14-bit CC（高精双字节）",
                                onClick = {
                                    mapping = MidiMapping(
                                        type = MidiType.CC,
                                        channel = lockedChannel,
                                        number = ccNumber.coerceIn(0, 31),
                                        resolution = MidiResolution.FOURTEEN_BIT,
                                    )
                                    saveError = null
                                },
                                backdrop = backdrop,
                                selected = mapping.type == MidiType.CC &&
                                    mapping.resolution == MidiResolution.FOURTEEN_BIT,
                            )
                        }
                    }

                    ControlKind.BUTTON -> {
                        FieldLabel("按钮触发方式")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ButtonBehavior.entries.forEach { behavior ->
                                GlassPill(
                                    text = behavior.displayName,
                                    onClick = { buttonBehavior = behavior },
                                    backdrop = backdrop,
                                    selected = buttonBehavior == behavior,
                                )
                            }
                        }
                        FieldLabel("按钮信号类型（通道 CH $lockedChannel 自动锁定）")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassPill(
                                text = "Note（音符）",
                                onClick = {
                                    mapping = MidiMapping(
                                        type = MidiType.NOTE,
                                        channel = lockedChannel,
                                        number = noteNumber,
                                        resolution = MidiResolution.SEVEN_BIT,
                                    )
                                    saveError = null
                                },
                                backdrop = backdrop,
                                selected = mapping.type == MidiType.NOTE,
                            )
                            GlassPill(
                                text = "CC（插件兼容）",
                                onClick = {
                                    mapping = MidiMapping(
                                        type = MidiType.CC,
                                        channel = lockedChannel,
                                        number = ccNumber,
                                        resolution = MidiResolution.SEVEN_BIT,
                                    )
                                    saveError = null
                                },
                                backdrop = backdrop,
                                selected = mapping.type == MidiType.CC,
                            )
                        }
                    }
                }

                when (mapping.type) {
                    MidiType.NOTE -> NumberRow(
                        label = "音符编号",
                        number = mapping.number,
                        range = 0..127,
                        backdrop = backdrop,
                        onChange = { value ->
                            noteNumber = value
                            mapping = mapping.copy(number = value)
                            saveError = null
                        },
                    )

                    MidiType.CC -> NumberRow(
                        label = "CC 编号",
                        number = mapping.number,
                        range = if (mapping.resolution == MidiResolution.FOURTEEN_BIT) 0..31 else 0..127,
                        backdrop = backdrop,
                        onChange = { value ->
                            ccNumber = value
                            mapping = mapping.copy(number = value)
                            saveError = null
                        },
                    )

                    MidiType.PITCH_BEND -> Unit
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.035f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PresetRow("锁定 MIDI 通道", "CH ${mapping.channel}")
                    val mappingDesc = when (mapping.type) {
                        MidiType.CC -> if (mapping.resolution == MidiResolution.FOURTEEN_BIT) {
                            "14-bit CC ${mapping.number} (LSB ${mapping.number + 32})"
                        } else {
                            "7-bit CC ${mapping.number}"
                        }
                        MidiType.NOTE -> "Note ${mapping.number} (${buttonBehavior.displayName})"
                        MidiType.PITCH_BEND -> "14-bit Pitch Bend（16384 级单消息）"
                    }
                    PresetRow("当前预设信号", mappingDesc)
                }

                val problem = validation ?: saveError
                if (problem != null) {
                    BasicText(
                        problem,
                        style = TextStyle(GlassDanger, 12.sp),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassPill(
                        text = "保存",
                        onClick = {
                            val draft = initial.copy(
                                label = labelText.trim(),
                                mapping = mapping,
                                buttonBehavior = buttonBehavior,
                            )
                            val candidate = draft.withDefaultLabel(allControls)
                            val error = onSave(candidate)
                            if (error == null) onDismiss() else saveError = error
                        },
                        backdrop = backdrop,
                        selected = true,
                        enabled = validation == null,
                    )
                    GlassPill(
                        text = "取消",
                        onClick = onDismiss,
                        backdrop = backdrop,
                    )
                }
            }
        }
    }
}

@Composable
fun ChannelNameEditor(
    backdrop: Backdrop,
    stripNumber: Int,
    initialName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val defaultTitle = "CH $stripNumber"
    var nameText by remember(stripNumber, initialName) {
        mutableStateOf(initialName.ifBlank { defaultTitle })
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .imePadding()
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            backdrop = backdrop,
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .clickable {},
            shape = RoundedCornerShape(28.dp),
            tint = Color.White,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        ) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        BasicText(
                            "修改通道名称",
                            style = TextStyle(GlassText, 20.sp, FontWeight.SemiBold),
                        )
                        BasicText(
                            "通道 $defaultTitle · 留空或恢复默认将显示为 $defaultTitle",
                            style = TextStyle(GlassMuted, 11.sp),
                        )
                    }
                    BasicText(
                        "×",
                        Modifier
                            .clickable(onClick = onDismiss)
                            .padding(8.dp),
                        style = TextStyle(GlassMuted, 24.sp),
                    )
                }

                FieldLabel("通道显示名称")
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.04f), RoundedCornerShape(14.dp)),
                ) {
                    GlassTextField(
                        value = nameText,
                        onValueChange = { nameText = it },
                        placeholder = "例如 Kick / Bass / Vocal / Synth",
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassPill(
                        text = "保存",
                        onClick = {
                            onSave(nameText.trim())
                            onDismiss()
                        },
                        backdrop = backdrop,
                        selected = true,
                    )
                    GlassPill(
                        text = "恢复默认",
                        onClick = {
                            onSave("")
                            onDismiss()
                        },
                        backdrop = backdrop,
                    )
                    GlassPill(
                        text = "取消",
                        onClick = onDismiss,
                        backdrop = backdrop,
                    )
                }
            }
        }
    }
}

@Composable
fun DeleteChannelConfirmDialog(
    backdrop: Backdrop,
    stripNumber: Int,
    customName: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val defaultTitle = "CH $stripNumber"
    val targetLabel = if (!customName.isNullOrBlank()) {
        "「$customName」($defaultTitle)"
    } else {
        defaultTitle
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            backdrop = backdrop,
            modifier = Modifier
                .fillMaxWidth(0.84f)
                .clickable {},
            shape = RoundedCornerShape(28.dp),
            tint = Color.White,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        ) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        BasicText(
                            "删除通道",
                            style = TextStyle(GlassText, 20.sp, FontWeight.SemiBold),
                        )
                        BasicText(
                            "删除后右侧通道编号将自动前移",
                            style = TextStyle(GlassMuted, 11.sp),
                        )
                    }
                    BasicText(
                        "×",
                        Modifier
                            .clickable(onClick = onDismiss)
                            .padding(8.dp),
                        style = TextStyle(GlassMuted, 24.sp),
                    )
                }

                BasicText(
                    "确定要删除通道 $targetLabel 及其包含的推子、旋钮与按钮吗？",
                    style = TextStyle(GlassText, 14.sp),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val dangerShape = RoundedCornerShape(50)
                    Box(
                        Modifier
                            .clip(dangerShape)
                            .background(GlassDanger.copy(alpha = 0.14f))
                            .border(0.5.dp, GlassDanger.copy(alpha = 0.45f), dangerShape)
                            .clickable {
                                onConfirm()
                                onDismiss()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            "确认删除",
                            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            style = TextStyle(GlassDanger, 14.sp, FontWeight.SemiBold),
                        )
                    }
                    GlassPill(
                        text = "取消",
                        onClick = onDismiss,
                        backdrop = backdrop,
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    BasicText(text, style = TextStyle(GlassMuted, 12.sp, FontWeight.Medium))
}

@Composable
private fun NumberRow(
    label: String,
    number: Int,
    range: IntRange,
    backdrop: Backdrop,
    onChange: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassPill(
            text = "−",
            onClick = { onChange((number - 1).coerceIn(range.first, range.last)) },
            backdrop = backdrop,
            enabled = number > range.first,
        )
        BasicText(
            "$label $number",
            Modifier.weight(1f),
            style = TextStyle(GlassText, 15.sp, FontWeight.Medium),
        )
        GlassPill(
            text = "+",
            onClick = { onChange((number + 1).coerceIn(range.first, range.last)) },
            backdrop = backdrop,
            enabled = number < range.last,
        )
    }
}

@Composable
private fun PresetRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, style = TextStyle(GlassMuted, 12.sp))
        BasicText(value, style = TextStyle(GlassText, 12.sp, FontWeight.SemiBold))
    }
}

private fun MidiMapping.forKind(kind: ControlKind): MidiMapping = when (kind) {
    ControlKind.BUTTON -> copy(
        type = if (type == MidiType.NOTE) type else MidiType.CC,
        resolution = MidiResolution.SEVEN_BIT,
        number = number.coerceIn(0, 127),
    )

    ControlKind.KNOB, ControlKind.FADER -> copy(
        type = if (type == MidiType.NOTE) MidiType.CC else type,
        resolution = MidiResolution.FOURTEEN_BIT,
        number = when (type) {
            MidiType.NOTE -> 1
            MidiType.PITCH_BEND -> number
            MidiType.CC -> number.coerceIn(0, 31)
        },
    )
}
