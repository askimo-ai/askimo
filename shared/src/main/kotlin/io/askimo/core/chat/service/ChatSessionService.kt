/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.service

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import dev.langchain4j.data.message.Content
import dev.langchain4j.rag.content.retriever.ContentRetriever
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever
import io.askimo.core.chat.domain.ChatMessage
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.domain.Project
import io.askimo.core.chat.domain.SESSION_TITLE_MAX_LENGTH
import io.askimo.core.chat.dto.ChatMessageDTO
import io.askimo.core.chat.dto.FileAttachmentDTO
import io.askimo.core.chat.dto.TurnTimelineEntry
import io.askimo.core.chat.mapper.ChatMessageMapper.toDTO
import io.askimo.core.chat.mapper.ChatMessageMapper.toDTOs
import io.askimo.core.chat.mapper.ChatMessageMapper.toDomain
import io.askimo.core.chat.repository.ChatMessageRepository
import io.askimo.core.chat.repository.ChatSessionRepository
import io.askimo.core.chat.repository.PaginationDirection
import io.askimo.core.chat.repository.ProjectRepository
import io.askimo.core.chat.repository.ResourceCollectionRepository
import io.askimo.core.chat.repository.SessionMemoryRepository
import io.askimo.core.chat.util.AttachmentStorageManager
import io.askimo.core.chat.util.FileContentExtractor
import io.askimo.core.chat.util.FileSizeExceededException
import io.askimo.core.config.AppConfig
import io.askimo.core.context.AppContext
import io.askimo.core.context.MessageRole
import io.askimo.core.db.DatabaseManager
import io.askimo.core.db.Pageable
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.ModelChangedEvent
import io.askimo.core.event.internal.PushDataToServerEvent
import io.askimo.core.event.internal.ReasoningEffortChangedEvent
import io.askimo.core.event.internal.SessionCreatedEvent
import io.askimo.core.event.internal.SessionDeletedEvent
import io.askimo.core.event.internal.SessionTitleUpdatedEvent
import io.askimo.core.logging.logger
import io.askimo.core.memory.MemoryMessage
import io.askimo.core.memory.TokenAwareSummarizingMemory
import io.askimo.core.providers.ChatClient
import io.askimo.core.providers.ModelProvider
import io.askimo.core.rag.RagUtils
import io.askimo.core.rag.container.IndexingContainer
import io.askimo.core.rag.container.asIndexingContainer
import io.askimo.core.util.formatFileSize
import io.askimo.core.vision.toUserMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.SortOrder
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * Data class to hold both ChatClient and its associated memory for a session.
 * This allows us to access and update memory directly when needed.
 */
data class SessionChatContext(
    val chatClient: ChatClient,
    val memory: TokenAwareSummarizingMemory,
)

/**
 * A single conversation's worth of bookmarked messages, used by the global Bookmarks view.
 */
data class BookmarkGroup(
    val session: ChatSession,
    val messages: List<ChatMessageDTO>,
)

/**
 * Result of resuming a chat session.
 */
data class ResumeSessionResult(
    val success: Boolean,
    val sessionId: String,
    val messages: List<ChatMessageDTO> = emptyList(),
    val errorMessage: String? = null,
)

/**
 * Result of resuming a chat session with pagination.
 */
data class ResumeSessionPaginatedResult(
    val success: Boolean,
    val sessionId: String,
    val title: String? = null,
    val directiveId: String?,
    val project: Project? = null,
    val messages: List<ChatMessageDTO> = emptyList(),
    val cursor: Instant? = null,
    val hasMore: Boolean = false,
    val errorMessage: String? = null,
    /** Persistent user-selected resource collections for this session (chip state). */
    val activeResourceCollectionIds: List<String> = emptyList(),
)

/**
 * Service for managing chat sessions with common logic shared between CLI and desktop.
 *
 * This service coordinates between multiple repositories to provide high-level
 * operations for chat session management, following the proper layered architecture.
 *
 * @param sessionRepository The chat session repository
 * @param messageRepository The chat message repository
 * @param sessionMemoryRepository The session memory repository
 * @param projectRepository The project repository
 * @param appContext The application context
 */
