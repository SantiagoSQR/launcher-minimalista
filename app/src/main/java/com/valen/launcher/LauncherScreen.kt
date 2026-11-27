package com.valen.launcher

import android.widget.Toast
import android.content.Intent
import android.net.Uri
import android.content.ActivityNotFoundException
import java.util.Date
import android.provider.Settings
import java.time.LocalDate
import android.os.SystemClock
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class Screen { Home, Drawer, Settings, ManageApps, Favorites, App, Wait, Progression, HiddenGate, Hidden, LockedGate, Locked, Widgets, Stats, Theme, Help }

/** The favorites layouts offered in Ajustes, in the order the setting cycles through them. */
private val favoriteStyles = listOf("list" to "Lista", "fan" to "Abanico", "curve" to "Curva")

/**
 * Page to settle on after a release. A clear fling (in screens per second) goes to the next page in
 * its direction; otherwise moving 20% of the way away from the starting page is enough to change.
 */
private fun settleTarget(value: Float, velocity: Float, low: Float, high: Float, start: Float): Float = when {
    velocity > .35f -> kotlin.math.ceil(value)
    velocity < -.35f -> kotlin.math.floor(value)
    value - start > .2f -> kotlin.math.ceil(value)
    value - start < -.2f -> kotlin.math.floor(value)
    else -> start
}.coerceIn(low, high)

/** The page of the given parity (0 = home, 1 = widgets) closest to the ring position. */
private fun nearestPage(position: Float, parity: Int): Float =
    2f * kotlin.math.round((position - parity) / 2f) + parity

@Composable
fun LauncherScreen(
    model: LauncherModel, activity: MainActivity, defaultHome: Boolean, homeRequest: Int,
    widgetRequest: Int, chooseHome: () -> Unit, pickWidget: (Float) -> Unit,
    exportSettings: () -> Unit, importSettings: () -> Unit
) {
    val preferences by model.preferences.collectAsState()
    val palette = paletteFor(preferences?.get(paletteKey))
    CompositionLocalProvider(LocalPalette provides palette) {
        LauncherContent(model, activity, defaultHome, homeRequest, widgetRequest, chooseHome, pickWidget, exportSettings, importSettings)
    }
}

