/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.keymap

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import io.askimo.core.i18n.LocalizationManager
import io.askimo.ui.util.Platform

/**
 * Centralized keyboard shortcut manager for the Askimo Desktop application.
 * Handles all keyboard shortcuts and provides platform-specific key mappings.
 */
object KeyMapManager {
    /**
     * Determines if the primary modifier key (Command on macOS, Ctrl on others) is pressed
     */
    fun KeyEvent.isPrimaryModifierPressed(): Boolean = if (Platform.isMac) isMetaPressed else isCtrlPressed

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

        // View shortcuts
        ENTER_FULLSCREEN("shortcut.enter.fullscreen", Key.F, requiresPrimaryModifier = true, requiresCtrl = true),
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
        SEND_MESSAGE("shortcut.send.message", Key.Enter),
        NEW_LINE("shortcut.new.line", Key.Enter, requiresShift = true),
        ;

        /**
         * Gets the localized description for this shortcut
         */
        fun getDescription(): String = LocalizationManager.getString(descriptionKey)

        /**
         * Checks if this shortcut matches the given key event
         */
        fun matches(keyEvent: KeyEvent): Boolean {
            if (keyEvent.key != key) return false
            if (requiresPrimaryModifier && !keyEvent.isPrimaryModifierPressed()) return false
            if (!requiresPrimaryModifier && keyEvent.isPrimaryModifierPressed()) return false
            if (requiresShift && !keyEvent.isShiftPressed) return false
            if (!requiresShift && keyEvent.isShiftPressed) return false
            if (requiresAlt && !keyEvent.isAltPressed) return false
            if (!requiresAlt && keyEvent.isAltPressed) return false
            if (requiresCtrl && !keyEvent.isCtrlPressed) return false
            if (!requiresCtrl && keyEvent.isCtrlPressed) return false
            return true
        }

        /**
         * Returns a human-readable string for this shortcut
         */
        fun getDisplayString(): String {
            val parts = mutableListOf<String>()

            if (requiresPrimaryModifier) {
                parts.add(Platform.modifierKey)
            }
            if (requiresCtrl) {
                parts.add(if (Platform.isMac) "⌃" else "Ctrl")
            }
            if (requiresShift) {
                parts.add(if (Platform.isMac) "⇧" else "Shift")
            }
            if (requiresAlt) {
                parts.add(if (Platform.isMac) "⌥" else "Alt")
            }

            // Special key names
            val keyName = when (key) {
                Key.Enter -> if (Platform.isMac) "↵" else "Enter"
                Key.Escape -> if (Platform.isMac) "⎋" else "Esc"
                Key.Comma -> ","
                else -> key.keyCode.toInt().toChar().uppercaseChar().toString()
            }
            parts.add(keyName)

            return parts.joinToString(if (Platform.isMac) "" else "+")
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
            AppShortcut.SEND_MESSAGE,
            AppShortcut.NEW_LINE,
        ),
    )
}
