/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import io.askimo.core.chat.dto.ChatMessageDTO
import io.askimo.core.chat.dto.FileAttachmentDTO
import io.askimo.ui.common.theme.BackgroundImage
import io.askimo.ui.common.theme.FontSettings
import io.askimo.ui.common.theme.LayoutDensity
import io.askimo.ui.common.theme.ThemeMode
import io.askimo.ui.common.theme.ThemePaletteStyle
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.theme.detectMacOSDarkMode
import java.util.Locale

// ── ChatViewState ─────────────────────────────────────────────────────────────

/**
 * Holds chat view state per session for restoration when switching sessions.
 */
data class ChatViewState(
    val inputText: TextFieldValue = TextFieldValue(""),
    val attachments: List<FileAttachmentDTO> = emptyList(),
    val editingMessage: ChatMessageDTO? = null,
)

// ── ThemeState ────────────────────────────────────────────────────────────────

data class ThemeState(
    val themeMode: ThemeMode,
    val useDarkMode: Boolean,
    val fontSettings: FontSettings,
    val layoutDensity: LayoutDensity,
    val locale: Locale,
    val backgroundImage: BackgroundImage,
    val accentColorHex: String?,
    val themePaletteStyle: ThemePaletteStyle,
)

@Composable
fun rememberThemeState(): ThemeState {
    val themeMode by ThemePreferences.themeMode.collectAsState()
    val fontSettings by ThemePreferences.fontSettings.collectAsState()
    val layoutDensity by ThemePreferences.layoutDensity.collectAsState()
    val locale by ThemePreferences.locale.collectAsState()
    val backgroundImage by ThemePreferences.backgroundImage.collectAsState()
    val accentColorHex by ThemePreferences.accentColorHex.collectAsState()
    val themePaletteStyle by ThemePreferences.themePaletteStyle.collectAsState()
    var isSystemInDarkMode by remember { mutableStateOf(detectMacOSDarkMode()) }
    LaunchedEffect(themeMode) {
        if (themeMode == ThemeMode.SYSTEM) isSystemInDarkMode = detectMacOSDarkMode()
    }
    val useDarkMode = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkMode
    }
    return ThemeState(
        themeMode = themeMode,
        useDarkMode = useDarkMode,
        fontSettings = fontSettings,
        layoutDensity = layoutDensity,
        locale = locale,
        backgroundImage = backgroundImage,
        accentColorHex = accentColorHex,
        themePaletteStyle = themePaletteStyle,
    )
}

// ── Window state persistence ──────────────────────────────────────────────────

/**
 * Creates a [WindowState] pre-populated from [ThemePreferences] and
 * automatically saves changes back whenever size, position or placement change.
 */
@Composable
fun rememberPersistedWindowState(): WindowState {
    val savedWidth = ThemePreferences.getWindowWidth()
    val savedHeight = ThemePreferences.getWindowHeight()
    val savedX = ThemePreferences.getWindowX()
    val savedY = ThemePreferences.getWindowY()
    val isMaximized = ThemePreferences.isWindowMaximized()

    val windowState = rememberWindowState(
        width = if (savedWidth > 0) savedWidth.dp else 1920.dp,
        height = if (savedHeight > 0) savedHeight.dp else 1080.dp,
        position = if (savedX >= 0 && savedY >= 0) {
            WindowPosition(savedX.dp, savedY.dp)
        } else {
            WindowPosition.Aligned(Alignment.Center)
        },
        placement = if (isMaximized) WindowPlacement.Maximized else WindowPlacement.Floating,
    )

    LaunchedEffect(windowState.size, windowState.position, windowState.placement) {
        ThemePreferences.saveWindowState(
            width = windowState.size.width.value.toInt(),
            height = windowState.size.height.value.toInt(),
            x = windowState.position.x.value.toInt(),
            y = windowState.position.y.value.toInt(),
            isMaximized = windowState.placement == WindowPlacement.Maximized,
        )
    }

    return windowState
}