@Composable
private fun LauncherContent(
    model: LauncherModel, activity: MainActivity, defaultHome: Boolean, homeRequest: Int,
    widgetRequest: Int, chooseHome: () -> Unit, pickWidget: (Float) -> Unit,
    exportSettings: () -> Unit, importSettings: () -> Unit
) {
    val apps by model.apps.collectAsState()
    val dailyActivity by model.dailyActivity.collectAsState()
    val usage by model.usage.collectAsState()
    val attempt by model.interruption.collectAsState()
    val preferences by model.preferences.collectAsState()
    val palette = LocalPalette.current
    val leftHanded = preferences?.get(fanHandKey) == "left"
    val favoritesStyle = preferences?.get(favoritesStyleKey)?.takeIf { key -> favoriteStyles.any { it.first == key } } ?: "fan"
    fun savedOffset(value: String?): Offset {
        val parts = value?.split(',')
        fun coordinate(index: Int) = parts?.getOrNull(index)?.toFloatOrNull()?.takeIf { it.isFinite() } ?: 0f
        return Offset(coordinate(0), coordinate(1))
    }
    val fanPosition = remember(preferences?.get(fanPositionKey)) { savedOffset(preferences?.get(fanPositionKey)) }
    val raysPosition = remember(preferences?.get(fanRaysPositionKey)) { savedOffset(preferences?.get(fanRaysPositionKey)) }
    val retro = preferences?.get(appearanceKey) == "retro"
    val panel = palette.background
    val inter = FontFamily(Font(R.font.inter))
    val clockFont = remember(retro) {
        if (retro) FontFamily(Font(R.font.inter, weight = FontWeight.Bold,
            variationSettings = FontVariation.Settings(FontVariation.weight(700)))) else inter
    }
    val tone = TextStyle(color = palette.text, fontSize = 18.sp, fontFamily = inter)
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val settingsPackage = remember(context) { context.settingsPackage() }
    var protectionEnabled by remember(context) { mutableStateOf(context.protectionEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        protectionEnabled = context.protectionEnabled()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(Screen.Home) }
    var statsFromSettings by remember { mutableStateOf(false) }
    var manageQuery by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var autoArmed by remember { mutableStateOf(false) }
    var appOrigin by remember { mutableStateOf(Screen.Settings) }
    var returnToSearch by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<App?>(null) }
    var lockDeadline by remember { mutableLongStateOf(0) }
    var hiddenDeadline by remember { mutableLongStateOf(0) }
    var alias by remember { mutableStateOf("") }
    val favoriteIds = remember(preferences) { decodeFavorites(preferences?.get(favoritesKey)) }
    val favorites = remember(apps, favoriteIds) {
        val byId = apps.associateBy { it.id }
        favoriteIds.mapNotNull(byId::get)
    }
    fun locked(app: App) = preferences?.get(app.locked) == "on"
    val visibleFavorites = favorites.filterNot(::locked)

    // Page positions. ring: even = home, odd = widgets; the two pages wrap around like a carousel, so
    // the same swipe direction keeps alternating between them. sheet: 0 = closed, 1 = app search open.
    val ring = remember { Motion(scope) }
    val sheet = remember { Motion(scope) }
    var lastStep by remember { mutableFloatStateOf(1f) }
    val drag = remember { object { var low = -1f; var high = 1f; var start = 0f } }
    fun widgetsVisibility(): Float = (1f - abs(ring.value - nearestPage(ring.value, 1))).coerceIn(0f, 1f)
    fun settleRing(target: Float, velocity: Float = 0f) {
        ring.animateTo(target, velocity) { ring.snap(if (target.roundToInt().mod(2) == 0) 0f else 1f) }
    }

    fun title(app: App) = preferences?.get(app.alias)?.ifBlank { app.label } ?: app.label
    fun failed() {
        Toast.makeText(context, "No se pudo abrir la aplicación", Toast.LENGTH_SHORT).show()
    }
    fun cancel() {
        model.cancelInterruption()
        model.cancelBlockedAccess()
        screen = Screen.Home
        autoArmed = false
        query = ""
    }
    fun dismissWait() {
        model.cancelInterruption()
        model.cancelBlockedAccess()
        screen = if (returnToSearch) Screen.Drawer else Screen.Home
        autoArmed = false
        if (!returnToSearch) query = ""
    }
    fun open(app: App) {
        val data = preferences ?: return
        val hidden = data[app.hidden] == "on"
        val locked = data[app.locked] == "on"
        if (locked && screen != Screen.Locked) return
        if (hidden && screen != Screen.Hidden && !locked) return
        returnToSearch = screen == Screen.Drawer
        autoArmed = false
        keyboard?.hide()
        val gated = data[app.restricted] == "on" || data[app.protected] == "on" || hidden || locked
        if (gated && (locked || !model.isStillOpen(app))) {
            selected = app
            screen = Screen.Wait
            model.requestInterruption(app)
        } else {
            screen = Screen.Home
            query = ""
            if (!model.launch(app)) failed()
        }
    }
    fun configure(app: App) {
        appOrigin = screen
        autoArmed = false
        keyboard?.hide()
        selected = app
        alias = preferences?.get(app.alias).orEmpty()
        screen = Screen.App
    }
    fun lockScreen() {
        if (!ForegroundProtection.lockScreen()) Toast.makeText(context,
            "Para apagar la pantalla con doble toque, activá el servicio de accesibilidad del launcher",
            Toast.LENGTH_LONG).show()
    }
    fun openAlarms() {
        try {
            context.startActivity(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No hay una app de reloj para abrir", Toast.LENGTH_SHORT).show()
        }
    }
    fun closeSearch() {
        screen = Screen.Home
        query = ""
        autoArmed = false
    }
    LaunchedEffect(homeRequest) { cancel() }
    LaunchedEffect(widgetRequest) { if (widgetRequest != 0) screen = Screen.Widgets }
    // Coming back to the launcher starts on a still home screen instead of replaying a slide.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (!activity.pickingWidget) {
            ring.snap(0f)
            sheet.snap(0f)
        }
    }
    LaunchedEffect(screen) {
        // Decide from where the ring is headed, not where it is: right after a short swipe the page is
        // still near where it started, but the gesture already chose (and is animating to) its target.
        val goal = ring.target.roundToInt()
        val onHome = goal.mod(2) == 0
        when (screen) {
            Screen.Home -> {
                // Only programmatic returns (back, home button) need a move: back the way we came.
                if (!onHome) settleRing((goal - lastStep).toFloat())
                sheet.animateTo(0f)
            }
            Screen.Widgets -> {
                sheet.snap(0f)
                if (onHome) settleRing((goal + lastStep).toFloat())
                model.refreshWidgetUpdates()
            }
            Screen.Drawer -> {
                if (!onHome) ring.snap(0f)
                sheet.animateTo(1f)
            }
            else -> {
                ring.snap(0f)
                sheet.snap(0f)
            }
        }
    }
    BackHandler(screen != Screen.Home) {
        if (screen == Screen.Wait) dismissWait()
        else {
            model.cancelBlockedAccess()
            screen = when (screen) {
                Screen.ManageApps, Screen.Favorites, Screen.Progression, Screen.HiddenGate, Screen.Hidden,
                Screen.LockedGate, Screen.Locked, Screen.Theme, Screen.Help -> Screen.Settings
                Screen.App -> appOrigin
                Screen.Stats -> if (statsFromSettings) Screen.Settings else Screen.Home
                else -> Screen.Home
            }
            if (screen != Screen.Drawer) query = ""
        }
    }

    val focus = remember { FocusRequester() }
    val normalized = remember(query) { searchKey(query.trim()) }
    val searchIndex = remember(apps, preferences) {
        if (preferences == null) emptyList()
        else apps.filter { preferences?.get(it.hidden) != "on" && !locked(it) }
            .map { it to searchKey(title(it)) }
            .sortedBy { it.second }
    }
    val results = remember(normalized, searchIndex) {
        searchIndex.mapNotNull { (app, shown) ->
            val rank = matchRank(normalized, shown)
            if (rank == Int.MAX_VALUE) null else app to rank
        }.sortedBy { it.second }
    }
    val sole = results.singleOrNull()
    // A query that spells out exactly one app's name or alias picks it even when other apps also
    // contain those letters (short aliases usually do); a single remaining result is picked too.
    val exact = results.filter { it.second == 0 }.singleOrNull()?.first
    val auto = preferences?.get(autoKey) != "off"
    val automaticApp = (sole?.first ?: exact)?.takeIf {
        (preferences?.get(it.restricted) != "on" && preferences?.get(it.protected) != "on") || model.isStillOpen(it)
    }
    LaunchedEffect(screen, query, automaticApp, auto, autoArmed) {
        if (screen == Screen.Drawer && auto && autoArmed && normalized.isNotEmpty() && automaticApp != null) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                delay(250)
                open(automaticApp)
            }
        }
    }
    // The keyboard waits for the sheet to finish sliding: resizing the page for it mid-slide stutters.
    LaunchedEffect(screen) {
        if (screen == Screen.Drawer) {
            snapshotFlow { sheet.value }.first { it >= .999f }
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }

    val stack = screen == Screen.Home || screen == Screen.Widgets || screen == Screen.Drawer
    // Blur the wallpaper as the widgets page comes in. Quantized, since each change is a window update.
    LaunchedEffect(stack, activity.blurAvailable) {
        if (!stack) activity.setWallpaperBlur(0f)
        else snapshotFlow { (widgetsVisibility() * 6).roundToInt() / 6f }.distinctUntilChanged()
            .collect { activity.setWallpaperBlur(it) }
    }

    if (stack) BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val height = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val widgetsShown by remember { derivedStateOf { widgetsVisibility() > 0f } }
        val searchShown by remember { derivedStateOf { sheet.value > 0f } }
        fun settleSheet(velocity: Float) {
            val target = settleTarget(sheet.value, velocity, 0f, 1f, if (screen == Screen.Drawer) 1f else 0f)
            sheet.animateTo(target, velocity)
            if (target == 1f) {
                if (screen != Screen.Drawer) {
                    query = ""
                    autoArmed = false
                    screen = Screen.Drawer
                }
            } else if (screen == Screen.Drawer) closeSearch()
        }
        Box(
            Modifier.fillMaxSize().pageDrag(
                accept = { axis, delta ->
                    val resting = abs(ring.value - ring.value.roundToInt()) < .001f
                    when (axis) {
                        Axis.Horizontal -> {
                            val ok = screen != Screen.Drawer && sheet.value == 0f
                            if (ok) {
                                drag.start = ring.value.roundToInt().toFloat()
                                drag.low = drag.start - 1f
                                drag.high = drag.start + 1f
                            }
                            ok
                        }
                        Axis.Vertical -> {
                            val onHome = screen == Screen.Home && resting && ring.value.roundToInt().mod(2) == 0
                            // Pulling down on the home screen opens Quick Settings (through the
                            // accessibility service, when it is on); pulling up opens the search.
                            if (onHome && delta.y > 0) ForegroundProtection.openQuickSettings()
                            onHome && delta.y < 0
                        }
                    }
                },
                onDrag = { axis, delta ->
                    when (axis) {
                        Axis.Horizontal -> ring.snap((ring.value - delta.x / width).coerceIn(drag.low, drag.high))
                        Axis.Vertical -> sheet.snap((sheet.value - delta.y / height).coerceIn(0f, 1f))
                    }
                },
                onEnd = { axis, velocity ->
                    when (axis) {
                        Axis.Horizontal -> {
                            val speed = -velocity.x / width
                            val target = settleTarget(ring.value, speed, drag.low, drag.high, drag.start)
                            if (target != drag.start) lastStep = target - drag.start
                            settleRing(target, speed)
                            val widgets = target.roundToInt().mod(2) == 1
                            if (widgets && screen != Screen.Widgets) screen = Screen.Widgets
                            else if (!widgets && screen == Screen.Widgets) screen = Screen.Home
                        }
                        Axis.Vertical -> settleSheet(-velocity.y / height)
                    }
                }
            )
        ) {
            // Home: stays transparent over the wallpaper.
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    translationX = (nearestPage(ring.value, 0) - ring.value) * width
                }.safeDrawingPadding()
            ) {
                HomePage(
                    model, context, lifecycle, tone, clockFont, retro, defaultHome, chooseHome,
                    visibleFavorites, ::title, favoritesStyle, leftHanded, fanPosition, raysPosition, dailyActivity, usage,
                    openSettings = { screen = Screen.Settings },
                    openDrawer = { screen = Screen.Drawer },
                    openWidgets = { screen = Screen.Widgets },
                    openStats = { statsFromSettings = false; screen = Screen.Stats },
                    open = ::open, configure = ::configure,
                    lockScreen = ::lockScreen, openAlarms = ::openAlarms
                )
            }
            // Widgets: the wallpaper shows through, blurred behind a light tint of the theme. Kept
            // composed so the hosted widget views live as long as the home screen.
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    translationX = (nearestPage(ring.value, 1) - ring.value) * width
                }.background(panel.copy(alpha = if (activity.blurAvailable) .35f else .72f))
                    .safeDrawingPadding()
                    .then(if (widgetsShown || screen == Screen.Widgets) Modifier else Modifier.clearAndSetSemantics {})
            ) {
                WidgetPanel(model, activity, tone, pickWidget, ::lockScreen)
            }
            // App search: a sheet that follows the finger up from the bottom and back down.
            if (searchShown || screen == Screen.Drawer) {
                val listState = rememberLazyListState()
                val pullDown = remember(listState) {
                    object : NestedScrollConnection {
                        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                            if (source != NestedScrollSource.UserInput || sheet.value >= 1f || available.y >= 0f) return Offset.Zero
                            sheet.snap((sheet.value - available.y / height).coerceAtMost(1f))
                            return Offset(0f, available.y)
                        }

                        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                            if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                            keyboard?.hide()
                            sheet.snap((sheet.value - available.y / height).coerceAtLeast(0f))
                            return Offset(0f, available.y)
                        }

                        override suspend fun onPreFling(available: Velocity): Velocity {
                            if (sheet.value >= 1f) return Velocity.Zero
                            settleSheet(-available.y / height)
                            return available
                        }
                    }
                }
                Box(
                    Modifier.fillMaxSize().graphicsLayer { translationY = (1f - sheet.value) * height }
                        .background(panel).safeDrawingPadding().imePadding()
                        .pageDrag(
                            accept = { axis, delta -> axis == Axis.Vertical && delta.y > 0 && screen == Screen.Drawer },
                            onDrag = { _, delta ->
                                keyboard?.hide()
                                sheet.snap((sheet.value - delta.y / height).coerceIn(0f, 1f))
                            },
                            onEnd = { _, velocity -> settleSheet(-velocity.y / height) }
                        )
                ) {
                    Column(Modifier.padding(horizontal = 24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).background(palette.raised, RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp)) {
                                Input(query, { query = it; autoArmed = true }, "Buscar aplicaciones", tone,
                                    Modifier.focusRequester(focus), onOpen = { (sole?.first ?: exact)?.let(::open) })
                            }
                            if (query.isNotEmpty()) Action("Borrar", tone) { query = ""; autoArmed = false }
                        }
                        if (results.isEmpty() && normalized.isNotEmpty()) BasicText("Sin resultados", style = tone)
                        val indexed = normalized.isEmpty() && results.size > 12
                        val firstByLetter = remember(results, indexed) {
                            if (!indexed) emptyMap()
                            else buildMap { results.forEachIndexed { i, (app, _) -> putIfAbsent(indexLetter(title(app)), i) } }
                        }
                        Row(Modifier.weight(1f)) {
                            @Composable
                            fun rail() {
                                if (indexed) LetterRail(
                                    firstByLetter.keys.toList(), tone, leftHanded,
                                    onTouch = { keyboard?.hide(); autoArmed = false },
                                    onSelect = { letter -> firstByLetter[letter]?.let { scope.launch { listState.scrollToItem(it) } } },
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                            if (leftHanded) rail()
                            LazyColumn(
                                Modifier.weight(1f).nestedScroll(pullDown).pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        autoArmed = false
                                    }
                                },
                                state = listState
                            ) {
                                items(results, key = { it.first.id }) { (app, _) ->
                                    val shown = title(app)
                                    val ambiguous = results.any { it.first.id != app.id && title(it.first) == shown }
                                    val label = if (ambiguous && shown != app.label) "$shown · ${app.label}" else shown
                                    AppRow(label, tone, { open(app) }, { configure(app) }, retroList = retro)
                                }
                            }
                            if (!leftHanded) rail()
                        }
                    }
                }
            }
        }
    } else Box(Modifier.fillMaxSize().background(panel).safeDrawingPadding().imePadding()) {
        when (screen) {
            Screen.Home, Screen.Drawer, Screen.Widgets -> Unit
            Screen.Settings -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                item {
                    ManagementTitle("Ajustes", tone)
                    ManagementGroup("Ayuda", tone) {
                        ManagementAction("Gestos y funciones", tone) { screen = Screen.Help }
                    }
                    ManagementGroup("Favoritos", tone) {
                        ManagementAction("Ordenar favoritos", tone) { screen = Screen.Favorites }
                        ManagementAction("Formato", tone, favoriteStyles.first { it.first == favoritesStyle }.second) {
                            val index = favoriteStyles.indexOfFirst { it.first == favoritesStyle }
                            model.save(favoritesStyleKey, favoriteStyles[(index + 1) % favoriteStyles.size].first)
                        }
                        ManagementAction("Mano", tone, if (leftHanded) "Izquierda" else "Derecha") {
                            model.save(fanHandKey, if (leftHanded) "right" else "left")
                        }
                    }
                    ManagementGroup("Apariencia", tone) {
                        ManagementAction("Estilo", tone, if (retro) "Retro" else "Clásico") {
                            model.save(appearanceKey, if (retro) "classic" else "retro")
                        }
                        ManagementAction("Tema", tone, palette.name) { screen = Screen.Theme }
                    }
                    ManagementGroup("Apertura de apps", tone) {
                        ManagementSwitch("Apertura automática", auto, tone) {
                            model.save(autoKey, if (it) "on" else "off")
                        }
                        ManagementAction("Demoras progresivas", tone) { screen = Screen.Progression }
                        ManagementAction("Gestionar aplicaciones", tone) {
                            manageQuery = ""
                            screen = Screen.ManageApps
                        }
                    }
                    ManagementGroup("Protección", tone) {
                        HiddenAccess(tone) {
                            val seconds = preferences?.get(hiddenDelayKey)?.toIntOrNull() ?: 10
                            hiddenDeadline = SystemClock.elapsedRealtime() + seconds * 1000L
                            screen = Screen.HiddenGate
                        }
                        if (apps.any(::locked)) {
                            ManagementAction("Apps bloqueadas", tone, "10 min") {
                                lockDeadline = model.beginBlockedAccess()
                                screen = Screen.LockedGate
                            }
                            if (!protectionEnabled) ManagementAction("Activar protección de aperturas externas", tone) {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        }
                    }
                    ManagementGroup("Uso", tone) {
                        ManagementAction("Estadísticas", tone) {
                            statsFromSettings = true
                            screen = Screen.Stats
                        }
                    }
                    ManagementGroup("Copia de seguridad", tone) {
                        ManagementAction("Exportar ajustes", tone, "↗", exportSettings)
                        ManagementAction("Importar ajustes", tone, "↙", importSettings)
                        BasicText("Guarda favoritos, alias, demoras, bloqueos, tema, historial y la ubicación de los widgets en un archivo .json.",
                            Modifier.padding(bottom = 12.dp), style = tone.copy(fontSize = 13.sp, color = palette.text.copy(alpha = .7f)))
                    }
                    if (!defaultHome) ManagementAction(stringResource(R.string.choose_home), tone, action = chooseHome)
                    Spacer(Modifier.height(20.dp))
                }
            }
            Screen.Help -> HelpPanel(tone)
            Screen.Theme -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                ManagementTitle("Tema", tone)
                ManagementGroup("Colores", tone) {
                    palettes.forEach { option ->
                        val chosen = option.key == palette.key
                        Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { model.save(paletteKey, option.key) }
                            .heightIn(min = 56.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.background(option.background, RoundedCornerShape(10.dp)).padding(6.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(option.surface, option.strong, option.accent, option.text).forEach {
                                    Box(Modifier.size(18.dp).background(it, CircleShape))
                                }
                            }
                            BasicText(option.name, Modifier.weight(1f).padding(horizontal = 12.dp), style = tone.copy(fontSize = 16.sp))
                            Box(Modifier.size(20.dp).border(1.5.dp, palette.accent, CircleShape).padding(5.dp)) {
                                if (chosen) Box(Modifier.fillMaxSize().background(palette.accent, CircleShape))
                            }
                        }
                    }
                }
            }
            Screen.ManageApps -> Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                ManagementTitle("Gestionar aplicaciones", tone)
                Input(manageQuery, { manageQuery = it }, "Buscar aplicaciones", tone)
                val term = searchKey(manageQuery.trim())
                val matches = if (preferences == null) emptyList()
                    else apps.filter { !locked(it) && term in searchKey(title(it)) }
                if (matches.isEmpty()) BasicText("Sin resultados", style = tone)
                LazyColumn {
                    items(matches, key = { it.id }) { app ->
                        ManagementAction(title(app), tone, if (preferences?.get(app.hidden) == "on") "Oculta" else "›") {
                            configure(app)
                        }
                    }
                }
            }
            Screen.App -> selected?.let { app ->
              var confirmLock by remember(app.id) { mutableStateOf(false) }
              BackHandler(confirmLock) { confirmLock = false }
              Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                    val restricted = preferences?.get(app.restricted) == "on"
                    val hidden = preferences?.get(app.hidden) == "on"
                    val isLocked = locked(app)
                    val protected = preferences?.get(app.protected) == "on"
                    val favorite = app.id in favoriteIds
                    ManagementTitle(title(app), tone)
                    val status = listOfNotNull(
                        "Bloqueada".takeIf { isLocked },
                        "Oculta".takeIf { hidden && !isLocked },
                        "Con demora".takeIf { !isLocked && (restricted || protected) },
                        "En favoritos".takeIf { favorite }
                    ).joinToString(" · ")
                    if (status.isNotEmpty()) BasicText(status, style = tone.copy(fontSize = 14.sp, color = palette.accent))
                    ManagementGroup("Nombre y acceso", tone) {
                        Input(alias, { alias = it }, "Alias", tone)
                        ManagementAction("Guardar alias", tone) {
                            model.save(app.alias, alias.trim())
                            keyboard?.hide()
                            screen = appOrigin
                        }
                        if (!hidden && !isLocked) {
                            ManagementAction(if (favorite) "Quitar de favoritos" else "Añadir a favoritos", tone) {
                                model.toggleFavorite(app)
                            }
                        }
                    }
                    ManagementGroup("Apertura", tone) {
                        ManagementSwitch("Pausa al abrir", restricted, tone) {
                            model.save(app.restricted, if (it) "on" else "off")
                        }
                        if (app.component.packageName != settingsPackage) {
                            ManagementSwitch("Protección desde Ajustes", protected, tone) {
                                model.save(app.protected, if (it) "on" else "off")
                            }
                            if ((protected || isLocked) && !protectionEnabled) {
                                ManagementAction("Abrir ajustes de accesibilidad", tone) {
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                }
                            }
                        }
                    }
                    ManagementGroup("Visibilidad y bloqueo", tone) {
                        ManagementAction(if (hidden) "Mostrar aplicación" else "Ocultar aplicación", tone) {
                            model.toggleHidden(app) { message ->
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        }
                        if (app.component.packageName != settingsPackage) {
                            if (isLocked) {
                                BasicText("Para quitar el bloqueo, entrá en Apps bloqueadas.",
                                    Modifier.padding(vertical = 10.dp), style = tone.copy(fontSize = 14.sp))
                            } else ManagementAction("Bloquear app", tone) { confirmLock = true }
                        }
                    }
                    ManagementGroup("Android", tone) {
                        ManagementAction("Información de la app", tone, "↗") {
                            try {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", app.component.packageName, null)))
                            } catch (_: ActivityNotFoundException) {
                                failed()
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
                if (confirmLock) LockConfirmation(title(app), tone, cancel = { confirmLock = false }) {
                    model.save(app.locked, "on")
                    confirmLock = false
                }
              }
            }
            Screen.Stats -> StatisticsPanel(
                if (preferences == null) emptyList() else dailyActivity, usage, LocalDate.now(),
                { id, label -> apps.firstOrNull { it.id == id }?.let(::title) ?: label },
                { id ->
                    android.content.ComponentName.unflattenFromString(id)?.packageName?.let { pkg ->
                        preferences?.get(hiddenKey(pkg)) == "on" || preferences?.get(lockedKey(pkg)) == "on"
                    } == true
                }, tone
            ) {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            Screen.HiddenGate -> WaitPanel("Aplicaciones ocultas", hiddenDeadline, tone, confirm = {
                screen = Screen.Hidden
            }, cancel = { screen = Screen.Settings })
            Screen.Hidden -> LazyColumn(Modifier.padding(horizontal = 24.dp)) {
                item { BasicText("Aplicaciones ocultas", style = tone.copy(fontSize = 28.sp)) }
                val hidden = apps.filter { preferences?.get(it.hidden) == "on" && !locked(it) }
                if (hidden.isEmpty()) item { BasicText("No hay aplicaciones ocultas", style = tone) }
                items(hidden, key = { it.id }) { Action(title(it), tone) { open(it) } }
            }
            Screen.LockedGate -> {
                val view = LocalView.current
                DisposableEffect(view) {
                    val previous = view.keepScreenOn
                    view.keepScreenOn = true
                    onDispose { view.keepScreenOn = previous }
                }
                WaitPanel("Apps bloqueadas", lockDeadline, tone, confirm = {
                    if (model.confirmBlockedAccess()) screen = Screen.Locked
                }, cancel = {
                    model.cancelBlockedAccess()
                    screen = Screen.Settings
                })
            }
            Screen.Locked -> LazyColumn(Modifier.padding(horizontal = 24.dp)) {
                item { BasicText("Apps bloqueadas", style = tone.copy(fontSize = 28.sp)) }
                items(apps.filter(::locked), key = { it.id }) { app ->
                    Action(title(app), tone) { open(app) }
                    Action("Quitar bloqueo de ${title(app)}", tone) { model.unlock(app) }
                }
            }
            Screen.Wait -> WaitPanel(selected?.let(::title).orEmpty(), attempt?.deadline, tone, attempt?.openingCount, {
                model.completeInterruption()?.let { opened ->
                    screen = Screen.Home
                    query = ""
                    if (!opened) failed()
                }
            }, ::dismissWait)
            Screen.Progression -> {
                var value by remember { mutableStateOf(preferences?.get(progressionKey) ?: defaultProgression.joinToString(", ")) }
                var hiddenValue by remember { mutableStateOf(preferences?.get(hiddenDelayKey) ?: "10") }
                var resumeValue by remember { mutableStateOf(preferences?.get(resumeWindowKey) ?: defaultResumeMinutes.toString()) }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                    BasicText("Demoras en segundos", style = tone.copy(fontSize = 28.sp))
                    BasicText("Separá los valores con comas", style = tone)
                    Input(value, { value = it }, "5, 10, 20, 30, 45, 60", tone)
                    BasicText("Espera para aplicaciones ocultas", style = tone)
                    Input(hiddenValue, { hiddenValue = it }, "10", tone)
                    BasicText("Minutos en que una app sigue abierta", style = tone)
                    BasicText("Si la usaste hace menos que esto y no apagaste la pantalla, no vuelve a esperar. 0 para esperar siempre.",
                        style = tone.copy(fontSize = 14.sp, color = palette.text.copy(alpha = .7f)))
                    Input(resumeValue, { resumeValue = it }, defaultResumeMinutes.toString(), tone)
                    Action("Guardar demoras", tone) {
                        val delays = parseDelays(value)
                        val hiddenSeconds = hiddenValue.trim().toIntOrNull()
                        val resumeMinutes = resumeValue.trim().toIntOrNull()
                        if (delays == null || hiddenSeconds == null || hiddenSeconds < 0 || resumeMinutes == null || resumeMinutes < 0) {
                            Toast.makeText(context, "Ingresá uno o más enteros no negativos", Toast.LENGTH_SHORT).show()
                        } else {
                            model.saveDelays(delays, hiddenSeconds)
                            model.save(resumeWindowKey, resumeMinutes.toString())
                            keyboard?.hide()
                            screen = Screen.Settings
                        }
                    }
                }
            }
            Screen.Favorites -> Favorites(favorites, ::title, tone) {
                val current = it.map(App::id)
                model.save(favoritesKey, JSONArray(current + favoriteIds.filterNot { id -> favorites.any { app -> app.id == id } }).toString())
            }
        }
    }
}

