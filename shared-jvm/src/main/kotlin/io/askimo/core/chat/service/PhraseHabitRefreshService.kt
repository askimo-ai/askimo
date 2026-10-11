/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.service

import io.askimo.core.chat.repository.ChatMessageRepository
import io.askimo.core.chat.repository.UserPhraseSuggestionRepository
import io.askimo.core.config.AppConfig
import io.askimo.core.context.AppContext
import io.askimo.core.context.MessageRole
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.UserMessageAddedEvent
import io.askimo.core.logging.logger
import io.askimo.core.providers.cleanJsonResponse
import io.askimo.core.user.repository.UserProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Periodically asks the AI to extract recurring phrases/sentence openers from the user's own
 * messages, persisting the result via [UserPhraseSuggestionRepository]. Keeps the AI cost off
 * the typing path — see [PersistedPhraseSuggestionService] for the zero-cost reader.
 *
 * ### Trigger
 * [start] subscribes to [UserMessageAddedEvent] (posted per persisted **user** message, see
 * `ChatMessageRepository.addMessage`) and schedules a debounced, gated refresh attempt.
 *
 * ### Gating (re-checked from the DB each attempt, so it survives restarts without drift)
 * A refresh calls the AI only when:
 *  - [io.askimo.core.config.SuggestionsConfig.aiExtractionEnabled] is true (opt-in — off by
 *    default, since this is the only path that sends chat content to the active AI provider),
 *    **and**
 *  - at least [cooldown] has elapsed since the previous attempt, **and**
 *  - **either** [minMessagesBetweenRefresh] new user messages or [minRefreshInterval] of time
 *    has passed since the last successful refresh, **and**
 *  - the user has sent at least [MIN_TOTAL_MESSAGES_FOR_COLD_START] messages overall.
 *
 * ### Privacy backstop
 * Every extracted phrase is validated — not just trusted — via [looksLikeRawContent] before
 * persistence, rejecting anything that still looks like unstripped raw message content (e.g.
 * from a model that failed to generalize, or a prompt-injection attempt in a user message).
 */
