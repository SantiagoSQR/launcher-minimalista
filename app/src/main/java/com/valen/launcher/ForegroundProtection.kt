package com.valen.launcher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    private var previousPackage: String? = null
    private val settings by lazy { settingsPackage() }
    private val screenState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            previousPackage = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(screenState, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        scope.launch { application.preferences.data.collect { rules = it } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val foreground = event.packageName?.toString()
        val fromSettings = previousPackage == settings
        previousPackage = foreground
        if (fromSettings && foreground != null && foreground != settings &&
            rules?.get(protectionKey(foreground)) == "on"
        ) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    override fun onInterrupt() {
        previousPackage = null
    }

    override fun onDestroy() {
        previousPackage = null
        unregisterReceiver(screenState)
        scope.cancel()
        super.onDestroy()
    }
}
