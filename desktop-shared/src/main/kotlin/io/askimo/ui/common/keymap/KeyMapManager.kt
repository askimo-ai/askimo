/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.keymap

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import io.askimo.core.i18n.LocalizationManager

/**
 * Centralized keyboard shortcut manager for the Askimo Desktop application.
 * Handles all keyboard shortcuts and provides platform-specific key mappings.
 */
object KeyMapManager {
    // Encapsulates macOS vs. Windows/Linux modifier-key differences — see KeyModifierScheme.kt
    // for why that logic lives in its own per-platform classes rather than inline branches here.
    private val modifierScheme: KeyModifierScheme = KeyModifierScheme.forCurrentPlatform()

    /**
     * Defines all application shortcuts
     */
    enum class AppShortcut(
        val descriptionKey: String,
        val key: Key,
        val requiresPrimaryModifier: Boolean = false,
        val requiresShift: Boolean = false,
        val requiresAlt: Boolean = false,
        val requiresCtrl: Boolean = false,
    ) {
        // Global shortcuts
        NEW_CHAT("shortcut.new.chat", Key.N, requiresPrimaryModifier = true),
        SEARCH_IN_CHAT("shortcut.search.in.chat", Key.F, requiresPrimaryModifier = true),
        GLOBAL_SEARCH("shortcut.global.search", Key.F, requiresPrimaryModifier = true, requiresShift = true),
        TOGGLE_CHAT_HISTORY("shortcut.toggle.chat.history", Key.H, requiresPrimaryModifier = true),
        OPEN_SETTINGS("shortcut.open.settings", Key.Comma, requiresPrimaryModifier = true),
        STOP_AI_RESPONSE("shortcut.stop.ai.response", Key.S, requiresPrimaryModifier = true),
        QUIT_APPLICATION("shortcut.quit.application", Key.Q, requiresPrimaryModifier = true),
        SHOW_KEYBOARD_SHORTCUTS("shortcut.show.keyboard.shortcuts", Key.Slash, requiresPrimaryModifier = true),

        // View shortcuts
        // Uses Alt (not Ctrl) as the extra modifier: on non-mac platforms the "primary
        // modifier" collapses onto Ctrl, so pairing it with an explicit requiresCtrl here
        // would be indistinguishable from SEARCH_IN_CHAT's plain Ctrl+F. Alt stays a
        // distinct physical key on every platform, keeping this shortcut unambiguous.
        ENTER_FULLSCREEN("shortcut.enter.fullscreen", Key.F, requiresPrimaryModifier = true, requiresAlt = true),
        NAVIGATE_TO_SESSIONS("shortcut.navigate.to.sessions", Key.E, requiresPrimaryModifier = true),
        NAVIGATE_TO_PROJECTS("shortcut.navigate.to.projects", Key.P, requiresPrimaryModifier = true),
        NAVIGATE_TO_BOOKMARKS("shortcut.navigate.to.bookmarks", Key.B, requiresPrimaryModifier = true, requiresShift = true),

        // Project shortcuts
        CREATE_PROJECT("shortcut.create.project", Key.N, requiresPrimaryModifier = true, requiresShift = true),

        // Terminal shortcuts
        NEW_TERMINAL("shortcut.new.terminal", Key.T, requiresPrimaryModifier = true, requiresShift = true),

        // Backup shortcuts
        EXPORT_BACKUP("shortcut.export.backup", Key.E, requiresPrimaryModifier = true, requiresShift = true),
        IMPORT_BACKUP("shortcut.import.backup", Key.I, requiresPrimaryModifier = true, requiresShift = true),

        // Search shortcuts (platform-specific)
        CLOSE_SEARCH("shortcut.close.search", Key.Escape),
        NEXT_SEARCH_RESULT("shortcut.next.search.result", Key.G, requiresPrimaryModifier = true),
        PREVIOUS_SEARCH_RESULT("shortcut.previous.search.result", Key.G, requiresPrimaryModifier = true, requiresShift = true),

        // Input shortcuts
        ATTACH_FILE("shortcut.attach.file", Key.A, requiresPrimaryModifier = true, requiresShift = true),
        TOGGLE_VOICE_RECORDING("shortcut.toggle.voice.recording", Key.M, requiresPrimaryModifier = true, requiresShift = true),
        SEND_MESSAGE("shortcut.send.message", Key.Enter),
        NEW_LINE("shortcut.new.line", Key.Enter, requiresShift = true),

        // Voice playback / accessibility shortcuts
        TOGGLE_LAST_RESPONSE_PLAYBACK(
            "shortcut.toggle.last.response.playback",
            Key.L,
            requiresPrimaryModifier = true,
            requiresShift = true,
        ),
        FOCUS_MESSAGE_LIST("shortcut.focus.message.list", Key.J, requiresPrimaryModifier = true, requiresShift = true),
        ;

        /**
         * Gets the localized description for this shortcut
         */
        fun getDescription(): String = LocalizationManager.getString(descriptionKey)

        private val modifiers: ShortcutModifiers
            get() = ShortcutModifiers(requiresPrimaryModifier, requiresCtrl, requiresShift, requiresAlt)

        /**
         * Checks if this shortcut matches the given key event. Modifier matching is fully
         * delegated to [KeyModifierScheme] — see that file for why macOS vs. Windows/Linux
         * differences live there instead of as inline branches here.
         */
        fun matches(keyEvent: KeyEvent): Boolean = keyEvent.key == key && modifierScheme.matches(modifiers, keyEvent)

        /**
         * Returns a human-readable string for this shortcut
         */
        fun getDisplayString(): String {
            val parts = modifierScheme.modifierLabels(modifiers).toMutableList()
            parts.add(modifierScheme.keyLabel(key))
            return parts.joinToString(modifierScheme.displaySeparator)
        }
    }

