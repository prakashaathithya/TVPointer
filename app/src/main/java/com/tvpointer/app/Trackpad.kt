package com.tvpointer.app

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.hypot
import kotlin.math.max

/**
 * Laptop-style trackpad gestures:
 *  - one finger drag  -> onMove
 *  - one finger tap   -> onClick
 *  - press and hold   -> onLongPress, then drag with the button held, onLongRelease on lift
 *  - two finger drag  -> onScroll
 *  - two finger tap   -> onTwoFingerTap
 * Deltas are in pixels; the caller converts them.
 */
fun Modifier.trackpad(
    longPressMs: Long = 550,
    onMove: (Float, Float) -> Unit,
    onScroll: (Float, Float) -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onLongRelease: () -> Unit,
    onTwoFingerTap: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downTime = down.uptimeMillis
        val slop = viewConfiguration.touchSlop
        var maxPointers = 1
        var moved = false
        var longFired = false
        var travel = 0f

        while (true) {
            val waitingForLongPress = !moved && !longFired && maxPointers == 1
            val event = if (waitingForLongPress) {
                val left = longPressMs - (SystemClock.uptimeMillis() - downTime)
                withTimeoutOrNull(max(left, 1L)) { awaitPointerEvent() }
            } else {
                awaitPointerEvent()
            }
            if (event == null) {
                longFired = true
                onLongPress()
                continue
            }

            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            maxPointers = max(maxPointers, pressed.size)

            val moving = pressed.filter { it.previousPressed }
            if (moving.isNotEmpty()) {
                val dx = moving.map { it.position.x - it.previousPosition.x }.average().toFloat()
                val dy = moving.map { it.position.y - it.previousPosition.y }.average().toFloat()
                travel += hypot(dx, dy)
                if (travel > slop) moved = true
                if (moved) {
                    when {
                        pressed.size >= 2 && !longFired -> onScroll(dx, dy)
                        maxPointers == 1 -> onMove(dx, dy) // also drags after a long press
                    }
                }
            }
            event.changes.forEach { it.consume() }
        }

        if (longFired) {
            onLongRelease()
        } else if (!moved) {
            if (maxPointers >= 2) onTwoFingerTap() else onClick()
        }
    }
}
