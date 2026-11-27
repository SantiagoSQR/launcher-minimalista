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
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.res.booleanResource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val fanPositionKey = stringPreferencesKey("fan-position")
private val fanHandKey = stringPreferencesKey("fan-hand")

private enum class Screen { Home, Drawer, Settings, Favorites, App, Wait, Progression, HiddenGate, Hidden, LockedGate, Locked, Widgets, Stats }

@Composable
fun LauncherScreen(
    model: LauncherModel, activity: MainActivity, defaultHome: Boolean, homeRequest: Int,
    widgetRequest: Int, chooseHome: () -> Unit, pickWidget: (Float) -> Unit
) {
    val apps by model.apps.collectAsState()
    val dailyActivity by model.dailyActivity.collectAsState()
    val usage by model.usage.collectAsState()
    val attempt by model.interruption.collectAsState()
    val preferences by model.preferences.collectAsState()
    val leftHanded = preferences?.get(fanHandKey) == "left"
    val fanPosition = remember(preferences?.get(fanPositionKey)) {
        val parts = preferences?.get(fanPositionKey)?.split(',')
        fun coordinate(index: Int) = parts?.getOrNull(index)?.toFloatOrNull()?.takeIf { it.isFinite() } ?: 0f
        Offset(coordinate(0), coordinate(1))
    }
    val retro = booleanResource(R.bool.retro_style)
    val panel = Color(0xff15110e)
    val inter = FontFamily(Font(R.font.inter))
    val clockFont = remember(retro) {
        if (retro) FontFamily(Font(R.font.inter, weight = FontWeight.Bold,
            variationSettings = FontVariation.Settings(FontVariation.weight(700)))) else inter
    }
    val tone = TextStyle(color = Color(0xfff5eadc), fontSize = 18.sp, fontFamily = inter)
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val settingsPackage = remember(context) { context.settingsPackage() }
    var protectionEnabled by remember(context) { mutableStateOf(context.protectionEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        protectionEnabled = context.protectionEnabled()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var screen by remember { mutableStateOf(Screen.Home) }
    var widgetsSide by remember { mutableIntStateOf(1) }
    var statsFromSettings by remember { mutableStateOf(false) }
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
        if (data[app.restricted] == "on" || data[app.protected] == "on" || hidden || locked) {
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
    LaunchedEffect(homeRequest) { cancel() }
    LaunchedEffect(widgetRequest) { if (widgetRequest != 0) screen = Screen.Widgets }
    BackHandler(screen != Screen.Home) {
        if (screen == Screen.Wait) dismissWait()
        else {
            model.cancelBlockedAccess()
            screen = when (screen) {
                Screen.Favorites, Screen.Progression, Screen.HiddenGate, Screen.Hidden, Screen.LockedGate, Screen.Locked -> Screen.Settings
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
    }
    val results = remember(normalized, searchIndex) {
        searchIndex.mapNotNull { (app, shown) ->
            val rank = matchRank(normalized, shown)
            if (rank == Int.MAX_VALUE) null else app to rank
        }.sortedBy { it.second }
    }
    val sole = results.singleOrNull()
    val auto = preferences?.get(autoKey) != "off"
    val automaticApp = sole?.first?.takeIf {
        preferences?.get(it.restricted) != "on" &&
            preferences?.get(it.protected) != "on"
    }
    LaunchedEffect(screen, query, automaticApp, auto, autoArmed) {
        if (screen == Screen.Drawer && auto && autoArmed && normalized.isNotEmpty() && automaticApp != null) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                delay(250)
                open(automaticApp)
            }
        }
    }
    LaunchedEffect(screen) {
        if (screen == Screen.Drawer) {
            focus.requestFocus()
            keyboard?.show()
        }
    }

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            when {
                (initialState == Screen.Home && targetState == Screen.Drawer) ||
                    (initialState == Screen.Drawer && targetState == Screen.Home) -> {
                    val direction = if (targetState == Screen.Drawer) 1 else -1
                    (fadeIn(tween(155)) + slideInVertically(tween(155)) { direction * it / 14 }) togetherWith
                        (fadeOut(tween(120)) + slideOutVertically(tween(120)) { -direction * it / 14 })
                }
                (initialState == Screen.Home && targetState == Screen.Widgets) ||
                    (initialState == Screen.Widgets && targetState == Screen.Home) -> {
                    val direction = if (targetState == Screen.Widgets) widgetsSide else -widgetsSide
                    (fadeIn(tween(155)) + slideInHorizontally(tween(155)) { direction * it / 14 }) togetherWith
                        (fadeOut(tween(120)) + slideOutHorizontally(tween(120)) { -direction * it / 14 })
                }
                else -> EnterTransition.None togetherWith ExitTransition.None
            }
        },
        label = "Pantallas del launcher"
    ) { displayed ->
      val background = if (displayed == Screen.Home) Modifier else Modifier.background(panel)
      Box(Modifier.fillMaxSize().then(background).safeDrawingPadding().imePadding()) {
        when (displayed) {
            Screen.Home -> {
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
                Box(
                    Modifier.fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures(onLongPress = { screen = Screen.Settings }) }
                        .pointerInput(Unit) {
                            var horizontal = 0f
                            var vertical = 0f
                            detectDragGestures(
                                onDragStart = { horizontal = 0f; vertical = 0f },
                                onDragEnd = {
                                    val threshold = 56.dp.toPx()
                                    if (kotlin.math.abs(horizontal) > kotlin.math.abs(vertical)) {
                                        if (kotlin.math.abs(horizontal) > threshold) {
                                            widgetsSide = if (horizontal < 0f) 1 else -1
                                            screen = Screen.Widgets
                                        }
                                    } else if (vertical < -threshold) screen = Screen.Drawer
                                    else if (vertical > threshold) ForegroundProtection.openQuickSettings()
                                }
                            ) { _, delta -> horizontal += delta.x; vertical += delta.y }
                        },
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Column(Modifier.padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            BasicText(
                                android.text.format.DateFormat.getTimeFormat(context).format(Date.from(now.toInstant())),
                                modifier = Modifier.semantics {
                                    customActions = listOf(
                                        CustomAccessibilityAction("Abrir aplicaciones") { screen = Screen.Drawer; true },
                                        CustomAccessibilityAction("Abrir ajustes") { screen = Screen.Settings; true },
                                        CustomAccessibilityAction("Abrir widgets") {
                                            widgetsSide = 1
                                            screen = Screen.Widgets
                                            true
                                        }
                                    )
                                },
                                style = tone.copy(
                                    fontSize = 58.sp,
                                    fontFamily = clockFont,
                                    fontWeight = if (retro) FontWeight.Bold else tone.fontWeight,
                                    fontFeatureSettings = "tnum"
                                )
                            )
                            val date = now.format(DateTimeFormatter.ofLocalizedDate(
                                FormatStyle.FULL
                            ).withLocale(contextLocale))
                            BasicText(date, style = tone.copy(fontSize = 16.sp))
                            val openings = dailyActivity.filter { it.day == day.toString() }.sumOf { it.openings }
                            val estimate = usage?.get(day.toString())?.let(::usageText)
                            val count = if (openings == 1) "1 apertura" else "$openings aperturas"
                            val summary = listOfNotNull(estimate?.let { "$it de uso estimado" }, count).joinToString(" · ")
                            Action(summary, tone.copy(fontSize = 13.sp)) {
                                statsFromSettings = false
                                screen = Screen.Stats
                            }
                            if (!defaultHome) Action(stringResource(R.string.choose_home), tone, action = chooseHome)
                        }
                    }
                    FanFavorites(visibleFavorites, ::title, tone, leftHanded,
                        Modifier.matchParentSize(), fanPosition,
                        { model.save(fanPositionKey, "${it.x},${it.y}") },
                        ::open, ::configure, { model.save(fanHandKey, if (leftHanded) "right" else "left") })
                }
            }
            Screen.Drawer -> Column(Modifier.padding(horizontal = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).background(Color(0xff30251e),
                        androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(horizontal = 12.dp)) {
                        Input(query, { query = it; autoArmed = true }, "Buscar aplicaciones", tone,
                            Modifier.focusRequester(focus), onOpen = { sole?.first?.let(::open) })
                    }
                    if (query.isNotEmpty()) Action("Borrar", tone) { query = ""; autoArmed = false }
                }
                if (results.isEmpty() && normalized.isNotEmpty()) BasicText("Sin resultados", style = tone)
                LazyColumn(Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        autoArmed = false
                    }
                }) {
                    items(results, key = { it.first.id }) { (app, _) ->
                        val shown = title(app)
                        val ambiguous = results.any { it.first.id != app.id && title(it.first) == shown }
                        val label = if (ambiguous && shown != app.label) "$shown · ${app.label}" else shown
                        AppRow(label, tone, { open(app) }, { configure(app) }, retroList = retro)
                    }
                }
            }
            Screen.Settings -> LazyColumn(Modifier.padding(horizontal = 24.dp)) {
                item {
                    BasicText("Ajustes", style = tone.copy(fontSize = 28.sp))
                    HiddenAccess(tone) {
                        val seconds = preferences?.get(hiddenDelayKey)?.toIntOrNull() ?: 10
                        hiddenDeadline = SystemClock.elapsedRealtime() + seconds * 1000L
                        screen = Screen.HiddenGate
                    }
                    if (apps.any(::locked)) Action("Apps bloqueadas", tone) {
                        lockDeadline = model.beginBlockedAccess()
                        screen = Screen.LockedGate
                    }
                    Action("Estadísticas", tone) {
                        statsFromSettings = true
                        screen = Screen.Stats
                    }
                    Action("Demoras progresivas", tone) { screen = Screen.Progression }
                    Action("Ordenar favoritos", tone) { screen = Screen.Favorites }
                    Action(if (auto) "Apertura automática activada" else "Apertura automática desactivada", tone) {
                        model.save(autoKey, if (auto) "off" else "on")
                    }
                    if (!defaultHome) Action(stringResource(R.string.choose_home), tone, action = chooseHome)
                }
                items(apps, key = { it.id }) {
                    val suffix = when {
                        locked(it) -> " · bloqueada"
                        preferences?.get(it.hidden) == "on" -> " · oculta"
                        else -> ""
                    }
                    Action(title(it) + suffix, tone) { configure(it) }
                }
            }
            Screen.App -> selected?.let { app ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                    if (retro) RetroHeading(app.label, tone)
                    else BasicText(app.label, style = tone.copy(fontSize = 28.sp))
                    Input(alias, { alias = it }, "Alias", tone)
                    Action("Guardar alias", tone, retroList = retro) {
                        model.save(app.alias, alias.trim())
                        keyboard?.hide()
                        screen = appOrigin
                    }
                    val restricted = preferences?.get(app.restricted) == "on"
                    Action(if (restricted) "Interrupción activada" else "Interrupción desactivada", tone, retroList = retro) {
                        model.save(app.restricted, if (restricted) "off" else "on")
                    }
                    if (app.component.packageName != settingsPackage) {
                        if (locked(app)) {
                            BasicText("Para quitar el bloqueo, entrá en Apps bloqueadas.", style = tone)
                        } else Action("Bloquear app", tone, retroList = retro) {
                            model.save(app.locked, "on")
                        }
                        if (locked(app) && !protectionEnabled) {
                            BasicText("Para bloquear aperturas externas, activá el servicio de accesibilidad.", style = tone)
                            Action("Abrir ajustes de accesibilidad", tone, retroList = retro) {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        }

                        val protected = preferences?.get(app.protected) == "on"
                        Action(if (protected) "Protección desde Ajustes activada" else "Proteger aperturas desde Ajustes", tone, retroList = retro) {
                            model.save(app.protected, if (protected) "off" else "on")
                        }
                        if (protected && !protectionEnabled) {
                            BasicText("La protección desde Ajustes requiere activar el servicio de accesibilidad del launcher. La pausa desde el launcher ya está activa.",
                                style = tone.copy(fontSize = 14.sp))
                            Action("Abrir ajustes de accesibilidad", tone, retroList = retro) {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        }
                    }
                    val hidden = preferences?.get(app.hidden) == "on"
                    if (!hidden) {
                        Action(if (app.id in favoriteIds) "Quitar de favoritos" else "Añadir a favoritos", tone, retroList = retro) {
                            model.toggleFavorite(app)
                        }
                    }
                    Action("Información de la app", tone, retroList = retro) {
                        try {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", app.component.packageName, null)))
                        } catch (_: ActivityNotFoundException) {
                            failed()
                        }
                    }
                    Action(if (hidden) "Mostrar aplicación" else "Ocultar aplicación", tone, retroList = retro) {
                        model.toggleHidden(app) { message ->
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            Screen.Stats -> StatisticsPanel(
                dailyActivity, usage, LocalDate.now(),
                { id, label -> apps.firstOrNull { it.id == id }?.let(::title) ?: label }, tone
            ) {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            Screen.Widgets -> WidgetPanel(model, activity, tone, pickWidget)
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
                Column(Modifier.padding(horizontal = 24.dp)) {
                    BasicText("Demoras en segundos", style = tone.copy(fontSize = 28.sp))
                    BasicText("Separá los valores con comas", style = tone)
                    Input(value, { value = it }, "5, 10, 20, 30, 45, 60", tone)
                    BasicText("Espera para aplicaciones ocultas", style = tone)
                    Input(hiddenValue, { hiddenValue = it }, "10", tone)
                    Action("Guardar demoras", tone) {
                        val delays = parseDelays(value)
                        val hiddenSeconds = hiddenValue.trim().toIntOrNull()
                        if (delays == null || hiddenSeconds == null || hiddenSeconds < 0) Toast.makeText(context, "Ingresá uno o más enteros no negativos", Toast.LENGTH_SHORT).show()
                        else {
                            model.saveDelays(delays, hiddenSeconds)
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
    val row = if (retroList) Modifier.fillMaxWidth().background(if (pressed) Color(0xff473124) else Color.Transparent) else Modifier
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
        BasicText(label, Modifier.fillMaxWidth().background(if (pressed) Color(0xff473124) else Color.Transparent)
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