    /**
     * Handles a key event and returns the matching shortcut, if any
     */
    fun handleKeyEvent(keyEvent: KeyEvent): AppShortcut? {
        if (keyEvent.type != KeyEventType.KeyDown) return null
        return AppShortcut.entries.firstOrNull { it.matches(keyEvent) }
    }

    /**
     * Gets all shortcuts grouped by category
     * Useful for displaying shortcuts in settings or help screens
     */
    fun getAllShortcuts(): Map<String, List<AppShortcut>> = mapOf(
        LocalizationManager.getString("shortcut.category.global") to listOf(
            AppShortcut.NEW_CHAT,
            AppShortcut.SEARCH_IN_CHAT,
            AppShortcut.GLOBAL_SEARCH,
            AppShortcut.TOGGLE_CHAT_HISTORY,
            AppShortcut.OPEN_SETTINGS,
            AppShortcut.STOP_AI_RESPONSE,
            AppShortcut.QUIT_APPLICATION,
            AppShortcut.SHOW_KEYBOARD_SHORTCUTS,
        ),
        LocalizationManager.getString("shortcut.category.file") to listOf(
            AppShortcut.EXPORT_BACKUP,
            AppShortcut.IMPORT_BACKUP,
        ),
        LocalizationManager.getString("shortcut.category.view") to listOf(
            AppShortcut.NAVIGATE_TO_SESSIONS,
            AppShortcut.NAVIGATE_TO_PROJECTS,
            AppShortcut.NAVIGATE_TO_BOOKMARKS,
            AppShortcut.ENTER_FULLSCREEN,
        ),
        LocalizationManager.getString("shortcut.category.project") to listOf(
            AppShortcut.CREATE_PROJECT,
        ),
        LocalizationManager.getString("shortcut.category.terminal") to listOf(
            AppShortcut.NEW_TERMINAL,
        ),
        LocalizationManager.getString("shortcut.category.search") to listOf(
            AppShortcut.CLOSE_SEARCH,
            AppShortcut.NEXT_SEARCH_RESULT,
            AppShortcut.PREVIOUS_SEARCH_RESULT,
        ),
        LocalizationManager.getString("shortcut.category.input") to listOf(
            AppShortcut.ATTACH_FILE,
            AppShortcut.TOGGLE_VOICE_RECORDING,
            AppShortcut.SEND_MESSAGE,
            AppShortcut.NEW_LINE,
            AppShortcut.TOGGLE_LAST_RESPONSE_PLAYBACK,
            AppShortcut.FOCUS_MESSAGE_LIST,
        ),
    )
}
