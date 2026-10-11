/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.service

/**
 * Provides inline "ghost text" completion suggestions for the chat input field,
 * similar to shell history autocomplete or Smart Compose.
 *
 * Implementations should be fast (called on every debounced keystroke) and must
 * not block the UI thread — callers are expected to invoke [suggest] from a
 * background dispatcher (e.g. `Dispatchers.IO`).
 */
interface SuggestionService {
    /**
     * Given the current user input (text typed so far, cursor assumed at the end),
     * returns the suggested continuation (i.e. just the remainder to append),
     * or null if there's no suggestion.
     *
     * @param input The text currently typed by the user.
     * @param sessionId Optional id of the active chat session, used by implementations
     *   that scope suggestions to the current conversation's history. Implementations
     *   that don't need session scoping (e.g. a static/demo provider) may ignore it.
     */
    fun suggest(input: String, sessionId: String? = null): String?
}
