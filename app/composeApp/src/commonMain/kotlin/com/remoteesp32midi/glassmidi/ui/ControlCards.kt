package com.remoteesp32midi.glassmidi.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.remoteesp32midi.glassmidi.model.ChannelStrip
import com.remoteesp32midi.glassmidi.model.Control
import com.remoteesp32midi.glassmidi.model.ControlKind
import com.remoteesp32midi.glassmidi.model.MidiResolution
import com.remoteesp32midi.glassmidi.model.MidiType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
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
        val interactionSource = remember(control.id) { MutableInteractionSource() }
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(14.dp),
            ) {
                Box(Modifier.align(Alignment.Center)) {
                    when (control.kind) {
                        ControlKind.BUTTON -> ButtonControl(
                            control = control,
                            backdrop = backdrop,
                        )

                        ControlKind.KNOB -> KnobControl(
                            control = control,
                            backdrop = backdrop,
                            interactionSource = interactionSource,
                        )

                        ControlKind.FADER -> SliderControl(
                            control = control,
                            backdrop = backdrop,
                            interactionSource = interactionSource,
                        )
                    }
                }
            }
            CardValueGesture(
                control = control,
                onPressStart = onPressStart,
                onPressEnd = onPressEnd,
                onValueChange = onContinuousChange,
                onFinished = onContinuousFinished,
                interactionSource = interactionSource,
                sensitivity = 320.dp,
                modifier = Modifier.matchParentSize(),
            )
            ControlCaption(
                control = control,
                compact = false,
                onEdit = onEdit,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(14.dp),
            )
        }
    }
}

@Composable
fun CompactControlCard(
    control: Control,
    backdrop: Backdrop,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onContinuousChange: (Int) -> Unit,
    onContinuousFinished: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    isDropTarget: Boolean = false,
    onDragStart: () -> Unit = {},
    onDrag: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
) {
    Box(
        modifier
            .fillMaxSize()
            .then(
                if (isDropTarget) {
                    Modifier.border(1.5.dp, GlassAccent, RoundedCornerShape(18.dp))
                } else {
                    Modifier
                }
            )
    ) {
        GlassSurface(
            backdrop = backdrop,
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(18.dp),
        ) {
            val interactionSource = remember(control.id) { MutableInteractionSource() }
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                ) {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (control.kind) {
                            ControlKind.BUTTON -> CompactButtonControl(
                                control = control,
                            )

                            ControlKind.KNOB -> CompactKnobControl(
                                control = control,
                                backdrop = backdrop,
                                interactionSource = interactionSource,
                            )

                            ControlKind.FADER -> CompactSliderControl(
                                control = control,
                                backdrop = backdrop,
                                interactionSource = interactionSource,
                            )
                        }
                    }
                }
                CardValueGesture(
                    control = control,
                    onPressStart = onPressStart,
                    onPressEnd = onPressEnd,
                    onValueChange = onContinuousChange,
                    onFinished = onContinuousFinished,
                    interactionSource = interactionSource,
                    sensitivity = 220.dp,
                    modifier = Modifier.matchParentSize(),
                )
                ControlCaption(
                    control = control,
                    compact = true,
                    onEdit = onEdit,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                    onDragStart = onDragStart,
                    onDrag = onDrag,
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel,
                )
            }
        }
    }
}

