package com.valen.launcher

import android.app.Activity
import android.appwidget.AppWidgetProviderInfo
import android.util.SizeF
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.room.*
import kotlin.math.ceil
import kotlin.math.floor

@Entity
data class Widget(
    @PrimaryKey val id: Int,
    val provider: String,
    val x: Int,
    val y: Int,
    val columns: Int,
    val rows: Int
) {
    fun overlaps(other: Widget): Boolean = id != other.id &&
        x < other.x + other.columns && other.x < x + columns &&
        y < other.y + other.rows && other.y < y + rows
}

@Dao
interface WidgetDao {
    @Query("SELECT * FROM Widget ORDER BY y, x") fun observe(): kotlinx.coroutines.flow.Flow<List<Widget>>
    @Query("SELECT * FROM Widget") suspend fun all(): List<Widget>
    @Upsert suspend fun save(widget: Widget)
    @Query("DELETE FROM Widget WHERE id = :id") suspend fun remove(id: Int)
}

private const val columns = 4
internal const val widgetRowDp = 72f

internal data class WidgetBounds(
    val minColumns: Int, val maxColumns: Int, val minRows: Int, val maxRows: Int
) {
    fun fits(widget: Widget) = widget.columns in minColumns..maxColumns &&
        widget.rows in minRows..maxRows && widget.x >= 0 &&
        widget.x + widget.columns <= columns && widget.y >= 0
}

internal fun widgetBounds(info: AppWidgetProviderInfo, cellWidth: Float, density: Float): WidgetBounds {
    fun limit(default: Int, minimum: Int, maximum: Int, step: Float, mode: Int): Pair<Int, Int> {
        val resize = info.resizeMode and mode != 0
        val min = if (resize && minimum > 0) minimum else default
        val start = ceil(min / density / step).toInt().coerceAtLeast(1)
        val end = if (!resize) start else if (maximum > 0) {
            floor(maximum / density / step).toInt().coerceAtLeast(start)
        } else Int.MAX_VALUE
        return start to end
    }
    val (minWidth, maxWidth) = limit(info.minWidth, info.minResizeWidth, info.maxResizeWidth, cellWidth, AppWidgetProviderInfo.RESIZE_HORIZONTAL)
    val (minHeight, maxHeight) = limit(info.minHeight, info.minResizeHeight, info.maxResizeHeight, widgetRowDp, AppWidgetProviderInfo.RESIZE_VERTICAL)
    return WidgetBounds(minWidth, maxWidth.coerceAtMost(columns), minHeight, maxHeight)
}

@Composable
internal fun WidgetPanel(model: LauncherModel, activity: MainActivity, style: TextStyle, pick: (Float) -> Unit) {
    val widgets by model.widgets.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("Widgets", Modifier.weight(1f), style = style)
            Action(if (editing) "Listo" else "Editar", style) {
                editing = !editing
                selected = null
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp)) {
            val columnWidth = maxWidth / columns
            val density = LocalDensity.current.density
            val focused = widgets.firstOrNull { it.id == selected }
            Column {
                if (editing) Action("Añadir widget", style) { pick(columnWidth.value) }
                if (editing && focused != null) {
                    val info = model.widgetManager.getAppWidgetInfo(focused.id)
                    if (info != null) {
                        val limits = widgetBounds(info, columnWidth.value, density)
                        WidgetEditor(focused, widgets, limits, style, model::placeWidget) {
                            model.removeWidget(focused.id)
                            selected = null
                        }
                    }
                }
                if (widgets.isEmpty()) BasicText("Todavía no hay widgets", style = style)
                Box(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    val lastRow = widgets.maxOfOrNull { it.y + it.rows } ?: 0
                    Box(Modifier.fillMaxWidth().height(widgetRowDp.dp * lastRow)) {
                        for (widget in widgets) {
                            val info = model.widgetManager.getAppWidgetInfo(widget.id) ?: continue
                            key(widget.id) {
                                val width = columnWidth * widget.columns
                                val height = widgetRowDp.dp * widget.rows
                                Box(Modifier.offset(columnWidth * widget.x, widgetRowDp.dp * widget.y).size(width, height)) {
                                    if (editing) {
                                        val border = style.color.copy(alpha = if (selected == widget.id) 1f else .35f)
                                        Box(Modifier.fillMaxSize().padding(2.dp).border(1.dp, border)
                                            .clickable { selected = widget.id }.padding(8.dp)) {
                                            BasicText(info.loadLabel(activity.packageManager), style = style)
                                        }
                                    } else {
                                        AndroidView(
                                            factory = { model.widgetHost.createView(it, widget.id, info) },
                                            modifier = Modifier.fillMaxSize(),
                                            update = { view ->
                                                val sizes = listOf(SizeF(width.value, height.value))
                                                val key = R.id.widget_size
                                                if (view.getTag(key) != sizes) {
                                                    view.updateAppWidgetSize(android.os.Bundle(), sizes)
                                                    view.setTag(key, sizes)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetEditor(
    widget: Widget, widgets: List<Widget>, bounds: WidgetBounds, style: TextStyle,
    place: (Widget) -> Unit, remove: () -> Unit
) {
    @Composable
    fun change(label: String, next: Widget) {
        if (bounds.fits(next) && widgets.none(next::overlaps)) Action(label, style) { place(next) }
    }
    Row {
        change("Izq.", widget.copy(x = widget.x - 1))
        change("Der.", widget.copy(x = widget.x + 1))
        change("Subir", widget.copy(y = widget.y - 1))
        change("Bajar", widget.copy(y = widget.y + 1))
    }
    Row {
        change("− ancho", widget.copy(columns = widget.columns - 1))
        change("+ ancho", widget.copy(columns = widget.columns + 1))
        change("− alto", widget.copy(rows = widget.rows - 1))
        change("+ alto", widget.copy(rows = widget.rows + 1))
    }
    Action("Quitar widget", style, action = remove)
}
