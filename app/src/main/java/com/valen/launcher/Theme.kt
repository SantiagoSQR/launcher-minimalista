package com.valen.launcher

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.stringPreferencesKey

val paletteKey = stringPreferencesKey("palette")

/**
 * Colors for every non-home surface. All palettes share the same recipe: a very dark background
 * tinted toward the hue, a creamy pastel accent, and a cream text color, so any of them reads the same.
 */
@Immutable
data class Palette(
    val key: String,
    val name: String,
    /** Full-screen background of search, settings and other panels. */
    val background: Color,
    /** Grouped cards in settings and statistics. */
    val surface: Color,
    /** Fields and nodes that sit on top of the background (search box, fan circles). */
    val raised: Color,
    /** Row highlight while pressed. */
    val pressed: Color,
    /** Creamy highlight: details, chart bars, switches on, selected nodes. */
    val accent: Color,
    /** Text and icons drawn on [accent]. */
    val onAccent: Color,
    /** Thin lines and borders. */
    val line: Color,
    /** Switch track when off. */
    val muted: Color,
    /** Main text. */
    val text: Color,
    /** Big primary button (Cancelar on the pause screen). */
    val strong: Color,
    val onStrong: Color,
)

val palettes = listOf(
    Palette(
        "cafe", "Café", background = Color(0xff15110e), surface = Color(0xff271d17), raised = Color(0xff30251e),
        pressed = Color(0xff473124), accent = Color(0xffcfaa85), onAccent = Color(0xff2b1a12), line = Color(0xffa18167),
        muted = Color(0xff63564a), text = Color(0xfff5eadc), strong = Color(0xff83573d), onStrong = Color(0xfffff4e8)
    ),
    Palette(
        "azul", "Azul", background = Color(0xff0f1318), surface = Color(0xff1a222c), raised = Color(0xff222c38),
        pressed = Color(0xff2c3a4a), accent = Color(0xffaec5dd), onAccent = Color(0xff14202c), line = Color(0xff7f93a8),
        muted = Color(0xff4f5a66), text = Color(0xffeef1f4), strong = Color(0xff4d6a8a), onStrong = Color(0xfff1f5fa)
    ),
    Palette(
        "verde", "Verde", background = Color(0xff111511), surface = Color(0xff1c231c), raised = Color(0xff253025),
        pressed = Color(0xff2f3d2c), accent = Color(0xffb8cba4), onAccent = Color(0xff1a2413), line = Color(0xff879a78),
        muted = Color(0xff535e4d), text = Color(0xffeff2e8), strong = Color(0xff5a7448), onStrong = Color(0xfff3f7ec)
    ),
    Palette(
        "rojizo", "Rojizo", background = Color(0xff171010), surface = Color(0xff261a19), raised = Color(0xff312220),
        pressed = Color(0xff4a2d29), accent = Color(0xffdcae9f), onAccent = Color(0xff2d1612), line = Color(0xffa77e75),
        muted = Color(0xff64524e), text = Color(0xfff6eae6), strong = Color(0xff8a4b40), onStrong = Color(0xfffff2ee)
    ),
    Palette(
        "morado", "Morado", background = Color(0xff141119), surface = Color(0xff201a28), raised = Color(0xff2a2234),
        pressed = Color(0xff3a2e4a), accent = Color(0xffc6b5de), onAccent = Color(0xff1f1530), line = Color(0xff9384a8),
        muted = Color(0xff5a5066), text = Color(0xfff2edf6), strong = Color(0xff6c5590), onStrong = Color(0xfff6f1fb)
    ),
)

fun paletteFor(key: String?): Palette = palettes.firstOrNull { it.key == key } ?: palettes.first()

val LocalPalette = staticCompositionLocalOf { palettes.first() }