@Composable
private fun HomePage(
    model: LauncherModel, context: android.content.Context, lifecycle: Lifecycle, tone: TextStyle,
    clockFont: FontFamily, retro: Boolean, defaultHome: Boolean, chooseHome: () -> Unit,
    favorites: List<App>, title: (App) -> String, favoritesStyle: String, leftHanded: Boolean, fanPosition: Offset,
    raysPosition: Offset,
    dailyActivity: List<DailyActivity>, usage: Map<String, Long>?,
    openSettings: () -> Unit, openDrawer: () -> Unit, openWidgets: () -> Unit, openStats: () -> Unit,
    open: (App) -> Unit, configure: (App) -> Unit,
    lockScreen: () -> Unit, openAlarms: () -> Unit,
) {
    val contextLocale = LocalConfiguration.current.locales[0]
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    val day = now.toLocalDate()
    LaunchedEffect(day) { model.refreshUsage() }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = ZonedDateTime.now()
                delay(60_000L - now.second * 1000L - now.nano / 1_000_000L)
            }
        }
    }
    BoxWithConstraints(
        // Long press opens the settings; double tap on empty space turns the screen off. Taps on
        // favorites, the clock and other buttons are consumed by them and never reach this.
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(onLongPress = { openSettings() }, onDoubleTap = { lockScreen() })
        },
        contentAlignment = Alignment.TopCenter
    ) {
        val favoritesHeight = maxHeight * 0.55f
        Column(
            Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(Modifier.padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(
                    android.text.format.DateFormat.getTimeFormat(context).format(Date.from(now.toInstant())),
                    // Tapping the clock opens the alarms of the system clock app.
                    modifier = Modifier.clickable(onClickLabel = "Abrir alarmas", onClick = openAlarms).semantics {
                        customActions = listOf(
                            CustomAccessibilityAction("Abrir aplicaciones") { openDrawer(); true },
                            CustomAccessibilityAction("Abrir ajustes") { openSettings(); true },
                            CustomAccessibilityAction("Abrir widgets") { openWidgets(); true }
                        )
                    },
                    style = tone.copy(
                        fontSize = 58.sp,
                        fontFamily = clockFont,
                        fontWeight = if (retro) FontWeight.Bold else tone.fontWeight,
                        fontFeatureSettings = "tnum"
                    )
                )
                val date = now.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(contextLocale))
                BasicText(date, style = tone.copy(fontSize = 16.sp))
                val openings = dailyActivity.filter { it.day == day.toString() }.sumOf { it.openings }
                val estimate = usage?.get(day.toString())?.let(::usageText)
                val count = if (openings == 1) "1 apertura" else "$openings aperturas"
                val summary = listOfNotNull(estimate?.let { "$it de uso estimado" }, count).joinToString(" · ")
                Action(summary, tone.copy(fontSize = 13.sp), action = openStats)
                if (!defaultHome) Action(stringResource(R.string.choose_home), tone, action = chooseHome)
            }
            when {
                favorites.isEmpty() -> Unit
                favoritesStyle == "fan" -> FavoritesFan(favorites, title, tone, leftHanded, open, configure, Modifier.weight(1f),
                    raysPosition) { model.save(fanRaysPositionKey, "${it.x},${it.y}") }
                favoritesStyle == "list" -> {
                    // Scrolls only when the list overflows, so a swipe up anywhere still opens the search.
                    val scroll = rememberScrollState()
                    Column(
                        Modifier.heightIn(max = favoritesHeight).verticalScroll(scroll, enabled = scroll.maxValue > 0)
                            .padding(bottom = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        favorites.forEach { app ->
                            key(app.id) { AppRow(title(app), tone.copy(fontWeight = FontWeight.Medium), { open(app) }, { configure(app) }) }
                        }
                    }
                }
            }
        }
        if (favoritesStyle == "curve" && favorites.isNotEmpty()) {
            FanFavorites(favorites, title, tone, leftHanded, Modifier.matchParentSize(), fanPosition,
                { model.save(fanPositionKey, "${it.x},${it.y}") },
                open, configure, { model.save(fanHandKey, if (leftHanded) "right" else "left") })
        }
    }
}

