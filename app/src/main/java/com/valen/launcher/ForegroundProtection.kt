package com.valen.launcher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal fun protectionKey(packageName: String) = stringPreferencesKey("protect-foreground:$packageName")

internal fun Context.protectionEnabled(): Boolean {
    val service = ComponentName(this, ForegroundProtection::class.java)
    return getSystemService(AccessibilityManager::class.java)
        .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any {
            val info = it.resolveInfo.serviceInfo
            ComponentName(info.packageName, info.name) == service
        }
}

internal fun Context.settingsPackage(): String =
    Intent(Settings.ACTION_SETTINGS).resolveActivity(packageManager)?.packageName ?: "com.android.settings"

internal class ForegroundProtection : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var rules: Preferences? = null
    private var lastRedirectAt = Long.MIN_VALUE
    private val settings by lazy { settingsPackage() }
    private val screenState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            grant = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(screenState, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        scope.launch { application.preferences.data.collect { rules = it } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val foreground = event.packageName?.toString() ?: return
        val now = SystemClock.uptimeMillis()
        val current = grant
        if (current != null && event.eventTime < current.issuedAt) return
        if (current != null && !current.entered && now - current.issuedAt > ENTRY_WINDOW_MS) grant = null

        if (foreground == packageName) {
            grant = null
            return
        }
        if (foreground == "com.android.systemui" || foreground == "com.android.permissioncontroller" ||
            foreground == "com.google.android.permissioncontroller"
        ) return

        if (rules?.get(protectionKey(foreground)) != "on" || foreground == settings) {
            if (grant?.entered == true && isActivity(foreground, event.className?.toString())) grant = null
            return
        }

        val allowed = grant
        if (allowed?.packageName == foreground) {
            allowed.entered = true
            return
        }
        grant = null
        if (lastRedirectAt == Long.MIN_VALUE || now - lastRedirectAt >= REDIRECT_INTERVAL_MS) {
            if (performGlobalAction(GLOBAL_ACTION_HOME)) lastRedirectAt = now
        }
    }

    private fun isActivity(packageName: String, className: String?): Boolean {
        if (className == null) return false
        return try {
            packageManager.getActivityInfo(
                ComponentName.createRelative(packageName, className),
                PackageManager.ComponentInfoFlags.of(0)
            )
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    override fun onInterrupt() {
        grant = null
    }

    override fun onDestroy() {
        grant = null
        unregisterReceiver(screenState)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ENTRY_WINDOW_MS = 5_000L
        private const val REDIRECT_INTERVAL_MS = 400L
        private data class Grant(val packageName: String, val issuedAt: Long, var entered: Boolean = false)
        private var grant: Grant? = null

        // The launcher and service run in the same process, on the main thread.
        fun authorize(packageName: String) {
            grant = Grant(packageName, SystemClock.uptimeMillis())
        }

        fun revoke() {
            grant = null
        }
    }
}
