package com.valen.launcher

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        BasicText(label, style = style.copy(fontSize = 28.sp))
        if (openingCount != null) {
            BasicText(
                if (openingCount == 1) "La abriste 1 vez hoy" else "La abriste ${openingCount} veces hoy",
                modifier = Modifier.padding(top = 8.dp),
                style = style
            )
        }
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.widthIn(min = 208.dp).heightIn(min = 64.dp)
                .background(Color(0xff176b3a))
                .clickable(role = Role.Button, onClick = cancel)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            BasicText("Cancelar", style = style.copy(color = Color.White, fontSize = 22.sp))
        }
        Spacer(Modifier.height(8.dp))
        when {
            deadline == null -> BasicText("Preparando…", style = style)
            remaining > 0 -> BasicText("${(remaining + 999) / 1000} s", style = style)
            else -> Action("Continuar", style.copy(fontSize = 14.sp)) { if (SystemClock.elapsedRealtime() >= deadline) confirm() }
        }
    }
}