class PhraseHabitRefreshService(
    private val messageRepository: ChatMessageRepository,
    private val phraseRepository: UserPhraseSuggestionRepository,
    private val suggestionCache: PersistedPhraseSuggestionService,
    private val userProfileRepository: UserProfileRepository,
    private val appContext: AppContext,
    private val minRefreshInterval: Duration = 6.hours,
    private val minMessagesBetweenRefresh: Long = 20,
    private val cooldown: Duration = 15.minutes,
    /** How long a burst of [UserMessageAddedEvent]s is debounced into a single refresh attempt. */
    private val debounceDelay: Duration = 2.seconds,
) {
    companion object {
        /** How many of the most recent user messages to feed the AI per refresh cycle. */
        private const val DELTA_SAMPLE_LIMIT = 30
        private const val MAX_HABITS_PER_CALL = 5
        private const val MIN_TOTAL_MESSAGES_FOR_COLD_START = 5

        /**
         * Minimum word count before [looksLikeRawContent] applies the verbatim-overlap check —
         * short generic boilerplate (e.g. "Thanks!") is always allowed, even if it happens to
         * match a source message.
         */
        private const val MIN_SUSPICIOUS_WORDS = 6

        /** See [looksLikeRawContent]. */
        private const val VERBATIM_COVERAGE_THRESHOLD = 0.7

        /** Digits, emails, URLs, file paths — specifics the prompt asks the model to strip. */
        private val SPECIFIC_CONTENT_PATTERN = Regex(
            """\d|@[\w.-]+\.[a-z]{2,}|https?://|[/\\][\w.-]+[/\\]""",
            RegexOption.IGNORE_CASE,
        )
    }

    private val log = logger<PhraseHabitRefreshService>()
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()

    @Volatile
    private var pendingRefreshJob: Job? = null

    @Volatile
    private var lastAttemptAt: Instant? = null

    /** Set once the user clears [MIN_TOTAL_MESSAGES_FOR_COLD_START] — skips the COUNT(*) query
     *  on every later attempt. */
    @Volatile
    private var coldStartPassed = false

    @Serializable
    private data class HabitEntry(val key: String, val phrase: String)

    @Serializable
    private data class HabitList(val habits: List<HabitEntry> = emptyList())

    /**
     * Subscribes to [UserMessageAddedEvent] and attempts a debounced, gated refresh for
     * [profileId] on each burst of new user messages. Call once, e.g. after profile load.
     */
    fun start(profileId: String) {
        suggestionCache.refreshCache(profileId)
        scope.launch {
            EventBus.internalEvents
                .filterIsInstance<UserMessageAddedEvent>()
                .collect {
                    pendingRefreshJob?.cancel()
                    pendingRefreshJob = scope.launch {
                        delay(debounceDelay) // debounce a tight burst of events into one attempt
                        attemptRefresh(profileId)
                    }
                }
        }
    }

    /**
     * Cancels the [EventBus] subscription started by [start] and any pending debounced job.
     * App-lifetime singletons in production never need this, but tests that create a fresh
     * instance per test case should call it (e.g. in `@AfterEach`) to stop the background
     * collector — otherwise it keeps listening on the process-wide [EventBus] indefinitely.
     */
    fun close() {
        scope.cancel()
    }

    /**
     * Clears all persisted phrase suggestions for [profileId], coordinated with any in-flight
     * or pending refresh so a worker's merge can't race the clear and leave suggestions present
     * immediately after a reported reset.
     *
     * Cancels the debounced [pendingRefreshJob] first, then suspends on [refreshMutex] — if
     * [attemptRefresh] is already running (e.g. mid AI call), this waits for it to finish
     * merging and refreshing the cache, *then* clears and refreshes again, guaranteeing the
     * clear is the last writer. Safe to call from a UI coroutine.
     */
    suspend fun resetSuggestions(profileId: String) {
        pendingRefreshJob?.cancel()
        refreshMutex.withLock {
            phraseRepository.clear(profileId)
            suggestionCache.refreshCache(profileId)
        }
    }

    private fun attemptRefresh(profileId: String) {
        val now = Clock.System.now()
        // Cheap in-memory cooldown check first — skip the mutex/DB entirely for bursts.
        lastAttemptAt?.let { last ->
            if (now - last < cooldown) return
        }

        // Skip if a refresh is already in flight — it reflects the latest messages, so
        // queuing up to re-run the same gate checks would be wasted work.
        if (!refreshMutex.tryLock()) return
        try {
            if (!AppConfig.suggestions.aiExtractionEnabled) return
            lastAttemptAt = now

            if (!coldStartPassed) {
                if (messageRepository.countByRole(MessageRole.USER) < MIN_TOTAL_MESSAGES_FOR_COLD_START) {
                    return
                }
                coldStartPassed = true
            }

            val lastRefresh = runCatching { phraseRepository.lastRefreshedAt(profileId) }.getOrNull()

            if (lastRefresh != null) {
                val elapsed = now - lastRefresh
                val newUserMessages = runCatching {
                    messageRepository.countUserMessagesSince(lastRefresh)
                }.getOrDefault(0)

                val countGatePassed = newUserMessages >= minMessagesBetweenRefresh
                val timeGatePassed = elapsed >= minRefreshInterval
                if (!countGatePassed && !timeGatePassed) return
            }

            val messages = runCatching {
                messageRepository.getRecentUserMessagesGlobal(limit = DELTA_SAMPLE_LIMIT)
                    .map { it.content }
                    .filter { it.isNotBlank() }
            }.getOrDefault(emptyList())

            if (messages.isEmpty()) return

            val habits = runCatching { extractHabits(profileId, messages) }
                .onFailure { e -> log.warn("Phrase habit extraction failed for profile {}: {}", profileId, e.message) }
                .getOrNull() ?: return

            // Advance the gate checkpoint on any successful attempt — including one that
            // legitimately found no recurring habits — so the cooldown/count gate isn't
            // bypassed on the very next message (it's independent of suggestion rows, which
            // only change when a habit is actually merged).
            phraseRepository.recordRefreshCheckpoint(profileId, now)

            val safeHabits = habits.filterNot { looksLikeRawContent(it.phrase, messages) }
            if (safeHabits.size != habits.size) {
                log.warn(
                    "Dropped {} of {} extracted habit(s) for profile {} — looked like unstripped raw message content",
                    habits.size - safeHabits.size,
                    habits.size,
                    profileId,
                )
            }
            if (safeHabits.isEmpty()) return

            safeHabits.forEach { habit ->
                phraseRepository.mergeHabit(profileId, habit.key, habit.phrase)
            }
            suggestionCache.refreshCache(profileId)
            log.debug("Phrase habit refresh merged {} habit(s) for profile {}", safeHabits.size, profileId)
        } finally {
            refreshMutex.unlock()
        }
    }

    /**
     * Rejects a [phrase] that looks like raw message content rather than a generalized habit —
     * either it contains digits/emails/URLs/paths (specifics the prompt asks to strip), or it's
     * a long (>= [MIN_SUSPICIOUS_WORDS] words) near-verbatim excerpt of one of [sourceMessages].
     * Short generic phrases are allowed through even if they match a source message verbatim —
     * that's the expected output for boilerplate openers/closers, not a privacy leak.
     */
    private fun looksLikeRawContent(phrase: String, sourceMessages: List<String>): Boolean {
        if (SPECIFIC_CONTENT_PATTERN.containsMatchIn(phrase)) return true

        val wordCount = phrase.trim().split(Regex("\\s+")).size
        if (wordCount < MIN_SUSPICIOUS_WORDS) return false

        val normalizedPhrase = phrase.trim().lowercase().replace(Regex("\\s+"), " ")
        return sourceMessages.any { message ->
            val normalizedMessage = message.trim().lowercase().replace(Regex("\\s+"), " ")
            normalizedMessage.contains(normalizedPhrase) &&
                normalizedPhrase.length >= normalizedMessage.length * VERBATIM_COVERAGE_THRESHOLD
        }
    }

    private fun extractHabits(profileId: String, messages: List<String>): List<HabitEntry> {
        val client = appContext.createUtilityClient()
        val existingCategories = phraseRepository.getRepresentativePhrasesByCategory(profileId)
        val personalization = runCatching { userProfileRepository.getPersonalizationContext() }.getOrNull()
        val prompt = buildExtractionPrompt(personalization, existingCategories, messages)

        val raw = client.sendMessage(prompt)
        val cleaned = cleanJsonResponse(
            raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim(),
        )

        return json.decodeFromString<HabitList>(cleaned).habits
            .map { HabitEntry(key = it.key.trim(), phrase = it.phrase.trim()) }
            .filter { it.key.isNotBlank() && it.phrase.isNotBlank() }
            .take(MAX_HABITS_PER_CALL)
    }

    private fun buildExtractionPrompt(
        personalization: String?,
        existingCategories: Map<String, String>,
        messages: List<String>,
    ): String {
        val existingBlock = if (existingCategories.isEmpty()) {
            "(none yet)"
        } else {
            existingCategories.entries.joinToString("\n") { (key, phrase) -> "- $key: \"$phrase\"" }
        }
        val messagesBlock = messages.joinToString("\n") { "- $it" }
        val personalizationBlock = personalization?.let {
            "\nUSER CONTEXT (use this to correctly interpret domain-specific phrasing — do not\n" +
                "copy it into any habit phrase):\n$it\n"
        } ?: ""

        return """
            You are analyzing a chat user's own recent messages to detect recurring typing
            habits — phrases, sentence openers, or closers they tend to reuse — so they can be
            offered as autocomplete suggestions in the future.
            $personalizationBlock
            EXISTING HABIT CATEGORIES (reuse a key below if a message matches one of these;
            only invent a new key if the pattern is genuinely new):
            $existingBlock

            RECENT USER MESSAGES (most recent first):
            $messagesBlock

            RULES:
            - Reuse an existing category key whenever a message matches it, and return a
              refreshed/representative phrase for it.
            - Only create a NEW category key if the same new pattern recurs at least twice in
              the messages above — a single one-off message is not enough evidence.
            - Generalize phrases: strip specific names, numbers, file paths, project names —
              keep the reusable skeleton of the sentence only.
            - Keys must be short, stable, snake_case identifiers (e.g. "closing_courtesy",
              "status_check_opener").
            - Return at most $MAX_HABITS_PER_CALL habits, most notable first.
            - If nothing recurring is found, return an empty list.

            Respond with ONLY a JSON object of this exact shape, nothing else, no markdown fences:
            {"habits": [{"key": "...", "phrase": "..."}]}
        """.trimIndent()
    }
}
