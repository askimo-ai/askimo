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
 * App-lifetime singleton (see `DesktopModule`) holding a bounded, content-keyed map ([cache])
 * of the last [HISTORY_SCAN_LIMIT] distinct, eligible user messages. Seeded once from the DB
 * on the first [suggest] call, then kept in sync via [UserMessageAddedEvent] — no further DB
 * reads on the typing path. Repeats bump [HistoryEntry.count]/recency instead of adding a
 * duplicate slot. Short acknowledgments and multi-line/structured pastes (see
 * [isEligibleCandidate]) never enter the cache.
 *
 * **Known gap:** `EventBus.post` emits asynchronously, so a message persisted right around the
 * first [suggest] call can be both read by [seedIfNeeded] *and* delivered via
 * [UserMessageAddedEvent] — double-counting it in [HistoryEntry.count]. With count-based
 * ranking, a message sent once could then outrank genuinely repeated history. A proper fix
 * needs a shared message identity/watermark between the seed query and the event stream so
 * already-seeded events can be deduped; not implemented here as it's a narrow startup race.
 *
 * ### Ranking
 * Case-insensitive whole-input prefix match (predictable "retype faster", not a guess).
 * Among matches, most *frequently* typed wins, ties broken by most *recent* — same scheme as
 * [PersistedPhraseSuggestionService].
 *
 * [sessionId] only gates "is there an active session" — not used to filter candidates.
 */
class HistoryBackedSuggestionService(
    private val messageRepository: ChatMessageRepository,
) : SuggestionService {

    companion object {
        /** Max distinct, eligible user messages retained. */
        private const val HISTORY_SCAN_LIMIT = 200

        /** Seed fetch escalates up to this multiple of [HISTORY_SCAN_LIMIT] raw rows — see [seedIfNeeded]. */
        private const val SEED_OVERFETCH_FACTOR = 5

        /** Minimum typed length before bothering to search. */
        private const val MIN_INPUT_LENGTH = 3

        /** Minimum message length to cache/suggest — filters out "yes"/"ok"/"thanks" style
         *  replies without needing a phrase denylist. */
        private const val MIN_CANDIDATE_LENGTH = 10

        /** Dedup key — trim + case-fold so casing/whitespace variants collapse into one entry. */
        private fun dedupKey(text: String): String = text.trim().lowercase()

        /** Rejects: too short (see [MIN_CANDIDATE_LENGTH]), multi-line, or starting with
         *  `{`/`[` (pasted JSON/code — would break single-line ghost text). */
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
     * @property content Original text — completions are spliced from this.
     * @property count Times sent — primary ranking signal.
     * @property lastSeenOrder Monotonic touch counter — tie-breaker and LRU eviction basis.
     */
    private data class HistoryEntry(val content: String, var count: Int, var lastSeenOrder: Long)

    /** Keyed by [dedupKey] → [HistoryEntry], capped at [HISTORY_SCAN_LIMIT]. */
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
        // Copy entries (not just the list) while locked: count/lastSeenOrder are mutable and
        // can be updated concurrently by onUserMessageAdded right after the lock is released.
        val snapshot = synchronized(cacheLock) { cache.values.map { it.copy() } }

        return bestPrefixMatch(
            candidates = snapshot.asSequence(),
            trimmedInput = trimmed,
            textOf = { it.content },
            comparator = compareBy({ it.count }, { it.lastSeenOrder }),
        )
    }

    /**
     * Lazily seeds the cache from the DB — runs once per lifetime.
     *
     * [ChatMessageRepository.getRecentUserMessagesGlobal] only takes a `limit` (no cursor), so
     * true non-overlapping batches aren't possible. Instead this escalates the fetch limit in
     * [HISTORY_SCAN_LIMIT] steps — re-querying only if the previous attempt didn't yield enough
     * eligible messages — capped at [SEED_OVERFETCH_FACTOR] x [HISTORY_SCAN_LIMIT] rows.
     * Usually stops after one cheap 200-row fetch.
     */
    private fun seedIfNeeded() {
        if (seeded) return
        synchronized(cacheLock) {
            if (seeded) return
            val maxFetchLimit = HISTORY_SCAN_LIMIT * SEED_OVERFETCH_FACTOR
            var fetchLimit = HISTORY_SCAN_LIMIT

            while (true) {
                val rows = messageRepository.getRecentUserMessagesGlobal(limit = fetchLimit)

                // Rebuild from scratch so count/lastSeenOrder aren't inflated by re-seeing the
                // same rows across escalating fetches.
                cache.clear()
                order = 0
                rows.map { it.content }.asReversed().forEach { addOrTouch(it) }

                val dbExhausted = rows.size < fetchLimit
                val gotEnough = cache.size >= HISTORY_SCAN_LIMIT
                if (gotEnough || dbExhausted || fetchLimit >= maxFetchLimit) break
                fetchLimit = (fetchLimit + HISTORY_SCAN_LIMIT).coerceAtMost(maxFetchLimit)
            }
            seeded = true
        }
    }

    private fun onUserMessageAdded(content: String) {
        synchronized(cacheLock) { addOrTouch(content) }
    }

    /** Must hold [cacheLock]. Bumps an existing entry or inserts one, evicting the
     *  least-recently-touched entry over capacity. No-ops if [isEligibleCandidate] fails. */
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
