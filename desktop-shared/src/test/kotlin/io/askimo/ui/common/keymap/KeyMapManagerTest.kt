/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.keymap

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(InternalComposeUiApi::class)
class KeyMapManagerTest {

    private fun keyEvent(
        key: Key,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
        meta: Boolean = false,
    ): KeyEvent = KeyEvent(
        key = key,
        type = KeyEventType.KeyDown,
        isCtrlPressed = ctrl,
        isMetaPressed = meta,
        isAltPressed = alt,
        isShiftPressed = shift,
    )

    @Test
    fun `Ctrl+N matches NEW_CHAT`() {
        assertEquals(
            KeyMapManager.AppShortcut.NEW_CHAT,
            KeyMapManager.handleKeyEvent(keyEvent(Key.N, ctrl = true)),
        )
    }

    @Test
    fun `Ctrl+Comma matches OPEN_SETTINGS`() {
        assertEquals(
            KeyMapManager.AppShortcut.OPEN_SETTINGS,
            KeyMapManager.handleKeyEvent(keyEvent(Key.Comma, ctrl = true)),
        )
    }

    @Test
    fun `Ctrl+Shift+N matches CREATE_PROJECT, not NEW_CHAT`() {
        assertEquals(
            KeyMapManager.AppShortcut.CREATE_PROJECT,
            KeyMapManager.handleKeyEvent(keyEvent(Key.N, ctrl = true, shift = true)),
        )
    }

    @Test
    fun `plain N without modifiers does not match any shortcut`() {
        assertNull(KeyMapManager.handleKeyEvent(keyEvent(Key.N)))
    }

    @Test
    fun `N with only Meta (no Ctrl) does not match on non-mac`() {
        // Guards against the Windows/Super key accidentally being treated as a modifier.
        assertNull(KeyMapManager.handleKeyEvent(keyEvent(Key.N, meta = true)))
    }

    @Test
    fun `Ctrl+Alt+F matches ENTER_FULLSCREEN, not SEARCH_IN_CHAT`() {
        assertEquals(
            KeyMapManager.AppShortcut.ENTER_FULLSCREEN,
            KeyMapManager.handleKeyEvent(keyEvent(Key.F, ctrl = true, alt = true)),
        )
    }

    @Test
    fun `plain Ctrl+F still matches SEARCH_IN_CHAT`() {
        assertEquals(
            KeyMapManager.AppShortcut.SEARCH_IN_CHAT,
            KeyMapManager.handleKeyEvent(keyEvent(Key.F, ctrl = true)),
        )
    }

    @Test
    fun `Ctrl+Shift+F matches GLOBAL_SEARCH`() {
        assertEquals(
            KeyMapManager.AppShortcut.GLOBAL_SEARCH,
            KeyMapManager.handleKeyEvent(keyEvent(Key.F, ctrl = true, shift = true)),
        )
    }

    @Test
    fun `getDisplayString does not duplicate the primary modifier on non-mac`() {
        // NEW_CHAT only requires the primary modifier (Ctrl on Windows/Linux) — the display
        // string must show "Ctrl+N", not something with a duplicated "Ctrl".
        val display = KeyMapManager.AppShortcut.NEW_CHAT.getDisplayString()
        assertEquals(1, display.count { it == '+' }, "expected exactly one '+' separator in '$display'")
        assertEquals("Ctrl+N", display)
    }

    // ── Component-local shortcuts (ChatInputField.kt / ChatView.kt) ──────────────────────────
    //
    // Unlike NEW_CHAT/OPEN_SETTINGS/etc. above, none of the shortcuts below are registered in
    // NativeMenuBar.kt — they have no menu or native OS accelerator on any platform, mac
    // included. They're matched purely via KeyMapManager.handleKeyEvent() from inside
    // onPreviewKeyEvent handlers scoped to the chat input field / chat view, which reliably
    // self-focus whenever rendered. So unlike the Main.kt-dispatcher shortcuts, these were
    // never affected by the "nothing has Compose focus" bug — but they were just as broken by
    // the modifier-matching bug on Windows/Linux (see KeyModifierScheme.kt), since that bug
    // affected every `requiresPrimaryModifier` shortcut uniformly, regardless of whether a menu
    // was involved at all.

    @Test
    fun `Ctrl+Shift+A matches ATTACH_FILE`() {
        assertEquals(
            KeyMapManager.AppShortcut.ATTACH_FILE,
            KeyMapManager.handleKeyEvent(keyEvent(Key.A, ctrl = true, shift = true)),
        )
    }

    @Test
    fun `Ctrl+Shift+M matches TOGGLE_VOICE_RECORDING`() {
        assertEquals(
            KeyMapManager.AppShortcut.TOGGLE_VOICE_RECORDING,
            KeyMapManager.handleKeyEvent(keyEvent(Key.M, ctrl = true, shift = true)),
        )
    }

    @Test
    fun `plain Enter matches SEND_MESSAGE, not NEW_LINE`() {
        assertEquals(
            KeyMapManager.AppShortcut.SEND_MESSAGE,
            KeyMapManager.handleKeyEvent(keyEvent(Key.Enter)),
        )
    }

    @Test
    fun `Shift+Enter matches NEW_LINE, not SEND_MESSAGE`() {
        assertEquals(
            KeyMapManager.AppShortcut.NEW_LINE,
            KeyMapManager.handleKeyEvent(keyEvent(Key.Enter, shift = true)),
        )
    }

    @Test
    fun `Ctrl+Shift+J matches FOCUS_MESSAGE_LIST`() {
        assertEquals(
            KeyMapManager.AppShortcut.FOCUS_MESSAGE_LIST,
            KeyMapManager.handleKeyEvent(keyEvent(Key.J, ctrl = true, shift = true)),
        )
    }

    @Test
    fun `Ctrl+Shift+L matches TOGGLE_LAST_RESPONSE_PLAYBACK`() {
        assertEquals(
            KeyMapManager.AppShortcut.TOGGLE_LAST_RESPONSE_PLAYBACK,
            KeyMapManager.handleKeyEvent(keyEvent(Key.L, ctrl = true, shift = true)),
        )
    }

    @Test
    fun `plain Escape matches CLOSE_SEARCH`() {
        assertEquals(
            KeyMapManager.AppShortcut.CLOSE_SEARCH,
            KeyMapManager.handleKeyEvent(keyEvent(Key.Escape)),
        )
    }

    @Test
    fun `Ctrl+G matches NEXT_SEARCH_RESULT, not PREVIOUS_SEARCH_RESULT`() {
        assertEquals(
            KeyMapManager.AppShortcut.NEXT_SEARCH_RESULT,
            KeyMapManager.handleKeyEvent(keyEvent(Key.G, ctrl = true)),
        )
    }

    @Test
    fun `Ctrl+Shift+G matches PREVIOUS_SEARCH_RESULT, not NEXT_SEARCH_RESULT`() {
        assertEquals(
            KeyMapManager.AppShortcut.PREVIOUS_SEARCH_RESULT,
            KeyMapManager.handleKeyEvent(keyEvent(Key.G, ctrl = true, shift = true)),
        )
    }
}
