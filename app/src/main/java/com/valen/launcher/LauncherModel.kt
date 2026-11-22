package com.valen.launcher

import android.app.Application
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import androidx.room.withTransaction
import android.content.ComponentName
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import kotlinx.coroutines.Job
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import java.time.LocalDate
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.text.Normalizer
import java.util.Locale

private val Application.preferences by preferencesDataStore("launcher")

data class App(val component: ComponentName, val label: String) {
    val id get() = component.flattenToString()
    val hidden get() = hiddenKey(component.packageName)
    val restricted get() = stringPreferencesKey("restricted:$id")
    val alias get() = stringPreferencesKey("alias:$id")
}

val favoritesKey = stringPreferencesKey("favorites")
val colorKey = stringPreferencesKey("color")
fun hiddenKey(packageName: String) = stringPreferencesKey("hidden:$packageName")
val hiddenDelayKey = stringPreferencesKey("hidden-delay")
val progressionKey = stringPreferencesKey("progression")
val autoKey = stringPreferencesKey("auto")

fun decodeFavorites(value: String?): List<String> {
    val array = JSONArray(value ?: "[]")
    return List(array.length()) { array.getString(it) }
}

fun searchKey(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

class LauncherModel(private val app: Application) : AndroidViewModel(app) {
    private val database = Room.databaseBuilder(app, LauncherDatabase::class.java, "launcher.db")
        .addMigrations(addWidgets).build()
    private val widgetDao = database.widgets()
    val widgets = widgetDao.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val widgetManager: AppWidgetManager = AppWidgetManager.getInstance(app)
    val widgetHost = object : AppWidgetHost(app, 1024) {
        override fun onAppWidgetRemoved(appWidgetId: Int) {
            viewModelScope.launch { widgetDao.remove(appWidgetId) }
        }
    }

    fun cleanWidgets(pendingId: Int?) {
        viewModelScope.launch {
            val existing = widgetDao.all().map { it.id }.toSet()
            val ids = widgetHost.appWidgetIds.toSet()
            (existing - ids).forEach { widgetDao.remove(it) }
            (ids - existing - setOfNotNull(pendingId)).forEach(widgetHost::deleteAppWidgetId)
        }
    }

    fun isHidden(packageName: String): Boolean = preferences.value?.get(hiddenKey(packageName)) == "on"

    fun addWidget(id: Int, cellWidth: Float, rejected: () -> Unit) {
        viewModelScope.launch {
            val info = widgetManager.getAppWidgetInfo(id)
            val hidden = info != null && app.preferences.data.first()[hiddenKey(info.provider.packageName)] == "on"
            if (info == null || hidden) {
                widgetHost.deleteAppWidgetId(id)
                rejected()
                return@launch
            }
            val density = app.resources.displayMetrics.density
            val bounds = widgetBounds(info, cellWidth, density)
            if (bounds.minColumns > bounds.maxColumns) {
                widgetHost.deleteAppWidgetId(id)
                rejected()
                return@launch
            }
            val width = (info.targetCellWidth.takeIf { it > 0 }
                ?: kotlin.math.ceil(info.minWidth / density / cellWidth).toInt()).coerceIn(bounds.minColumns, bounds.maxColumns)
            val height = (info.targetCellHeight.takeIf { it > 0 }
                ?: kotlin.math.ceil(info.minHeight / density / widgetRowDp).toInt()).coerceIn(bounds.minRows, bounds.maxRows)
            database.withTransaction {
                val current = widgetDao.all()
                val template = Widget(id, info.provider.flattenToString(), 0, 0, width, height)
                val lastRow = current.maxOfOrNull { it.y + it.rows } ?: 0
                val placed = (0..lastRow).asSequence().flatMap { row ->
                    (0..4 - width).asSequence().map { col -> template.copy(x = col, y = row) }
                }.first { next -> current.none(next::overlaps) }
                widgetDao.save(placed)
            }
        }
    }

    fun placeWidget(widget: Widget) {
        viewModelScope.launch {
            database.withTransaction {
                val current = widgetDao.all()
                if (current.any { it.id == widget.id } && current.none(widget::overlaps)) widgetDao.save(widget)
            }
        }
    }

    fun removeWidget(id: Int) {
        viewModelScope.launch {
            widgetDao.remove(id)
            widgetHost.deleteAppWidgetId(id)
        }
    }
    private val history = database.history()
    val dailyActivity = history.days().stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())
    private val usageEstimates = MutableStateFlow<Map<String, Long>?>(null)
    val usage = usageEstimates.asStateFlow()
    private var usageJob: Job? = null

    fun refreshUsage() {
        usageJob?.cancel()
        usageJob = viewModelScope.launch {
            usageEstimates.value = kotlinx.coroutines.withContext(Dispatchers.IO) { estimateUsage(app, LocalDate.now()) }
        }
    }
    private val pending = MutableStateFlow<Interruption?>(null)
    val interruption = pending.asStateFlow()
    private var lookup: Job? = null
    private var lastWrite: Job? = null

    private val launcher = app.getSystemService(LauncherApps::class.java)
    private val installed = MutableStateFlow(emptyList<App>())
    val apps = installed.asStateFlow()
    val preferences = app.preferences.data.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private var refreshJob: Job? = null
    private val packageChanges = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) = refresh()
        override fun onPackageRemoved(packageName: String, user: UserHandle) = refresh()
        override fun onPackageChanged(packageName: String, user: UserHandle) = refresh()
        override fun onPackagesAvailable(packages: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
        override fun onPackagesUnavailable(packages: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
    }

    init {
        launcher.registerCallback(packageChanges)
    }

    override fun onCleared() {
        launcher.unregisterCallback(packageChanges)
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            installed.value = launcher.getActivityList(null, Process.myUserHandle())
                .filter { it.componentName.packageName != app.packageName }
                .map { App(it.componentName, it.label.toString()) }
                .sortedBy { searchKey(it.label) }
        }
    }

    fun requestInterruption(target: App) {
        lookup?.cancel()
        val delays = preferences.value?.get(progressionKey)?.let(::parseDelays) ?: defaultProgression
        lookup = viewModelScope.launch {
            lastWrite?.join()
            val count = history.openingCount(LocalDate.now().toString(), target.id)
            pending.value = Interruption(target, delays[count.coerceAtMost(delays.lastIndex)], count, System.currentTimeMillis())
        }
    }

    fun cancelInterruption() {
        lookup?.cancel()
        val attempt = pending.value ?: return
        pending.value = null
        record(attempt.app, "cancelled", attempt)
    }

    fun completeInterruption(): Boolean? {
        val attempt = pending.value ?: return null
        if (android.os.SystemClock.elapsedRealtime() < attempt.deadline) return null
        pending.value = null
        return launch(attempt.app, attempt)
    }

    private fun record(target: App, outcome: String, attempt: Interruption?) {
        val event = LaunchEvent(
            target.id, target.label, LocalDate.now().toString(),
            attempt?.timestamp ?: System.currentTimeMillis(), outcome, attempt?.seconds
        )
        val previousWrite = lastWrite
        lastWrite = viewModelScope.launch {
            previousWrite?.join()
            history.record(event)
        }
    }

    fun launch(target: App, attempt: Interruption? = null): Boolean {
        val opened = start(target)
        if (opened || attempt != null) record(target, if (opened) "opened" else "failed", attempt)
        return opened
    }

    private fun start(target: App): Boolean = try {
        launcher.startMainActivity(target.component, Process.myUserHandle(), null, null)
        true
    } catch (_: android.content.ActivityNotFoundException) {
        refresh()
        false
    } catch (_: SecurityException) {
        refresh()
        false
    }

    fun save(key: androidx.datastore.preferences.core.Preferences.Key<String>, value: String) {
        viewModelScope.launch { app.preferences.edit { it[key] = value } }
    }

    fun saveDelays(progression: List<Int>, hiddenSeconds: Int) {
        viewModelScope.launch {
            app.preferences.edit {
                it[progressionKey] = progression.joinToString(", ")
                it[hiddenDelayKey] = hiddenSeconds.toString()
            }
        }
    }

    fun toggleHidden(target: App, rejected: (String) -> Unit) {
        viewModelScope.launch {
            if (!isHidden(target.component.packageName) && widgetDao.all().any {
                ComponentName.unflattenFromString(it.provider)?.packageName == target.component.packageName
            }) {
                rejected("Quitá los widgets de la aplicación primero")
                return@launch
            }
            var blocked = false
            app.preferences.edit { data ->
                if (data[target.hidden] == "on") data.remove(target.hidden)
                else if (decodeFavorites(data[favoritesKey]).any {
                    ComponentName.unflattenFromString(it)?.packageName == target.component.packageName
                }) blocked = true
                else data[target.hidden] = "on"
            }
            if (blocked) rejected("Quitá la aplicación de favoritos primero")
        }
    }

    fun toggleFavorite(target: App) {
        viewModelScope.launch {
            app.preferences.edit {
                if (it[target.hidden] == "on") return@edit
                val id = target.id
                val current = decodeFavorites(it[favoritesKey])
                it[favoritesKey] = JSONArray(if (id in current) current - id else current + id).toString()
            }
        }
    }
}
