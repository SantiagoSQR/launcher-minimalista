package com.valen.launcher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

internal const val maxFanApps = 10

private class FanSpot(val center: Offset, val label: Rect?, val angle: Float, val radius: Float)

/**
 * Favorites spread as rays from the bottom corner under the thumb. Each ray ends in a circle with the
 * app's initial and its name above it. Tap a circle, or press the corner dot, slide toward an app and
 * let go. The layout is a single arc while names fit, then staggers into two radii, and finally keeps
 * only the initials (the name shows while it is under the thumb).
 */
@Composable
internal fun FavoritesFan(
    apps: List<App>, title: (App) -> String, style: TextStyle, leftHanded: Boolean,
    open: (App) -> Unit, edit: (App) -> Unit, modifier: Modifier = Modifier,
    /** Saved displacement of the hub from its corner, as fractions of the area (x mirrored for the left hand). */
    position: Offset = Offset.Zero, onMove: (Offset) -> Unit = {},
) {
    val shown = apps.take(maxFanApps)
    if (shown.isEmpty()) return
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val haptics = LocalHapticFeedback.current
    val labelStyle = style.copy(fontSize = 12.sp)
    var hot by remember { mutableStateOf<Int?>(null) }
    var dragging by remember { mutableStateOf(false) }
    val hubScale by animateFloatAsState(if (dragging) 1.6f else 1f, label = "hub")
    val latestOpen by rememberUpdatedState(open)
    val latestHaptics by rememberUpdatedState(haptics)
    val latestMove by rememberUpdatedState(onMove)
    var moving by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val px = { dp: Float -> dp * density.density }
        val inset = px(28f)
        val corner = Offset(if (leftHanded) inset else w - inset, h - inset)
        // The hub can be dragged anywhere in the lower part of the screen; it starts in the corner.
        fun clampHub(point: Offset) = Offset(point.x.coerceIn(inset, w - inset), point.y.coerceIn(h * .3f, h - inset))
        val direction = if (leftHanded) -1f else 1f
        val savedHub = clampHub(corner + Offset(position.x * w * direction, position.y * h))
        // Not keyed on the saved spot: the gesture below keeps writing to this same state even if the
        // area resizes mid-drag. A newly saved position clears it.
        var draggedHub by remember { mutableStateOf<Offset?>(null) }
        LaunchedEffect(position) { draggedHub = null }
        val hub = draggedHub ?: savedHub
        val circle = px(46f)
        val labelGap = px(4f)
        val maxLabel = px(110f).roundToInt()
        val reachX = if (leftHanded) w - hub.x else hub.x
        val radius = minOf(reachX - px(120f), hub.y - px(40f), px(270f)).coerceAtLeast(px(110f))
        val labels = shown.map { app ->
            measurer.measure(title(app), labelStyle, overflow = TextOverflow.Ellipsis, maxLines = 1,
                constraints = Constraints(maxWidth = maxLabel))
        }

        fun layout(staggered: Boolean, withLabels: Boolean): List<FanSpot> {
            val n = shown.size
            val start = 184f
            val sweep = 81f
            return shown.indices.map { i ->
                // The first favorite sits at the top (most vertical ray), like the list and the curve.
                val degrees = if (n == 1) 225f else start + sweep * (n - 1 - i) / (n - 1)
                val r = if (staggered && i % 2 == 1) radius * .66f else radius
                val rad = degrees * PI.toFloat() / 180f
                val dx = cos(rad) * r
                val center = Offset(if (leftHanded) hub.x - dx else hub.x + dx, hub.y + sin(rad) * r)
                val size = labels[i].size
                // The name continues the ray just past its circle, so neighboring names diverge with
                // the rays instead of stacking; nudged back inside the screen near the edges.
                val ux = if (leftHanded) -cos(rad) else cos(rad)
                val uy = sin(rad)
                val reach = circle / 2f + labelGap + abs(ux) * size.width / 2f + abs(uy) * size.height / 2f
                val labelCenter = center + Offset(ux, uy) * reach
                val left = (labelCenter.x - size.width / 2f).coerceIn(px(6f), (w - px(6f) - size.width).coerceAtLeast(px(6f)))
                val label = if (!withLabels) null else Rect(
                    Offset(left, labelCenter.y - size.height / 2f),
                    androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat())
                )
                FanSpot(center, label, degrees, r)
            }
        }

        fun fits(spots: List<FanSpot>): Boolean {
            val boxes = spots.flatMap { spot ->
                listOfNotNull(Rect(spot.center, circle / 2f + px(3f)), spot.label?.inflate(px(2f)))
            }
            val screen = Rect(0f, 0f, w, h)
            if (boxes.any { it.left < 0 || it.right > screen.right || it.top < 0 }) return false
            val perSpot = spots.map { spot -> listOfNotNull(Rect(spot.center, circle / 2f + px(3f)), spot.label?.inflate(px(2f))) }
            for (a in perSpot.indices) for (b in a + 1 until perSpot.size) {
                if (perSpot[a].any { x -> perSpot[b].any { y -> x.overlaps(y) } }) return false
            }
            return boxes.isNotEmpty()
        }

        val arc = layout(staggered = false, withLabels = true)
        val stagger = layout(staggered = true, withLabels = true)
        val (spots, showLabels) = when {
            fits(arc) -> arc to true
            fits(stagger) -> stagger to true
            else -> layout(staggered = true, withLabels = false) to false
        }

        fun pick(point: Offset): Int? {
            if (hypot(point.x - hub.x, point.y - hub.y) < px(44f)) return null
            val angle = atan2(point.y - hub.y, if (leftHanded) hub.x - point.x else point.x - hub.x)
            return spots.indices.minByOrNull { i ->
                val spot = spots[i]
                val spotAngle = atan2(spot.center.y - hub.y, if (leftHanded) hub.x - spot.center.x else spot.center.x - hub.x)
                abs(angle - spotAngle) * radius + abs(hypot(point.x - spot.center.x, point.y - spot.center.y)) * .25f
            }
        }

        // While the thumb is over an app, it grows and reaches out a little, and its neighbors lean
        // away from it (the closest ones the most), lines and all.
        val animated = spots.mapIndexed { i, spot ->
            key(i) {
                val focus = hot
                val lean = if (focus == null || focus == i) 0f
                    else sign(spot.angle - spots[focus].angle) * 6f / abs(i - focus)
                val angle by animateFloatAsState((spot.angle + lean).coerceIn(180f, 269f), spring(dampingRatio = .7f, stiffness = 500f), label = "angle")
                val reach by animateFloatAsState(if (focus == i) px(12f) else 0f, spring(dampingRatio = .6f, stiffness = 500f), label = "reach")
                val grow by animateFloatAsState(when (focus) { null -> 1f; i -> 1.3f; else -> .92f },
                    spring(dampingRatio = .55f, stiffness = 500f), label = "grow")
                val rad = angle * PI.toFloat() / 180f
                val r = spot.radius + reach
                val dx = cos(rad) * r
                Triple(Offset(if (leftHanded) hub.x - dx else hub.x + dx, hub.y + sin(rad) * r), grow, spot.center)
            }
        }

        val palette = LocalPalette.current
        val line = palette.line.copy(alpha = .56f)
        Canvas(Modifier.fillMaxSize()) {
            animated.forEachIndexed { i, (center, _, _) ->
                val on = hot == i
                drawLine(if (on) palette.accent else line, hub, center, strokeWidth = if (on) px(2f) else px(1f))
            }
            drawCircle(palette.accent.copy(alpha = if (dragging) 1f else .8f), px(7f) * hubScale * (if (moving) 1.5f else 1f), hub)
        }

        spots.forEachIndexed { i, spot ->
            val app = shown[i]
            val on = hot == i
            val name = title(app)
            val (center, grow, base) = animated[i]
            val size = circle * grow
            val sizeDp = with(density) { size.toDp() }
            Box(
                Modifier.offset { IntOffset((center.x - size / 2).roundToInt(), (center.y - size / 2).roundToInt()) }
                    .size(sizeDp)
                    .semantics { contentDescription = name }
                    .combinedClickable(role = Role.Button, onClickLabel = "Abrir", onClick = { open(app) },
                        onLongClickLabel = "Editar aplicación", onLongClick = { edit(app) }),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    if (on) drawCircle(palette.accent)
                    else {
                        drawCircle(palette.raised)
                        drawCircle(palette.line, style = Stroke(px(1f)))
                    }
                }
                BasicText(name.trim().take(1).uppercase(), style = style.copy(
                    fontSize = 16.sp * grow, fontWeight = FontWeight.Medium,
                    color = if (on) palette.onAccent else style.color
                ))
            }
            val label = spot.label ?: if (on) layout(true, true)[i].label else null
            if (label != null && (showLabels || on)) {
                // The name rides along with its circle, kept clear of it as the circle grows.
                val away = (center - hub).let { it / it.getDistance().coerceAtLeast(1f) }
                val shift = center - base + away * ((size - circle) / 2f)
                val x = (label.left + shift.x).coerceIn(px(6f), (w - px(6f) - label.width).coerceAtLeast(px(6f)))
                BasicText(
                    name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.offset { IntOffset(x.roundToInt(), (label.top + shift.y).roundToInt()) }
                        .size(with(density) { label.width.toDp() + 1.dp }, with(density) { label.height.toDp() })
                        .clearAndSetSemantics { }
                        .combinedClickable(onClick = { open(app) }, onLongClick = { edit(app) }),
                    style = labelStyle.copy(color = if (on || hot == null) style.color else style.color.copy(alpha = .6f))
                )
            }
        }

        // Thumb hub: press the dot and slide toward an app, release to open it; or hold it still for a
        // moment and drag to move the whole fan. Keyed on Unit and reading the latest layout through
        // state, so recompositions mid-gesture don't drop the finger.
        val latestSavedHub by rememberUpdatedState(savedHub)
        val latestCorner by rememberUpdatedState(corner)
        val latestSize by rememberUpdatedState(Offset(w, h))
        val latestDirection by rememberUpdatedState(direction)
        val latestClamp by rememberUpdatedState<(Offset) -> Offset>({ point -> clampHub(point) })
        // Lambdas, not function references: two references to the same local function compare equal
        // even when they captured different values, so the state would keep the first (stale) one.
        val latestPick by rememberUpdatedState<(Offset) -> Int?>({ point -> pick(point) })
        val latestHub by rememberUpdatedState(hub)
        val latestShown by rememberUpdatedState(shown)
        Box(
            // The touch target stays at the saved spot during a gesture (only the drawing follows the
            // finger), so pointer positions keep one frame of reference while the fan is being moved.
            Modifier.offset { IntOffset((savedHub.x - px(36f)).roundToInt(), (savedHub.y - px(36f)).roundToInt()) }
                .size(72.dp)
                .semantics { contentDescription = "Selector de favoritos" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        dragging = true
                        try {
                            // Sliding right away picks an app; holding still past the long-press time
                            // moves the fan instead (null means the timeout won).
                            val slid: Boolean? = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                var result: Boolean? = null
                                while (result == null) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                    if (change == null || !change.pressed) result = false
                                    else {
                                        change.consume()
                                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) result = true
                                    }
                                }
                                result
                            }
                            if (slid == null) {
                                moving = true
                                latestHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                val start = latestSavedHub
                                var point = start
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    change.consume()
                                    point = latestClamp(start + (change.position - down.position))
                                    draggedHub = point
                                    if (!change.pressed) break
                                }
                                val size = latestSize
                                val shift = point - latestCorner
                                latestMove(Offset(shift.x / size.x * latestDirection, shift.y / size.y))
                                return@awaitEachGesture
                            }
                            if (slid == false) return@awaitEachGesture
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                                val origin = Offset(latestSavedHub.x - 36.dp.toPx(), latestSavedHub.y - 36.dp.toPx())
                                val next = latestPick(origin + change.position)
                                if (next != hot) {
                                    hot = next
                                    if (next != null) latestHaptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                            val chosen = hot
                            hot = null
                            if (chosen != null) latestShown.getOrNull(chosen)?.let(latestOpen)
                        } finally {
                            dragging = false
                            moving = false
                            hot = null
                        }
                    }
                }
        )
    }
}
