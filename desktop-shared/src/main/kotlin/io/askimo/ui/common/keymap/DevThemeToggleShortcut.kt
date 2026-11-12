/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.keymap

import io.askimo.core.config.AppConfig
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.util.Platform
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * Hidden, dev-only global shortcut: Cmd+A (macOS) / Ctrl+A (Windows/Linux) toggles the
 * light/dark preview, regardless of which Askimo window has focus (main window or any dialog).
 *
 * Registered via AWT [KeyboardFocusManager] instead of [KeyMapManager]'s Compose-level
 * `onPreviewKeyEvent`, since each `AlertDialog`/`Dialog` opens its own native window on Compose
 * Desktop and a Compose handler on the main window never sees key events while one is focused.
 * A [java.awt.KeyEventDispatcher] intercepts events process-wide before window routing, so it
 * works everywhere, and naturally never fires when another app has focus.
 *
 * Not a [KeyMapManager.AppShortcut] — stays out of `KeyboardShortcutsDialog`, menu bars, and
 * i18n. Inert unless Developer Mode ([AppConfig.developer]) is active.
 *
 * Call once at startup, before `application { }`.
 */
fun installDevThemeToggleShortcut() {
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher { event ->
        if (event.id != AwtKeyEvent.KEY_PRESSED || event.keyCode != AwtKeyEvent.VK_B) {
            return@addKeyEventDispatcher false
        }
        if (event.isAltDown || event.isShiftDown) return@addKeyEventDispatcher false

        val primaryPressed = if (Platform.isMac) {
            event.isMetaDown && !event.isControlDown
        } else {
            event.isControlDown
        }
        if (!primaryPressed) return@addKeyEventDispatcher false

        if (!AppConfig.developer.enabled || !AppConfig.developer.active) return@addKeyEventDispatcher false

        ThemePreferences.toggleDarkLightMode()
        true
    }
}
