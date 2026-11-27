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
    modifier: Modifier, onOpen: (App) -> Unit, onEdit: (App) -> Unit, onSwitchHand: () -> Unit
) {
    BoxWithConstraints(modifier) {
        val haptics = LocalHapticFeedback.current
        val availableWidth = maxWidth
        val label = style.copy(fontSize = 13.sp, lineHeight = 16.sp,
            textAlign = if (leftHanded) TextAlign.End else TextAlign.Start)
        val slots = remember(maxWidth, maxHeight, apps.size, leftHanded) {
            fanSlots(maxWidth.value, maxHeight.value, apps.size, leftHanded)
        }
        if (slots.isEmpty()) return@BoxWithConstraints
        val last = slots.last()
        val triggerX = (maxWidth.value - last.x).dp - triggerSize / 2
        val triggerY = last.y.dp - triggerSize / 2
        var highlighted by remember(slots) { mutableIntStateOf(-1) }
        val latestOpen by rememberUpdatedState(onOpen)
        val switchHand by rememberUpdatedState(onSwitchHand)

        fun select(index: Int) {
            highlighted = -1
            if (index in slots.indices) latestOpen(apps[index])
        }

        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.matchParentSize()) {
                val points = slots.map { Offset(it.x.dp.toPx(), it.y.dp.toPx()) }
                if (points.size > 1) {
                    val path = Path().apply {
                        moveTo(points.first().x, points.first().y)
                        points.drop(1).dropLast(1).forEachIndexed { index, point ->
                            val midpoint = (point + points[index + 2]) / 2f
                            quadraticBezierTo(point.x, point.y, midpoint.x, midpoint.y)
                        }
                        lineTo(points.last().x, points.last().y)
                    }
                    drawPath(path, Color(0xffa18167).copy(alpha = .56f), style = Stroke(width = 1.dp.toPx()))
                }
            }
            slots.forEachIndexed { index, center ->
                val app = apps[index]
                val selected = index == highlighted
                val name = title(app)
                val letters = remember(name) {
                    val codePoints = name.codePoints().filter { Character.isLetter(it) }.limit(2).toArray()
                    String(codePoints, 0, codePoints.size).uppercase()
                }
                val gap = nodeSize / 2 + labelGap
                val labelRoom = if (leftHanded) center.x.dp - gap else availableWidth - center.x.dp - gap
                val nameWidth = labelWidth.coerceAtMost(labelRoom.coerceAtLeast(0.dp))
                val nameX = if (leftHanded) center.x.dp - gap - nameWidth else center.x.dp + gap
                BasicText(name, Modifier.offset(nameX, center.y.dp - 4.dp).width(nameWidth)
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
                .background(Color(0xffcfaa85), CircleShape)
                .semantics {
                    role = Role.Button
                    contentDescription = if (leftHanded) "Cambiar a modo diestro" else "Cambiar a modo zurdo"
                    onClick { switchHand(); true }
                }
                .pointerInput(apps, slots, triggerX, triggerY) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        var dragged = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.isConsumed) break
                                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop && !dragged) {
                                    dragged = true
                                }
                                if (dragged) {
                                    val x = triggerX.value + change.position.x / this.density
                                    val y = triggerY.value + change.position.y / this.density
                                    val next = slots.indexOfFirst {
                                        (it - Offset(x, y)).getDistance() <= nodeSize.value / 2
                                    }
                                    if (next != highlighted && next >= 0) {
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                    }
                                    highlighted = next
                                }
                                change.consume()
                                if (!change.pressed) {
                                    if (dragged) select(highlighted) else switchHand()
                                    break
                                }
                            }
                        } finally {
                            if (dragged) select(-1)
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            BasicText(if (leftHanded) "→" else "←",
                style = style.copy(color = Color(0xff2b1a12), fontSize = 27.sp))
        }
    }
}

@Composable
private fun FanNode(letters: String, selected: Boolean, style: TextStyle, modifier: Modifier) {
    Box(modifier.size(nodeSize)
        .background(if (selected) Color(0xffcfaa85) else Color(0xff30241d), CircleShape)
        .border(1.dp, Color(0xffa18167), CircleShape),
        contentAlignment = Alignment.Center) {
        BasicText(letters, style = style.copy(color = if (selected) Color(0xff2b1a12) else style.color, fontSize = 16.sp), maxLines = 1)
    }
}
