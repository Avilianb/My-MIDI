/*
 * Adapted from Kyant0/AndroidLiquidGlass backdrop-catalog (Apache-2.0), tag 2.0.1.
 * The catalog helper starts on pointer-down and does not consume; this version
 * consumes so the parent LazyVerticalGrid cannot steal the slider drag.
 */
package com.remoteesp32midi.glassmidi.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.util.fastFirstOrNull

/**
 * 按下即开始拖动（不做 touch slop），并立即消费事件，避免被父级滚动容器抢走。
 * 与 catalog 一样，按下时先回调一次 drag(Offset.Zero)，随后按每次位移回调。
 */
internal suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onDrag: (dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        onDragStart(down.position)
        onDrag(Offset.Zero)

        var pointerId = down.id
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.fastFirstOrNull { it.id == pointerId }
                ?: return@awaitEachGesture onDragCancel()

            if (change.changedToUpIgnoreConsumed()) {
                change.consume()
                onDragEnd()
                return@awaitEachGesture
            }
            if (change.isConsumed) {
                onDragCancel()
                return@awaitEachGesture
            }

            val delta = change.positionChange()
            if (delta != Offset.Zero) {
                change.consume()
                onDrag(delta)
            }
            pointerId = change.id
        }
    }
}

/**
 * Passive twin of [inspectDragGestures] for the liquid tab highlight.
 *
 * The same thumb also carries the consuming drag modifier. This recognizer
 * must observe the pointer without consuming, otherwise one of the two
 * modifiers cancels the other.
 */
internal suspend fun PointerInputScope.inspectDragGesturesPassive(
    onDragStart: (down: Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onDrag: (dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onDragStart(down.position)

        var pointerId = down.id
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.fastFirstOrNull { it.id == pointerId }
                ?: return@awaitEachGesture onDragCancel()

            if (change.changedToUpIgnoreConsumed()) {
                onDragEnd()
                return@awaitEachGesture
            }

            val delta = change.positionChange()
            if (delta != Offset.Zero) {
                onDrag(delta)
            }
            pointerId = change.id
        }
    }
}
