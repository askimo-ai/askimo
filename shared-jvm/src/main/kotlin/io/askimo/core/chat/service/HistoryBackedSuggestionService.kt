/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.service

import io.askimo.core.chat.repository.ChatMessageRepository
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.UserMessageAddedEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * [SuggestionService] backed by the user's own chat history **across all sessions**.
 * Suggests the remainder of a previously-sent user message that starts with the current
 * input — similar to shell history autocomplete, spanning every session rather than just
 * the active one.
 *
 * ### Caching
 * Registered as an app-lifetime singleton (see `DesktopModule`), so a single bounded FIFO
 * ([cache]) of the last [HISTORY_SCAN_LIMIT] user messages is kept in-memory: seeded once,
 * lazily, from the DB on the first [suggest] call via
 * [ChatMessageRepository.getRecentUserMessagesGlobal] (which already filters to
 * `role = USER` server-side), then kept in sync purely by consuming
 * [UserMessageAddedEvent] — no further DB reads happen on the typing path.
 *
 * [sessionId] is only used as an "is there an active session at all" gate, not as a filter
 * — suggestions may be drawn from any session.
 */
class HistoryBackedSuggestionService(
    private val messageRepository: ChatMessageRepository,
) : SuggestionService {

    companion object {
        /** How many of the most recent user messages (across all sessions) to retain/scan. */
        private const val HISTORY_SCAN_LIMIT = 200

        /** Minimum typed length before we bother searching — avoids noisy 1-2 char matches. */
        private const val MIN_INPUT_LENGTH = 3
    }

    /** Global FIFO of user message content, oldest → newest, capped at [HISTORY_SCAN_LIMIT]. */
    private val cache = ArrayDeque<String>()
    private val cacheLock = Any()

    @Volatile
    private var seeded = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            EventBus.internalEvents
                .filterIsInstance<UserMessageAddedEvent>()
                .collect { onUserMessageAdded(it.content) }
        }
    }

    override fun suggest(input: String, sessionId: String?): String? {
        if (sessionId.isNullOrBlank()) return null

        val trimmed = input.trimStart()
        if (trimmed.length < MIN_INPUT_LENGTH) return null

        seedIfNeeded()
        val snapshot = synchronized(cacheLock) { cache.toList() }

        // Oldest → newest; scan newest-first so the most recent matching message wins.
        val match = snapshot.asReversed().firstOrNull { candidate ->
            candidate.length > trimmed.length &&
                candidate.startsWith(trimmed, ignoreCase = true) &&
                !candidate.equals(trimmed, ignoreCase = true)
        } ?: return null

        return match.substring(trimmed.length)
    }

    /** Lazily seeds the FIFO cache from the DB — runs at most once for this singleton's lifetime. */
    private fun seedIfNeeded() {
        if (seeded) return
        synchronized(cacheLock) {
            if (seeded) return
            // getRecentUserMessagesGlobal already filters role = USER server-side and returns
            // most-recent-first; reverse to store oldest → newest for FIFO append semantics.
            val seedContent = messageRepository
                .getRecentUserMessagesGlobal(limit = HISTORY_SCAN_LIMIT)
                .map { it.content }
                .asReversed()
            cache.addAll(seedContent)
            seeded = true
        }
    }

    private fun onUserMessageAdded(content: String) {
        synchronized(cacheLock) {
            cache.addLast(content)
            if (cache.size > HISTORY_SCAN_LIMIT) cache.removeFirst()
        }
    }
}
