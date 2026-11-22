package com.valen.launcher

import android.widget.Toast
import android.content.Intent
import android.provider.Settings
import java.time.LocalDate
import android.os.SystemClock
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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

private enum class Screen { Home, Drawer, Settings, Favorites, App, Wait, Progression, HiddenGate, Hidden, Widgets, Stats }

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
    val ink = if (preferences?.get(colorKey) == "black") Color.Black else Color.White
    val panel = if (ink == Color.Black) Color.White else Color(0xff101010)
    val tone = TextStyle(color = ink, fontSize = 18.sp)
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var screen by remember { mutableStateOf(Screen.Home) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<App?>(null) }
    var hiddenDeadline by remember { mutableLongStateOf(0) }
    var alias by remember { mutableStateOf("") }
    val favoriteIds = remember(preferences) { decodeFavorites(preferences?.get(favoritesKey)) }
    val favorites = remember(apps, favoriteIds) {
        val byId = apps.associateBy { it.id }
        favoriteIds.mapNotNull(byId::get)
    }
    fun title(app: App) = preferences?.get(app.alias)?.ifBlank { app.label } ?: app.label
    fun failed() {
        Toast.makeText(context, "No se pudo abrir la aplicación", Toast.LENGTH_SHORT).show()
    }
    fun cancel() {
        model.cancelInterruption()
        screen = Screen.Home
        query = ""
    }
    fun open(app: App) {
        screen = Screen.Home
        query = ""
        keyboard?.hide()
        if (preferences?.get(app.restricted) == "on" || preferences?.get(app.hidden) == "on") {
            selected = app
            screen = Screen.Wait
            model.requestInterruption(app)
        } else if (!model.launch(app)) failed()
    }
    fun configure(app: App) {
        selected = app
        alias = preferences?.get(app.alias).orEmpty()
        screen = Screen.App
    }
    LaunchedEffect(homeRequest) { cancel() }
    LaunchedEffect(widgetRequest) { if (widgetRequest != 0) screen = Screen.Widgets }
    BackHandler(screen != Screen.Home) {
        if (screen == Screen.Wait) cancel()
        else {
            screen = if (screen in listOf(Screen.Favorites, Screen.App, Screen.Progression, Screen.HiddenGate, Screen.Hidden)) Screen.Settings else Screen.Home
            query = ""
        }
    }

    val focus = remember { FocusRequester() }
    val normalized = remember(query) { searchKey(query.trim()) }
    val results = remember(normalized, apps, preferences) {
        apps.filter {
            preferences?.get(it.hidden) != "on" && (normalized in searchKey(it.label) || normalized in searchKey(title(it)))
        }
    }
    val sole = results.singleOrNull()
    val auto = preferences?.get(autoKey) != "off"
    LaunchedEffect(screen, query, sole, auto) {
        if (screen == Screen.Drawer && auto && normalized.isNotEmpty() && sole != null) {
            delay(500)
            open(sole)
        }
    }
    LaunchedEffect(screen) {
        if (screen == Screen.Drawer) {
            focus.requestFocus()
            keyboard?.show()
        }
    }

    val background = if (screen == Screen.Home) Modifier else Modifier.background(panel)
    Box(Modifier.fillMaxSize().then(background).safeDrawingPadding().imePadding()) {
        when (screen) {
            Screen.Home -> {
                val contextLocale = context.resources.configuration.locales[0]
                var now by remember { mutableStateOf(ZonedDateTime.now()) }
                val day = now.toLocalDate()
                LaunchedEffect(day) { model.refreshUsage() }
                LaunchedEffect(Unit) {
                    while (true) {
                        now = ZonedDateTime.now()
                        delay(60_000L - now.second * 1000L - now.nano / 1_000_000L)
                    }
                }
                BoxWithConstraints(
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
                                        if (kotlin.math.abs(horizontal) > threshold) screen = Screen.Widgets
                                    } else if (vertical < -threshold) screen = Screen.Drawer
                                }
                            ) { _, delta -> horizontal += delta.x; vertical += delta.y }
                        },
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(Modifier.padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        BasicText(now.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(contextLocale)), style = tone.copy(fontSize = 58.sp))
                        BasicText(now.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(contextLocale)), style = tone)
                        val openings = dailyActivity.filter { it.day == day.toString() }.sumOf { it.openings }
                        val estimate = usage?.get(day.toString())?.let(::usageText)
                        val summary = (estimate?.let { "$it de uso estimado" } ?: "Uso no disponible") + " · $openings aperturas"
                        Action(summary, tone.copy(fontSize = 13.sp)) { screen = Screen.Stats }
                        if (!defaultHome) Action(stringResource(R.string.choose_home), tone, chooseHome)
                    }
                    if (favorites.isNotEmpty()) {
                        LazyColumn(
                            Modifier.align(Alignment.BottomCenter).heightIn(max = maxHeight * 0.55f).padding(bottom = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) { items(favorites, key = { it.id }) { Action(title(it), tone) { open(it) } } }
                    }
                }
            }
            Screen.Drawer -> Column(Modifier.padding(horizontal = 24.dp)) {
                Input(query, { query = it }, "Buscar aplicaciones", tone, Modifier.focusRequester(focus))
                LazyColumn { items(results, key = { it.id }) { Action(title(it), tone) { open(it) } } }
            }
            Screen.Settings -> LazyColumn(Modifier.padding(horizontal = 24.dp)) {
                item {
                    BasicText("Ajustes", style = tone.copy(fontSize = 28.sp))
                    HiddenAccess(tone) {
                        val seconds = preferences?.get(hiddenDelayKey)?.toIntOrNull() ?: 10
                        hiddenDeadline = SystemClock.elapsedRealtime() + seconds * 1000L
                        screen = Screen.HiddenGate
                    }
                    Action("Estadísticas", tone) { screen = Screen.Stats }
                    Action("Demoras progresivas", tone) { screen = Screen.Progression }
                    Action("Ordenar favoritos", tone) { screen = Screen.Favorites }
                    Action(if (auto) "Apertura automática activada" else "Apertura automática desactivada", tone) {
                        model.save(autoKey, if (auto) "off" else "on")
                    }
                    Action(if (ink == Color.White) "Texto blanco" else "Texto negro", tone) {
                        model.save(colorKey, if (ink == Color.White) "black" else "white")
                    }
                    if (!defaultHome) Action(stringResource(R.string.choose_home), tone, chooseHome)
                }
                items(apps, key = { it.id }) {
                    val suffix = if (preferences?.get(it.hidden) == "on") " · oculta" else ""
                    Action(title(it) + suffix, tone) { configure(it) }
                }
            }
            Screen.App -> selected?.let { app ->
                Column(Modifier.padding(horizontal = 24.dp)) {
                    BasicText(app.label, style = tone.copy(fontSize = 28.sp))
                    Input(alias, { alias = it }, "Alias", tone)
                    Action("Guardar alias", tone) {
                        model.save(app.alias, alias.trim())
                        keyboard?.hide()
                        screen = Screen.Settings
                    }
                    val restricted = preferences?.get(app.restricted) == "on"
                    Action(if (restricted) "Interrupción activada" else "Interrupción desactivada", tone) {
                        model.save(app.restricted, if (restricted) "off" else "on")
                    }
                    val hidden = preferences?.get(app.hidden) == "on"
                    if (!hidden) {
                        Action(if (app.id in favoriteIds) "Quitar de favoritos" else "Añadir a favoritos", tone) {
                            model.toggleFavorite(app)
                        }
                    }
                    Action(if (hidden) "Mostrar aplicación" else "Ocultar aplicación", tone) {
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
                val hidden = apps.filter { preferences?.get(it.hidden) == "on" }
                if (hidden.isEmpty()) item { BasicText("No hay aplicaciones ocultas", style = tone) }
                items(hidden, key = { it.id }) { Action(title(it), tone) { open(it) } }
            }
            Screen.Wait -> WaitPanel(selected?.let(::title).orEmpty(), attempt?.deadline, tone, attempt?.openingCount, {
                model.completeInterruption()?.let { opened ->
                    screen = Screen.Home
                    if (!opened) failed()
                }
            }, ::cancel)
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

@Composable
private fun Favorites(apps: List<App>, title: (App) -> String, style: TextStyle, save: (List<App>) -> Unit) {
    var order by remember(apps) { mutableStateOf(apps) }
    val state = rememberLazyListState()
    val latestSave by rememberUpdatedState(save)
    LazyColumn(Modifier.padding(horizontal = 24.dp), state = state) {
        item { BasicText("Arrastrá para ordenar", style = style) }
        items(order, key = { it.id }) { app ->
            Row(verticalAlignment = Alignment.CenterVertically) {
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

@Composable
internal fun Action(label: String, style: TextStyle, action: () -> Unit) {
    BasicText(label, Modifier.clickable(onClick = action).padding(vertical = 12.dp, horizontal = 8.dp)
        .heightIn(min = 24.dp), style = style)
}

@Composable
internal fun Input(value: String, change: (String) -> Unit, hint: String, style: TextStyle, modifier: Modifier = Modifier) {
    BasicTextField(value, change, modifier.fillMaxWidth().padding(vertical = 16.dp), singleLine = true,
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
