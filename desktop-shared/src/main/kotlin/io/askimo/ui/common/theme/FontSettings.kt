/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import java.awt.GraphicsEnvironment

val LocalFontScale = compositionLocalOf { 1f }
val LocalCodeFontFamily = compositionLocalOf<FontFamily> { FontFamily.Monospace }

data class FontSettings(
    val uiFontFamily: String = "System Default",
    val codeFontFamily: String = "System Monospace",
    val fontSize: FontSize = FontSize.MEDIUM,
    val customFontScale: Float? = null,
    val lineSpacing: LineSpacing = LineSpacing.NORMAL,
) {
    companion object {
        const val SYSTEM_DEFAULT = "System Default"
        const val SYSTEM_MONOSPACE = "System Monospace"
        const val MIN_CUSTOM_FONT_SCALE = 0.8f
        const val MAX_CUSTOM_FONT_SCALE = 2.5f
    }

    val effectiveScale: Float
        get() = (customFontScale ?: fontSize.scale).coerceIn(MIN_CUSTOM_FONT_SCALE, MAX_CUSTOM_FONT_SCALE)
}

enum class FontSize(val displayName: String, val scale: Float) {
    SMALL("Small", 0.85f),
    MEDIUM("Medium", 1.0f),
    LARGE("Large", 1.15f),
    EXTRA_LARGE("Extra Large", 1.35f),
    XX_LARGE("XX Large", 1.6f),
    XXX_LARGE("XXX Large", 1.9f),
}

enum class LineSpacing(val displayName: String, val multiplier: Float) {
    COMPACT("Compact", 0.9f),
    NORMAL("Normal", 1.0f),
    RELAXED("Relaxed", 1.25f),
    SPACIOUS("Spacious", 1.5f),
}

/**
 * Maps font family names to predefined [FontFamily] types.
 */
private val installedFontFamiliesByLowercase: Map<String, String> by lazy {
    GraphicsEnvironment
        .getLocalGraphicsEnvironment()
        .availableFontFamilyNames
        .associateBy { it.lowercase() }
}

@OptIn(ExperimentalTextApi::class)
private fun resolveInstalledFontFamily(fontName: String): FontFamily? {
    val installedName = installedFontFamiliesByLowercase[fontName.lowercase()] ?: return null
    return FontFamily(installedName)
}

private fun classifyGenericFontFamily(fontName: String): FontFamily = when (fontName.lowercase()) {
    "monospace", "courier", "courier new", "consolas", "monaco", "menlo",
    "dejavu sans mono", "lucida console", "jetbrains mono", "fira code", "source code pro",
    "sf mono", "ui-monospace", "ubuntu mono", "inconsolata",
    -> FontFamily.Monospace

    "serif", "times", "times new roman", "georgia", "palatino",
    "garamond", "baskerville", "book antiqua",
    -> FontFamily.Serif

    "cursive", "comic sans ms", "apple chancery", "brush script mt" -> FontFamily.Cursive

    else -> FontFamily.SansSerif
}

fun loadUiFontFamily(fontName: String): FontFamily = when (fontName) {
    FontSettings.SYSTEM_DEFAULT -> FontFamily.Default

    else -> resolveInstalledFontFamily(fontName)
        ?: classifyGenericFontFamily(fontName)
}

fun loadCodeFontFamily(fontName: String): FontFamily = when (fontName) {
    FontSettings.SYSTEM_MONOSPACE -> FontFamily.Monospace

    FontSettings.SYSTEM_DEFAULT -> FontFamily.Default

    else -> resolveInstalledFontFamily(fontName)
        ?: classifyGenericFontFamily(fontName)
}

/**
 * Creates a [Typography] scaled and set to the font in [fontSettings].
 */
fun createCustomTypography(fontSettings: FontSettings): Typography {
    val fontFamily = loadUiFontFamily(fontSettings.uiFontFamily)
    val scale = fontSettings.effectiveScale
    val lh = fontSettings.lineSpacing.multiplier
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = fontFamily, fontSize = base.displayLarge.fontSize * scale, lineHeight = base.displayLarge.lineHeight * lh),
        displayMedium = base.displayMedium.copy(fontFamily = fontFamily, fontSize = base.displayMedium.fontSize * scale, lineHeight = base.displayMedium.lineHeight * lh),
        displaySmall = base.displaySmall.copy(fontFamily = fontFamily, fontSize = base.displaySmall.fontSize * scale, lineHeight = base.displaySmall.lineHeight * lh),
        headlineLarge = base.headlineLarge.copy(fontFamily = fontFamily, fontSize = base.headlineLarge.fontSize * scale, lineHeight = base.headlineLarge.lineHeight * lh),
        headlineMedium = base.headlineMedium.copy(fontFamily = fontFamily, fontSize = base.headlineMedium.fontSize * scale, lineHeight = base.headlineMedium.lineHeight * lh),
        headlineSmall = base.headlineSmall.copy(fontFamily = fontFamily, fontSize = base.headlineSmall.fontSize * scale, lineHeight = base.headlineSmall.lineHeight * lh),
        titleLarge = base.titleLarge.copy(fontFamily = fontFamily, fontSize = base.titleLarge.fontSize * scale, lineHeight = base.titleLarge.lineHeight * lh),
        titleMedium = base.titleMedium.copy(fontFamily = fontFamily, fontSize = base.titleMedium.fontSize * scale, lineHeight = base.titleMedium.lineHeight * lh),
        titleSmall = base.titleSmall.copy(fontFamily = fontFamily, fontSize = base.titleSmall.fontSize * scale, lineHeight = base.titleSmall.lineHeight * lh),
        bodyLarge = base.bodyLarge.copy(fontFamily = fontFamily, fontSize = base.bodyLarge.fontSize * scale, lineHeight = base.bodyLarge.lineHeight * lh),
        bodyMedium = base.bodyMedium.copy(fontFamily = fontFamily, fontSize = base.bodyMedium.fontSize * scale, lineHeight = base.bodyMedium.lineHeight * lh),
        bodySmall = base.bodySmall.copy(fontFamily = fontFamily, fontSize = base.bodySmall.fontSize * scale, lineHeight = base.bodySmall.lineHeight * lh),
        labelLarge = base.labelLarge.copy(fontFamily = fontFamily, fontSize = base.labelLarge.fontSize * scale, lineHeight = base.labelLarge.lineHeight * lh),
        labelMedium = base.labelMedium.copy(fontFamily = fontFamily, fontSize = base.labelMedium.fontSize * scale, lineHeight = base.labelMedium.lineHeight * lh),
        labelSmall = base.labelSmall.copy(fontFamily = fontFamily, fontSize = base.labelSmall.fontSize * scale, lineHeight = base.labelSmall.lineHeight * lh),
    )
}
