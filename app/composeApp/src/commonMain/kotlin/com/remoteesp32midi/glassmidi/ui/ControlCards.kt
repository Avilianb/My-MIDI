package com.remoteesp32midi.glassmidi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.remoteesp32midi.glassmidi.model.Control
import com.remoteesp32midi.glassmidi.model.ControlKind
import com.remoteesp32midi.glassmidi.model.MidiResolution
import com.remoteesp32midi.glassmidi.model.MidiType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ControlCard(
    control: Control,
    backdrop: Backdrop,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onContinuousChange: (Int) -> Unit,
    onContinuousFinished: () -> Unit,
    onEdit: () -> Unit,
) {
    GlassSurface(
        backdrop = backdrop,
        modifier = Modifier
            .fillMaxWidth()
            .height(236.dp),
        shape = RoundedCornerShape(26.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    BasicText(
                        control.label,
                        style = TextStyle(GlassText, 17.sp, FontWeight.SemiBold),
                    )
                    Spacer(Modifier.height(3.dp))
                    BasicText(
                        control.mapping.displayLabel(),
                        style = TextStyle(GlassMuted, 11.sp),
                    )
                }
                BasicText(
                    "编辑",
                    Modifier.pointerInput(control.id) {
                        detectTapGestures(onTap = { onEdit() })
                    },
                    style = TextStyle(GlassAccent, 12.sp, FontWeight.Medium),
                )
            }

            Spacer(Modifier.height(8.dp))
            when (control.kind) {
                ControlKind.BUTTON -> ButtonControl(
                    control = control,
                    onPressStart = onPressStart,
                    onPressEnd = onPressEnd,
                )

                ControlKind.KNOB -> KnobControl(
                    control = control,
                    onValueChange = onContinuousChange,
                    onFinished = onContinuousFinished,
                )

                ControlKind.FADER -> FaderControl(
                    control = control,
                    onValueChange = onContinuousChange,
                    onFinished = onContinuousFinished,
                )
            }
        }
    }
}

@Composable
private fun ButtonControl(
    control: Control,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
) {
    val isPressed = control.value > 0
    Box(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .background(
                color = if (isPressed) GlassAccent.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.06f),
                shape = RoundedCornerShape(20.dp),
            )
            .pointerInput(control.id) {
                detectTapGestures(
                    onPress = {
                        onPressStart()
                        try {
                            tryAwaitRelease()
                        } finally {
                            onPressEnd()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                if (isPressed) "ON" else "PRESS",
                style = TextStyle(
                    color = if (isPressed) GlassText else GlassMuted,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            BasicText(
                "value ${control.value}",
                style = TextStyle(GlassMuted, 11.sp),
            )
        }
    }
}

@Composable
private fun KnobControl(
    control: Control,
    onValueChange: (Int) -> Unit,
    onFinished: () -> Unit,
) {
    var dragValue by remember(control.id) { mutableFloatStateOf(control.value.toFloat()) }
    var isDragging by remember(control.id) { mutableStateOf(false) }
    val fraction = (dragValue / 16383f).coerceIn(0f, 1f)
    // 外部回传更新 control.value 时同步指针；拖动期间不被回传打断。
    LaunchedEffect(control.value, isDragging) {
        if (!isDragging) dragValue = control.value.toFloat()
    }
    // 整个旋钮拖动约 200 dp 覆盖 0..16383，避免不同屏幕密度下手感差异。
    val unitsPerPixel = with(LocalDensity.current) { 16383f / 200.dp.toPx() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(150.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(132.dp)
                .pointerInput(control.id, unitsPerPixel) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            isDragging = true
                            dragValue = control.value.toFloat()
                        },
                        onDragEnd = {
                            isDragging = false
                            onFinished()
                        },
                        onDragCancel = {
                            isDragging = false
                            onFinished()
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        dragValue = (dragValue - dragAmount * unitsPerPixel).coerceIn(0f, 16383f)
                        onValueChange(dragValue.toInt())
                    }
                },
        ) {
            Canvas(Modifier.size(132.dp)) {
                val stroke = 11.dp.toPx()
                val inset = stroke / 2f + 5.dp.toPx()
                val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
                drawArc(
                    color = Color.White.copy(alpha = 0.12f),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = GlassAccent,
                    startAngle = 135f,
                    sweepAngle = 270f * fraction,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                val angle = (135f + 270f * fraction) * PI.toFloat() / 180f
                val radius = arcSize.width / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val dot = Offset(
                    center.x + cos(angle) * radius,
                    center.y + sin(angle) * radius,
                )
                drawCircle(GlassText, radius = 4.5.dp.toPx(), center = dot)
            }
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText(
                    control.value.toString(),
                    style = TextStyle(
                        color = GlassText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    ),
                )
                BasicText("0–16383", style = TextStyle(GlassMuted, 10.sp))
            }
        }
    }
}

@Composable
private fun FaderControl(
    control: Control,
    onValueChange: (Int) -> Unit,
    onFinished: () -> Unit,
) {
    var dragValue by remember(control.id) { mutableFloatStateOf(control.value.toFloat()) }
    var isDragging by remember(control.id) { mutableStateOf(false) }
    val fraction = (dragValue / 16383f).coerceIn(0f, 1f)
    // 外部回传更新 control.value 时同步滑块；拖动期间不被回传打断。
    LaunchedEffect(control.value, isDragging) {
        if (!isDragging) dragValue = control.value.toFloat()
    }
    // 拖动整条 132 dp 轨道正好覆盖 0..16383，保证推子行程与显示一致。
    val unitsPerPixel = with(LocalDensity.current) { 16383f / 132.dp.toPx() }
    Row(
        Modifier
            .fillMaxWidth()
            .height(150.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(
            Modifier
                .width(58.dp)
                .height(132.dp)
                .pointerInput(control.id, unitsPerPixel) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            isDragging = true
                            dragValue = control.value.toFloat()
                        },
                        onDragEnd = {
                            isDragging = false
                            onFinished()
                        },
                        onDragCancel = {
                            isDragging = false
                            onFinished()
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        dragValue = (dragValue - dragAmount * unitsPerPixel).coerceIn(0f, 16383f)
                        onValueChange(dragValue.toInt())
                    }
                },
        ) {
            val trackWidth = 16.dp.toPx()
            val left = (size.width - trackWidth) / 2f
            val radius = CornerRadius(trackWidth / 2f)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.10f),
                topLeft = Offset(left, 0f),
                size = Size(trackWidth, size.height),
                cornerRadius = radius,
            )
            val fillHeight = size.height * fraction
            drawRoundRect(
                color = GlassAccent,
                topLeft = Offset(left, size.height - fillHeight),
                size = Size(trackWidth, fillHeight),
                cornerRadius = radius,
            )
            drawCircle(
                color = GlassText,
                radius = 7.dp.toPx(),
                center = Offset(size.width / 2f, size.height - fillHeight),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                control.value.toString(),
                style = TextStyle(GlassText, 18.sp, FontWeight.SemiBold),
            )
            BasicText("14-bit", style = TextStyle(GlassMuted, 10.sp))
        }
    }
}

fun com.remoteesp32midi.glassmidi.model.MidiMapping.displayLabel(): String =
    when (type) {
        MidiType.NOTE -> "Note ${number} · CH ${channel}"
        MidiType.CC -> if (resolution == MidiResolution.FOURTEEN_BIT) {
            "CC ${number}/${number + 32} · CH ${channel}"
        } else {
            "CC ${number} · CH ${channel}"
        }
    }