@Composable
private fun CardValueGesture(
    control: Control,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onValueChange: (Int) -> Unit,
    onFinished: () -> Unit,
    interactionSource: MutableInteractionSource,
    sensitivity: Dp,
    modifier: Modifier = Modifier,
) {
    val unitsPerPixel = with(LocalDensity.current) { 16383f / sensitivity.toPx() }
    val currentValue by rememberUpdatedState(control.value)
    var dragValue by remember(control.id) { mutableFloatStateOf(control.value.toFloat()) }
    val gesture = when (control.kind) {
        ControlKind.BUTTON -> Modifier.pointerInput(control.id) {
            detectTapGestures(
                onPress = {
                    val press = PressInteraction.Press(Offset.Zero)
                    interactionSource.tryEmit(press)
                    onPressStart()
                    try {
                        tryAwaitRelease()
                    } finally {
                        onPressEnd()
                        interactionSource.tryEmit(PressInteraction.Release(press))
                    }
                }
            )
        }

        ControlKind.KNOB -> Modifier.pointerInput(control.id, unitsPerPixel) {
            var press: PressInteraction.Press? = null
            detectVerticalDragGestures(
                onDragStart = {
                    dragValue = currentValue.toFloat()
                    press = PressInteraction.Press(Offset.Zero)
                    interactionSource.tryEmit(press)
                },
                onDragEnd = {
                    onFinished()
                    press?.let { interactionSource.tryEmit(PressInteraction.Release(it)) }
                },
                onDragCancel = {
                    onFinished()
                    press?.let { interactionSource.tryEmit(PressInteraction.Cancel(it)) }
                },
            ) { change, dragAmount ->
                change.consume()
                dragValue = (dragValue - dragAmount * unitsPerPixel).coerceIn(0f, 16383f)
                onValueChange(dragValue.roundToInt())
            }
        }

        ControlKind.FADER -> Modifier.pointerInput(control.id, unitsPerPixel) {
            var press: PressInteraction.Press? = null
            detectHorizontalDragGestures(
                onDragStart = {
                    dragValue = currentValue.toFloat()
                    press = PressInteraction.Press(Offset.Zero)
                    interactionSource.tryEmit(press)
                },
                onDragEnd = {
                    onFinished()
                    press?.let { interactionSource.tryEmit(PressInteraction.Release(it)) }
                },
                onDragCancel = {
                    onFinished()
                    press?.let { interactionSource.tryEmit(PressInteraction.Cancel(it)) }
                },
            ) { change, dragAmount ->
                change.consume()
                dragValue = (dragValue + dragAmount * unitsPerPixel).coerceIn(0f, 16383f)
                onValueChange(dragValue.roundToInt())
            }
        }
    }
    Box(modifier.then(gesture))
}

@Composable
private fun ControlCaption(
    control: Control,
    compact: Boolean,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    onDragStart: () -> Unit = {},
    onDrag: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
) {
    val caption = if (compact) {
        modifier
            .pointerInput(control.id) {
                detectTapGestures(onTap = { onEdit() })
            }
            .pointerInput(control.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragCancel() },
                ) { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            }
    } else {
        modifier.pointerInput(control.id) {
            detectTapGestures(onTap = { onEdit() })
        }
    }
    Row(
        caption,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            control.label,
            style = TextStyle(
                color = GlassText,
                fontSize = if (compact) 8.sp else 12.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(if (compact) 3.dp else 4.dp))
        BasicText(
            "CH ${control.mapping.channel}",
            style = TextStyle(
                color = GlassMuted,
                fontSize = if (compact) 6.sp else 9.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun CompactButtonControl(
    control: Control,
) {
    val isPressed = control.value > 0
    Box(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clip(RoundedCornerShape(13.dp))
            .background(
                if (isPressed) {
                    GlassAccent.copy(alpha = 0.18f)
                } else {
                    Color.Black.copy(alpha = 0.04f)
                }
            ),
    )
}

@Composable
private fun CompactKnobControl(
    control: Control,
    backdrop: Backdrop,
    interactionSource: InteractionSource,
) {
    val animationScope = rememberCoroutineScope()
    val dialBackdrop = rememberLayerBackdrop()
    val dampedDragAnimation = remember(animationScope, control.id) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = control.value.toFloat(),
            valueRange = 0f..16383f,
            visibilityThreshold = 1f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {},
            onDragStopped = {},
            onDrag = { _, _ -> },
        )
    }
    LaunchedEffect(control.value) {
        val value = control.value.toFloat()
        if (dampedDragAnimation.targetValue != value) {
            dampedDragAnimation.updateValue(value)
        }
    }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> dampedDragAnimation.press()
                is PressInteraction.Release, is PressInteraction.Cancel -> dampedDragAnimation.release()
            }
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val diameter = minOf(maxWidth, maxHeight).coerceAtLeast(48.dp)
        Box(
            Modifier.size(diameter),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(dialBackdrop),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val fraction = dampedDragAnimation.progress
                    val stroke = 5.dp.toPx()
                    val inset = stroke / 2f + 2.dp.toPx()
                    val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
                    drawArc(
                        color = Color.Black.copy(alpha = 0.08f),
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
                }
            }
            Box(Modifier.fillMaxSize()) {
                KnobGlassHandle(
                    backdrop = backdrop,
                    dialBackdrop = dialBackdrop,
                    dampedDragAnimation = dampedDragAnimation,
                    dialSize = diameter,
                    stroke = 5.dp,
                    insetExtra = 2.dp,
                    thumbSize = 24.dp,
                )
            }
        }
    }
}

