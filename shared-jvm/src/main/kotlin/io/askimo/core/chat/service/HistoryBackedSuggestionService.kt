/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.service

import io.askimo.core.chat.repository.ChatMessageRepository
import io.askimo.core.context.MessageRole

/**
 * [SuggestionService] backed by the user's own chat history within the current session.
 * Suggests the remainder of a previously-sent user message that starts with the current
 * input — similar to shell history autocomplete.
 *
 * Scoped to [sessionId] passed into [suggest]; messages from other sessions are not
 * considered. Returns null when no [sessionId] is supplied (e.g. before a session exists).
 */
class HistoryBackedSuggestionService(
    private val messageRepository: ChatMessageRepository,
) : SuggestionService {

    companion object {
        /** How many of the most recent active messages in the session to scan for a match. */
        private const val HISTORY_SCAN_LIMIT = 200

        /** Minimum typed length before we bother searching — avoids noisy 1-2 char matches. */
        private const val MIN_INPUT_LENGTH = 3
    }

    override fun suggest(input: String, sessionId: String?): String? {
        if (sessionId.isNullOrBlank()) return null

        val trimmed = input.trimStart()
        if (trimmed.length < MIN_INPUT_LENGTH) return null

        // getRecentActiveMessages returns oldest → newest; reverse so the most recent
        // matching message wins (more likely to be relevant than an old one).
        val recentUserMessages = messageRepository
            .getRecentActiveMessages(sessionId, limit = HISTORY_SCAN_LIMIT)
            .asReversed()
            .asSequence()
            .filter { it.role == MessageRole.USER }
            .map { it.content }

        val match = recentUserMessages.firstOrNull { candidate ->
            candidate.length > trimmed.length &&
                candidate.startsWith(trimmed, ignoreCase = true) &&
                !candidate.equals(trimmed, ignoreCase = true)
        } ?: return null

        return match.substring(trimmed.length)
    }
}