@Composable
private fun Favorites(apps: List<App>, title: (App) -> String, style: TextStyle, save: (List<App>) -> Unit) {
    var order by remember(apps) { mutableStateOf(apps) }
    val state = rememberLazyListState()
    val latestSave by rememberUpdatedState(save)
    LazyColumn(Modifier.padding(horizontal = 24.dp), state = state) {
        item { BasicText("Arrastrá para ordenar", style = style) }
        items(order, key = { it.id }) { app ->
            val index = order.indexOf(app)
            fun move(to: Int): Boolean {
                val from = order.indexOf(app)
                if (from < 0 || to !in order.indices) return false
                val next = order.toMutableList().apply { add(to, removeAt(from)) }
                order = next
                latestSave(next)
                return true
            }
            Row(Modifier.semantics {
                customActions = buildList {
                    if (index > 0) add(CustomAccessibilityAction("Subir") { move(index - 1) })
                    if (index < order.lastIndex) add(CustomAccessibilityAction("Bajar") { move(index + 1) })
                }
            }, verticalAlignment = Alignment.CenterVertically) {
                BasicText(title(app), Modifier.weight(1f).padding(vertical = 14.dp), style = style)
                BasicText(
                    "↕",
                    Modifier.pointerInput(app.id) {
                        var center = 0f
                        detectDragGestures(
                            onDragStart = {
                                val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == app.id }
                                center = item?.let { row -> row.offset + row.size / 2f } ?: 0f
                            },
                            onDragEnd = { latestSave(order) },
                            onDragCancel = { order = apps }
                        ) { change, amount ->
                            change.consume()
                            center += amount.y
                            val target = state.layoutInfo.visibleItemsInfo.firstOrNull {
                                center >= it.offset && center < it.offset + it.size && it.key is String
                            }
                            val from = order.indexOf(app)
                            val to = order.indexOfFirst { it.id == target?.key }
                            if (from >= 0 && to >= 0 && from != to) {
                                order = order.toMutableList().apply { add(to, removeAt(from)) }
                            }
                        }
                    }.padding(16.dp),
                    style = style
                )
            }
        }
    }
}