@Composable
private fun CompactSliderControl(
    control: Control,
    backdrop: Backdrop,
    interactionSource: InteractionSource,
) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LiquidSlider(
            value = { control.value.toFloat() },
            valueRange = 0f..16383f,
            visibilityThreshold = 1f,
            backdrop = backdrop,
            interactionSource = interactionSource,
        )
    }
}

@Composable
private fun ButtonControl(
    control: Control,
    backdrop: Backdrop,
) {
    val isPressed = control.value > 0
    GlassSurface(
        backdrop = backdrop,
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        shape = RoundedCornerShape(20.dp),
        tint = if (isPressed) GlassAccent.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.03f),
    ) {
        Box(Modifier.fillMaxSize())
    }
}

@Composable
private fun KnobControl(
    control: Control,
    backdrop: Backdrop,
    interactionSource: InteractionSource,
) {
    val animationScope = rememberCoroutineScope()
    val dialBackdrop = rememberLayerBackdrop()
    val dampedDragAnimation = remember(animationScope, control.id) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = control.value.toFloat(),
            valueRange = 0f..16383f,
            visibilityThreshold = 1f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {},
            onDragStopped = {},
            onDrag = { _, _ -> },
        )
    }
    LaunchedEffect(control.value) {
        val value = control.value.toFloat()
        if (dampedDragAnimation.targetValue != value) {
            dampedDragAnimation.updateValue(value)
        }
    }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> dampedDragAnimation.press()
                is PressInteraction.Release, is PressInteraction.Cancel -> dampedDragAnimation.release()
            }
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(150.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(132.dp),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(dialBackdrop),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val fraction = dampedDragAnimation.progress
                    val stroke = 11.dp.toPx()
                    val inset = stroke / 2f + 5.dp.toPx()
                    val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
                    drawArc(
                        color = Color.Black.copy(alpha = 0.08f),
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
                }
            }
            Box(Modifier.fillMaxSize()) {
                KnobGlassHandle(
                    backdrop = backdrop,
                    dialBackdrop = dialBackdrop,
                    dampedDragAnimation = dampedDragAnimation,
                    dialSize = 132.dp,
                    stroke = 11.dp,
                    insetExtra = 5.dp,
                    thumbSize = 32.dp,
                )
            }
        }
    }
}

@Composable
private fun SliderControl(
    control: Control,
    backdrop: Backdrop,
    interactionSource: InteractionSource,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(150.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        LiquidSlider(
            value = { control.value.toFloat() },
            valueRange = 0f..16383f,
            visibilityThreshold = 1f,
            backdrop = backdrop,
            interactionSource = interactionSource,
        )
    }
}

