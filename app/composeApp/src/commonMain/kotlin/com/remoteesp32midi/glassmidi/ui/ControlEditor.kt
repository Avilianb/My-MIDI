package com.remoteesp32midi.glassmidi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.remoteesp32midi.glassmidi.model.Control
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
    onDelete: () -> Unit,
) {
    var draft by remember(initial.id, initial.kind) { mutableStateOf(initial) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val validation = validateControl(draft, allControls)
    LaunchedEffect(draft) { saveError = null }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            backdrop = backdrop,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.9f)
                .clickable {},
            shape = RoundedCornerShape(32.dp),
            tint = Color(0xFF0B1422),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        BasicText(
                            if (initial.id.isBlank()) "添加控件" else "编辑控件",
                            style = TextStyle(GlassText, 24.sp, FontWeight.SemiBold),
                        )
                        BasicText(
                            "配置 MIDI 映射",
                            style = TextStyle(GlassMuted, 12.sp),
                        )
                    }
                    BasicText(
                        "×",
                        Modifier
                            .clickable(onClick = onDismiss)
                            .padding(8.dp),
                        style = TextStyle(GlassMuted, 26.sp),
                    )
                }

                FieldLabel("名称")
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(16.dp)),
                ) {
                    GlassTextField(
                        value = draft.label,
                        onValueChange = {
                            draft = draft.copy(label = it)
                            saveError = null
                        },
                        placeholder = "例如 Cutoff",
                    )
                }

                FieldLabel("控件类型")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ControlKind.entries.forEach { kind ->
                        GlassPill(
                            text = kind.displayName,
                            onClick = {
                                val oldKind = draft.kind
                                val suffix = draft.label
                                    .takeIf { it.startsWith(oldKind.displayName + " ") }
                                    ?.removePrefix(oldKind.displayName + " ")
                                val candidateLabel = if (suffix != null) {
                                    "${kind.displayName} $suffix"
                                } else {
                                    draft.label
                                }
                                val uniqueLabel = if (candidateLabel.isNotBlank() &&
                                    allControls.any { it.id != draft.id && it.label == candidateLabel }
                                ) {
                                    defaultControlLabel(
                                        kind,
                                        allControls,
                                        draft.id,
                                    )
                                } else {
                                    candidateLabel
                                }
                                draft = draft.copy(
                                    label = uniqueLabel,
                                    kind = kind,
                                    mapping = draft.mapping.forKind(kind),
                                    value = if (kind == ControlKind.BUTTON) {
                                        draft.value.coerceIn(0, 127)
                                    } else {
                                        draft.value.coerceIn(0, 16383)
                                    },
                                )
                                saveError = null
                            },
                            backdrop = backdrop,
                            selected = draft.kind == kind,
                        )
                    }
                }

                FieldLabel("MIDI 类型")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MidiType.entries.forEach { type ->
                        val enabled = draft.kind == ControlKind.BUTTON || type == MidiType.CC
                        GlassPill(
                            text = type.displayName,
                            onClick = {
                                if (enabled) draft = draft.copy(
                                    mapping = draft.mapping.copy(
                                        type = type,
                                        resolution = if (draft.kind == ControlKind.BUTTON) {
                                            MidiResolution.SEVEN_BIT
                                        } else {
                                            MidiResolution.FOURTEEN_BIT
                                        },
                                        number = if (draft.kind == ControlKind.BUTTON) {
                                            draft.mapping.number.coerceIn(0, 127)
                                        } else {
                                            draft.mapping.number.coerceIn(0, 31)
                                        },
                                    )
                                )
                                saveError = null
                            },
                            backdrop = backdrop,
                            selected = draft.mapping.type == type,
                            enabled = enabled,
                        )
                    }
                }

                FieldLabel("通道")
                StepperRow(
                    label = "CH ${draft.mapping.channel}",
                    onMinus = {
                        draft = draft.copy(
                            mapping = draft.mapping.copy(
                                channel = if (draft.mapping.channel <= 1) 16 else draft.mapping.channel - 1
                            )
                        )
                    },
                    onPlus = {
                        draft = draft.copy(
                            mapping = draft.mapping.copy(
                                channel = if (draft.mapping.channel >= 16) 1 else draft.mapping.channel + 1
                            )
                        )
                    },
                    backdrop = backdrop,
                )

                FieldLabel(
                    if (draft.mapping.resolution == MidiResolution.FOURTEEN_BIT) {
                        "CC MSB（LSB = MSB + 32）"
                    } else if (draft.mapping.type == MidiType.NOTE) {
                        "Note 编号"
                    } else {
                        "CC 编号"
                    }
                )
                StepperRow(
                    label = if (draft.mapping.resolution == MidiResolution.FOURTEEN_BIT) {
                        "CC ${draft.mapping.number} / ${draft.mapping.number + 32}"
                    } else {
                        "${draft.mapping.type.displayName} ${draft.mapping.number}"
                    },
                    onMinus = {
                        draft = draft.copy(
                            mapping = draft.mapping.copy(number = (draft.mapping.number - 1).coerceAtLeast(0))
                        )
                    },
                    onPlus = {
                        val max = if (draft.mapping.resolution == MidiResolution.FOURTEEN_BIT) 31 else 127
                        draft = draft.copy(
                            mapping = draft.mapping.copy(number = (draft.mapping.number + 1).coerceAtMost(max))
                        )
                    },
                    backdrop = backdrop,
                )

                val problem = validation ?: saveError
                if (problem != null) {
                    BasicText(
                        problem,
                        style = TextStyle(GlassDanger, 12.sp),
                    )
                }

                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassPill(
                        text = "保存",
                        onClick = {
                            val error = onSave(draft.withDefaultLabel(allControls))
                            if (error == null) onDismiss() else saveError = error
                        },
                        backdrop = backdrop,
                        selected = true,
                        enabled = validation == null,
                    )
                    if (initial.id.isNotBlank()) {
                        GlassPill(
                            text = "删除",
                            onClick = {
                                onDelete()
                                onDismiss()
                            },
                            backdrop = backdrop,
                        )
                    }
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
private fun StepperRow(
    label: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    backdrop: Backdrop,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassPill(text = "−", onClick = onMinus, backdrop = backdrop)
        BasicText(
            label,
            Modifier.weight(1f),
            style = TextStyle(GlassText, 16.sp, FontWeight.Medium),
        )
        GlassPill(text = "+", onClick = onPlus, backdrop = backdrop)
    }
}

private fun MidiMapping.forKind(kind: ControlKind): MidiMapping = when (kind) {
    ControlKind.BUTTON -> copy(
        type = if (type == MidiType.NOTE) type else MidiType.CC,
        resolution = MidiResolution.SEVEN_BIT,
        number = number.coerceIn(0, 127),
    )

    ControlKind.KNOB, ControlKind.FADER -> copy(
        type = MidiType.CC,
        resolution = MidiResolution.FOURTEEN_BIT,
        number = if (type == MidiType.NOTE) 1 else number.coerceIn(0, 31),
    )
}