private fun matchRank(query: String, value: String): Int = when {
    value == query -> 0
    value.startsWith(query) -> 1
    value.split(' ', '-', '_').any { it.startsWith(query) } -> 2
    query in value -> 3
    else -> Int.MAX_VALUE
}

@Composable
private fun RetroHeading(label: String, style: TextStyle) {
    BasicText(label, Modifier.padding(vertical = 12.dp), style = style.copy(fontWeight = FontWeight.Medium))
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color.Gray))
}

@Composable
private fun AppRow(label: String, style: TextStyle, open: () -> Unit, edit: () -> Unit, retroList: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val row = if (retroList) Modifier.fillMaxWidth().background(if (pressed) LocalPalette.current.pressed else Color.Transparent) else Modifier
    Box(row.combinedClickable(interactionSource = interaction, indication = if (retroList) null else androidx.compose.foundation.LocalIndication.current,
        role = Role.Button, onClick = open, onLongClickLabel = "Editar aplicación", onLongClick = edit)
        .sizeIn(minWidth = 48.dp, minHeight = 48.dp).padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) { BasicText(label, style = style) }
}

@Composable
internal fun Action(label: String, style: TextStyle, retroList: Boolean = false, action: () -> Unit) {
    if (retroList) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        BasicText(label, Modifier.fillMaxWidth().background(if (pressed) LocalPalette.current.pressed else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = action)
            .padding(vertical = 12.dp, horizontal = 8.dp).heightIn(min = 24.dp),
            style = style)
        return
    }
    BasicText(label, Modifier.clickable(role = Role.Button, onClick = action).padding(vertical = 12.dp, horizontal = 8.dp)
        .sizeIn(minWidth = 32.dp, minHeight = 24.dp), style = style)
}

