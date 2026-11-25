package com.valen.launcher

import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val triggerSize = 56.dp
private val edgeMargin = 16.dp
private val tileWidth = 94.dp

private fun fanSlots(width: Float, height: Float, tileHeight: Float, count: Int, leftHanded: Boolean): List<Offset> {
    val origin = Offset(width - edgeMargin.value - triggerSize.value / 2, height - edgeMargin.value - triggerSize.value / 2)
    val halfWidth = tileWidth.value / 2
    val halfHeight = tileHeight / 2
    if (width < tileWidth.value || height <= tileHeight + triggerSize.value) return emptyList()
    val reachX = minOf(250f, origin.x - halfWidth - 4f)
    val reachY = minOf(350f, origin.y - halfHeight - 4f)
    for (size in minOf(count, 6) downTo 1) {
        val spots = List(size) { index ->
            val angle = if (size == 1) 38.0 else 5.0 + 73.0 * index / (size - 1)
            val radians = Math.toRadians(angle)
            val x = (origin.x - reachX * sin(radians)).toFloat().coerceIn(halfWidth, width - halfWidth)
            Offset(x, (origin.y - reachY * cos(radians)).toFloat())
        }
        if (spots.all { spot ->
                spot.y in halfHeight..(height - halfHeight) &&
                    (abs(spot.x - origin.x) >= halfWidth + triggerSize.value / 2 + 6f ||
                        abs(spot.y - origin.y) >= halfHeight + triggerSize.value / 2 + 6f)
            } && spots.indices.all { i ->
                spots.take(i).none { other ->
                    abs(other.x - spots[i].x) < tileWidth.value + 6f &&
                        abs(other.y - spots[i].y) < tileHeight + 6f
                }
            }
        ) {
            return if (leftHanded) spots.map { Offset(width - it.x, it.y) } else spots
        }
    }
    return emptyList()
}

@Composable
internal fun FanFavorites(
    apps: List<App>, title: (App) -> String, style: TextStyle, leftHanded: Boolean,
    modifier: Modifier, menuHeight: Dp, onOpen: (App) -> Unit, onEdit: (App) -> Unit, onDrawer: () -> Unit
) {
    BoxWithConstraints(modifier) {
        val haptics = LocalHapticFeedback.current
        val density = LocalDensity.current
        val label = style.copy(fontSize = 13.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
        val tileHeight = maxOf(76.dp, 42.dp + with(density) { label.lineHeight.toDp() } * 2)
        val slots = remember(maxWidth, maxHeight, menuHeight, tileHeight, apps.size, leftHanded) {
            val top = (maxHeight - menuHeight).value
            fanSlots(maxWidth.value, menuHeight.value, tileHeight.value, apps.size, leftHanded)
                .map { it.copy(y = it.y + top) }
        }
        val right = (maxWidth - edgeMargin - triggerSize).coerceAtLeast(0.dp)
        val triggerX = if (leftHanded) edgeMargin else right
        val triggerY = (maxHeight - edgeMargin - triggerSize).coerceAtLeast(0.dp)
        var expanded by remember(apps, slots) { mutableStateOf(false) }
        var highlighted by remember { mutableIntStateOf(-1) }
        val latestOpen by rememberUpdatedState(onOpen)
        val latestDrawer by rememberUpdatedState(onDrawer)
        val origin = TransformOrigin(if (leftHanded) 0f else 1f, 1f)

        fun select(index: Int) {
            expanded = false
            highlighted = -1
            if (index in slots.indices) latestOpen(apps[index])
        }

        fun toggle() {
            if (slots.isEmpty()) latestDrawer() else expanded = !expanded
        }

        BackHandler(expanded) { select(-1) }
        if (expanded) {
            Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { select(-1) } })
        }
        AnimatedVisibility(
            visible = expanded,
            modifier = Modifier.matchParentSize(),
            enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = .88f, transformOrigin = origin),
            exit = fadeOut(tween(110)) + scaleOut(tween(130), targetScale = .88f, transformOrigin = origin)
        ) {
            Box(Modifier.fillMaxSize()) {
                slots.forEachIndexed { index, center ->
                    val app = apps[index]
                    val selected = index == highlighted
                    Column(
                        Modifier.offset {
                            IntOffset(((center.x.dp - tileWidth / 2).toPx()).roundToInt(),
                                ((center.y.dp - tileHeight / 2).toPx()).roundToInt())
                        }.size(tileWidth, tileHeight)
                            .background(if (selected) Color.White else Color(0xe8171717), RoundedCornerShape(12.dp))
                            .combinedClickable(enabled = expanded, role = Role.Button, onLongClickLabel = "Editar aplicación",
                                onClick = { select(index) }, onLongClick = { select(-1); onEdit(app) })
                            .padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)
                    ) {
                        FanIcon(app)
                        BasicText(title(app), style = if (selected) label.copy(color = Color.Black) else label,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        Box(
            Modifier.offset {
                IntOffset(triggerX.roundToPx(), triggerY.roundToPx())
            }.size(triggerSize)
                .background(if (expanded) Color.White else Color.Black, CircleShape)
                .border(1.dp, Color.White, CircleShape)
                .semantics {
                    role = Role.Button
                    contentDescription = when {
                        expanded -> "Cerrar favoritos"
                        slots.isEmpty() -> "Abrir aplicaciones"
                        else -> "Abrir favoritos"
                    }
                    onClick { toggle(); true }
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
                                    expanded = slots.isNotEmpty()
                                }
                                if (dragged) {
                                    val x = triggerX.value + change.position.x / this.density
                                    val y = triggerY.value + change.position.y / this.density
                                    val next = slots.indexOfFirst {
                                        abs(it.x - x) <= tileWidth.value / 2 && abs(it.y - y) <= tileHeight.value / 2
                                    }
                                    if (next != highlighted && next >= 0) {
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                    }
                                    highlighted = next
                                }
                                change.consume()
                                if (!change.pressed) {
                                    if (dragged) select(highlighted) else toggle()
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
            BasicText(when {
                expanded -> "×"
                leftHanded -> "↗"
                else -> "↖"
            },
                style = style.copy(color = if (expanded) Color.Black else Color.White, fontSize = 27.sp))
        }
    }
}

@Composable
private fun FanIcon(app: App) {
    val context = LocalContext.current
    val pixels = with(LocalDensity.current) { 30.dp.roundToPx().coerceAtLeast(1) }
    val icon = remember(app.component, pixels) {
        try {
            context.packageManager.getActivityIcon(app.component).toBitmap(pixels, pixels).asImageBitmap()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
    if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(30.dp))
}