@Composable
private fun KnobGlassHandle(
    backdrop: Backdrop,
    dialBackdrop: LayerBackdrop,
    dampedDragAnimation: DampedDragAnimation,
    dialSize: Dp,
    stroke: Dp,
    insetExtra: Dp,
    thumbSize: Dp,
) {
    val density = LocalDensity.current
    val thumbPx = with(density) { thumbSize.toPx() }
    val dialPx = with(density) { dialSize.toPx() }
    val strokePx = with(density) { stroke.toPx() }
    val extraPx = with(density) { insetExtra.toPx() }
    val arcRadius = (dialPx - strokePx - extraPx * 2f) / 2f
    val dialRefraction = rememberBackdrop(dialBackdrop) { drawBackdrop ->
        val progress = dampedDragAnimation.pressProgress
        val scaleX = lerp(2f / 3f, 1f, progress)
        val scaleY = lerp(0f, 1f, progress)
        scale(scaleX, scaleY) { drawBackdrop() }
    }
    val combinedBackdrop = rememberCombinedBackdrop(backdrop, dialRefraction)
    Box(
        Modifier
            .size(thumbSize)
            .graphicsLayer {
                val angle =
                    (135f + 270f * dampedDragAnimation.progress) * PI.toFloat() / 180f
                translationX = dialPx / 2f + cos(angle) * arcRadius - thumbPx / 2f
                translationY = dialPx / 2f + sin(angle) * arcRadius - thumbPx / 2f
            }
            .drawBackdrop(
                backdrop = combinedBackdrop,
                shape = { CircleShape },
                effects = {
                    val progress = dampedDragAnimation.pressProgress
                    blur(8f.dp.toPx() * (1f - progress))
                    lens(
                        10f.dp.toPx() * progress,
                        14f.dp.toPx() * progress,
                        chromaticAberration = true,
                    )
                },
                highlight = {
                    val progress = dampedDragAnimation.pressProgress
                    Highlight.Ambient.copy(
                        width = Highlight.Ambient.width / 1.5f,
                        blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                        alpha = progress,
                    )
                },
                shadow = {
                    Shadow(
                        radius = 4f.dp,
                        color = Color.Black.copy(alpha = 0.05f),
                    )
                },
                innerShadow = {
                    val progress = dampedDragAnimation.pressProgress
                    InnerShadow(
                        radius = 4f.dp * progress,
                        alpha = progress,
                    )
                },
                layerBlock = {
                    scaleX = dampedDragAnimation.scaleX
                    scaleY = dampedDragAnimation.scaleY
                    val velocity = dampedDragAnimation.velocity / 10f
                    scaleX /= 1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f)
                    scaleY *= 1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f)
                },
                onDrawSurface = {
                    val progress = dampedDragAnimation.pressProgress
                    drawRect(Color.White.copy(alpha = 1f - progress))
                },
            )
    )
}