@Composable
internal fun Input(value: String, change: (String) -> Unit, hint: String, style: TextStyle, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null) {
    BasicTextField(value, change, modifier.fillMaxWidth().semantics { contentDescription = hint }.padding(vertical = 16.dp), singleLine = true,
        keyboardOptions = if (onOpen == null) KeyboardOptions.Default else KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onOpen?.invoke() }),
        textStyle = style, cursorBrush = SolidColor(style.color), decorationBox = { field ->
            Box {
                if (value.isEmpty()) BasicText(hint, style = style.copy(color = style.color.copy(alpha = 0.65f)))
                field()
            }
        })
}

@Composable
private fun HiddenAccess(style: TextStyle, open: () -> Unit) {
    val currentOpen by rememberUpdatedState(open)
    BasicText(
        "Aplicaciones ocultas · mantené presionado",
        Modifier.semantics { onLongClick("Esperar para entrar") { currentOpen(); true } }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val released = withTimeoutOrNull(1500) {
                        waitForUpOrCancellation(PointerEventPass.Main)
                        true
                    }
                    if (released == null) {
                        down.consume()
                        currentOpen()
                    }
                }
            }
            .padding(vertical = 12.dp, horizontal = 8.dp).heightIn(min = 24.dp),
        style = style
    )
}

