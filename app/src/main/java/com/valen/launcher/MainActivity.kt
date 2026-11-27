package com.valen.launcher

import android.app.Activity
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.widget.Toast
import android.content.Intent
import android.net.Uri
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

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
                info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL == 0 -> {
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

    /** True while Android's widget picker, bind or configuration screens are on top of the launcher. */
    val pickingWidget get() = widgetId != null || restoring != null

    // ---- Wallpaper blur behind the widgets page ---------------------------------------------------

    /** Whether this device can blur the wallpaper behind the launcher window right now. */
    var blurAvailable by mutableStateOf(false)
        private set
    private var blurRadius = 0
    private val blurListener = java.util.function.Consumer<Boolean> { blurAvailable = it }

    /** Blurs the wallpaper behind the window; [amount] goes from 0 (sharp) to 1 (fully blurred). */
    fun setWallpaperBlur(amount: Float) {
        val radius = if (blurAvailable) (amount.coerceIn(0f, 1f) * 36 * resources.displayMetrics.density).roundToInt() else 0
        if (radius == blurRadius) return
        blurRadius = radius
        window.setBackgroundBlurRadius(radius)
    }

    // ---- Backup -----------------------------------------------------------------------------------

    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val result = runCatching {
                val json = model.exportBackup()
                contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }
            widgetError(if (result.isSuccess) "Ajustes exportados" else "No se pudieron exportar los ajustes")
        }
    }

    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        importFrom(uri)
    }

    private fun exportSettings() {
        exportFile.launch("launcher-ajustes-${LocalDate.now()}.json")
    }

    private fun importSettings() {
        importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
    }

    private fun importFrom(uri: Uri) {
        lifecycleScope.launch {
            val result = runCatching {
                val json = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                model.importBackup(json)
            }
            result.onFailure { widgetError(it.message?.takeIf { m -> m.length < 80 } ?: "No se pudo leer el archivo") }
            result.onSuccess { missing ->
                if (missing.isEmpty()) widgetError("Ajustes importados")
                else {
                    widgetError("Ajustes importados. Restaurando ${missing.size} widget(s)…")
                    restoreQueue.addAll(missing)
                    restoreNext()
                }
            }
        }
    }

    /** Widgets from a backup still waiting to be bound again, one Android dialog at a time. */
    private val restoreQueue = ArrayDeque<Widget>()
    private var restoring: Pair<Int, Widget>? = null
    private var restoredSkipped = 0

    private val bindWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val (id, _) = restoring ?: return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) configureRestored() else {
            model.widgetHost.deleteAppWidgetId(id)
            restoring = null
            restoredSkipped++
            restoreNext()
        }
    }

    private fun restoreNext() {
        if (restoring != null) return
        val target = restoreQueue.removeFirstOrNull()
        if (target == null) {
            if (restoredSkipped > 0) widgetError("$restoredSkipped widget(s) no se pudieron restaurar")
            restoredSkipped = 0
            widgetRequest++
            return
        }
        val provider = ComponentName.unflattenFromString(target.provider)
        if (provider == null || model.widgetManager.installedProviders.none { it.provider == provider } ||
            model.isHidden(provider.packageName)) {
            restoredSkipped++
            restoreNext()
            return
        }
        val id = model.widgetHost.allocateAppWidgetId()
        restoring = id to target
        if (model.widgetManager.bindAppWidgetIdIfAllowed(id, provider)) configureRestored()
        else try {
            bindWidget.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider))
        } catch (_: ActivityNotFoundException) {
            model.widgetHost.deleteAppWidgetId(id)
            restoring = null
            restoredSkipped++
            restoreNext()
        }
    }

    private fun configureRestored() {
        val (id, _) = restoring ?: return
        val info = model.widgetManager.getAppWidgetInfo(id)
        val needsSetup = info?.configure != null &&
            info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL == 0
        if (!needsSetup) return finishRestored(info != null)
        try {
            model.widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, 43, null)
        } catch (_: RuntimeException) {
            finishRestored(false)
        }
    }

    private fun finishRestored(ok: Boolean) {
        val (id, target) = restoring ?: return
        restoring = null
        if (ok) model.placeRestoredWidget(id, target) else {
            model.widgetHost.deleteAppWidgetId(id)
            restoredSkipped++
        }
        restoreNext()
    }

    // ---- Lifecycle --------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = savedInstanceState?.getInt("widget-id", -1)?.takeIf { it >= 0 }
        widgetColumnWidth = savedInstanceState?.getFloat("widget-width") ?: 0f
        model.cleanWidgets(widgetId)
        val transparent = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = transparent, navigationBarStyle = transparent)
        setContent {
            LauncherScreen(
                model, this, isDefaultHome, homeRequest, widgetRequest, ::chooseHome, ::pickWidget,
                ::exportSettings, ::importSettings
            )
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        windowManager.addCrossWindowBlurEnabledListener(mainExecutor, blurListener)
    }

    override fun onDetachedFromWindow() {
        windowManager.removeCrossWindowBlurEnabledListener(blurListener)
        super.onDetachedFromWindow()
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
        model.noteLauncherVisible()
    }

    override fun onStart() {
        super.onStart()
        model.startWidgets()
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
        if (requestCode == 43) finishRestored(resultCode == Activity.RESULT_OK)
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

    override fun onPause() {
        val interrupted = model.cancelInterruption()
        val blocked = model.cancelBlockedAccess()
        if (interrupted || blocked) homeRequest++
        super.onPause()
    }

    override fun onStop() {
        model.cancelInterruption()
        if (!pickingWidget) homeRequest++
        model.stopWidgets()
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