@Composable
fun ChannelStripCard(
    strip: ChannelStrip,
    totalStrips: Int,
    backdrop: Backdrop,
    editMode: Boolean,
    landscape: Boolean,
    onPressStart: (String) -> Unit,
    onPressEnd: (String) -> Unit,
    onContinuousChange: (String, Int) -> Unit,
    onContinuousFinished: (String) -> Unit,
    onEditControl: (Control) -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onDeleteStrip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StripHeader(
            strip = strip,
            totalStrips = totalStrips,
            editMode = editMode,
            onMoveLeft = onMoveLeft,
            onMoveRight = onMoveRight,
            onDeleteStrip = onDeleteStrip,
        )

        if (landscape) {
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column(
                    Modifier
                        .weight(0.44f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StripVerticalFaderSlot(
                        control = strip.fader,
                        backdrop = backdrop,
                        editMode = editMode,
                        onValueChange = { onContinuousChange(strip.fader.id, it) },
                        onFinished = { onContinuousFinished(strip.fader.id) },
                        onEdit = { onEditControl(strip.fader) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                    StripLiquidButtonSlot(
                        control = strip.button,
                        fallbackTitle = "Btn",
                        backdrop = backdrop,
                        editMode = editMode,
                        onPressStart = { onPressStart(strip.button.id) },
                        onPressEnd = { onPressEnd(strip.button.id) },
                        onEdit = { onEditControl(strip.button) },
                        modifier = Modifier
                            .height(30.dp)
                            .fillMaxWidth(),
                    )
                }
                Column(
                    Modifier
                        .weight(0.56f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    strip.knobs.forEachIndexed { idx, knob ->
                        StripKnobSlot(
                            control = knob,
                            fallbackTitle = "K${idx + 1}",
                            backdrop = backdrop,
                            editMode = editMode,
                            onValueChange = { onContinuousChange(knob.id, it) },
                            onFinished = { onContinuousFinished(knob.id) },
                            onEdit = { onEditControl(knob) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                    }
                }
            }
        } else {
            StripVerticalFaderSlot(
                control = strip.fader,
                backdrop = backdrop,
                editMode = editMode,
                onValueChange = { onContinuousChange(strip.fader.id, it) },
                onFinished = { onContinuousFinished(strip.fader.id) },
                onEdit = { onEditControl(strip.fader) },
                modifier = Modifier
                    .weight(0.48f)
                    .fillMaxWidth(),
            )
            Column(
                Modifier
                    .weight(0.42f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                strip.knobs.forEachIndexed { idx, knob ->
                    StripKnobSlot(
                        control = knob,
                        fallbackTitle = "K${idx + 1}",
                        backdrop = backdrop,
                        editMode = editMode,
                        onValueChange = { onContinuousChange(knob.id, it) },
                        onFinished = { onContinuousFinished(knob.id) },
                        onEdit = { onEditControl(knob) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }
            }
            StripLiquidButtonSlot(
                control = strip.button,
                fallbackTitle = "Btn",
                backdrop = backdrop,
                editMode = editMode,
                onPressStart = { onPressStart(strip.button.id) },
                onPressEnd = { onPressEnd(strip.button.id) },
                onEdit = { onEditControl(strip.button) },
                modifier = Modifier
                    .height(32.dp)
                    .fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StripHeader(
    strip: ChannelStrip,
    totalStrips: Int,
    editMode: Boolean,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onDeleteStrip: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (editMode) Arrangement.SpaceBetween else Arrangement.Center,
    ) {
        if (editMode) {
            BasicText(
                "‹",
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(enabled = strip.index > 0, onClick = onMoveLeft)
                    .padding(horizontal = 3.dp),
                style = TextStyle(
                    color = if (strip.index > 0) GlassAccent else GlassMuted.copy(alpha = 0.35f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
        BasicText(
            strip.title,
            style = TextStyle(
                color = GlassText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
        if (editMode) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                BasicText(
                    "›",
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(
                            enabled = strip.index < totalStrips - 1,
                            onClick = onMoveRight,
                        )
                        .padding(horizontal = 3.dp),
                    style = TextStyle(
                        color = if (strip.index < totalStrips - 1) GlassAccent else GlassMuted.copy(alpha = 0.35f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                if (totalStrips > 1) {
                    BasicText(
                        "×",
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(onClick = onDeleteStrip)
                            .padding(horizontal = 3.dp),
                    style = TextStyle(
                            color = GlassDanger,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun StripVerticalFaderSlot(
    control: Control,
    backdrop: Backdrop,
    editMode: Boolean,
    onValueChange: (Int) -> Unit,
    onFinished: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember(control.id) { MutableInteractionSource() }
    val unitsPerPixel = with(LocalDensity.current) { 16383f / 350.dp.toPx() }
    val currentValue by rememberUpdatedState(control.value)
    var dragValue by remember(control.id) { mutableFloatStateOf(control.value.toFloat()) }
    var isDragging by remember(control.id) { mutableStateOf(false) }

    LaunchedEffect(control.value, isDragging) {
        if (!isDragging) {
            dragValue = control.value.toFloat()
        }
    }

    val gestureModifier = if (editMode) {
        Modifier.pointerInput(control.id, true) {
            detectTapGestures(onTap = { onEdit() })
        }
    } else {
        Modifier.pointerInput(control.id, unitsPerPixel, false) {
            var press: PressInteraction.Press? = null
            inspectDragGestures(
                onDragStart = {
                    isDragging = true
                    dragValue = currentValue.toFloat()
                    val p = PressInteraction.Press(Offset.Zero)
                    press = p
                    interactionSource.tryEmit(p)
                },
                onDragEnd = {
                    isDragging = false
                    onFinished()
                    press?.let { interactionSource.tryEmit(PressInteraction.Release(it)) }
                },
                onDragCancel = {
                    isDragging = false
                    onFinished()
                    press?.let { interactionSource.tryEmit(PressInteraction.Cancel(it)) }
                },
            ) { delta ->
                if (delta.y != 0f) {
                    dragValue = (dragValue - delta.y * unitsPerPixel).coerceIn(0f, 16383f)
                    onValueChange(dragValue.roundToInt())
                }
            }
        }
    }
    val customLabel = control.customStripLabel("Fader")
    val bottomReserve = if (editMode || customLabel != null) 16.dp else 6.dp
    Box(
        modifier
            .then(gestureModifier)
            .padding(horizontal = 2.dp, vertical = 2.dp),
    ) {
        // Ableton Live 音量推子 dB 刻度（0 dB Unity Gain 位于 85% 行程，即 CC 108 / 14-bit 13926）
        AbletonFaderScale(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 6.dp, bottom = bottomReserve),
        )

        Box(
            Modifier
                .fillMaxSize()
                .padding(top = 6.dp, bottom = bottomReserve),
            contentAlignment = Alignment.Center,
        ) {
            VerticalLiquidSlider(
                value = { dragValue },
                valueRange = 0f..16383f,
                visibilityThreshold = 1f,
                backdrop = backdrop,
                interactionSource = interactionSource,
                modifier = Modifier.fillMaxHeight(),
            )
        }

        val caption = if (editMode) {
            customLabel ?: "改名"
        } else {
            customLabel
        }
        if (caption != null) {
            BasicText(
                caption,
                Modifier.align(Alignment.BottomCenter),
                style = TextStyle(
                    color = if (editMode) GlassAccent else GlassMuted,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class AbletonDbMark(
    val progress: Float,
    val label: String?,
    val isZeroDb: Boolean = false,
)

private val ABLETON_DB_MARKS = listOf(
    AbletonDbMark(progress = 1.00f, label = "+6"),
    AbletonDbMark(progress = 0.85f, label = "0", isZeroDb = true),
    AbletonDbMark(progress = 0.70f, label = "-6"),
    AbletonDbMark(progress = 0.55f, label = "-12"),
    AbletonDbMark(progress = 0.43f, label = null),
    AbletonDbMark(progress = 0.33f, label = "-24"),
    AbletonDbMark(progress = 0.19f, label = "-36"),
    AbletonDbMark(progress = 0.10f, label = null),
    AbletonDbMark(progress = 0.00f, label = "-∞"),
)

private val ABLETON_LABELED_MARKS = ABLETON_DB_MARKS.filter { it.label != null }

@Composable
private fun AbletonFaderScale(modifier: Modifier = Modifier) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val centerX = size.width / 2f
            val trackGap = 8.dp.toPx()
            for (mark in ABLETON_DB_MARKS) {
                val y = size.height * (1f - mark.progress)
                val tickLen = when {
                    mark.isZeroDb -> 6.dp.toPx()
                    mark.label != null -> 4.dp.toPx()
                    else -> 2.5.dp.toPx()
                }
                val strokeWidth = if (mark.isZeroDb) 1.4.dp.toPx() else 1.dp.toPx()
                val color = when {
                    mark.isZeroDb -> GlassAccent.copy(alpha = 0.55f)
                    mark.label != null -> Color.Black.copy(alpha = 0.15f)
                    else -> Color.Black.copy(alpha = 0.08f)
                }
                drawLine(
                    color = color,
                    start = Offset(centerX - trackGap - tickLen, y),
                    end = Offset(centerX - trackGap, y),
                    strokeWidth = strokeWidth,
                )
                drawLine(
                    color = color,
                    start = Offset(centerX + trackGap, y),
                    end = Offset(centerX + trackGap + tickLen, y),
                    strokeWidth = strokeWidth,
                )
            }
        }

        Layout(
            content = {
                for (mark in ABLETON_LABELED_MARKS) {
                    BasicText(
                        text = mark.label.orEmpty(),
                        style = TextStyle(
                            color = if (mark.isZeroDb) {
                                GlassAccent
                            } else {
                                GlassMuted.copy(alpha = 0.68f)
                            },
                            fontSize = if (mark.isZeroDb) 7.5.sp else 6.5.sp,
                            fontWeight = if (mark.isZeroDb) FontWeight.Bold else FontWeight.Medium,
                        ),
                        maxLines = 1,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val placeables = measurables.map { it.measure(loose) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                val labelStartX = (constraints.maxWidth / 2) + 15.dp.roundToPx()
                if (labelStartX >= constraints.maxWidth - 6.dp.roundToPx()) return@layout
                var lastBottom = -10_000
                placeables.forEachIndexed { idx, placeable ->
                    val mark = ABLETON_LABELED_MARKS[idx]
                    val centerY = (constraints.maxHeight * (1f - mark.progress)).roundToInt()
                    val topY = (centerY - placeable.height / 2)
                        .coerceIn(0, (constraints.maxHeight - placeable.height).coerceAtLeast(0))
                    if (mark.isZeroDb || topY >= lastBottom + 1) {
                        placeable.placeRelative(
                            x = labelStartX.coerceAtMost(constraints.maxWidth - placeable.width),
                            y = topY,
                        )
                        lastBottom = topY + placeable.height
                    }
                }
            }
        }
    }
}

/**
 * 通道条内的紧凑型旋钮槽位：
 * - 按下立即锁定，手指可在全屏高度上下滑动调整；
 * - 按住期间整个旋钮槽位浮现与底部 Dock 相同的液态玻璃折射高亮球，提示已锁定可修改。
 */
@Composable
private fun StripKnobSlot(
    control: Control,
    fallbackTitle: String,
    backdrop: Backdrop,
    editMode: Boolean,
    onValueChange: (Int) -> Unit,
    onFinished: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animationScope = rememberCoroutineScope()
    val interactionSource = remember(control.id) { MutableInteractionSource() }
    val unitsPerPixel = with(LocalDensity.current) { 16383f / 300.dp.toPx() }
    val currentValue by rememberUpdatedState(control.value)
    var dragValue by remember(control.id) { mutableFloatStateOf(control.value.toFloat()) }
    var isDragging by remember(control.id) { mutableStateOf(false) }

    val dampedDragAnimation = remember(animationScope, control.id) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = control.value.toFloat(),
            valueRange = 0f..16383f,
            visibilityThreshold = 1f,
            initialScale = 0.86f,
            pressedScale = 1.0f,
            onDragStarted = {},
            onDragStopped = {},
            onDrag = { _, _ -> },
        )
    }
    LaunchedEffect(control.value, isDragging) {
        if (!isDragging) {
            val value = control.value.toFloat()
            dragValue = value
            if (dampedDragAnimation.targetValue != value) {
                dampedDragAnimation.updateValue(value)
            }
        }
    }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> dampedDragAnimation.press()
                is PressInteraction.Release, is PressInteraction.Cancel -> dampedDragAnimation.release()
            }
        }
    }

    val gestureModifier = if (editMode) {
        Modifier.pointerInput(control.id, true) {
            detectTapGestures(onTap = { onEdit() })
        }
    } else {
        Modifier.pointerInput(control.id, unitsPerPixel, false) {
            var press: PressInteraction.Press? = null
            inspectDragGestures(
                onDragStart = {
                    isDragging = true
                    dragValue = currentValue.toFloat()
                    val p = PressInteraction.Press(Offset.Zero)
                    press = p
                    interactionSource.tryEmit(p)
                },
                onDragEnd = {
                    isDragging = false
                    onFinished()
                    press?.let { interactionSource.tryEmit(PressInteraction.Release(it)) }
                },
                onDragCancel = {
                    isDragging = false
                    onFinished()
                    press?.let { interactionSource.tryEmit(PressInteraction.Cancel(it)) }
                },
            ) { delta ->
                if (delta.y != 0f) {
                    dragValue = (dragValue - delta.y * unitsPerPixel).coerceIn(0f, 16383f)
                    dampedDragAnimation.updateValue(dragValue)
                    onValueChange(dragValue.roundToInt())
                }
            }
        }
    }

    val showGlassHighlight by remember(editMode) {
        derivedStateOf { !editMode && dampedDragAnimation.pressProgress > 0.001f }
    }

    Box(
        modifier.then(gestureModifier),
        contentAlignment = Alignment.Center,
    ) {
        // 仅在按住旋钮时挂载液态玻璃高亮球（derivedStateOf 仅在按下/松开边界翻转一次，空闲与编辑态零开销）
        if (showGlassHighlight) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 3.dp, vertical = 2.dp)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            val minDim = size.minDimension
                            val glass = 0.4f + 0.6f * progress
                            lens(
                                refractionHeight = minDim * 0.18f * glass,
                                refractionAmount = minDim * 0.28f * glass,
                                chromaticAberration = true,
                            )
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Default.copy(alpha = (0.45f + 0.55f * progress) * progress)
                        },
                        shadow = {
                            val progress = dampedDragAnimation.pressProgress
                            Shadow(
                                radius = 6f.dp * progress,
                                alpha = progress * 0.55f,
                            )
                        },
                        innerShadow = {
                            val progress = dampedDragAnimation.pressProgress
                            InnerShadow(
                                radius = 5f.dp * progress,
                                alpha = progress,
                            )
                        },
                        layerBlock = {
                            val progress = dampedDragAnimation.pressProgress
                            alpha = progress
                            val velocity = dampedDragAnimation.velocity / 10f
                            scaleX = dampedDragAnimation.scaleX * (1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f))
                            scaleY = dampedDragAnimation.scaleY / (1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f))
                        },
                        onDrawSurface = {
                            val progress = dampedDragAnimation.pressProgress
                            drawRect(
                                GlassAccent.copy(alpha = 0.08f * progress),
                            )
                        },
                    ),
            )
        }

        // 居中旋钮表盘与数值显示：在 Canvas 内取 min(宽, 高, 36.dp) 绘制正圆，绝不撑破槽位
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp, vertical = 2.dp)
                .graphicsLayer {
                    val contentScale = lerp(1f, 1.08f, dampedDragAnimation.pressProgress)
                    scaleX = contentScale
                    scaleY = contentScale
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val fraction = dampedDragAnimation.progress
                    val diameter = minOf(size.width, size.height, 36.dp.toPx())
                    val stroke = (diameter * 0.11f).coerceIn(3.2.dp.toPx(), 4.2.dp.toPx())
                    val radius = ((diameter - stroke) / 2f - 1.5.dp.toPx()).coerceAtLeast(4f)
                    val arcTopLeft = Offset(center.x - radius, center.y - radius)
                    val arcSize = Size(radius * 2f, radius * 2f)
                    drawArc(
                        color = Color.Black.copy(alpha = 0.11f),
                        startAngle = 135f,
                        sweepAngle = 270f,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = GlassAccent,
                        startAngle = 135f,
                        sweepAngle = 270f * fraction,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                    val angleRad = ((135f + 270f * fraction) * PI / 180.0).toFloat()
                    val dotCenter = Offset(
                        x = center.x + cos(angleRad) * radius,
                        y = center.y + sin(angleRad) * radius,
                    )
                    drawCircle(
                        color = Color.White,
                        radius = stroke * 0.60f,
                        center = dotCenter,
                    )
                    drawCircle(
                        color = GlassAccent,
                        radius = stroke * 0.36f,
                        center = dotCenter,
                    )
                }
                BasicText(
                    "${(dampedDragAnimation.progress * 100f).roundToInt()}",
                    style = TextStyle(
                        color = if (isDragging) GlassAccent else GlassText,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
            val customTag = control.customStripLabel(fallbackTitle)
            val tag = if (editMode) {
                customTag ?: fallbackTitle
            } else {
                customTag
            }
            if (tag != null) {
                BasicText(
                    tag,
                    style = TextStyle(
                        color = if (editMode || isDragging) GlassAccent else GlassMuted,
                        fontSize = 7.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 每个通道条底部的独立液态玻璃按钮：
 * 采用液态玻璃折射胶囊 + 按下缩放与高光反馈。
 */
@Composable
private fun StripLiquidButtonSlot(
    control: Control,
    fallbackTitle: String,
    backdrop: Backdrop,
    editMode: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isPressed = control.value > 0
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        label = "stripButtonScale",
    )
    val gestureModifier = if (editMode) {
        Modifier.pointerInput(control.id, true) {
            detectTapGestures(onTap = { onEdit() })
        }
    } else {
        Modifier.pointerInput(control.id, false) {
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
        }
    }
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(
                when {
                    isPressed -> GlassAccent.copy(alpha = 0.18f)
                    else -> Color.White.copy(alpha = 0.72f)
                },
            )
            .border(
                width = if (isPressed || editMode) 1.dp else 0.5.dp,
                color = when {
                    isPressed -> GlassAccent
                    editMode -> GlassAccent.copy(alpha = 0.35f)
                    else -> Color.Black.copy(alpha = 0.08f)
                },
                shape = shape,
            )
            .then(gestureModifier)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            control.shortStripLabel(fallbackTitle),
            style = TextStyle(
                color = when {
                    isPressed || editMode -> GlassAccent
                    else -> GlassText
                },
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun Control.shortStripLabel(fallback: String): String {
    val trimmed = label.replace(Regex("^CH\\s+\\d+\\s+"), "").trim()
    return trimmed.ifBlank { fallback }
}

private fun Control.customStripLabel(defaultShortLabel: String): String? {
    val short = shortStripLabel(defaultShortLabel)
    return short.takeUnless { it.equals(defaultShortLabel, ignoreCase = true) }
}
private fun Control.shortMappingTag(): String = when (mapping.type) {
    MidiType.NOTE -> "N${mapping.number}"
    MidiType.CC -> if (mapping.resolution == MidiResolution.FOURTEEN_BIT) {
        "CC${mapping.number}"
    } else {
        "cc${mapping.number}"
    }
    MidiType.PITCH_BEND -> "PB"
}

