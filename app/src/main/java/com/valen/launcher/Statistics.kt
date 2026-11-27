package com.valen.launcher

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import java.time.format.TextStyle as DateTextStyle
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
    name: (String, String) -> String, privateApp: (String) -> Boolean, style: TextStyle, openPermission: () -> Unit
) {
    var days by remember { mutableIntStateOf(7) }
    var appId by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }
    val entries = activity.map {
        if (privateApp(it.appId)) it.copy(appId = "private-apps", label = "Ocultas y bloqueadas") else it
    }
    val apps = entries.distinctBy { it.appId }
    val todayKey = today.toString()
    val todayRows = entries.filter { it.day == todayKey }
    val week = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val byDate = entries.groupingBy { it.day }.fold(0) { sum, row -> sum + row.openings }
    val weekValues = week.map { byDate[it.toString()] ?: 0 }
    val largest = weekValues.max().coerceAtLeast(1)
    val locale = LocalConfiguration.current.locales[0]
    val all = appId == null
    val start = today.minusDays((days - 1).coerceAtLeast(0).toLong()).toString()
    val rows = entries.filter { it.day <= todayKey && (days == 0 || it.day >= start) && (all || it.appId == appId) }
    val earthAccent = LocalPalette.current.accent
    val historyDays = (rows.map { it.day } + if (all) usage.orEmpty().keys else emptySet())
        .distinct().filter { days == 0 || it >= start }.sortedDescending()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        item {
            ManagementTitle("Estadísticas", style)
            ManagementGroup("Hoy", style) {
                StatValue(todayRows.sumOf { it.openings }.toString(), "Aperturas desde el launcher", style)
                val cancelled = todayRows.sumOf { it.cancelled }
                val total = cancelled + todayRows.sumOf { it.completed }
                StatValue("$cancelled de $total", "Pausas canceladas", style)
                usage?.get(todayKey)?.let { StatValue(usageText(it), "Pantalla estimada por Android", style) }
                if (usage == null) ManagementAction("Dar acceso a estadísticas de Android", style, action = openPermission)
            }
            ManagementGroup("Aperturas · últimos 7 días", style) {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    week.forEachIndexed { index, day ->
                        val value = weekValues[index]
                        val dayName = day.dayOfWeek.getDisplayName(DateTextStyle.FULL, locale)
                        Column(Modifier.weight(1f).semantics {
                            contentDescription = "$dayName ${day.dayOfMonth}: $value aperturas"
                        }, horizontalAlignment = Alignment.CenterHorizontally) {
                            BasicText(value.toString(), style = style.copy(fontSize = 11.sp, textAlign = TextAlign.Center))
                            Box(Modifier.fillMaxWidth().height(64.dp).padding(top = 4.dp), contentAlignment = Alignment.BottomCenter) {
                                Box(Modifier.fillMaxWidth(.68f).height((56f * value / largest).dp.coerceAtLeast(2.dp))
                                    .background(if (index == week.lastIndex) earthAccent else earthAccent.copy(alpha = .55f),
                                        RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)))
                            }
                            BasicText(day.dayOfWeek.getDisplayName(DateTextStyle.NARROW, locale),
                                Modifier.padding(top = 4.dp), style = style.copy(fontSize = 11.sp))
                        }
                    }
                }
            }
            ManagementGroup("Por aplicación", style) {
                todayRows.groupBy { it.appId }.values
                    .map { appRows -> appRows.first() to appRows.sumOf { it.openings } }
                    .filter { it.second > 0 }.sortedByDescending { it.second }.take(4).forEach { (row, count) ->
                        ManagementAction(name(row.appId, row.label), style,
                            if (count == 1) "1 apertura" else "$count aperturas") {
                            appId = row.appId
                            choosing = false
                            showDetail = true
                        }
                    }
                ManagementAction(if (showDetail) "Ocultar detalle" else "Ver detalle", style) {
                    showDetail = !showDetail
                }
            }
        }
        if (showDetail) item {
            Action(when (days) { 7 -> "Últimos 7 días"; 30 -> "Últimos 30 días"; else -> "Todo el historial" }, style) {
                days = when (days) { 7 -> 30; 30 -> 0; else -> 7 }
            }
            Action(apps.firstOrNull { it.appId == appId }?.let { name(it.appId, it.label) } ?: "Todas las aplicaciones", style) {
                choosing = !choosing
            }
        }
        if (showDetail && choosing) {
            item { Action("Todas las aplicaciones", style) { appId = null; choosing = false } }
            items(apps.sortedBy { name(it.appId, it.label) }, key = { it.appId }) { app ->
                Action(name(app.appId, app.label), style) { appId = app.appId; choosing = false }
            }
        } else if (showDetail) {
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

@Composable
private fun StatValue(value: String, label: String, style: TextStyle) {
    val earthAccent = LocalPalette.current.accent
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(value, style = style.copy(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
        BasicText(label, Modifier.weight(1f), style = style.copy(fontSize = 13.sp, color = earthAccent))
    }
}
