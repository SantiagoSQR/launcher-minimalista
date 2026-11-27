package com.valen.launcher

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** A gesture-driven value (page progress) that can follow a finger and then settle with a spring. */
@Stable
internal class Motion(private val scope: CoroutineScope) {
    var value by mutableFloatStateOf(0f)
        private set
    private var job: Job? = null
    /** Where the value is headed: the running animation's target, or the value itself when at rest. */
    var target = 0f
        private set

    val settling get() = job?.isActive == true

    fun snap(next: Float) {
        job?.cancel()
        target = next
        value = next
    }

    /** Animates to [next]; a request for the target already being animated keeps the running spring. */
    fun animateTo(next: Float, velocity: Float = 0f, done: () -> Unit = {}) {
        if (next == target && (settling || value == next)) return
        job?.cancel()
        target = next
        job = scope.launch {
            animate(value, next, velocity, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)) { v, _ -> value = v }
            done()
        }
    }
}

internal enum class Axis { Horizontal, Vertical }

/**
 * Locks a drag to one axis after touch slop. Runs in the main pass, so a child that consumed the
 * gesture (a scrolling list, the favorites fan) keeps it. [accept] decides whether to claim a drag
 * given its axis and initial direction.
 */
@Composable
internal fun Modifier.pageDrag(
    accept: (Axis, Offset) -> Boolean,
    onDrag: (Axis, Offset) -> Unit,
    onEnd: (Axis, velocity: Offset) -> Unit,
): Modifier {
    val latestAccept = rememberUpdatedState(accept)
    val latestDrag = rememberUpdatedState(onDrag)
    val latestEnd = rememberUpdatedState(onEnd)
    return pageDragInput({ a, o -> latestAccept.value(a, o) }, { a, o -> latestDrag.value(a, o) }, { a, v -> latestEnd.value(a, v) })
}

private fun Modifier.pageDragInput(
    accept: (Axis, Offset) -> Boolean,
    onDrag: (Axis, Offset) -> Unit,
    onEnd: (Axis, velocity: Offset) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        val tracker = VelocityTracker()
        tracker.addPointerInputChange(down)
        var total = Offset.Zero
        var axis: Axis? = null
        while (axis == null) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed || change.isConsumed) return@awaitEachGesture
            tracker.addPointerInputChange(change)
            total += change.positionChange()
            if (abs(total.x) > slop || abs(total.y) > slop) {
                val candidate = if (abs(total.x) > abs(total.y)) Axis.Horizontal else Axis.Vertical
                if (!accept(candidate, total)) return@awaitEachGesture
                change.consume()
                axis = candidate
            }
        }
        onDrag(axis, total)
        // Follow the finger until it lifts, including the movement carried by the final up event.
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            tracker.addPointerInputChange(change)
            val delta = change.positionChange()
            if (delta != Offset.Zero) onDrag(axis, delta)
            change.consume()
            if (!change.pressed) break
        }
        val velocity = tracker.calculateVelocity()
        onEnd(axis, Offset(velocity.x, velocity.y))
    }
}
