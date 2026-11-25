package com.valen.launcher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal fun lockedKey(packageName: String) = stringPreferencesKey("lock-foreground:$packageName")

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
    private var previousPackage: String? = null
    private val settings by lazy { settingsPackage() }
    private val screenState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            previousPackage = null
            revoke()
        }
    }

    override fun onCreate() {
        super.onCreate()
        service = this
        registerReceiver(screenState, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        scope.launch { application.preferences.data.collect { rules = it } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val foreground = event.packageName?.toString() ?: return
        val fromSettings = previousPackage == settings
        previousPackage = foreground
        if (foreground == "com.android.systemui" || foreground == "com.android.permissioncontroller" ||
            foreground == "com.google.android.permissioncontroller"
        ) return
        val allowed = session
        if (allowed != null) {
            if (event.eventTime < allowed.issuedAt) return
            if (foreground == allowed.packageName && (allowed.entered ||
                    SystemClock.uptimeMillis() - allowed.issuedAt <= 5_000L)
            ) {
                allowed.entered = true
                previousPackage = foreground
                return
            }
            if (foreground == packageName || foreground == settings ||
                isActivity(foreground, event.className?.toString()) ||
                rules?.get(lockedKey(foreground)) == "on"
            ) revoke()
        }
        if (foreground != settings && foreground != packageName &&
            (rules?.get(lockedKey(foreground)) == "on" ||
                (fromSettings && rules?.get(protectionKey(foreground)) == "on"))
        ) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun isActivity(packageName: String, className: String?): Boolean {
        if (className.isNullOrEmpty()) return false
        return try {
            packageManager.getActivityInfo(ComponentName.createRelative(packageName, className),
                PackageManager.ComponentInfoFlags.of(0))
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    override fun onInterrupt() {
        previousPackage = null
        revoke()
    }

    override fun onDestroy() {
        previousPackage = null
        if (service === this) {
            service = null
            revoke()
        }
        unregisterReceiver(screenState)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private data class Session(val packageName: String, val issuedAt: Long, var entered: Boolean = false)
        private var session: Session? = null
        private var service: ForegroundProtection? = null

        fun authorize(packageName: String) {
            session = Session(packageName, SystemClock.uptimeMillis())
        }

        fun revoke() {
            session = null
        }

        fun openQuickSettings() {
            service?.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        }
    }
}
