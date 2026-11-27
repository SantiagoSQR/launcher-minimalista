package com.valen.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** The index letter an app is filed under: its first letter without accents, or # for anything else. */
internal fun indexLetter(title: String): String {
    val first = searchKey(title.trim()).firstOrNull() ?: return "#"
    return if (first.isLetter()) first.uppercase() else "#"
}

/**
 * Alphabet rail for the app drawer. While the finger is on it, the letters around the finger bulge
 * out toward the screen like a wave and the current letter grows a little more.
 *
 * Everything is drawn in one Canvas and the finger position is only read while drawing, so moving the
 * finger repaints the rail without recomposing anything.
 */
@Composable
internal fun LetterRail(
    letters: List<String>,
    style: TextStyle,
    leftHanded: Boolean,
    onTouch: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (letters.isEmpty()) return
    val haptics = LocalHapticFeedback.current
    val select by rememberUpdatedState(onSelect)
    val touch by rememberUpdatedState(onTouch)
    val scope = rememberCoroutineScope()
    var finger by remember { mutableFloatStateOf(0f) }
    var active by remember { mutableIntStateOf(-1) }
    val intensity = remember { Animatable(0f) }
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val dim = style.color.copy(alpha = .5f)
    val plain = remember(style) { style.copy(fontSize = 11.sp) }
    val bold = remember(style) { plain.copy(fontWeight = FontWeight.Medium) }
    val laidOut = remember(letters, plain) { letters.map { measurer.measure(it, plain) to measurer.measure(it, bold) } }

    Canvas(
        modifier.fillMaxHeight().width(36.dp)
            .semantics { contentDescription = "Índice alfabético" }
            .pointerInput(letters) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    touch()
                    scope.launch { intensity.animateTo(1f, spring(stiffness = 700f)) }
                    val slot = size.height.toFloat() / letters.size
                    fun update(y: Float) {
                        finger = (y / slot - .5f).coerceIn(0f, letters.lastIndex.toFloat())
                        val index = finger.roundToInt()
                        if (index != active) {
                            active = index
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            select(letters[index])
                        }
                    }
                    update(down.position.y)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        update(change.position.y)
                    }
                    active = -1
                    scope.launch { intensity.animateTo(0f, spring(stiffness = 500f)) }
                }
            }
    ) {
        val slot = size.height / letters.size
        val bulge = 30.dp.toPx() * if (leftHanded) 1f else -1f
        val strength = intensity.value
        letters.indices.forEach { index ->
            val wave = (1f - abs(index - finger) / 3.5f).coerceAtLeast(0f) * strength
            val current = if (index == active) strength else 0f
            val (regular, emphasized) = laidOut[index]
            val text = if (current > .5f) emphasized else regular
            val scale = 1f + .9f * wave + .45f * current
            val center = Offset(size.width / 2f + bulge * wave, slot * index + slot / 2f)
            translate(center.x, center.y) {
                scale(scale, scale, pivot = Offset.Zero) {
                    drawText(text, color = lerp(dim, style.color, maxOf(wave, current)),
                        topLeft = Offset(-text.size.width / 2f, -text.size.height / 2f))
                }
            }
        }
    }
}