class ChatSessionService(
    private val sessionRepository: ChatSessionRepository = DatabaseManager.getInstance().getChatSessionRepository(),
    private val messageRepository: ChatMessageRepository = DatabaseManager.getInstance().getChatMessageRepository(),
    private val sessionMemoryRepository: SessionMemoryRepository = DatabaseManager.getInstance().getSessionMemoryRepository(),
    private val projectRepository: ProjectRepository = DatabaseManager.getInstance().getProjectRepository(),
    private val resourceCollectionRepository: ResourceCollectionRepository = DatabaseManager.getInstance().getResourceCollectionRepository(),
    private val appContext: AppContext,
) {
    private val log = logger<ChatSessionService>()

    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Cache of session contexts (ChatClient + memory). Each session can have two
     * contexts (regular + vision), keyed as "sessionId" / "sessionId_vision".
     */
    private val sessionContextCache: Cache<String, SessionChatContext> = Caffeine.newBuilder()
        .maximumSize(20) // regular + vision clients
        .expireAfterAccess(30.minutes.toJavaDuration())
        .removalListener<String, SessionChatContext> { sessionId, context, cause ->
            if (context != null && sessionId != null) {
                log.debug("Evicting session context for session {} (cause: {})", sessionId, cause)
            }
        }
        .build()

    /**
     * Shared memory instances — regular and vision clients use the same memory
     * to keep conversation continuity.
     */
    private val memoryCache: Cache<String, TokenAwareSummarizingMemory> = Caffeine.newBuilder()
        .maximumSize(10)
        .expireAfterAccess(30.minutes.toJavaDuration())
        .removalListener<String, TokenAwareSummarizingMemory> { sessionId, memory, _ ->
            // Skip summarization if nothing changed since the last cycle (avoids a
            // wasteful AI call, e.g. user opened a session and left without sending).
            if (memory != null && sessionId != null) {
                if (memory.hasNewMessagesSinceLastSummary()) {
                    log.debug("Memory evicted for session {}, triggering background summarization", sessionId)
                    memory.triggerAsyncSummarization()
                } else {
                    log.debug("Memory evicted for session {}, no new messages since last summary — skipping", sessionId)
                }
            }
        }
        .build()

    /**
     * Per-session web-search-in-RAG toggle. Absent = false. Changing the value
     * invalidates the session context cache (memory cache is left untouched).
     */
    private val sessionWebSearchState = ConcurrentHashMap<String, Boolean>()

    /**
     * Enable/disable live web search in RAG for a session. Evicts the cached
     * [SessionChatContext] so the next send rebuilds the retriever; memory is preserved.
     *
     * @param sessionId The session to update.
     * @param enabled   true to include web results in RAG retrieval.
     */
    fun setWebSearchForSession(sessionId: String, enabled: Boolean) {
        val previous = sessionWebSearchState.put(sessionId, enabled)
        if (previous != enabled) {
            sessionContextCache.invalidate(sessionId)
            log.debug(
                "Web search in RAG {} for session {}, context cache invalidated",
                if (enabled) "enabled" else "disabled",
                sessionId,
            )
        }
    }

    init {
        eventScope.launch {
            EventBus.internalEvents
                .filterIsInstance<ModelChangedEvent>()
                .collect { event ->
                    handleModelChanged(event)
                }
        }
        eventScope.launch {
            EventBus.internalEvents
                .filterIsInstance<ReasoningEffortChangedEvent>()
                .collect { event ->
                    handleReasoningEffortChanged(event)
                }
        }
    }

    /**
     * Handle model change — clear all cached contexts since they use the old model.
     */
    private fun handleModelChanged(event: ModelChangedEvent) {
        log.info("Model changed to ${event.newModel} for provider ${event.provider}, clearing cached contexts")
        sessionContextCache.invalidateAll()
    }

    /**
     * Handle reasoning effort change — clear cached contexts so the next send
     * recreates the ChatClient with the updated reasoning level.
     */
    private fun handleReasoningEffortChanged(event: ReasoningEffortChangedEvent) {
        log.info("Reasoning effort changed to ${event.newEffort} for ${event.model} (${event.provider}), clearing cached contexts")
        sessionContextCache.invalidateAll()
    }

    /**
     * Get or create shared memory for a session, so regular and vision clients
     * share the same conversation history.
     */
    internal fun getOrCreateSharedMemory(sessionId: String): TokenAwareSummarizingMemory = memoryCache.get(sessionId) { _ ->
        TokenAwareSummarizingMemory(
            appContext,
            sessionId = sessionId,
            sessionMemoryRepository = sessionMemoryRepository,
            userMemoryRepository = DatabaseManager.getInstance().getUserMemoryRepository(),
            summarizationTimeoutSeconds = AppConfig.chat.summarizationTimeoutSeconds,
        )
    }

    /**
     * Get or create a chat context (client + memory) for a session. Regular and
     * vision clients share the same memory for conversation continuity.
     */
    private fun getOrCreateContextForSession(
        sessionId: String,
    ): SessionChatContext = sessionContextCache.get(sessionId) { _ ->
        val project = projectRepository.findProjectBySessionId(sessionId)

        // Reuse shared memory across regular and vision clients
        val sharedMemory = getOrCreateSharedMemory(sessionId)

        val useWebSearch = sessionWebSearchState[sessionId] ?: false

        // Resolve the single RAG container backing this session's retriever: a
        // project's sources take precedence; otherwise fall back to the session's
        // chip-selected Resource Collection (at most one is ever active). Only one
        // container is active at a time — combining both is a possible future enhancement.
        val container: IndexingContainer? = when {
            project != null -> project.asIndexingContainer()

            else -> {
                sessionRepository.getSession(sessionId)
                    ?.activeResourceCollectionIds
                    ?.firstOrNull()
                    ?.let { resourceCollectionRepository.getCollection(it) }
                    ?.asIndexingContainer()
            }
        }

        // Content retriever only if a container is active
        val retriever = container?.let {
            log.debug("Session {} using RAG container: {} {}, useWebSearch={}", sessionId, it.type, it.id, useWebSearch)
            createRetrieverForContainer(appContext.createUtilityClient(), it, useWebSearch)
        }

        val chatClient = appContext.createStatefulChatSession(
            sessionId = sessionId,
            retriever = retriever,
            memory = sharedMemory,
        )

        SessionChatContext(chatClient, sharedMemory)
    }

    /**
     * Get or create a ChatClient for a session (vision-capable if requested).
     * Regular and vision clients share the same conversation memory.
     */
    fun getOrCreateClientForSession(
        sessionId: String,
    ): ChatClient = getOrCreateContextForSession(sessionId).chatClient

    /**
     * Build a [ContentRetriever] for any RAG-indexed [IndexingContainer] (a [Project] or a
     * [io.askimo.core.chat.domain.ResourceCollection]). Same pipeline for both — hybrid
     * vector (JVector) + keyword (Lucene) search with RRF fusion, optionally augmented
     * with live web search. Only the container's id and knowledgeSources differ.
     */
    private fun createRetrieverForContainer(
        classifierChatClient: ChatClient,
        container: IndexingContainer,
        useWebSearch: Boolean = false,
    ): ContentRetriever? {
        try {
            val embeddingModel = appContext.getEmbeddingModel()

            val embeddingStore = RagUtils.getEmbeddingStore(container.id, embeddingModel)

            val ragConfig = AppConfig.rag

            val vectorRetriever = RagUtils.enrichContentRetrieverWithLucene(
                classifierChatClient,
                container.id,
                EmbeddingStoreContentRetriever.builder()
                    .embeddingStore(embeddingStore)
                    .embeddingModel(embeddingModel)
                    .maxResults(ragConfig.vectorSearchMaxResults)
                    .minScore(ragConfig.vectorSearchMinScore)
                    .build(),
                container.knowledgeSources.map { it.resourceIdentifier },
                useWebSearch = useWebSearch,
            )

            return vectorRetriever
        } catch (e: Exception) {
            log.error("Failed to create content retriever for ${container.type} ${container.id}", e)
            return null
        }
    }

    /**
     * Get all sessions sorted by most recently updated first.
     */
    fun getSessions(limit: Int): List<ChatSession> = sessionRepository.getSessions(limit)

    fun getSessionsWithoutProject(limit: Int, sortOrder: SortOrder = SortOrder.DESC): List<ChatSession> = sessionRepository.getSessionsWithoutProject(limit, sortOrder)

    /**
     * Count all sessions not belonging to any project.
     */
    fun countSessionsWithoutProject(): Int = sessionRepository.countSessionsWithoutProject()

    /**
     * Search sessions by title (case-insensitive) that have no project, with pagination.
     */
    fun searchSessionsWithoutProject(
        titleQuery: String,
        page: Int = 1,
        pageSize: Int = 10,
        sortOrder: SortOrder = SortOrder.DESC,
    ): Pageable<ChatSession> = sessionRepository.searchSessionsWithoutProject(titleQuery, page, pageSize, sortOrder)

    /**
     * Get sessions with pagination support.
     * Only returns sessions without a project (projectId is null).
     * Sessions with projects are accessed through ProjectView.
     *
     * @param page The page number (1-indexed)
     * @param pageSize The number of sessions per page
     * @return PagedSessions containing the sessions for the requested page and pagination info
     */
    fun getSessionsPagedWithoutProject(page: Int, pageSize: Int, sortOrder: SortOrder = SortOrder.DESC): Pageable<ChatSession> = sessionRepository.getSessionsPaged(page, pageSize, projectFilter = false, sortOrder = sortOrder)

    /**
     * Returns the cached memory for [sessionId] if currently loaded, or null if never
     * accessed / evicted. Used by ChatViewModel to subscribe to pressureLevel and call forceCompact.
     */
    fun getMemoryForSession(sessionId: String): TokenAwareSummarizingMemory? = memoryCache.getIfPresent(sessionId)

    /**
     * Returns the memory for [sessionId], creating and caching it synchronously if
     * absent. A fast local DB read — safe to call on any background thread right
     * after session resume.
     */
    fun getOrCreateMemoryForSession(sessionId: String): TokenAwareSummarizingMemory = getOrCreateSharedMemory(sessionId)

    fun getSessionById(sessionId: String): ChatSession? = sessionRepository.getSession(sessionId)

    /**
     * Create a new session.
     *
     * @param session The session to create
     * @return The created session with generated ID (if not provided)
     */
    fun createSession(session: ChatSession): ChatSession {
        val createdSession = sessionRepository.createSession(session)

        getOrCreateClientForSession(createdSession.id)

        eventScope.launch {
            EventBus.emit(
                SessionCreatedEvent(
                    sessionId = createdSession.id,
                    projectId = createdSession.projectId,
                ),
            )
        }

        // Asynchronously generate a better AI title from the first user message.
        // The trimmed title is already persisted — this is a best-effort improvement.
        if (session.title.isNotBlank()) {
            eventScope.launch {
                try {
                    val prompt = """
                        Generate a short, concise title (150 words max, no quotes, no punctuation at end)
                        for a conversation that starts with this user message:
                        "${session.title}"
                        Respond with only the title, nothing else.
                    """.trimIndent()
                    val utilityChatClient = appContext.createUtilityClient()

                    // Normalize to a single line and cap length before persisting/broadcasting
                    // (mirrors TitleGenerator.fallbackTitle) since the model may not honor
                    // the "short, no punctuation" instruction.
                    val aiTitle = utilityChatClient.sendMessage(prompt)
                        .trim()
                        .replace("\n", " ")
                        .take(SESSION_TITLE_MAX_LENGTH)

                    if (aiTitle.isNotBlank()) {
                        sessionRepository.updateSessionTitle(createdSession.id, aiTitle)

                        EventBus.emit(
                            SessionTitleUpdatedEvent(
                                sessionId = createdSession.id,
                                newTitle = aiTitle,
                            ),
                        )

                        log.debug("AI title generated for session {}: {}", createdSession.id, aiTitle)
                    }
                } catch (e: Exception) {
                    log.debug("Failed to generate AI title for session {}: {}", createdSession.id, e.message)
                }
            }
        }

        return createdSession
    }

    /**
     * Fork a session from a specific AI message. Creates a new independent session
     * pre-populated with all active (non-outdated) messages from [sourceSessionId]
     * up to and including [upToMessageId]. Inherits directiveId and projectId from
     * the source; all messages get fresh IDs. The source session is left untouched.
     *
     * @param sourceSessionId The ID of the session to fork from.
     * @param upToMessageId   The ID of the AI message to fork from (inclusive).
     * @return The newly created (forked) [ChatSession].
     * @throws IllegalArgumentException if [sourceSessionId] does not exist or
     *   [upToMessageId] is not found among its active messages.
     */
    fun forkSession(sourceSessionId: String, upToMessageId: String): ChatSession {
        val sourceSession = sessionRepository.getSession(sourceSessionId)
            ?: throw IllegalArgumentException("Source session $sourceSessionId not found")

        // Active messages up to and including the target message (chronological order)
        val allMessages = messageRepository.getMessages(sourceSessionId)
        val activeMessages = allMessages.filter { !it.isOutdated }
        val targetIndex = activeMessages.indexOfFirst { it.id == upToMessageId }
        require(targetIndex >= 0) {
            "Message $upToMessageId not found in active messages of session $sourceSessionId"
        }
        val messagesToCopy = activeMessages.subList(0, targetIndex + 1)

        // Create the forked session (title is already descriptive, no AI title generation)
        val forkedSession = sessionRepository.createSession(
            ChatSession(
                id = "",
                title = "Fork of: ${sourceSession.title}",
                directiveId = sourceSession.directiveId,
                projectId = sourceSession.projectId,
            ),
        )

        // Bulk-insert copied messages under the new session ID with fresh UUIDs
        val copiedMessages = messagesToCopy.map { msg ->
            msg.copy(
                id = "", // auto-UUID in addMessages()
                sessionId = forkedSession.id,
                isOutdated = false,
                isBookmarked = false,
                editParentId = null,
            )
        }
        messageRepository.addMessages(copiedMessages)

        // Reflect the bulk write in the session's updatedAt
        sessionRepository.touchSession(forkedSession.id)

        // Warm up a chat client for the new session in the background (optional —
        // falls back to on-demand creation if it fails, e.g. no model in tests).
        eventScope.launch {
            try {
                getOrCreateClientForSession(forkedSession.id)
                log.debug("Pre-created chat client for forked session ${forkedSession.id} in background")
            } catch (e: Exception) {
                log.debug("Could not pre-create chat client for forked session ${forkedSession.id}: ${e.message}")
            }
        }

        eventScope.launch {
            EventBus.emit(
                SessionCreatedEvent(
                    sessionId = forkedSession.id,
                    projectId = forkedSession.projectId,
                ),
            )
        }

        log.info(
            "Forked session {} from session {} up to message {} ({} messages copied)",
            forkedSession.id,
            sourceSessionId,
            upToMessageId,
            copiedMessages.size,
        )

        return forkedSession
    }

    /**
     * Delete a session and all its related data (messages and summaries).
     * This method coordinates the deletion across multiple repositories.
     *
     * @param sessionId The ID of the session to delete
     * @return true if the session was deleted, false if it didn't exist
     */
    fun deleteSession(sessionId: String): Boolean {
        // Invalidate both regular and vision clients
        sessionContextCache.invalidate(sessionId)
        sessionContextCache.invalidate("${sessionId}_vision")
        memoryCache.invalidate(sessionId)
        sessionWebSearchState.remove(sessionId)

        messageRepository.deleteMessagesBySession(sessionId)
        sessionMemoryRepository.deleteBySessionId(sessionId)

        // Delete attachment files from storage
        AttachmentStorageManager.deleteAttachmentFiles(sessionId)

        val deleted = sessionRepository.deleteSession(sessionId)
        if (deleted) {
            EventBus.post(SessionDeletedEvent(sessionId = sessionId))
        }
        return deleted
    }

    /**
     * Update the starred status of a session.
     *
     * @param sessionId The ID of the session to update
     * @param isStarred true to star the session, false to unstar
     * @return true if the session was updated, false if it didn't exist
     */
    fun updateSessionStarred(sessionId: String, isStarred: Boolean): Boolean = sessionRepository.updateSessionStarred(sessionId, isStarred)

    /**
     * Rename the title of a chat session.
     * Marks the session as user-renamed so automatic title refresh is suppressed going forward.
     *
     * @param sessionId The ID of the session to rename
     * @param newTitle The new title for the session
     * @return true if the session was renamed, false if it didn't exist or the title is invalid
     */
    fun renameTitle(sessionId: String, newTitle: String): Boolean {
        val renamed = sessionRepository.updateSessionTitle(sessionId, newTitle)
        if (renamed) {
            sessionRepository.markAsUserRenamed(sessionId)
        }
        return renamed
    }

    /**
     * Update the directive for a chat session.
     *
     * @param sessionId The ID of the session to update
     * @param directiveId The directive ID to set (null to clear directive)
     * @return true if the session was updated, false if it didn't exist
     */
    fun updateSessionDirective(sessionId: String, directiveId: String?): Boolean = sessionRepository.updateSessionDirective(sessionId, directiveId)

    /**
     * Update the persistent set of active Resource Collections for a session
     * (chip state — see [ChatSession.activeResourceCollectionIds]).
     *
     * Note: this only persists the selection; it doesn't (yet) feed RAG retrieval —
     * that wiring is a separate, later phase.
     *
     * @param sessionId The ID of the session to update
     * @param collectionIds The full replacement list of active collection ids
     * @return true if the session was updated, false if it didn't exist
     */
    fun updateSessionActiveResourceCollections(sessionId: String, collectionIds: List<String>): Boolean {
        val updated = sessionRepository.updateSessionActiveResourceCollections(sessionId, collectionIds)
        if (updated) {
            // Invalidate the cached client/retriever so the next message rebuilds the
            // RAG pipeline against the newly selected collection (or none, if cleared).
            sessionContextCache.invalidate(sessionId)
            log.debug("Active resource collections updated for session {}, context cache invalidated", sessionId)
        }
        return updated
    }

    /**
     * Add a message to a session and update the session's timestamp.
     *
     * @param message The message to add
     * @param syncedAt When non-null, the message is pre-marked synced at insert time (single DB call).
     *   Pass [java.time.Instant.now] when the message is already persisted server-side so the sync push skips it.
     * @return The created message with generated ID
     */
    fun addMessage(message: ChatMessage, syncedAt: java.time.Instant? = null): ChatMessage {
        val createdMessage = messageRepository.addMessage(message, syncedAt)
        sessionRepository.touchSession(message.sessionId)
        EventBus.post(PushDataToServerEvent(reason = "message written"))
        return createdMessage
    }

    fun saveAiResponse(
        sessionId: String,
        response: String,
        /**
         * Pre-generated ID for the assistant message. When supplied, used as-is so
         * client and server agree on the same identity. Left blank (default), a new
         * UUID is generated at insert time.
         */
        messageId: String = "",
        isFailed: Boolean = false,
        inputTokens: Int? = null,
        outputTokens: Int? = null,
        totalTokens: Int? = null,
        durationMs: Long? = null,
        // Ordered tool-call + response-text blocks for this turn (Tool + Token only) —
        // mirrors AgentRunRecord.contentBlocks; see SessionManager.StreamingThread.timeline.
        contentBlocks: List<TurnTimelineEntry> = emptyList(),
    ): ChatMessage {
        // When a stable messageId was supplied and the message isn't a failure, the
        // server already persisted it. Pre-mark synced at insert time so no separate
        // markSynced() UPDATE is needed.
        val isPreSynced = messageId.isNotBlank() && !isFailed &&
            appContext.getActiveProvider() == ModelProvider.ASKIMO_PRO
        return addMessage(
            ChatMessage(
                id = messageId,
                sessionId = sessionId,
                role = MessageRole.ASSISTANT,
                content = response,
                isFailed = isFailed,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                totalTokens = totalTokens,
                durationMs = durationMs,
                contentBlocks = contentBlocks,
            ),
            syncedAt = if (isPreSynced) Instant.now() else null,
        )
    }

    /**
     * Get all messages for a session.
     *
     * @param sessionId The session ID
     * @return List of messages in chronological order
     */
    fun getMessages(sessionId: String): List<ChatMessageDTO> = messageRepository.getMessages(sessionId).toDTOs()

    /**
     * Mark messages as outdated after a specific message and update memory.
     * Clears the session memory and reloads it with the most recent 50 active messages.
     *
     * @param sessionId The session ID
     * @param fromMessageId The message ID to start from (exclusive)
     * @return Number of messages marked as outdated
     */
    fun markMessagesAsOutdatedAfter(sessionId: String, fromMessageId: String): Int {
        val count = messageRepository.markMessagesAsOutdatedAfter(sessionId, fromMessageId)

        // Get shared memory if session is active
        val sharedMemory = memoryCache.getIfPresent(sessionId)

        if (sharedMemory != null) {
            // Clear session memory and reload with the most recent 50 active messages
            sessionMemoryRepository.deleteBySessionId(sessionId)

            val remainingMessages = messageRepository.getRecentActiveMessages(sessionId, limit = 50).drop(1)

            val memoryMessages = remainingMessages.map { msg ->
                MemoryMessage(
                    content = msg.content,
                    type = when (msg.role) {
                        MessageRole.USER -> MessageRole.USER.value
                        MessageRole.ASSISTANT -> MessageRole.ASSISTANT.value
                        MessageRole.SYSTEM -> MessageRole.SYSTEM.value
                        MessageRole.TOOL_EXECUTION_RESULT_MESSAGE -> MessageRole.TOOL_EXECUTION_RESULT_MESSAGE.value
                    },
                    createdAt = msg.createdAt,
                )
            }

            sharedMemory.loadFromFilteredMemory(memoryMessages)

            log.debug(
                "Cleared memory and reloaded {} active messages for session {} after marking {} messages as outdated",
                memoryMessages.size,
                sessionId,
                count,
            )
        }

        return count
    }

    /**
     * Update the content of a message and mark it as edited.
     *
     * @param messageId The ID of the message to update
     * @param newContent The new content for the message
     * @return Number of messages updated (should be 1)
     */
    fun updateMessageContent(messageId: String, newContent: String): Int = messageRepository.updateMessageContent(messageId, newContent)

    /**
     * Resume a chat session by ID.
     *
     * @param sessionId The ID of the session to resume
     * @return ResumeSessionResult containing success status, messages, and any error
     */
    fun resumeSession(sessionId: String): ResumeSessionResult {
        val paginatedResult = resumeSessionPaginated(sessionId, limit = Int.MAX_VALUE)

        return ResumeSessionResult(
            success = paginatedResult.success,
            sessionId = paginatedResult.sessionId,
            messages = paginatedResult.messages,
            errorMessage = paginatedResult.errorMessage,
        )
    }

    /**
     * Resume a chat session by ID with paginated messages.
     *
     * @param sessionId The ID of the session to resume
     * @param limit The number of messages to load
     * @return ResumeSessionPaginatedResult containing success status, messages, cursor, and any error
     */
    fun resumeSessionPaginated(sessionId: String, limit: Int): ResumeSessionPaginatedResult {
        val existingSession = sessionRepository.getSession(sessionId)

        return if (existingSession != null) {
            // Load messages first for fast UI rendering
            val (messages, cursor) = messageRepository.getMessagesPaginated(
                sessionId = sessionId,
                limit = limit,
                cursor = null,
                direction = PaginationDirection.BACKWARD,
            )

            val project = existingSession.projectId?.let { projectId ->
                projectRepository.getProject(projectId)
            }

            // Eagerly create/cache memory so pressureLevel/utilization StateFlows are
            // live as soon as ChatViewModel subscribes. Fast local DB read, safe here.
            try {
                getOrCreateSharedMemory(sessionId)
            } catch (e: Exception) {
                log.debug("Could not eagerly create shared memory for session $sessionId: ${e.message}")
            }

            // Pre-create the full chat client (model + retriever) in the background —
            // heavier, must not block UI message rendering.
            eventScope.launch {
                try {
                    getOrCreateClientForSession(sessionId)
                    log.debug("Pre-created chat client for session $sessionId in background")
                } catch (e: Exception) {
                    log.debug("Could not pre-create chat client for session $sessionId: ${e.message}")
                }
            }

            ResumeSessionPaginatedResult(
                success = true,
                sessionId = sessionId,
                title = existingSession.title,
                directiveId = existingSession.directiveId,
                project = project,
                messages = messages.toDTOs(),
                cursor = cursor,
                hasMore = cursor != null,
                activeResourceCollectionIds = existingSession.activeResourceCollectionIds,
            )
        } else {
            ResumeSessionPaginatedResult(
                success = true,
                sessionId = sessionId,
                title = null,
                directiveId = null,
                project = null,
                messages = emptyList(),
                cursor = null,
                hasMore = false,
            )
        }
    }

    /**
     * Load previous messages for a session using pagination.
     *
     * @param sessionId The ID of the session
     * @param cursor The cursor to start from (timestamp of the oldest currently loaded message)
     * @param limit The number of messages to load
     * @return Pair of messages list and next cursor
     */
    fun loadPreviousMessages(sessionId: String, cursor: Instant, limit: Int): Pair<List<ChatMessageDTO>, Instant?> {
        val (messages, nextCursor) = messageRepository.getMessagesPaginated(
            sessionId = sessionId,
            limit = limit,
            cursor = cursor,
            direction = PaginationDirection.BACKWARD,
        )
        return Pair(messages.toDTOs(), nextCursor)
    }

    /**
     * Search messages in a session by content.
     *
     * @param sessionId The ID of the session to search in
     * @param searchQuery The search query (case-insensitive)
     * @param limit Maximum number of results to return
     * @return List of messages matching the search query
     */
    fun searchMessages(sessionId: String, searchQuery: String, limit: Int = 100): List<ChatMessageDTO> = messageRepository.searchMessages(sessionId, searchQuery, limit).toDTOs()

    /**
     * Get paginated messages for a session.
     *
     * @param sessionId The session ID
     * @param limit Number of messages to retrieve
     * @param cursor The cursor for pagination
     * @param direction Direction of pagination (FORWARD or BACKWARD)
     * @return Pair of messages list and next cursor
     */
    fun getMessagesPaginated(
        sessionId: String,
        limit: Int = 20,
        cursor: Instant? = null,
        direction: PaginationDirection = PaginationDirection.FORWARD,
    ): Pair<List<ChatMessageDTO>, Instant?> {
        val (messages, nextCursor) = messageRepository.getMessagesPaginated(sessionId, limit, cursor, direction)
        return Pair(messages.toDTOs(), nextCursor)
    }

    /**
     * Get all starred sessions.
     */
    fun getStarredSessions(): List<ChatSession> = sessionRepository.getStarredSessions()

    /**
     * Prepares a user message with attachments and URL contents as a UserMessage for
     * multi-modal support (text, images, file attachments, extracted URL contents).
     *
     * The directive (system instructions + session-specific directive) is prepended to
     * the user message to act as system-level instructions. LangChain4j AI Services sets
     * system messages at build time, so directives are prepended here to allow per-session
     * customization:
     * ```
     * [System Directive]
     * ---
     * [Session-Specific Directive]
     * ---
     * [User Message with Attachments and URL Contents]
     * ```
     * Attachments are inlined using file:// format; URLs detected with explicit intent
     * have their content extracted and appended.
     *
     * @return UserMessage ready to send to the AI (supports text + images)
     */
    fun prepareContextAndGetPromptForChat(
        sessionId: String,
        userMessage: ChatMessageDTO,
        willSaveUserMessage: Boolean,
    ): List<Content> {
        if (willSaveUserMessage) {
            // Save all attachments to persistent storage via AttachmentStorageManager
            val attachmentsWithStorage = try {
                AttachmentStorageManager.saveAttachments(sessionId, userMessage.attachments)
            } catch (e: FileSizeExceededException) {
                log.error("Attachment file too large: ${e.message}")
                throw e // Re-throw to be handled by the UI
            }

            // Pre-mark synced at insert time when the active provider persists messages
            // server-side — single DB call, no separate UPDATE needed.
            val preSyncedAt = if (appContext.getActiveProvider() == ModelProvider.ASKIMO_PRO) {
                Instant.now()
            } else {
                null
            }
            messageRepository.addMessage(
                ChatMessage(
                    id = userMessage.id!!,
                    sessionId = sessionId,
                    role = MessageRole.USER,
                    content = userMessage.content,
                    attachments = attachmentsWithStorage.toDomain(sessionId),
                ),
                syncedAt = preSyncedAt,
            )
        }

        sessionRepository.touchSession(sessionId)

        // Generate a title only if the session doesn't have one yet
        val session = sessionRepository.getSession(sessionId)
        if (session?.title.isNullOrBlank()) {
            val generatedTitle = sessionRepository.generateAndUpdateTitle(sessionId, userMessage.content)

            eventScope.launch {
                EventBus.emit(
                    SessionTitleUpdatedEvent(
                        sessionId = sessionId,
                        newTitle = generatedTitle,
                    ),
                )
            }
        }

        // Enrich with attachments/URL contents, then convert (handles text-only and
        // multi-modal images).
        val enrichedContent = constructMessageWithAttachmentsAndUrls(userMessage)
        val enrichedMessage = userMessage.copy(content = enrichedContent)
        return enrichedMessage.toUserMessage()
    }

    /**
     * Toggle bookmark state for a message.
     * @return true if the message is now bookmarked, false if un-bookmarked.
     */
    fun toggleBookmark(messageId: String): Boolean = messageRepository.toggleBookmark(messageId)

    /**
     * Return all bookmarked messages for a given session, ordered by creation time.
     */
    fun getBookmarkedMessages(sessionId: String): List<ChatMessageDTO> = messageRepository.getBookmarkedMessages(sessionId).toDTOs()

    /**
     * Return all bookmarked messages across all sessions, ordered newest-first.
     * Used by the global Bookmarks view.
     */
    fun getAllBookmarkedMessages(): List<ChatMessageDTO> = messageRepository.getAllBookmarkedMessages().toDTOs()

    /**
     * Return a map of sessionId → bookmark count for sessions with at least one bookmark.
     * Used by the sidebar to show 🔖 N badges without per-row queries.
     */
    fun getBookmarkCountsBySession(): Map<String, Int> = messageRepository.getBookmarkCountsBySession()

    /**
     * Return all bookmarked messages across all sessions grouped by their conversation,
     * ordered by session's most recent activity descending.
     * Used by the global Bookmarks view.
     */
    fun getAllBookmarkGroups(): List<BookmarkGroup> {
        val rows = messageRepository.getAllBookmarkedWithSessions()
        if (rows.isEmpty()) return emptyList()

        // groupBy on a LinkedHashMap preserves DB result order, so sessions stay
        // sorted by updatedAt DESC and messages by createdAt ASC, per the ORDER BY.
        return rows
            .groupBy({ it.second }, { it.first })
            .map { (session, messages) -> BookmarkGroup(session, messages.map { it.toDTO() }) }
    }

    /**
     * Extract content from an attachment file, trying storagePath first (persisted),
     * then falling back to filePath (temporary). Handles all error cases.
     *
     * @param attachment The attachment to extract content from
     * @return The extracted file content, or null if not available/readable
     * @throws FileSizeExceededException if the file exceeds the maximum allowed size
     */
    private fun extractAttachmentContent(attachment: FileAttachmentDTO): String? {
        // Try storagePath first (for re-running saved messages)
        val filePath = attachment.storagePath ?: attachment.filePath

        if (filePath == null) {
            log.error("Attachment has neither storagePath nor filePath: ${attachment.fileName}")
            return null
        }

        return try {
            val file = File(filePath)
            when {
                !file.exists() -> {
                    log.error("File not found: $filePath")
                    null
                }

                !FileContentExtractor.isSupported(file) -> {
                    log.warn("Unsupported file type: ${attachment.fileName}")
                    null
                }

                else -> FileContentExtractor.extractContent(file)
            }
        } catch (e: FileSizeExceededException) {
            log.error("File too large: ${attachment.fileName} (${e.fileSize} bytes, max: ${e.maxAllowedSize} bytes)")
            throw e // Re-throw to be handled by the UI
        } catch (e: Exception) {
            log.error("Failed to extract content from ${attachment.fileName}: ${e.message}", e)
            null
        }
    }

    private fun constructMessageWithAttachmentsAndUrls(
        userMessage: ChatMessageDTO,
    ): String = buildString {
        userMessage.attachments.forEach { attachment ->
            val content = when {
                attachment.content != null -> attachment.content
                else -> extractAttachmentContent(attachment)
            }

            // Only add file metadata and content if content is actually extractable
            if (content != null) {
                appendLine("---")
                appendLine("Attached file: ${attachment.fileName}")
                appendLine("File size: ${formatFileSize(attachment.size)}")
                appendLine()
                appendLine(content)
                appendLine("---")
                appendLine()
            }
        }

        // Then include user's message/question at the end
        appendLine(userMessage.content)
    }
}
