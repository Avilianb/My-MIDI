/*
 * Ported from Kyant0/AndroidLiquidGlass backdrop-catalog (Apache-2.0), tag 2.0.1.
 * Visuals/effects are copied from the catalog; gesture/value driving is adapted
 * for this app's light theme and LazyVerticalGrid.
 */
package com.remoteesp32midi.glassmidi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
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
import kotlinx.coroutines.flow.collectLatest

@Composable
fun LiquidSlider(
    value: () -> Float,
    valueRange: ClosedFloatingPointRange<Float>,
    visibilityThreshold: Float,
    backdrop: Backdrop,
    interactionSource: InteractionSource,
    modifier: Modifier = Modifier
) {
    // 白色背景主题：浅灰轨道 + 深蓝强调色。
    val accentColor = GlassAccent
    val trackColor = Color(0xFF787880).copy(0.22f)

    val trackBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        val trackWidth = constraints.maxWidth

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        val currentValue by rememberUpdatedState(value)
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = value(),
                valueRange = valueRange,
                visibilityThreshold = visibilityThreshold,
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStarted = {},
                onDragStopped = {},
                onDrag = { _, _ -> }
            )
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentValue() }
                .collectLatest { value ->
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

        Box(Modifier.layerBackdrop(trackBackdrop)) {
            Box(
                Modifier
                    .clip(Capsule())
                    .background(trackColor)
                    .height(6f.dp)
                    .fillMaxWidth()
            )

            Box(
                Modifier
                    .clip(Capsule())
                    .background(accentColor)
                    .height(6f.dp)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val width = (constraints.maxWidth * dampedDragAnimation.progress).fastRoundToInt()
                        layout(width, placeable.height) {
                            placeable.place(0, 0)
                        }
                    }
            )
        }

        Box(
            Modifier
                .graphicsLayer {
                    translationX =
                        (-size.width / 2f + trackWidth * dampedDragAnimation.progress)
                            .fastCoerceIn(-size.width / 4f, trackWidth - size.width * 3f / 4f) * if (isLtr) 1f else -1f
                }
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        backdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val progress = dampedDragAnimation.pressProgress
                            val scaleX = lerp(2f / 3f, 1f, progress)
                            val scaleY = lerp(0f, 1f, progress)
                            scale(scaleX, scaleY) {
                                drawBackdrop()
                            }
                        }
                    ),
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        blur(8f.dp.toPx() * (1f - progress))
                        lens(
                            10f.dp.toPx() * progress,
                            14f.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = progress
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 4f.dp,
                            color = Color.Black.copy(alpha = 0.14f)
                        )
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 4f.dp * progress,
                            alpha = progress
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(Color.White.copy(alpha = 1f - progress))
                    }
                )
                .size(40f.dp, 24f.dp)
        )
    }
}

@Composable
fun VerticalLiquidSlider(
    value: () -> Float,
    valueRange: ClosedFloatingPointRange<Float>,
    visibilityThreshold: Float,
    backdrop: Backdrop,
    interactionSource: InteractionSource,
    modifier: Modifier = Modifier,
) {
    val accentColor = GlassAccent
    val trackColor = Color(0xFF787880).copy(0.22f)
    val trackBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier.fillMaxHeight(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val trackHeight = constraints.maxHeight
        val animationScope = rememberCoroutineScope()
        val currentValue by rememberUpdatedState(value)
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = value(),
                valueRange = valueRange,
                visibilityThreshold = visibilityThreshold,
                initialScale = 1f,
                pressedScale = 1.45f,
                onDragStarted = {},
                onDragStopped = {},
                onDrag = { _, _ -> },
            )
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentValue() }
                .collectLatest { target ->
                    if (dampedDragAnimation.targetValue != target) {
                        dampedDragAnimation.updateValue(target)
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

        Box(
            Modifier
                .fillMaxHeight()
                .layerBackdrop(trackBackdrop),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier
                    .clip(Capsule())
                    .background(trackColor)
                    .width(6f.dp)
                    .fillMaxHeight(),
            )

            Box(
                Modifier
                    .clip(Capsule())
                    .background(accentColor)
                    .width(6f.dp)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val height = (constraints.maxHeight * dampedDragAnimation.progress).fastRoundToInt()
                        layout(placeable.width, height) {
                            placeable.place(0, 0)
                        }
                    },
            )
        }

        Box(
            Modifier
                .graphicsLayer {
                    val yFromTop = trackHeight * (1f - dampedDragAnimation.progress)
                    translationY = (-size.height / 2f + yFromTop)
                        .fastCoerceIn(-size.height / 4f, trackHeight - size.height * 3f / 4f)
                }
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        backdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val progress = dampedDragAnimation.pressProgress
                            val scaleX = lerp(0f, 1f, progress)
                            val scaleY = lerp(2f / 3f, 1f, progress)
                            scale(scaleX, scaleY) {
                                drawBackdrop()
                            }
                        },
                    ),
                    shape = { Capsule() },
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
                            color = Color.Black.copy(alpha = 0.14f),
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
                        scaleY /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleX *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(Color.White.copy(alpha = 1f - progress))
                    },
                )
                .size(34f.dp, 22f.dp),
        )
    }
}
