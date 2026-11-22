package com.valen.launcher

import android.app.Activity
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.widget.Toast
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private val model by viewModels<LauncherModel>()
    private var homeRequest by mutableIntStateOf(0)
    private val roles by lazy { getSystemService(RoleManager::class.java) }
    private var isDefaultHome by mutableStateOf(false)
    private val requestHome = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshHomeRole()
    }

    private var widgetId: Int? = null
    private var widgetColumnWidth = 0f
    private var widgetRequest by mutableIntStateOf(0)
    private val widgetPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = widgetId ?: return@registerForActivityResult
        val info = model.widgetManager.getAppWidgetInfo(id)
        when {
            result.resultCode != Activity.RESULT_OK || info == null -> finishWidget(false)
            model.isHidden(info.provider.packageName) -> {
                widgetError("La aplicación está oculta")
                finishWidget(false)
            }
            info.configure != null &&
                info.widgetFeatures and android.appwidget.AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL == 0 -> {
                try {
                    model.widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, 42, null)
                } catch (_: ActivityNotFoundException) {
                    finishWidget(false)
                    widgetError("No se pudo configurar el widget")
                } catch (_: SecurityException) {
                    finishWidget(false)
                    widgetError("No se pudo configurar el widget")
                }
            }
            else -> finishWidget(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = savedInstanceState?.getInt("widget-id", -1)?.takeIf { it >= 0 }
        widgetColumnWidth = savedInstanceState?.getFloat("widget-width") ?: 0f
        model.cleanWidgets(widgetId)
        val transparent = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = transparent, navigationBarStyle = transparent)
        setContent { LauncherScreen(model, this, isDefaultHome, homeRequest, widgetRequest, ::chooseHome, ::pickWidget) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homeRequest++
    }

    override fun onResume() {
        super.onResume()
        refreshHomeRole()
        model.refresh()
        model.refreshUsage()
    }

    override fun onStart() {
        super.onStart()
        model.widgetHost.startListening()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("widget-id", widgetId ?: -1)
        outState.putFloat("widget-width", widgetColumnWidth)
    }

    private fun pickWidget(columnWidth: Float) {
        if (widgetId != null) return
        val id = model.widgetHost.allocateAppWidgetId()
        widgetId = id
        widgetColumnWidth = columnWidth
        try {
            widgetPicker.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        } catch (_: ActivityNotFoundException) {
            finishWidget(false)
            widgetError("El selector de widgets no está disponible")
        }
    }

    @Deprecated("AppWidgetHost delivers configuration through this callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42) finishWidget(resultCode == Activity.RESULT_OK)
    }

    private fun finishWidget(added: Boolean) {
        val id = widgetId ?: return
        widgetId = null
        widgetRequest++
        if (added) model.addWidget(id, widgetColumnWidth) { widgetError("No se pudo añadir este widget") }
        else model.widgetHost.deleteAppWidgetId(id)
    }

    private fun widgetError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onStop() {
        model.cancelInterruption()
        if (widgetId == null) homeRequest++
        model.widgetHost.stopListening()
        super.onStop()
    }

    private fun refreshHomeRole() {
        isDefaultHome = roles.isRoleHeld(RoleManager.ROLE_HOME)
    }

    private fun chooseHome() {
        if (roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
            requestHome.launch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME))
        } else {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }
    }
}
