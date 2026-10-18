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
import kotlinx.coroutines.delay
import java.awt.GraphicsEnvironment
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * Poll interval used to detect OS-level appearance changes while in SYSTEM
 * mode. Polling (rather than relying on window-focus events) is used because
 * macOS appearance can be toggled from the menu bar / Control Center without
 * the app window ever losing focus — a focus-gated check can miss that
 * entirely and never fire at all.
 */
private val SYSTEM_THEME_POLL_INTERVAL = 30.seconds

val isScreenshotMode: Boolean = System.getenv("ASKIMO_SCREENSHOT_MODE") == "true"

private val SCREENSHOT_MODE_WIDTH = 1920.dp
private val SCREENSHOT_MODE_HEIGHT = 1080.dp

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

    // Re-check immediately on entering SYSTEM mode, then keep polling at a
    // fixed interval for as long as SYSTEM mode stays active.
    LaunchedEffect(themeMode) {
        if (themeMode != ThemeMode.SYSTEM) return@LaunchedEffect
        isSystemInDarkMode = detectMacOSDarkMode()
        while (true) {
            delay(SYSTEM_THEME_POLL_INTERVAL)
            val detected = detectMacOSDarkMode()
            if (detected != isSystemInDarkMode) isSystemInDarkMode = detected
        }
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
 * Clamps a saved width/height/x/y window geometry to the bounds of the
 * current default screen's work area. Geometry saved from a previous
 * session — especially geometry captured while the window was *maximized*
 * (which may reflect a different monitor's resolution, or include OS
 * chrome quirks) — can otherwise end up taller/wider than the current
 * screen, pushing the title bar off-screen when the window is restored.
 */
private fun clampWindowGeometryToScreen(
    width: Int,
    height: Int,
    x: Int,
    y: Int,
): IntArray {
    val bounds = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration.bounds
    }.getOrNull() ?: return intArrayOf(width, height, x, y)

    val clampedWidth = width.coerceIn(MIN_WINDOW_WIDTH, bounds.width)
    val clampedHeight = height.coerceIn(MIN_WINDOW_HEIGHT, bounds.height)
    val maxX = (bounds.x + bounds.width - clampedWidth).coerceAtLeast(bounds.x)
    val maxY = (bounds.y + bounds.height - clampedHeight).coerceAtLeast(bounds.y)
    val clampedX = x.coerceIn(bounds.x, maxX)
    val clampedY = y.coerceIn(bounds.y, maxY)
    return intArrayOf(clampedWidth, clampedHeight, clampedX, clampedY)
}

private const val MIN_WINDOW_WIDTH = 400
private const val MIN_WINDOW_HEIGHT = 300

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

    // Clamp whatever was saved (possibly stale/from another monitor, or
    // captured while maximized) to the current screen's visible work area
    // before it's ever applied to the window.
    val (clampedWidth, clampedHeight, clampedX, clampedY) = clampWindowGeometryToScreen(
        width = if (savedWidth > 0) savedWidth else 1920,
        height = if (savedHeight > 0) savedHeight else 1080,
        x = savedX,
        y = savedY,
    )

    val windowState = rememberWindowState(
        width = if (isScreenshotMode) {
            SCREENSHOT_MODE_WIDTH
        } else {
            clampedWidth.dp
        },
        height = if (isScreenshotMode) {
            SCREENSHOT_MODE_HEIGHT
        } else {
            clampedHeight.dp
        },
        position = if (savedX >= 0 && savedY >= 0) {
            WindowPosition(clampedX.dp, clampedY.dp)
        } else {
            WindowPosition.Aligned(Alignment.Center)
        },
        placement = if (isScreenshotMode) {
            WindowPlacement.Floating
        } else if (isMaximized) {
            WindowPlacement.Maximized
        } else {
            WindowPlacement.Floating
        },
    )

    LaunchedEffect(windowState.size, windowState.position, windowState.placement) {
        // Never persist window geometry captured while in screenshot mode —
        // it's a temporary fixed-size session, not a real user preference.
        if (isScreenshotMode) return@LaunchedEffect
        // Only persist width/height/position while Floating. While the window
        // is Maximized, windowState.size reports the full maximized dimensions
        // (which can reflect a different/larger monitor than the one the user
        // normally works in) — persisting those as the "restored" geometry is
        // what causes the restored window to exceed the screen and push the
        // title bar off-screen. The `isMaximized` flag itself is still saved
        // on every change so the app reopens maximized/floating correctly.
        if (windowState.placement == WindowPlacement.Floating) {
            val (w, h, x, y) = clampWindowGeometryToScreen(
                width = windowState.size.width.value.toInt(),
                height = windowState.size.height.value.toInt(),
                x = windowState.position.x.value.toInt(),
                y = windowState.position.y.value.toInt(),
            )
            ThemePreferences.saveWindowState(
                width = w,
                height = h,
                x = x,
                y = y,
                isMaximized = false,
            )
        } else {
            ThemePreferences.saveWindowState(
                width = savedWidth,
                height = savedHeight,
                x = savedX,
                y = savedY,
                isMaximized = windowState.placement == WindowPlacement.Maximized,
            )
        }
    }

    return windowState
}
