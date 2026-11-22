package com.valen.launcher

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DailyActivity(
    val day: String,
    val appId: String,
    val label: String,
    val openings: Int,
    val completed: Int,
    val cancelled: Int
)

internal fun estimateUsage(context: Context, today: LocalDate): Map<String, Long>? {
    val ops = context.getSystemService(AppOpsManager::class.java)
    if (ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) != AppOpsManager.MODE_ALLOWED) {
        return null
    }
    val usage = context.getSystemService(UsageStatsManager::class.java)
    val zone = ZoneId.systemDefault()
    val start = today.minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
    val now = System.currentTimeMillis()
    val events = try {
        usage.queryEvents(start, now)
    } catch (_: SecurityException) {
        return null
    } ?: return null
    val durations = mutableMapOf<String, Long>()
    var interactive = false
    var locked = false
    var last = start
    fun accumulate(until: Long) {
        if (interactive && !locked) {
            var time = last
            while (time < until) {
                val day = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
                val boundary = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val end = minOf(until, boundary)
                durations.merge(day.toString(), end - time, Long::plus)
                time = end
            }
        }
        last = until
    }
    val event = UsageEvents.Event()
    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        when (event.eventType) {
            UsageEvents.Event.SCREEN_INTERACTIVE -> {
                accumulate(event.timeStamp)
                interactive = true
            }
            UsageEvents.Event.SCREEN_NON_INTERACTIVE,
            UsageEvents.Event.DEVICE_SHUTDOWN,
            UsageEvents.Event.DEVICE_STARTUP -> {
                accumulate(event.timeStamp)
                interactive = false
            }
            UsageEvents.Event.KEYGUARD_SHOWN -> {
                accumulate(event.timeStamp)
                locked = true
            }
            UsageEvents.Event.KEYGUARD_HIDDEN -> {
                accumulate(event.timeStamp)
                locked = false
            }
        }
    }
    accumulate(now)
    return durations
}

internal fun usageText(milliseconds: Long): String {
    val minutes = milliseconds / 60_000
    return if (minutes < 60) "${minutes} min" else "${minutes / 60} h ${minutes % 60} min"
}

@Composable
internal fun StatisticsPanel(
    activity: List<DailyActivity>, usage: Map<String, Long>?, today: LocalDate,
    name: (String, String) -> String, style: TextStyle, openPermission: () -> Unit
) {
    var days by remember { mutableIntStateOf(7) }
    var appId by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    val apps = remember(activity) { activity.distinctBy { it.appId } }
    val all = appId == null
    val start = today.minusDays((days - 1).coerceAtLeast(0).toLong()).toString()
    val rows = activity.filter { (days == 0 || it.day >= start) && (all || it.appId == appId) }
    val historyDays = (rows.map { it.day } + if (all) usage.orEmpty().keys else emptySet())
        .distinct().filter { days == 0 || it >= start }.sortedDescending()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        item {
            BasicText("Estadísticas", style = style.copy(fontSize = 28.sp))
            if (usage == null) {
                BasicText("Tiempo de uso no disponible", style = style)
                Action("Dar acceso a estadísticas de Android", style, openPermission)
            } else {
                BasicText("Tiempo de pantalla activa y desbloqueada · estimación de Android", style = style.copy(fontSize = 14.sp))
            }
            Action(when (days) { 7 -> "Últimos 7 días"; 30 -> "Últimos 30 días"; else -> "Todo el historial" }, style) {
                days = when (days) { 7 -> 30; 30 -> 0; else -> 7 }
            }
            Action(apps.firstOrNull { it.appId == appId }?.let { name(it.appId, it.label) } ?: "Todas las aplicaciones", style) {
                choosing = !choosing
            }
        }
        if (choosing) {
            item { Action("Todas las aplicaciones", style) { appId = null; choosing = false } }
            items(apps.sortedBy { name(it.appId, it.label) }, key = { it.appId }) { app ->
                Action(name(app.appId, app.label), style) { appId = app.appId; choosing = false }
            }
        } else {
            item {
                BasicText("Aperturas desde el launcher: ${rows.sumOf { it.openings }}", style = style)
                BasicText("Interrupciones: ${rows.sumOf { it.completed }} completadas · ${rows.sumOf { it.cancelled }} canceladas", style = style)
                if (historyDays.isEmpty()) BasicText("Todavía no hay actividad registrada", style = style)
            }
            val byDay = rows.groupBy { it.day }
            items(historyDays, key = { it }) { day ->
                val records = byDay[day].orEmpty()
                Column(Modifier.padding(vertical = 12.dp)) {
                    BasicText(day, style = style)
                    if (all) usage?.get(day)?.let { BasicText("Uso estimado: ${usageText(it)}", style = style) }
                    BasicText("${records.sumOf { it.openings }} aperturas · ${records.sumOf { it.completed }} completadas · ${records.sumOf { it.cancelled }} canceladas", style = style.copy(fontSize = 15.sp))
                }
            }
        }
    }
}
