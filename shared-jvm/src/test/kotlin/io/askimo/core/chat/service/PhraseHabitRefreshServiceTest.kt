/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.service

import io.askimo.core.chat.domain.ChatMessage
import io.askimo.core.chat.repository.ChatMessageRepository
import io.askimo.core.chat.repository.UserPhraseSuggestionRepository
import io.askimo.core.config.AppConfig
import io.askimo.core.context.AppContext
import io.askimo.core.context.MessageRole
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.UserMessageAddedEvent
import io.askimo.core.providers.ChatClient
import io.askimo.core.user.repository.UserProfileRepository
import io.askimo.test.extensions.AskimoTestHome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Regression coverage for [PhraseHabitRefreshService]'s event/debounce wiring, cold-start and
 * refresh gates, checkpoint bookkeeping, extraction-failure handling, and — most importantly —
 * [PhraseHabitRefreshService] private `looksLikeRawContent` as the privacy backstop before
 * persistence (verified indirectly via which phrases do/don't reach [UserPhraseSuggestionRepository.mergeHabit]).
 *
 * [debounce] is injected as a few milliseconds in every test so debounce/gate assertions don't
 * require waiting out the real 2-second production default ([PhraseHabitRefreshService]'s
 * `debounceDelay` constructor param). [timeout] (org.mockito.kotlin) polls for the async
 * (event-driven) outcome instead of a fixed sleep for positive assertions; negative assertions
 * (`never()`) still need a bounded sleep since there's nothing to poll for.
 *
 * [startAndSettle] is used instead of calling [PhraseHabitRefreshService.start] directly: `start`
 * subscribes to [EventBus.internalEvents] from a newly-launched coroutine, so without a brief
 * settle delay there's a race where a test's [postUserMessage] call can emit *before* that
 * subscription is actually registered — [EventBus]'s underlying `SharedFlow` has `replay = 0`,
 * so such an event is silently dropped rather than buffered for the late subscriber.
 */
@AskimoTestHome
class PhraseHabitRefreshServiceTest {

    private lateinit var messageRepository: ChatMessageRepository
    private lateinit var phraseRepository: UserPhraseSuggestionRepository
    private lateinit var suggestionCache: PersistedPhraseSuggestionService
    private lateinit var userProfileRepository: UserProfileRepository
    private lateinit var appContext: AppContext
    private lateinit var chatClient: ChatClient

    private val profileId = "test-profile"

    /** Generous poll timeout for [timeout] — well above [debounce] + processing time. The
     *  companion [warmUp] absorbs one-time JIT/dispatcher/Mockito startup jitter up front, so
     *  this doesn't need to be inflated to cover whichever test method JUnit runs first. */
    private val verifyTimeoutMs = 2_000L
    private val debounce = 30.milliseconds

    /**
     * Every [PhraseHabitRefreshService] created via [newService] in the current test — closed
     * in [tearDown] so its [EventBus] subscription doesn't leak into later tests (which would
     * otherwise keep listening indefinitely and contend with subsequent tests' assertions).
     */
    private val createdServices = mutableListOf<PhraseHabitRefreshService>()

    private fun newService(
        minRefreshInterval: Duration = 6.hours,
        minMessagesBetweenRefresh: Long = 20,
        cooldown: Duration = 15.minutes,
    ) = PhraseHabitRefreshService(
        messageRepository = messageRepository,
        phraseRepository = phraseRepository,
        suggestionCache = suggestionCache,
        userProfileRepository = userProfileRepository,
        appContext = appContext,
        minRefreshInterval = minRefreshInterval,
        minMessagesBetweenRefresh = minMessagesBetweenRefresh,
        cooldown = cooldown,
        debounceDelay = debounce,
    ).also { createdServices.add(it) }

    /** See class doc — starts [service] and waits for its [EventBus] subscription to attach. */
    private fun startAndSettle(service: PhraseHabitRefreshService) {
        service.start(profileId)
        Thread.sleep(150)
    }

    private fun fakeUserMessage(content: String) = ChatMessage(
        id = "msg-${content.hashCode()}",
        sessionId = "session-1",
        role = MessageRole.USER,
        content = content,
        createdAt = Clock.System.now(),
    )

    @BeforeEach
    fun setUp() {
        messageRepository = mock()
        phraseRepository = mock()
        suggestionCache = mock()
        userProfileRepository = mock()
        appContext = mock()
        chatClient = mock()

        whenever(appContext.createUtilityClient()).thenReturn(chatClient)
        whenever(userProfileRepository.getPersonalizationContext()).thenReturn(null)
        whenever(phraseRepository.getRepresentativePhrasesByCategory(any())).thenReturn(emptyMap())
        whenever(phraseRepository.lastRefreshedAt(any())).thenReturn(null)
        whenever(messageRepository.countByRole(MessageRole.USER)).thenReturn(10)
        whenever(messageRepository.getRecentUserMessagesGlobal(any())).thenReturn(
            listOf(fakeUserMessage("hey can you help me debug this")),
        )
        whenever(chatClient.sendMessage(any())).thenReturn("""{"habits": []}""")

        AppConfig.updateField("suggestions.aiExtractionEnabled", true)
    }

    @AfterEach
    fun tearDown() {
        createdServices.forEach { it.close() }
        createdServices.clear()
    }

    private fun postUserMessage() = EventBus.post(UserMessageAddedEvent(sessionId = "session-1", content = "hi"))

    // ---------------------------------------------------------------------
    // Event / debounce behavior
    // ---------------------------------------------------------------------

    @Test
    fun `a burst of events collapses into a single refresh attempt`() {
        val service = newService()
        startAndSettle(service)

        // Fire 5 events in a tight burst — each should cancel the previous pending job.
        repeat(5) {
            postUserMessage()
            Thread.sleep(5)
        }

        verify(chatClient, timeout(verifyTimeoutMs)).sendMessage(any())
        // Give any extra (incorrectly-scheduled) attempts a chance to fire, then confirm only one did.
        Thread.sleep(200)
        verify(chatClient, times(1)).sendMessage(any())
    }

    @Test
    fun `no events means no refresh attempt`() {
        val service = newService()
        startAndSettle(service)

        Thread.sleep(verifyTimeoutMs)
        verify(chatClient, never()).sendMessage(any())
    }

    // ---------------------------------------------------------------------
    // Cold-start gate
    // ---------------------------------------------------------------------

    @Test
    fun `cold start gate blocks refresh below minimum total messages`() {
        whenever(messageRepository.countByRole(MessageRole.USER)).thenReturn(1)
        val service = newService()
        startAndSettle(service)

        postUserMessage()
        Thread.sleep(verifyTimeoutMs)

        verify(chatClient, never()).sendMessage(any())
    }

    @Test
    fun `cold start gate allows refresh at minimum total messages`() {
        whenever(messageRepository.countByRole(MessageRole.USER)).thenReturn(5)
        val service = newService()
        startAndSettle(service)

        postUserMessage()

        verify(chatClient, timeout(verifyTimeoutMs)).sendMessage(any())
    }

    // ---------------------------------------------------------------------
    // Refresh gate (count/time since last successful refresh)
    // ---------------------------------------------------------------------

    @Test
    fun `refresh gate blocks when neither message count nor interval threshold is met`() {
        whenever(phraseRepository.lastRefreshedAt(any())).thenReturn(Clock.System.now())
        whenever(messageRepository.countUserMessagesSince(any())).thenReturn(1)
        val service = newService(minRefreshInterval = 6.hours, minMessagesBetweenRefresh = 20)
        startAndSettle(service)

        postUserMessage()
        Thread.sleep(verifyTimeoutMs)

        verify(chatClient, never()).sendMessage(any())
    }

    @Test
    fun `refresh gate allows when message count threshold is met`() {
        whenever(phraseRepository.lastRefreshedAt(any())).thenReturn(Clock.System.now())
        whenever(messageRepository.countUserMessagesSince(any())).thenReturn(25)
        val service = newService(minRefreshInterval = 6.hours, minMessagesBetweenRefresh = 20)
        startAndSettle(service)

        postUserMessage()

        verify(chatClient, timeout(verifyTimeoutMs)).sendMessage(any())
    }

    // ---------------------------------------------------------------------
    // In-memory cooldown between attempts
    // ---------------------------------------------------------------------

    @Test
    fun `cooldown blocks a second attempt fired shortly after the first`() {
        val service = newService(cooldown = 500.milliseconds)
        startAndSettle(service)

        postUserMessage()
        verify(chatClient, timeout(verifyTimeoutMs)).sendMessage(any())

        // Second burst arrives well inside the cooldown window.
        postUserMessage()
        Thread.sleep(300)
        verify(chatClient, times(1)).sendMessage(any())
    }

    // ---------------------------------------------------------------------
    // Checkpoint updates
    // ---------------------------------------------------------------------

    @Test
    fun `checkpoint is recorded on a successful attempt even with no habits found`() {
        whenever(chatClient.sendMessage(any())).thenReturn("""{"habits": []}""")
        val service = newService()
        startAndSettle(service)

        postUserMessage()

        verify(phraseRepository, timeout(verifyTimeoutMs)).recordRefreshCheckpoint(eq(profileId), any())
        verify(phraseRepository, never()).mergeHabit(any(), any(), any())
    }

    @Test
    fun `checkpoint is not recorded when extraction fails`() {
        whenever(chatClient.sendMessage(any())).thenThrow(RuntimeException("provider unavailable"))
        val service = newService()
        startAndSettle(service)

        postUserMessage()
        // Give the (failed) attempt time to run.
        Thread.sleep(verifyTimeoutMs)

        verify(phraseRepository, never()).recordRefreshCheckpoint(any(), any())
        verify(phraseRepository, never()).mergeHabit(any(), any(), any())
    }

    // ---------------------------------------------------------------------
    // Extraction failure doesn't crash the debounce pipeline
    // ---------------------------------------------------------------------

    @Test
    fun `extraction failure does not refresh the suggestion cache`() {
        whenever(chatClient.sendMessage(any())).thenThrow(RuntimeException("boom"))
        val service = newService()
        startAndSettle(service)

        postUserMessage()
        Thread.sleep(verifyTimeoutMs)

        // refreshCache is called once up-front in start(); it must not be called again after a failure.
        verify(suggestionCache, times(1)).refreshCache(profileId)
    }

    // ---------------------------------------------------------------------
    // Privacy backstop: raw-content filtering before persistence
    // ---------------------------------------------------------------------

    @Test
    fun `short verbatim-matching phrase is persisted, not treated as a privacy leak`() {
        whenever(messageRepository.getRecentUserMessagesGlobal(any())).thenReturn(
            listOf(fakeUserMessage("Thanks!")),
        )
        whenever(chatClient.sendMessage(any())).thenReturn(
            """{"habits": [{"key": "closing_courtesy", "phrase": "Thanks!"}]}""",
        )
        val service = newService()
        startAndSettle(service)

        postUserMessage()

        verify(phraseRepository, timeout(verifyTimeoutMs)).mergeHabit(profileId, "closing_courtesy", "Thanks!")
    }

    @Test
    fun `long near-verbatim excerpt of a source message is dropped`() {
        val sourceMessage = "can you please help me understand why this test keeps failing on CI"
        whenever(messageRepository.getRecentUserMessagesGlobal(any())).thenReturn(
            listOf(fakeUserMessage(sourceMessage)),
        )
        // >= 6 words and near-verbatim (>=70% length coverage) of the source message — must be dropped.
        val rawPhrase = "can you please help me understand why this test keeps failing"
        whenever(chatClient.sendMessage(any())).thenReturn(
            """{"habits": [{"key": "debug_opener", "phrase": "$rawPhrase"}]}""",
        )
        val service = newService()
        startAndSettle(service)

        postUserMessage()

        verify(phraseRepository, timeout(verifyTimeoutMs)).recordRefreshCheckpoint(eq(profileId), any())
        verify(phraseRepository, never()).mergeHabit(any(), any(), any())
    }

    @Test
    fun `phrase containing specific content (email) is dropped regardless of length`() {
        whenever(messageRepository.getRecentUserMessagesGlobal(any())).thenReturn(
            listOf(fakeUserMessage("reach me at jane.doe@example.com if urgent")),
        )
        whenever(chatClient.sendMessage(any())).thenReturn(
            """{"habits": [{"key": "contact_info", "phrase": "reach me at jane.doe@example.com"}]}""",
        )
        val service = newService()
        startAndSettle(service)

        postUserMessage()

        verify(phraseRepository, timeout(verifyTimeoutMs)).recordRefreshCheckpoint(eq(profileId), any())
        verify(phraseRepository, never()).mergeHabit(any(), any(), any())
    }

    @Test
    fun `phrase containing a file path is dropped regardless of length`() {
        whenever(messageRepository.getRecentUserMessagesGlobal(any())).thenReturn(
            listOf(fakeUserMessage("look at /Users/jane/project/src/Main.kt please")),
        )
        whenever(chatClient.sendMessage(any())).thenReturn(
            """{"habits": [{"key": "file_ref", "phrase": "look at /Users/jane/project/src/Main.kt"}]}""",
        )
        val service = newService()
        startAndSettle(service)

        postUserMessage()

        verify(phraseRepository, timeout(verifyTimeoutMs)).recordRefreshCheckpoint(eq(profileId), any())
        verify(phraseRepository, never()).mergeHabit(any(), any(), any())
    }

    @Test
    fun `mixed batch persists only the safe habit and drops the unsafe one`() {
        whenever(messageRepository.getRecentUserMessagesGlobal(any())).thenReturn(
            listOf(
                fakeUserMessage("Thanks!"),
                fakeUserMessage("my number is 555-123-4567 call anytime"),
            ),
        )
        whenever(chatClient.sendMessage(any())).thenReturn(
            """
            {"habits": [
                {"key": "closing_courtesy", "phrase": "Thanks!"},
                {"key": "contact_info", "phrase": "my number is 555-123-4567"}
            ]}
            """.trimIndent(),
        )
        val service = newService()
        startAndSettle(service)

        postUserMessage()

        verify(phraseRepository, timeout(verifyTimeoutMs)).mergeHabit(profileId, "closing_courtesy", "Thanks!")
        verify(phraseRepository, never()).mergeHabit(eq(profileId), eq("contact_info"), any())
    }
}
