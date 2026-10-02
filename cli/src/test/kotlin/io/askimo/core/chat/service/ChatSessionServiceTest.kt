/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.service

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.repository.ChatMessageRepository
import io.askimo.core.chat.repository.ChatSessionRepository
import io.askimo.core.chat.repository.ProjectRepository
import io.askimo.core.chat.repository.SessionMemoryRepository
import io.askimo.core.context.AppContext
import io.askimo.core.context.AppContextParams
import io.askimo.core.db.DatabaseManager
import io.askimo.core.memory.TokenAwareSummarizingMemory
import io.askimo.core.providers.ChatClient
import io.askimo.core.providers.ModelProvider
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.after
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Path

/**
 * Regression coverage for [ChatSessionService]'s memory-cache eviction → background
 * summarization wiring. cache4k's `CacheEventListener` fires a distinct `CacheEvent`
 * per removal cause — `Removed` (explicit invalidation), `Expired` (TTL), `Evicted`
 * (size-based LRU) — and each one must still trigger summarization when the evicted
 * memory has unsaved changes.
 *
 * These tests drive the real `memoryCache` end-to-end through [ChatSessionService]'s public
 * API only (no `internal` access — the service lives in the `shared` module, these tests in
 * `cli`), and observe the outcome via the mocked secondary chat model: if summarization ran,
 * [ChatModel.chat] gets called.
 *
 * TTL-based expiry (`CacheEvent.Expired`) isn't covered end-to-end here since the cache's
 * 30-minute `expireAfterAccess` isn't practical to wait out (or fake-clock-drive) from outside
 * the `shared` module; cache4k's own test suite covers the TTL mechanics themselves, and
 * explicit-removal + size-eviction below exercise the exact same downstream handler.
 */
class ChatSessionServiceTest {

    private lateinit var mockAppContext: AppContext
    private lateinit var mockSecondaryModel: ChatModel
    private lateinit var mockChatClient: ChatClient
    private lateinit var mockParams: AppContextParams
    private lateinit var service: ChatSessionService

    /**
     * Every [TokenAwareSummarizingMemory] obtained via [trackedMemory] in the current test —
     * including evicted/filler ones. [tearDown] closes each one (draining pending
     * summarization + its DB persistence, and shutting down its executor) before deleting
     * fixture sessions, so a background write can't race `deleteAll()` or the class-level
     * `databaseManager.close()`.
     */
    private val createdMemories = mutableListOf<TokenAwareSummarizingMemory>()

    @BeforeEach
    fun setUp() {
        mockAppContext = mock()
        mockSecondaryModel = mock()
        mockChatClient = mock()
        mockParams = mock()

        whenever(mockAppContext.createUtilityClient()).thenReturn(mockChatClient)
        whenever(mockAppContext.createSecondaryModel()).thenReturn(mockSecondaryModel)
        whenever(mockAppContext.getActiveProvider()).thenReturn(ModelProvider.OPENAI)
        whenever(mockAppContext.params).thenReturn(mockParams)
        whenever(mockParams.model).thenReturn("gpt-4")
        whenever(mockAppContext.buildUserMemoryPrefix()).thenReturn("")
        whenever(mockSecondaryModel.chat(any<ChatRequest>())).thenReturn(
            ChatResponse.builder()
                .aiMessage(AiMessage.from("""{"keyFacts":{},"mainTopics":[],"recentContext":""}"""))
                .build(),
        )

        service = ChatSessionService(
            sessionRepository = sessionRepository,
            messageRepository = messageRepository,
            sessionMemoryRepository = sessionMemoryRepository,
            projectRepository = projectRepository,
            appContext = mockAppContext,
        )
    }

    @AfterEach
    fun tearDown() {
        createdMemories.forEach { it.close() }
        createdMemories.clear()
        sessionRepository.deleteAll()
    }

