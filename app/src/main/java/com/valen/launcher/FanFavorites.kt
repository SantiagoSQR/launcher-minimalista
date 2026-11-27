package com.valen.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*

private val triggerSize = 56.dp
private val nodeSize = 50.dp
private val labelWidth = 84.dp
private val labelGap = 8.dp


private fun fanSlots(width: Float, height: Float, count: Int, leftHanded: Boolean): List<Offset> {
    // Centers follow the thumb trace from the user's portrait screenshot.
    val trace = listOf(Offset(.699f, .49f), Offset(.56f, .526f), Offset(.431f, .58f),
        Offset(.321f, .648f), Offset(.25f, .722f))
    if (width < triggerSize.value || height < triggerSize.value) return emptyList()
    val radius = nodeSize.value / 2
    for (size in minOf(5, count) downTo 1) {
        val spots = List(size) { index ->
            val t = if (size == 1) 4f else 4f * index / (size - 1)
            val segment = t.toInt().coerceAtMost(3)
            val point = trace[segment] + (trace[segment + 1] - trace[segment]) * (t - segment)
            Offset(width * (if (leftHanded) 1f - point.x else point.x), height * point.y)
        }
        val last = spots.last()
        val switch = Offset(width - last.x, last.y)
        if (spots.all {
                it.x in radius..(width - radius) && it.y in radius..(height - radius) &&
                    (it - switch).getDistance() >= radius + triggerSize.value / 2 + 4f
            } && spots.zipWithNext().all { (first, next) ->
                (first - next).getDistance() >= nodeSize.value + 2f
            }
        ) return spots
    }
    return emptyList()
}

