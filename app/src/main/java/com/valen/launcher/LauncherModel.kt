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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.text.Normalizer
import java.util.Locale

internal val Application.preferences by preferencesDataStore("launcher")

data class App(val component: ComponentName, val label: String) {
    val id get() = component.flattenToString()
    val hidden get() = hiddenKey(component.packageName)
    val locked get() = lockedKey(component.packageName)
    val protected get() = protectionKey(component.packageName)
    val restricted get() = stringPreferencesKey("restricted:$id")
    val alias get() = stringPreferencesKey("alias:$id")
}

val favoritesKey = stringPreferencesKey("favorites")
fun hiddenKey(packageName: String) = stringPreferencesKey("hidden:$packageName")
val hiddenDelayKey = stringPreferencesKey("hidden-delay")
val progressionKey = stringPreferencesKey("progression")
val autoKey = stringPreferencesKey("auto")
/** "list", "fan" (rays from the corner) or "curve" (thumb trace with a movable button). */
val favoritesStyleKey = stringPreferencesKey("favorites-style")
val fanHandKey = stringPreferencesKey("fan-hand")
val fanPositionKey = stringPreferencesKey("fan-position")
val fanRaysPositionKey = stringPreferencesKey("fan-rays-position")
val resumeWindowKey = stringPreferencesKey("resume-window")
/** "classic" or "retro": bold clock and full-width flat rows in the app search. */
val appearanceKey = stringPreferencesKey("appearance")
const val defaultResumeMinutes = 10

fun decodeFavorites(value: String?): List<String> {
    val array = JSONArray(value ?: "[]")
    return List(array.length()) { array.getString(it) }
}