    companion object {
        private lateinit var testBaseScope: AskimoHome.TestBaseScope
        private lateinit var databaseManager: DatabaseManager
        private lateinit var sessionRepository: ChatSessionRepository
        private lateinit var messageRepository: ChatMessageRepository
        private lateinit var sessionMemoryRepository: SessionMemoryRepository
        private lateinit var projectRepository: ProjectRepository

        @JvmStatic
        @BeforeAll
        fun setUpClass(@TempDir tempDir: Path) {
            testBaseScope = AskimoHome.withTestBase(tempDir)
            databaseManager = DatabaseManager.getInMemoryTestInstance(this)

            sessionRepository = databaseManager.getChatSessionRepository()
            messageRepository = databaseManager.getChatMessageRepository()
            sessionMemoryRepository = databaseManager.getSessionMemoryRepository()
            projectRepository = databaseManager.getProjectRepository()
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            if (::databaseManager.isInitialized) {
                databaseManager.close()
            }
            DatabaseManager.reset()
            if (::testBaseScope.isInitialized) {
                testBaseScope.close()
            }
        }
    }

    // ── Explicit removal (CacheEvent.Removed) ───────────────────────────────────

    @Test
    fun `deleteSession explicitly invalidates memory cache and triggers summarization when memory has new messages`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Explicit removal - dirty"))
        val memory = trackedMemory(session.id)
        // More than AppConfig.memory.protectedRecentTurns (default 6) non-protected messages,
        // otherwise summarizeAndPruneWithParams() has nothing eligible to summarize and
        // returns early without ever calling the model.
        addDirtyMessages(memory)
        assertTrue(memory.hasNewMessagesSinceLastSummary())

        service.deleteSession(session.id)

        verify(mockSecondaryModel, timeout(5_000)).chat(any<ChatRequest>())
    }

    @Test
    fun `deleteSession explicitly invalidates memory cache but skips summarization when memory is unchanged`() {
        val session = sessionRepository.createSession(ChatSession(id = "", title = "Explicit removal - clean"))
        val memory = trackedMemory(session.id)
        assertFalse(memory.hasNewMessagesSinceLastSummary())

        service.deleteSession(session.id)

        verify(mockSecondaryModel, after(1_000).never()).chat(any<ChatRequest>())
    }

    // ── Size-based LRU eviction (CacheEvent.Evicted) ────────────────────────────

    @Test
    fun `size-based memory cache eviction triggers summarization for the evicted session`() {
        val evictedSession = sessionRepository.createSession(ChatSession(id = "", title = "Evicted by LRU - dirty"))
        val evictedMemory = trackedMemory(evictedSession.id)
        addDirtyMessages(evictedMemory)

        // memoryCache.maximumCacheSize == 10 — creating 10 more distinct sessions' shared
        // memories pushes the least-recently-accessed entry (evictedSession's) out.
        repeat(10) { i ->
            val filler = sessionRepository.createSession(ChatSession(id = "", title = "Filler $i"))
            trackedMemory(filler.id)
        }

        verify(mockSecondaryModel, timeout(5_000)).chat(any<ChatRequest>())
    }

    @Test
    fun `size-based memory cache eviction skips summarization when evicted session is unchanged`() {
        val evictedSession = sessionRepository.createSession(ChatSession(id = "", title = "Evicted by LRU - clean"))
        trackedMemory(evictedSession.id)

        repeat(10) { i ->
            val filler = sessionRepository.createSession(ChatSession(id = "", title = "Filler clean $i"))
            trackedMemory(filler.id)
        }

        verify(mockSecondaryModel, after(1_000).never()).chat(any<ChatRequest>())
    }

    /**
     * [ChatSessionService.getOrCreateMemoryForSession] that also tracks the returned memory
     * in [createdMemories] so [tearDown] can close it before fixture cleanup.
     */
    private fun trackedMemory(sessionId: String): TokenAwareSummarizingMemory = service.getOrCreateMemoryForSession(sessionId).also { createdMemories.add(it) }

    /**
     * Adds enough non-protected messages so a triggered summarization cycle has candidates
     * to actually summarize (default `AppConfig.memory.protectedRecentTurns` == 6 — anything
     * at or below that is fully "protected" and `summarizeAndPruneWithParams()` returns early
     * without calling the model).
     */
    private fun addDirtyMessages(memory: TokenAwareSummarizingMemory) {
        repeat(8) { i -> memory.add(UserMessage.from("Message $i that should eventually be summarized")) }
    }
}
