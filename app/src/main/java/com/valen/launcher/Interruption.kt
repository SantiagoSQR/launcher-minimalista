package com.valen.launcher

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

val defaultProgression = listOf(5, 10, 20, 30, 45, 60)

fun parseDelays(value: String): List<Int>? =
    value.split(',').map { it.trim().toIntOrNull() }.let { values ->
        if (values.any { it == null || it < 0 }) null else values.filterNotNull()
    }

data class Interruption(val app: App, val seconds: Int, val openingCount: Int, val timestamp: Long) {
    val deadline = SystemClock.elapsedRealtime() + seconds * 1000L
}

@Composable
fun WaitPanel(label: String, deadline: Long?, style: TextStyle, openingCount: Int? = null, confirm: () -> Unit, cancel: () -> Unit) {
    var remaining by remember(deadline) { mutableLongStateOf(deadline?.let { (it - SystemClock.elapsedRealtime()).coerceAtLeast(0) } ?: 0) }
    LaunchedEffect(deadline) {
        if (deadline != null) {
            while (remaining > 0) {
                delay(((remaining - 1) % 1000) + 1)
                remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        val availableHeight = maxHeight
        Column(Modifier.verticalScroll(rememberScrollState()).heightIn(min = availableHeight)
            .padding(top = availableHeight * .23f, bottom = availableHeight * .18f),
                Arrangement.Top, Alignment.CenterHorizontally) {
            BasicText(label, style = style.copy(fontSize = 28.sp))
            if (openingCount != null) {
                BasicText(
                    if (openingCount == 1) "La abriste 1 vez hoy" else "La abriste ${openingCount} veces hoy",
                    modifier = Modifier.padding(top = 8.dp),
                    style = style
                )
            }
            Spacer(Modifier.height(12.dp))
            BasicText(
                when {
                    deadline == null -> "Preparando…"
                    remaining > 0 -> {
                        val seconds = (remaining + 999) / 1000
                        if (seconds >= 60) "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
                        else "$seconds s"
                    }
                    else -> "Ya podés continuar"
                },
                modifier = if (deadline != null && remaining == 0L) Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                } else Modifier,
                style = style.copy(fontFeatureSettings = "tnum")
            )
            Spacer(Modifier.height(12.dp))
            Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                if (deadline != null && remaining == 0L) {
                    Action("Continuar", style.copy(fontSize = 13.sp)) {
                        if (SystemClock.elapsedRealtime() >= deadline) confirm()
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.fillMaxWidth().heightIn(min = 68.dp)
                    .background(LocalPalette.current.strong, androidx.compose.foundation.shape.RoundedCornerShape(15.dp))
                    .clickable(role = Role.Button, onClick = cancel)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicText("Cancelar", style = style.copy(color = LocalPalette.current.onStrong, fontSize = 19.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
            }
        }
    }
}