/** What the launcher can do and how to reach each thing, for someone opening it for the first time. */
@Composable
private fun HelpPanel(style: TextStyle) {
    val palette = LocalPalette.current
    val sections = listOf(
        "En el inicio" to listOf(
            "Deslizá hacia arriba" to "Abre el buscador de aplicaciones.",
            "Deslizá hacia los costados" to "Pasa a los widgets. Seguí en la misma dirección para volver al inicio, como si dieras la vuelta.",
            "Deslizá hacia abajo" to "Abre los ajustes rápidos de Android (requiere activar el servicio de accesibilidad del launcher).",
            "Mantené presionado el fondo" to "Abre estos ajustes.",
            "Doble toque en un espacio vacío" to "Apaga la pantalla (también en los widgets, fuera de los widgets). Requiere activar el servicio de accesibilidad del launcher.",
            "Tocá la hora" to "Abre las alarmas de la app de reloj.",
            "Tocá el resumen de aperturas" to "Abre las estadísticas.",
            "Gesto de volver" to "Desde cualquier pantalla, regresa al inicio.",
        ),
        "Favoritos" to listOf(
            "Tocá una app" to "La abre.",
            "Mantené presionada una app" to "Abre su configuración: alias, pausa, ocultar, bloquear.",
            "Abanico" to "Apoyá el dedo en el punto de la esquina y deslizalo hacia una app; al soltar se abre. Si lo mantenés quieto un momento y después arrastrás, movés todo el abanico.",
            "Curva" to "Tocá el botón redondo para cambiar de mano. Deslizá desde el botón hasta una app para abrirla. Mantenelo presionado y arrastrá para mover la curva.",
            "Formato y mano" to "Se cambian en Ajustes › Favoritos.",
        ),
        "Buscador" to listOf(
            "Escribí el nombre o el alias" to "Si queda una sola app, o el texto coincide exacto con un nombre o alias, se abre sola (Apertura automática).",
            "Barra de letras" to "Deslizá el dedo sobre las letras del costado para saltar a esa letra.",
            "Deslizá hacia abajo" to "Con la lista arriba de todo, cierra el buscador.",
            "Mantené presionada una app" to "Abre su configuración.",
        ),
        "Widgets" to listOf(
            "Editar" to "Permite añadir widgets, moverlos, cambiarles el tamaño y quitarlos.",
        ),
        "Pausas y protección" to listOf(
            "Pausa al abrir" to "Antes de abrir la app hay una cuenta regresiva que crece con cada apertura del día (Demoras progresivas).",
            "Apps que siguen abiertas" to "Si pasaste la pausa y la usaste hace poco, no vuelve a esperar hasta que apagues la pantalla o pase el tiempo configurado.",
            "Aplicaciones ocultas" to "No aparecen en el buscador. Para verlas, mantené presionada esa opción en Ajustes y esperá.",
            "Apps bloqueadas" to "Para abrirlas hay que esperar diez minutos con la pantalla encendida en Ajustes › Apps bloqueadas. Antes de bloquear una, te pide confirmar y esperar cinco segundos.",
        ),
        "Apariencia" to listOf(
            "Estilo" to "Clásico o Retro (reloj en negrita y filas planas en el buscador).",
            "Tema" to "Cinco paletas de color: Café, Azul, Verde, Rojizo y Morado.",
        ),
        "Copia de seguridad" to listOf(
            "Exportar e importar" to "Guarda y recupera favoritos, alias, demoras, bloqueos, tema, historial y widgets en un archivo .json. Al importar, Android pide permiso para volver a crear cada widget.",
        ),
    )
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        item { ManagementTitle("Gestos y funciones", style) }
        sections.forEach { (title, rows) ->
            item(key = title) {
                ManagementGroup(title, style) {
                    rows.forEach { (gesture, effect) ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            BasicText(gesture, style = style.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium))
                            BasicText(effect, Modifier.padding(top = 2.dp),
                                style = style.copy(fontSize = 14.sp, color = palette.text.copy(alpha = .75f)))
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * Locking is hard to undo (ten minutes of waiting), so it asks first, and the confirm button only
 * becomes available after a few seconds to rule out a hasty tap.
 */
@Composable
private fun LockConfirmation(name: String, style: TextStyle, cancel: () -> Unit, confirm: () -> Unit) {
    val palette = LocalPalette.current
    var remaining by remember { mutableIntStateOf(5) }
    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1000)
            remaining--
        }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f))
            .pointerInput(Unit) { detectTapGestures { cancel() } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.padding(24.dp).fillMaxWidth().background(palette.surface, RoundedCornerShape(16.dp))
                .pointerInput(Unit) { detectTapGestures { } }.padding(20.dp)
        ) {
            BasicText("¿Bloquear $name?", style = style.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold))
            BasicText(
                "Va a desaparecer del buscador y de favoritos. Para abrirla o desbloquearla vas a tener que " +
                    "esperar 10 minutos con la pantalla encendida en Ajustes › Apps bloqueadas.",
                Modifier.padding(top = 10.dp), style = style.copy(fontSize = 15.sp, color = palette.text.copy(alpha = .8f))
            )
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).background(palette.strong, RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button, onClick = cancel).padding(14.dp),
                contentAlignment = Alignment.Center
            ) { BasicText("Cancelar", style = style.copy(color = palette.onStrong, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)) }
            Spacer(Modifier.height(10.dp))
            val ready = remaining == 0
            Box(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .then(if (ready) Modifier.clickable(role = Role.Button, onClick = confirm) else Modifier)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    if (ready) "Bloquear" else "Esperá $remaining s para bloquear",
                    style = style.copy(fontSize = 15.sp, color = if (ready) palette.accent else palette.text.copy(alpha = .5f),
                        fontFeatureSettings = "tnum")
                )
            }
        }
    }
}
