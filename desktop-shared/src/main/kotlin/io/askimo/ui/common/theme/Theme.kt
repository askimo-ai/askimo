/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import kotlin.math.roundToInt

enum class ThemePaletteStyle(val materialStyle: PaletteStyle) {
    BALANCED(PaletteStyle.TonalSpot),
    VIBRANT(PaletteStyle.Vibrant),
    LITERAL(PaletteStyle.Fidelity),
    SOFT(PaletteStyle.Neutral),
    ;

    companion object {
        fun fromPreference(value: String?): ThemePaletteStyle = entries.firstOrNull { it.name == value } ?: DefaultThemePaletteStyle
    }
}

val DefaultThemeSeed = Color(0xFF707070) // Modern Gray
val DefaultThemePaletteStyle = ThemePaletteStyle.SOFT

fun generateAppColorScheme(
    seedColor: Color = DefaultThemeSeed,
    isDark: Boolean,
    paletteStyle: ThemePaletteStyle = DefaultThemePaletteStyle,
): ColorScheme = dynamicColorScheme(
    seedColor = seedColor,
    isDark = isDark,
    style = paletteStyle.materialStyle,
)

fun parseAccentColor(value: String?): Color? {
    if (value.isNullOrBlank()) return null
    val normalized = value.trim().removePrefix("#")
    if (normalized.length != 6 || !normalized.matches(Regex("^[0-9A-Fa-f]{6}$"))) return null
    return Color(0xFF000000 or normalized.toLong(16))
}

fun toAccentHex(color: Color): String {
    val r = (color.red * 255f).roundToInt().coerceIn(0, 255)
    val g = (color.green * 255f).roundToInt().coerceIn(0, 255)
    val b = (color.blue * 255f).roundToInt().coerceIn(0, 255)
    return String.format("#%02X%02X%02X", r, g, b)
}

fun applyAccentToColorScheme(
    accent: Color,
    isDark: Boolean,
    paletteStyle: ThemePaletteStyle = DefaultThemePaletteStyle,
): ColorScheme = generateAppColorScheme(
    seedColor = accent,
    isDark = isDark,
    paletteStyle = paletteStyle,
)