fun searchKey(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

private const val backupFormat = "launcher-minimalista-backup"
private const val backupVersion = 1

class LauncherModel(private val app: Application) : AndroidViewModel(app) {
    private val database = Room.databaseBuilder(app, LauncherDatabase::class.java, "launcher.db")
        .addMigrations(addWidgets, addInterruptionStart).build()
    private val widgetPolicy = Mutex()
    private val widgetDao = database.widgets()
    val widgets = widgetDao.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val widgetManager: AppWidgetManager = AppWidgetManager.getInstance(app)
    /** Inflates widget RemoteViews off the main thread; updates are still applied on it. */
    val widgetExecutor: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var widgetsStarted = false
    private var relisten: Job? = null

    fun startWidgets() {
        widgetsStarted = true
        runCatching { widgetHost.startListening() }
    }

    fun stopWidgets() {
        widgetsStarted = false
        relisten?.cancel()
        runCatching { widgetHost.stopListening() }
    }

    /**
     * If delivering an update to this host ever fails (for example when a burst of large RemoteViews
     * fills the binder buffer), the system drops the host callback and the widgets freeze on screen
     * while taps keep reaching the provider. Listening again re-registers the callback and replays
     * every missed update, so do it after each interaction and whenever the panel appears.
     */
    fun refreshWidgetUpdates() {
        relisten?.cancel()
        relisten = viewModelScope.launch {
            for (wait in longArrayOf(250, 1200)) {
                kotlinx.coroutines.delay(wait)
                if (widgetsStarted) runCatching { widgetHost.startListening() }
            }
        }
    }
    val widgetHost = object : AppWidgetHost(app, 1024) {
        override fun onAppWidgetRemoved(appWidgetId: Int) {
            viewModelScope.launch { widgetDao.remove(appWidgetId) }
        }
    }

    fun cleanWidgets(pendingId: Int?) {
        viewModelScope.launch {
            widgetPolicy.withLock {
                val existing = widgetDao.all().map { it.id }.toSet()
                val ids = widgetHost.appWidgetIds.toSet()
                (existing - ids).forEach { widgetDao.remove(it) }
                (ids - existing - setOfNotNull(pendingId)).forEach(widgetHost::deleteAppWidgetId)
            }
        }
    }

    fun isHidden(packageName: String): Boolean = preferences.value?.get(hiddenKey(packageName)) == "on"

    fun addWidget(id: Int, cellWidth: Float, rejected: () -> Unit) {
        viewModelScope.launch {
            widgetPolicy.withLock {
                val info = widgetManager.getAppWidgetInfo(id)
                val hidden = info != null && app.preferences.data.first()[hiddenKey(info.provider.packageName)] == "on"
                if (info == null || hidden) {
                    widgetHost.deleteAppWidgetId(id)
                    rejected()
                    return@withLock
                }
                val density = app.resources.displayMetrics.density
                val bounds = widgetBounds(info, cellWidth, density)
                if (bounds.minColumns > bounds.maxColumns) {
                    widgetHost.deleteAppWidgetId(id)
                    rejected()
                    return@withLock
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

    /**
     * Apps that already went through the pause and count as still open: opening one again from the
     * launcher skips the countdown while it was used within the configured window and the screen has
     * not been turned off since. Locked apps never get a session.
     */
    private val sessions = mutableMapOf<String, Long>()
    private var lastLaunched: String? = null
    private val screenOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            sessions.clear()
            lastLaunched = null
        }
    }

    init {
        launcher.registerCallback(packageChanges)
        app.registerReceiver(screenOff, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF),
            android.content.Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onCleared() {
        launcher.unregisterCallback(packageChanges)
        app.unregisterReceiver(screenOff)
        widgetExecutor.shutdown()
    }

    private fun resumeWindowMs(): Long =
        (preferences.value?.get(resumeWindowKey)?.toIntOrNull() ?: defaultResumeMinutes) * 60_000L

    /** True when [target] passed the pause recently enough to count as still open. */
    fun isStillOpen(target: App): Boolean {
        if (preferences.value?.get(target.locked) == "on") return false
        val window = resumeWindowMs()
        if (window <= 0) return false
        val lastUsed = sessions[target.component.packageName] ?: return false
        return android.os.SystemClock.elapsedRealtime() - lastUsed <= window
    }

    /**
     * Called when the launcher returns to the foreground: the app opened last was in use until now.
     * With usage access, every open session is also refreshed from the system foreground events,
     * which covers going back to an app through Recents.
     */
    fun noteLauncherVisible() {
        val now = android.os.SystemClock.elapsedRealtime()
        lastLaunched?.let { if (it in sessions) sessions[it] = now }
        if (sessions.isEmpty()) return
        val packages = sessions.keys.toSet()
        viewModelScope.launch {
            val seen = kotlinx.coroutines.withContext(Dispatchers.IO) { lastForeground(packages) }
            val offset = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
            seen.forEach { (pkg, wallTime) ->
                val current = sessions[pkg] ?: return@forEach
                sessions[pkg] = maxOf(current, wallTime - offset)
            }
        }
    }

    private fun lastForeground(packages: Set<String>): Map<String, Long> {
        val usage = app.getSystemService(android.app.usage.UsageStatsManager::class.java) ?: return emptyMap()
        val end = System.currentTimeMillis()
        val events = try {
            usage.queryEvents(end - resumeWindowMs(), end)
        } catch (_: SecurityException) {
            return emptyMap()
        } ?: return emptyMap()
        val result = mutableMapOf<String, Long>()
        val event = android.app.usage.UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val type = event.eventType
            if (event.packageName in packages && (type == android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED ||
                    type == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED)) {
                result[event.packageName] = maxOf(result[event.packageName] ?: 0L, event.timeStamp)
            }
        }
        return result
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

    private var lockDeadline: Long? = null
    private var blockedAccess = false

    fun beginBlockedAccess(): Long = (android.os.SystemClock.elapsedRealtime() + 600_000L).also {
        lockDeadline = it
        blockedAccess = false
    }

    fun confirmBlockedAccess(): Boolean {
        val deadline = lockDeadline ?: return false
        if (android.os.SystemClock.elapsedRealtime() < deadline) return false
        lockDeadline = null
        blockedAccess = true
        return true
    }

    fun cancelBlockedAccess(): Boolean {
        val active = lockDeadline != null || blockedAccess
        lockDeadline = null
        blockedAccess = false
        return active
    }

    fun unlock(target: App) {
        if (blockedAccess) save(target.locked, "off")
    }

    fun requestInterruption(target: App) {
        if (preferences.value?.get(target.locked) == "on" && !blockedAccess) return
        lookup?.cancel()
        val delays = preferences.value?.get(progressionKey)?.let(::parseDelays) ?: defaultProgression
        lookup = viewModelScope.launch {
            lastWrite?.join()
            val count = history.openingCount(LocalDate.now().toString(), target.id)
            pending.value = Interruption(target, delays[count.coerceAtMost(delays.lastIndex)], count, System.currentTimeMillis())
        }
    }

    fun cancelInterruption(): Boolean {
        val wasPreparing = lookup?.isActive == true
        lookup?.cancel()
        val attempt = pending.value ?: return wasPreparing
        pending.value = null
        record(attempt.app, "cancelled", attempt)
        return true
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
            System.currentTimeMillis(), outcome, attempt?.seconds, attempt?.timestamp
        )
        val previousWrite = lastWrite
        lastWrite = viewModelScope.launch {
            previousWrite?.join()
            history.record(event)
        }
    }

    fun launch(target: App, attempt: Interruption? = null): Boolean {
        val data = preferences.value ?: return false
        val protected = data[target.protected] == "on"
        val locked = data[target.locked] == "on"
        val gated = data[target.hidden] == "on" || data[target.restricted] == "on" || protected
        val resumed = attempt == null && gated && !locked && isStillOpen(target)
        if (attempt == null && gated && !resumed) return false
        if (locked && (!blockedAccess || attempt == null)) return false
        if (locked) {
            cancelBlockedAccess()
            ForegroundProtection.authorize(target.component.packageName)
        }
        val opened = start(target)
        if (locked && !opened) ForegroundProtection.revoke()
        val pkg = target.component.packageName
        if (opened) {
            lastLaunched = pkg
            if (!locked && (attempt != null || resumed)) sessions[pkg] = android.os.SystemClock.elapsedRealtime()
        }
        // Going back to an open app is the same visit, so it does not count as another opening.
        record(target, if (!opened) "failed" else if (resumed) "resumed" else "opened", attempt)
        return opened
    }

    // ---- Backup -------------------------------------------------------------------------------

    /** Everything worth keeping across reinstalls, as a self-describing JSON document. */
    suspend fun exportBackup(): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        lastWrite?.join()
        val prefs = app.preferences.data.first().asMap()
        val root = org.json.JSONObject()
        root.put("format", backupFormat)
        root.put("version", backupVersion)
        root.put("exportedAt", java.time.OffsetDateTime.now().toString())
        root.put("app", app.packageManager.getPackageInfo(app.packageName, 0).versionName)
        root.put("preferences", org.json.JSONObject().apply {
            prefs.entries.sortedBy { it.key.name }.forEach { (key, value) -> if (value is String) put(key.name, value) }
        })
        root.put("widgets", JSONArray().apply {
            widgetDao.all().sortedWith(compareBy({ it.y }, { it.x })).forEach {
                put(org.json.JSONObject().put("provider", it.provider).put("x", it.x).put("y", it.y)
                    .put("columns", it.columns).put("rows", it.rows))
            }
        })
        root.put("history", JSONArray().apply {
            history.all().forEach {
                put(org.json.JSONObject().put("appId", it.appId).put("label", it.label).put("day", it.day)
                    .put("timestamp", it.timestamp).put("outcome", it.outcome)
                    .put("waitSeconds", it.waitSeconds ?: org.json.JSONObject.NULL)
                    .put("interruptionStartedAt", it.interruptionStartedAt ?: org.json.JSONObject.NULL))
            }
        })
        root.toString(2)
    }

    /**
     * Replaces settings and history with the backup's. Widgets already on screen that match the
     * backup stay; the rest are removed, and the missing ones are returned so the activity can ask
     * Android to bind them again (widget ids cannot be carried between installs).
     */
    suspend fun importBackup(json: String): List<Widget> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val root = org.json.JSONObject(json)
        require(root.optString("format") == backupFormat) { "El archivo no es una copia de este launcher" }
        require(root.optInt("version") in 1..backupVersion) { "La copia es de una versión más nueva del launcher" }
        val values = root.getJSONObject("preferences")
        app.preferences.edit { data ->
            data.clear()
            values.keys().forEach { key -> data[stringPreferencesKey(key)] = values.getString(key) }
        }
        root.optJSONArray("history")?.let { rows ->
            val events = List(rows.length()) { i ->
                val row = rows.getJSONObject(i)
                LaunchEvent(row.getString("appId"), row.getString("label"), row.getString("day"),
                    row.getLong("timestamp"), row.getString("outcome"),
                    if (row.isNull("waitSeconds")) null else row.getInt("waitSeconds"),
                    if (row.isNull("interruptionStartedAt")) null else row.getLong("interruptionStartedAt"))
            }
            database.withTransaction {
                history.clear()
                history.recordAll(events)
            }
        }
        val wanted = root.optJSONArray("widgets")?.let { rows ->
            List(rows.length()) { i ->
                val row = rows.getJSONObject(i)
                Widget(0, row.getString("provider"), row.getInt("x"), row.getInt("y"), row.getInt("columns"), row.getInt("rows"))
            }
        }.orEmpty()
        widgetPolicy.withLock {
            val current = widgetDao.all()
            fun same(a: Widget, b: Widget) = a.provider == b.provider && a.x == b.x && a.y == b.y &&
                a.columns == b.columns && a.rows == b.rows
            current.filter { existing -> wanted.none { same(it, existing) } }.forEach {
                widgetDao.remove(it.id)
                widgetHost.deleteAppWidgetId(it.id)
            }
            wanted.filter { target -> current.none { same(it, target) } }
        }
    }

    /** Places a re-bound widget where the backup had it, or in the first free spot if that is taken. */
    fun placeRestoredWidget(id: Int, target: Widget) {
        viewModelScope.launch {
            widgetPolicy.withLock {
                database.withTransaction {
                    val current = widgetDao.all()
                    val columns = target.columns.coerceIn(1, 4)
                    val wanted = target.copy(id = id, columns = columns, x = target.x.coerceIn(0, 4 - columns),
                        rows = target.rows.coerceAtLeast(1), y = target.y.coerceAtLeast(0))
                    val placed = if (current.none(wanted::overlaps)) wanted else {
                        val lastRow = current.maxOfOrNull { it.y + it.rows } ?: 0
                        (0..lastRow).asSequence().flatMap { row ->
                            (0..4 - columns).asSequence().map { col -> wanted.copy(x = col, y = row) }
                        }.first { next -> current.none(next::overlaps) }
                    }
                    widgetDao.save(placed)
                }
            }
        }
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
            widgetPolicy.withLock {
                if (!isHidden(target.component.packageName) && widgetDao.all().any {
                    ComponentName.unflattenFromString(it.provider)?.packageName == target.component.packageName
                }) {
                    rejected("Quitá los widgets de la aplicación primero")
                    return@withLock
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
