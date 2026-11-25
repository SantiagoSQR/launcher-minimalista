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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlin.math.abs
import kotlin.math.roundToInt

private val triggerSize = 56.dp
private val edgeMargin = 30.dp
private val nodeSize = 54.dp
private val labelWidth = 84.dp
private val labelGap = 8.dp
private val rowWidth = nodeSize + labelWidth + labelGap

private fun fanSlots(width: Float, anchorY: Float, rowHeight: Float, count: Int, leftHanded: Boolean): List<Offset> {
    val origin = Offset(width - edgeMargin.value - triggerSize.value / 2, anchorY)
    val halfNode = nodeSize.value / 2
    val reachX = minOf(165f, origin.x - rowWidth.value + halfNode - 4f)
    val reachY = minOf(246f, origin.y - rowHeight / 2 - 4f)
    if (reachX * .53f < (nodeSize.value + triggerSize.value) / 2 + 5f || reachY < rowHeight) return emptyList()

    val size = minOf(count, 5, ((reachY - 10f) / (rowHeight + 5f)).toInt() + 1)
    // Start near the thumb and bend inward, then back toward the trigger's side.
    val inward = listOf(.53f, .87f, 1f, .71f, .31f)
    val upward = listOf(0f, .23f, .51f, .79f, 1f)
    val spacing = rowHeight + 5f
    val slack = reachY - 10f - spacing * (size - 1)
    return List(size) { index ->
        val x = origin.x - reachX * inward[index]
        val rise = 10f + spacing * index + slack * upward[index]
        Offset(if (leftHanded) width - x else x, origin.y - rise)
    }
}

@Composable
internal fun FanFavorites(
    apps: List<App>, title: (App) -> String, style: TextStyle, leftHanded: Boolean,
    modifier: Modifier, menuHeight: Dp, onOpen: (App) -> Unit, onEdit: (App) -> Unit, onSwitchHand: () -> Unit
) {
    BoxWithConstraints(modifier) {
        val haptics = LocalHapticFeedback.current
        val density = LocalDensity.current
        val label = style.copy(fontSize = 13.sp, lineHeight = 16.sp, textAlign = if (leftHanded) TextAlign.Start else TextAlign.End)
        val rowHeight = maxOf(nodeSize, with(density) { label.lineHeight.toDp() } * 2)
        val lift = minOf(112.dp, menuHeight * .24f)
        val right = (maxWidth - edgeMargin - triggerSize).coerceAtLeast(0.dp)
        val triggerX = if (leftHanded) edgeMargin else right
        val triggerY = (maxHeight - lift - triggerSize).coerceAtLeast(0.dp)
        val slots = remember(maxWidth, maxHeight, menuHeight, triggerY, rowHeight, apps.size, leftHanded) {
            val top = (maxHeight - menuHeight).value
            fanSlots(maxWidth.value, triggerY.value + triggerSize.value / 2 - top, rowHeight.value, apps.size, leftHanded)
                .map { it.copy(y = it.y + top) }
        }
        var highlighted by remember(slots) { mutableIntStateOf(-1) }
        val latestOpen by rememberUpdatedState(onOpen)
        val switchHand by rememberUpdatedState(onSwitchHand)

        fun select(index: Int) {
            highlighted = -1
            if (index in slots.indices) latestOpen(apps[index])
        }

        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.matchParentSize()) {
                val anchor = Offset((triggerX + triggerSize / 2).toPx(), (triggerY + triggerSize / 2).toPx())
                slots.forEachIndexed { index, center ->
                    drawLine(
                        Color.White.copy(alpha = if (index == highlighted) .85f else .36f),
                        anchor, Offset(center.x.dp.toPx(), center.y.dp.toPx()), 1.dp.toPx()
                    )
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
                Row(
                    Modifier.offset {
                        val left = if (leftHanded) center.x.dp - nodeSize / 2
                            else center.x.dp + nodeSize / 2 - rowWidth
                        IntOffset(left.roundToPx(), (center.y.dp - rowHeight / 2).roundToPx())
                    }.size(rowWidth, rowHeight)
                        .combinedClickable(role = Role.Button, onLongClickLabel = "Editar aplicación",
                            onClick = { select(index) }, onLongClick = { select(-1); onEdit(app) }),
                    horizontalArrangement = Arrangement.spacedBy(labelGap),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (leftHanded) FanNode(letters, selected, style)
                    BasicText(name, Modifier.width(labelWidth), style = label,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!leftHanded) FanNode(letters, selected, style)
                }
            }
        }

        Box(
            Modifier.offset {
                IntOffset(triggerX.roundToPx(), triggerY.roundToPx())
            }.size(triggerSize)
                .background(Color.White, CircleShape)
                .border(1.dp, Color.White, CircleShape)
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
                                        val towardLabel = (x - it.x) * if (leftHanded) 1f else -1f
                                        towardLabel in -nodeSize.value / 2..(rowWidth.value - nodeSize.value / 2) &&
                                            abs(it.y - y) <= rowHeight.value / 2
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
                style = style.copy(color = Color.Black, fontSize = 27.sp))
        }
    }
}

@Composable
private fun FanNode(letters: String, selected: Boolean, style: TextStyle) {
    Box(Modifier.size(nodeSize)
        .background(if (selected) Color.White else Color(0xff202020), CircleShape)
        .border(1.dp, if (selected) Color.White else Color(0xff878787), CircleShape),
        contentAlignment = Alignment.Center) {
        BasicText(letters, style = style.copy(color = if (selected) Color.Black else Color.White, fontSize = 16.sp), maxLines = 1)
    }
}
