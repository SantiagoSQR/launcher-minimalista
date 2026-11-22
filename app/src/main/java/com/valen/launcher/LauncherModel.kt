package com.valen.launcher

import android.app.Application
import android.content.ComponentName
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import kotlinx.coroutines.Job
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.text.Normalizer
import java.util.Locale

private val Application.preferences by preferencesDataStore("launcher")

data class App(val component: ComponentName, val label: String) {
    val id get() = component.flattenToString()
    val alias get() = stringPreferencesKey("alias:$id")
}

val favoritesKey = stringPreferencesKey("favorites")
val colorKey = stringPreferencesKey("color")
val autoKey = stringPreferencesKey("auto")

fun decodeFavorites(value: String?): List<String> {
    val array = JSONArray(value ?: "[]")
    return List(array.length()) { array.getString(it) }
}

fun searchKey(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

class LauncherModel(private val app: Application) : AndroidViewModel(app) {
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

    fun launch(target: App): Boolean = try {
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

    fun toggleFavorite(id: String) {
        viewModelScope.launch {
            app.preferences.edit {
                val current = decodeFavorites(it[favoritesKey])
                it[favoritesKey] = JSONArray(if (id in current) current - id else current + id).toString()
            }
        }
    }
}