@Composable
internal fun FanFavorites(
    apps: List<App>, title: (App) -> String, style: TextStyle, leftHanded: Boolean,
    modifier: Modifier, position: Offset, onMove: (Offset) -> Unit, onOpen: (App) -> Unit, onEdit: (App) -> Unit, onSwitchHand: () -> Unit
) {
    BoxWithConstraints(modifier) {
        val palette = LocalPalette.current
        val haptics = LocalHapticFeedback.current
        val measurer = rememberTextMeasurer()
        val screenDensity = LocalDensity.current
        val availableWidth = maxWidth
        val label = style.copy(fontSize = 13.sp, lineHeight = 16.sp,
            textAlign = if (leftHanded) TextAlign.End else TextAlign.Start)
        val width = maxWidth.value
        val height = maxHeight.value
        val slots = remember(width, height, apps.size, leftHanded) {
            fanSlots(width, height, apps.size, leftHanded)
        }
        if (slots.isEmpty()) return@BoxWithConstraints
        val last = slots.last()
        val trigger = Offset(width - last.x, last.y)
        val halfTrigger = triggerSize.value / 2
        val radius = nodeSize.value / 2
        val minX = maxOf(radius - slots.minOf { it.x }, halfTrigger - trigger.x)
        val maxX = minOf(width - radius - slots.maxOf { it.x }, width - halfTrigger - trigger.x)
        val minY = maxOf(radius - slots.minOf { it.y }, halfTrigger - trigger.y)
        val maxY = minOf(height - radius - slots.maxOf { it.y }, height - halfTrigger - trigger.y)
        fun constrain(offset: Offset) = Offset(offset.x.coerceIn(minX, maxX), offset.y.coerceIn(minY, maxY))

        val direction = if (leftHanded) -1f else 1f
        val savedOffset = constrain(Offset(position.x * width * direction, position.y * height))
        var dragOffset by remember(slots, height, savedOffset) { mutableStateOf<Offset?>(null) }
        val offset = dragOffset ?: savedOffset
        val triggerX = (trigger.x + offset.x).dp - triggerSize / 2
        val triggerY = (trigger.y + offset.y).dp - triggerSize / 2
        val savePosition by rememberUpdatedState(onMove)
        var highlighted by remember(slots) { mutableIntStateOf(-1) }
        val latestOpen by rememberUpdatedState(onOpen)
        val switchHand by rememberUpdatedState(onSwitchHand)

        fun select(index: Int) {
            highlighted = -1
            if (index in slots.indices) latestOpen(apps[index])
        }

        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.matchParentSize()) {
                val points = slots.map { it + offset }.map { Offset(it.x.dp.toPx(), it.y.dp.toPx()) }
                if (points.size > 1) {
                    val path = Path().apply {
                        moveTo(points.first().x, points.first().y)
                        points.drop(1).dropLast(1).forEachIndexed { index, point ->
                            val midpoint = (point + points[index + 2]) / 2f
                            quadraticBezierTo(point.x, point.y, midpoint.x, midpoint.y)
                        }
                        lineTo(points.last().x, points.last().y)
                    }
                    drawPath(path, palette.line.copy(alpha = .56f), style = Stroke(width = 1.dp.toPx()))
                }
            }
            // Names sit beside their circle, centered on it. Two-line names can reach the neighbor's, so
            // measure them and nudge overlapping pairs apart vertically until they clear each other.
            val gap = nodeSize / 2 + labelGap
            val labelBoxes = slots.mapIndexed { index, slot ->
                val center = slot + offset
                val labelRoom = if (leftHanded) center.x.dp - gap else availableWidth - center.x.dp - gap
                val nameWidth = labelWidth.coerceAtMost(labelRoom.coerceAtLeast(0.dp))
                val nameX = if (leftHanded) center.x.dp - gap - nameWidth else center.x.dp + gap
                val measured = measurer.measure(title(apps[index]), label, overflow = TextOverflow.Ellipsis, maxLines = 2,
                    constraints = Constraints(maxWidth = with(screenDensity) { nameWidth.roundToPx() }.coerceAtLeast(0)))
                val textHeight = with(screenDensity) { measured.size.height.toDp() }
                floatArrayOf(nameX.value, nameWidth.value, center.y - textHeight.value / 2f, textHeight.value)
            }
            val nodeReach = radius + 3f
            repeat(8) {
                // Keep every name off the other circles: push it away from the circle it overlaps.
                labelBoxes.forEachIndexed { i, box ->
                    slots.forEachIndexed { j, slot ->
                        if (i == j) return@forEachIndexed
                        val node = slot + offset
                        val besides = box[0] < node.x + nodeReach && node.x - nodeReach < box[0] + box[1]
                        if (!besides) return@forEachIndexed
                        val top = node.y - nodeReach
                        val bottom = node.y + nodeReach
                        if (box[2] < bottom && top < box[2] + box[3]) {
                            if (box[2] + box[3] / 2f >= node.y) box[2] = bottom else box[2] = top - box[3]
                        }
                    }
                }
                for (a in labelBoxes.indices) for (b in a + 1 until labelBoxes.size) {
                    val first = labelBoxes[a]
                    val second = labelBoxes[b]
                    val sideBySide = first[0] < second[0] + second[1] && second[0] < first[0] + first[1]
                    val upper = if (first[2] <= second[2]) first else second
                    val lower = if (upper === first) second else first
                    val overlap = upper[2] + upper[3] + 3f - lower[2]
                    if (sideBySide && overlap > 0f) {
                        upper[2] -= overlap / 2f
                        lower[2] += overlap / 2f
                    }
                }
            }
            slots.forEachIndexed { index, slot ->
                val center = slot + offset
                val app = apps[index]
                val selected = index == highlighted
                val name = title(app)
                val letters = remember(name) {
                    val codePoints = name.codePoints().filter { Character.isLetter(it) }.limit(2).toArray()
                    String(codePoints, 0, codePoints.size).uppercase()
                }
                val (nameX, nameWidth, nameTop) = labelBoxes[index]
                BasicText(name, Modifier.offset(nameX.dp, nameTop.dp).width(nameWidth.dp)
                    .clearAndSetSemantics {}, style = label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FanNode(letters, selected, style, Modifier
                    .offset(center.x.dp - nodeSize / 2, center.y.dp - nodeSize / 2)
                    .combinedClickable(role = Role.Button, onLongClickLabel = "Editar aplicación",
                        onClick = { select(index) }, onLongClick = { select(-1); onEdit(app) })
                    .semantics { contentDescription = name })

            }
        }

        Box(
            Modifier.offset {
                IntOffset(triggerX.roundToPx(), triggerY.roundToPx())
            }.size(triggerSize)
                .background(palette.accent, CircleShape)
                .semantics {
                    role = Role.Button
                    contentDescription = if (leftHanded) "Cambiar a modo diestro" else "Cambiar a modo zurdo"
                    onClick { switchHand(); true }
                }
                .pointerInput(apps, slots, savedOffset, width, height, leftHanded) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        var dragged = false
                        var moving = false
                        var committed = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.isConsumed) break
                                if (!dragged && !moving &&
                                    change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                                ) {
                                    moving = true
                                    dragOffset = savedOffset
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                if (moving) {
                                    dragOffset = constrain((dragOffset ?: savedOffset) + change.positionChange() / density)
                                } else {
                                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                        dragged = true
                                    }
                                    if (dragged) {
                                        val point = trigger + savedOffset - Offset(halfTrigger, halfTrigger) +
                                            change.position / density
                                        val next = slots.indexOfFirst {
                                            (it + savedOffset - point).getDistance() <= radius
                                        }
                                        if (next != highlighted && next >= 0) {
                                            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                        }
                                        highlighted = next
                                    }
                                }
                                change.consume()
                                if (!change.pressed) {
                                    when {
                                        moving -> dragOffset?.let {
                                            committed = true
                                            savePosition(Offset(it.x / width * direction, it.y / height))
                                        }
                                        dragged -> select(highlighted)
                                        else -> switchHand()
                                    }
                                    break
                                }
                            }
                        } finally {
                            if (!committed) dragOffset = null
                            highlighted = -1
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            BasicText(if (leftHanded) "→" else "←",
                style = style.copy(color = palette.onAccent, fontSize = 27.sp))
        }
    }
}

@Composable
private fun FanNode(letters: String, selected: Boolean, style: TextStyle, modifier: Modifier) {
    val palette = LocalPalette.current
    Box(modifier.size(nodeSize)
        .background(if (selected) palette.accent else palette.raised, CircleShape)
        .border(1.dp, palette.line, CircleShape),
        contentAlignment = Alignment.Center) {
        BasicText(letters, style = style.copy(color = if (selected) palette.onAccent else style.color, fontSize = 16.sp), maxLines = 1)
    }
}
