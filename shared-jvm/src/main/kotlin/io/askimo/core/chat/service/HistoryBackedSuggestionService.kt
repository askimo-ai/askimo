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
 * Registered as an app-lifetime singleton (see `DesktopModule`), so a single bounded,
 * content-keyed map ([cache]) of the last [HISTORY_SCAN_LIMIT] **distinct, eligible** user
 * messages is kept in-memory: seeded once, lazily, from the DB on the first [suggest] call via
 * [ChatMessageRepository.getRecentUserMessagesGlobal] (overfetched — see
 * [SEED_OVERFETCH_FACTOR] — since that query already filters to `role = USER` server-side but
 * not for eligibility), then kept in sync purely by consuming [UserMessageAddedEvent] — no
 * further DB reads happen on the typing path. Repeating a message bumps its
 * [HistoryEntry.count] and recency instead of adding a duplicate slot, so the bounded window
 * holds [HISTORY_SCAN_LIMIT] distinct messages, not occurrences. Short acknowledgments (see
 * [isEligibleCandidate]) and multi-line/structured pastes never enter the cache at all.
 *
 * ### Ranking
 * Matching is still a case-insensitive **whole-input prefix** of a past message, exactly as
 * before — this stays predictable "retype faster" behavior rather than guessing new content.
 * Among multiple matches, the candidate typed most *often* wins (ties broken by most
 * *recently*), matching the usage/recency ranking already used by
 * [PersistedPhraseSuggestionService].
 *
 * [sessionId] is only used as an "is there an active session at all" gate, not as a filter
 * — suggestions may be drawn from any session.
 */
class HistoryBackedSuggestionService(
    private val messageRepository: ChatMessageRepository,
) : SuggestionService {

    companion object {
        /** How many of the most recent **distinct, eligible** user messages (across all sessions) to retain/scan. */
        private const val HISTORY_SCAN_LIMIT = 200

        /**
         * Seeding overfetch multiplier: raw DB rows are mostly-distinct but often include a lot
         * of low-value short replies ("yes", "thanks") and literal repeats, which
         * [isEligibleCandidate] and dedup then collapse — so without overfetching, the
         * distinct/eligible window would end up far smaller than [HISTORY_SCAN_LIMIT]. Pulling
         * this many raw rows up front keeps the cache close to full of genuinely useful,
         * distinct messages.
         */
        private const val SEED_OVERFETCH_FACTOR = 5

        /** Minimum typed length before we bother searching — avoids noisy 1-2 char matches. */
        private const val MIN_INPUT_LENGTH = 3

        /**
         * Minimum content length for a message to be worth caching/suggesting at all — below
         * this, a message is almost always a short acknowledgment ("yes", "ok", "thanks") with
         * no useful remainder to complete. A plain length gate is simpler and more robust than
         * maintaining a phrase denylist (no locale/wording coverage gaps).
         */
        private const val MIN_CANDIDATE_LENGTH = 10

        /** Dedup key only — trims and case-folds so e.g. trailing-space/casing variants of the
         *  same message collapse into one [HistoryEntry] instead of wasting cache slots. Never
         *  used for match substring offsets (that's done against [HistoryEntry.content]). */
        private fun dedupKey(text: String): String = text.trim().lowercase()

        /**
         * Filters out candidates that shouldn't be cached/suggested at all:
         *  - blank or shorter than [MIN_CANDIDATE_LENGTH] (filters out "yes", "ok", "thanks",
         *    etc. without needing an explicit phrase list),
         *  - multi-line or otherwise "structured" content (contains `\n`/`\r`, or starts with a
         *    brace/bracket like `{`/`[` — typically pasted JSON/code, not a natural sentence) —
         *    suggesting these as inline ghost text breaks the single-line input UX.
         */
        private fun isEligibleCandidate(content: String): Boolean {
            val trimmed = content.trim()
            if (trimmed.length < MIN_CANDIDATE_LENGTH) return false
            if (trimmed.any { it == '\n' || it == '\r' }) return false
            if (trimmed.firstOrNull() in setOf('{', '[')) return false
            return true
        }
    }

    /**
     * One distinct historical message and its usage stats.
     *
     * @property content Original (non-normalized) message text — completions are spliced from
     *   this so casing/spacing in the suggestion matches what the user actually sent.
     * @property count How many times this exact message has been sent — the primary ranking
     *   signal (frequent phrases beat merely-recent ones).
     * @property lastSeenOrder Monotonic insertion/touch counter — tie-breaker, and basis for
     *   FIFO eviction of the least-recently-touched entry once [HISTORY_SCAN_LIMIT] is exceeded.
     */
    private data class HistoryEntry(val content: String, var count: Int, var lastSeenOrder: Long)

    /** Global map keyed by [dedupKey] → [HistoryEntry], capped at [HISTORY_SCAN_LIMIT] distinct entries. */
    private val cache = LinkedHashMap<String, HistoryEntry>()
    private val cacheLock = Any()
    private var order = 0L

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
        val snapshot = synchronized(cacheLock) { cache.values.toList() }

        return bestPrefixMatch(
            candidates = snapshot.asSequence(),
            trimmedInput = trimmed,
            textOf = { it.content },
            comparator = compareBy({ it.count }, { it.lastSeenOrder }),
        )
    }

    /** Lazily seeds the cache from the DB — runs at most once for this singleton's lifetime. */
    private fun seedIfNeeded() {
        if (seeded) return
        synchronized(cacheLock) {
            if (seeded) return
            // getRecentUserMessagesGlobal already filters role = USER server-side and returns
            // most-recent-first; reverse to seed oldest → newest so recency order is preserved.
            // Overfetch since isEligibleCandidate + dedup collapse a meaningful fraction of raw
            // rows (short replies, literal repeats) — without this the distinct/eligible window
            // would end up well under HISTORY_SCAN_LIMIT.
            val seedContent = messageRepository
                .getRecentUserMessagesGlobal(limit = HISTORY_SCAN_LIMIT * SEED_OVERFETCH_FACTOR)
                .map { it.content }
                .asReversed()
            seedContent.forEach { addOrTouch(it) }
            seeded = true
        }
    }

    private fun onUserMessageAdded(content: String) {
        synchronized(cacheLock) { addOrTouch(content) }
    }

    /** Must be called while holding [cacheLock]. Bumps an existing entry's count/recency, or
     *  inserts a new one — evicting the least-recently-touched entry once over capacity.
     *  No-ops for content that fails [isEligibleCandidate] (short replies, structured/multi-line
     *  pastes) — these are never worth caching as suggestions. */
    private fun addOrTouch(content: String) {
        if (!isEligibleCandidate(content)) return
        val key = dedupKey(content)
        val existing = cache[key]
        if (existing != null) {
            existing.count++
            existing.lastSeenOrder = ++order
        } else {
            cache[key] = HistoryEntry(content, count = 1, lastSeenOrder = ++order)
            if (cache.size > HISTORY_SCAN_LIMIT) {
                val oldestKey = cache.entries.minByOrNull { it.value.lastSeenOrder }?.key
                if (oldestKey != null) cache.remove(oldestKey)
            }
        }
    }
}
