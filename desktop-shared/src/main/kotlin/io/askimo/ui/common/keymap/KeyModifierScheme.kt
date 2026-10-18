/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.keymap

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import io.askimo.ui.util.Platform

/**
 * The modifier-key requirements declared by an [KeyMapManager.AppShortcut], expressed in
 * platform-agnostic terms. A [KeyModifierScheme] translates these onto a specific OS's
 * physical modifier keys.
 */
data class ShortcutModifiers(
    val requiresPrimaryModifier: Boolean,
    val requiresCtrl: Boolean,
    val requiresShift: Boolean,
    val requiresAlt: Boolean,
)

/**
 * Maps a shortcut's platform-agnostic [ShortcutModifiers] onto a specific OS's physical
 * modifier keys — both for matching incoming [KeyEvent]s and for rendering a human-readable
 * label.
 */
sealed interface KeyModifierScheme {
    /** Separator used when joining display parts, e.g. "" for mac glyphs, "+" elsewhere. */
    val displaySeparator: String

    /**
     * Whether [keyEvent]'s modifier keys satisfy [modifiers]. The key code itself (e.g. `N`)
     * is checked separately by the caller.
     */
    fun matches(modifiers: ShortcutModifiers, keyEvent: KeyEvent): Boolean

    /** Ordered, human-readable modifier labels to show before the key name (e.g. `["⌘", "⇧"]`). */
    fun modifierLabels(modifiers: ShortcutModifiers): List<String>

    /** Human-readable label for the shortcut's non-modifier key (Enter, Escape, N, ...). */
    fun keyLabel(key: Key): String

    companion object {
        /** The scheme matching the OS this JVM is currently running on. */
        fun forCurrentPlatform(): KeyModifierScheme = if (Platform.isMac) MacKeyModifierScheme else StandardKeyModifierScheme
    }
}

/**
 * macOS modifier model: Cmd (the "primary modifier") and Ctrl are distinct physical keys that
 * can be chorded together, so they're matched/displayed fully independently.
 */
private object MacKeyModifierScheme : KeyModifierScheme {
    override val displaySeparator = ""

    override fun matches(modifiers: ShortcutModifiers, keyEvent: KeyEvent): Boolean {
        if (keyEvent.isMetaPressed != modifiers.requiresPrimaryModifier) return false
        if (keyEvent.isCtrlPressed != modifiers.requiresCtrl) return false
        if (keyEvent.isShiftPressed != modifiers.requiresShift) return false
        if (keyEvent.isAltPressed != modifiers.requiresAlt) return false
        return true
    }

    override fun modifierLabels(modifiers: ShortcutModifiers): List<String> = buildList {
        if (modifiers.requiresPrimaryModifier) add(Platform.modifierKey) // "⌘"
        if (modifiers.requiresCtrl) add("⌃")
        if (modifiers.requiresShift) add("⇧")
        if (modifiers.requiresAlt) add("⌥")
    }

    override fun keyLabel(key: Key): String = when (key) {
        Key.Enter -> "↵"
        Key.Escape -> "⎋"
        Key.Comma -> ","
        Key.Slash -> "/"
        else -> key.keyCode.toInt().toChar().uppercaseChar().toString()
    }
}

/**
 * Windows/Linux modifier model: there is no separate OS-reserved key that can act as a
 * "primary modifier" distinct from Ctrl — Ctrl *is* the primary modifier. A shortcut's
 * [ShortcutModifiers.requiresCtrl] therefore collapses onto the same physical key as
 * [ShortcutModifiers.requiresPrimaryModifier] instead of stacking on top of it, and Meta
 * (the Windows/Super key) never participates in any shortcut on these platforms.
 */
private object StandardKeyModifierScheme : KeyModifierScheme {
    override val displaySeparator = "+"

    override fun matches(modifiers: ShortcutModifiers, keyEvent: KeyEvent): Boolean {
        val needsCtrl = modifiers.requiresPrimaryModifier || modifiers.requiresCtrl
        if (keyEvent.isCtrlPressed != needsCtrl) return false
        if (keyEvent.isMetaPressed) return false
        if (keyEvent.isShiftPressed != modifiers.requiresShift) return false
        if (keyEvent.isAltPressed != modifiers.requiresAlt) return false
        return true
    }

    override fun modifierLabels(modifiers: ShortcutModifiers): List<String> = buildList {
        if (modifiers.requiresPrimaryModifier || modifiers.requiresCtrl) add("Ctrl")
        if (modifiers.requiresShift) add("Shift")
        if (modifiers.requiresAlt) add("Alt")
    }

    override fun keyLabel(key: Key): String = when (key) {
        Key.Enter -> "Enter"
        Key.Escape -> "Esc"
        Key.Comma -> ","
        Key.Slash -> "/"
        else -> key.keyCode.toInt().toChar().uppercaseChar().toString()
    }
}
