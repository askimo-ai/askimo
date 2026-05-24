/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.service

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.rag.content.retriever.ContentRetriever
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever
import io.askimo.core.chat.domain.ChatMessage
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.domain.Project
import io.askimo.core.chat.dto.ChatMessageDTO
import io.askimo.core.chat.mapper.ChatMessageMapper.toDTOs
import io.askimo.core.chat.mapper.ChatMessageMapper.toDomain
import io.askimo.core.chat.repository.ChatMessageRepository
import io.askimo.core.chat.repository.ChatSessionRepository
import io.askimo.core.chat.repository.PaginationDirection
import io.askimo.core.chat.repository.ProjectRepository
import io.askimo.core.chat.repository.SessionMemoryRepository
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
import io.askimo.core.event.internal.SessionCreatedEvent
import io.askimo.core.event.internal.SessionDeletedEvent
import io.askimo.core.event.internal.SessionTitleUpdatedEvent
import io.askimo.core.logging.logger
import io.askimo.core.memory.MemoryMessage
import io.askimo.core.memory.TokenAwareSummarizingMemory
import io.askimo.core.providers.ChatClient
import io.askimo.core.rag.RagUtils
import io.askimo.core.util.formatFileSize
import io.askimo.core.vision.toUserMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
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
    private val appContext: AppContext,
) {
    private val log = logger<ChatSessionService>()

    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Cache of session contexts (ChatClient + TokenAwareSummarizingMemory).
     * Each session can have TWO contexts: regular and vision.
     * Cache keys: "sessionId" for regular, "sessionId_vision" for vision.
     * Caffeine provides automatic eviction when memory is low or sessions are inactive.
     */
    private val sessionContextCache: Cache<String, SessionChatContext> = Caffeine.newBuilder()
        .maximumSize(20) // Increased to accommodate both regular and vision clients
        .expireAfterAccess(30.minutes.toJavaDuration())
        .removalListener<String, SessionChatContext> { sessionId, context, cause ->
            if (context != null && sessionId != null) {
                log.debug("Evicting session context for session {} (cause: {})", sessionId, cause)
            }
        }
        .build()

    /**
     * Cache of shared memory instances.
     * Both regular and vision clients share the same memory to maintain conversation continuity.
     */
    private val memoryCache: Cache<String, TokenAwareSummarizingMemory> = Caffeine.newBuilder()
        .maximumSize(10)
        .expireAfterAccess(30.minutes.toJavaDuration())
        .removalListener<String, TokenAwareSummarizingMemory> { sessionId, memory, _ ->
            // When a session is evicted (user switched away or cache full), trigger summarization
            // unconditionally so the next resume loads a compact summary rather than raw messages.
            if (memory != null && sessionId != null) {
                log.debug("Memory evicted for session {}, triggering background summarization", sessionId)
                memory.triggerAsyncSummarization()
            }
        }
        .build()

    init {
        eventScope.launch {
            EventBus.internalEvents
                .filterIsInstance<ModelChangedEvent>()
                .collect { event ->
                    handleModelChanged(event)
                }
        }
    }

    /**
     * Handle model change event - clear all cached contexts since they use the old model.
     */
    private fun handleModelChanged(event: ModelChangedEvent) {
        log.info("Model changed to ${event.newModel} for provider ${event.provider}, clearing cached contexts")
        sessionContextCache.invalidateAll()
    }

    /**
     * Get or create shared memory for a session.
     * This ensures both regular and vision clients share the same conversation history.
     *
     * @param sessionId The session ID
     * @return Shared TokenAwareSummarizingMemory instance
     */
    private fun getOrCreateSharedMemory(sessionId: String): TokenAwareSummarizingMemory = memoryCache.get(sessionId) { _ ->
        TokenAwareSummarizingMemory(
            appContext,
            sessionId = sessionId,
            sessionMemoryRepository = sessionMemoryRepository,
            userMemoryRepository = DatabaseManager.getInstance().getUserMemoryRepository(),
            asyncSummarization = true,
            summarizationTimeoutSeconds = AppConfig.chat.summarizationTimeoutSeconds,
        )
    }

    /**
     * Get or create a chat context (client + memory) for a session.
     * Both regular and vision clients share the same memory to maintain conversation continuity.
     *
     * @param sessionId The session ID
     * @return SessionChatContext containing the ChatClient and its associated memory
     */
    private fun getOrCreateContextForSession(
        sessionId: String,
    ): SessionChatContext = sessionContextCache.get(sessionId) { _ ->
        val project = projectRepository.findProjectBySessionId(sessionId)

        // Get or create shared memory - REUSE across both regular and vision clients
        val sharedMemory = getOrCreateSharedMemory(sessionId)

        // Create content retriever if project has indexed paths
        val retriever = if (project != null) {
            log.debug("Session $sessionId belongs to project: ${project.id}")
            createRetrieverForProject(appContext.createUtilityClient(), project)
        } else {
            null
        }

        // Create client with vision support if requested
        val chatClient = appContext.createStatefulChatSession(
            sessionId = sessionId,
            retriever = retriever,
            memory = sharedMemory,
        )

        SessionChatContext(chatClient, sharedMemory)
    }

    /**
     * Get or create a ChatClient for a session.
     * If needsVision=true, returns a vision-capable client.
     * Both regular and vision clients share the same conversation memory.
     *
     * @param sessionId The session ID
     * @return ChatClient for the session (vision-capable if requested)
     */
    fun getOrCreateClientForSession(
        sessionId: String,
    ): ChatClient = getOrCreateContextForSession(sessionId).chatClient

    /**
     * Create a content retriever for a project if it has indexed paths.
     * Uses hybrid search combining:
     * - JVector for semantic similarity (vector embeddings)
     * - Lucene for keyword matching (BM25)
     * - Reciprocal Rank Fusion to merge results
     *
     * @param project The project to create a retriever for
     * @return Content retriever if project has indexed paths, null otherwise
     */
    private fun createRetrieverForProject(classifierChatClient: ChatClient, project: Project): ContentRetriever? {
        try {
            val embeddingModel = appContext.getEmbeddingModel()

            val embeddingStore = RagUtils.getEmbeddingStore(project.id, embeddingModel)

            val ragConfig = AppConfig.rag

            val vectorRetriever = RagUtils.enrichContentRetrieverWithLucene(
                classifierChatClient,
                project.id,
                EmbeddingStoreContentRetriever.builder()
                    .embeddingStore(embeddingStore)
                    .embeddingModel(embeddingModel)
                    .maxResults(ragConfig.vectorSearchMaxResults)
                    .minScore(ragConfig.vectorSearchMinScore)
                    .build(),
                project.knowledgeSources.map { it.resourceIdentifier },
            )

            return vectorRetriever
        } catch (e: Exception) {
            log.error("Failed to create content retriever for project ${project.id}", e)
            return null
        }
    }

    /**
     * Get all sessions sorted by most recently updated first.
     */
    fun getSessions(limit: Int): List<ChatSession> = sessionRepository.getSessions(limit)

    fun getSessionsWithoutProject(limit: Int): List<ChatSession> = sessionRepository.getSessionsWithoutProject(limit)

    /**
     * Get sessions with pagination support.
     * Only returns sessions without a project (projectId is null).
     * Sessions with projects are accessed through ProjectView.
     *
     * @param page The page number (1-indexed)
     * @param pageSize The number of sessions per page
     * @return PagedSessions containing the sessions for the requested page and pagination info
     */
    fun getSessionsPagedWithoutProject(page: Int, pageSize: Int): Pageable<ChatSession> = sessionRepository.getSessionsPaged(page, pageSize, projectFilter = false)

    /**
     * Get a session by ID.
     */
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

        // Asynchronously generate a better AI title using the first user message.
        // The trimmed title is already persisted by createSession — this is a best-effort improvement.
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

                    val aiTitle = utilityChatClient.sendMessage(prompt).trim()

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

        messageRepository.deleteMessagesBySession(sessionId)
        sessionMemoryRepository.deleteBySessionId(sessionId)

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
     *
     * @param sessionId The ID of the session to rename
     * @param newTitle The new title for the session
     * @return true if the session was renamed, false if it didn't exist or the title is invalid
     */
    fun renameTitle(sessionId: String, newTitle: String): Boolean = sessionRepository.updateSessionTitle(sessionId, newTitle)

    /**
     * Update the directive for a chat session.
     *
     * @param sessionId The ID of the session to update
     * @param directiveId The directive ID to set (null to clear directive)
     * @return true if the session was updated, false if it didn't exist
     */
    fun updateSessionDirective(sessionId: String, directiveId: String?): Boolean = sessionRepository.updateSessionDirective(sessionId, directiveId)

    /**
     * Add a message to a session and update the session's timestamp.
     *
     * @param message The message to add
     * @return The created message with generated ID
     */
    fun addMessage(message: ChatMessage): ChatMessage {
        val createdMessage = messageRepository.addMessage(message)
        sessionRepository.touchSession(message.sessionId)
        EventBus.post(PushDataToServerEvent(reason = "message written"))
        return createdMessage
    }

    fun saveAiResponse(sessionId: String, response: String, isFailed: Boolean = false): ChatMessage {
        val message = addMessage(
            ChatMessage(
                id = "",
                sessionId = sessionId,
                role = MessageRole.ASSISTANT,
                content = response,
                isFailed = isFailed,
            ),
        )

        return message
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
            // 1. Delete session memory from database
            sessionMemoryRepository.deleteBySessionId(sessionId)

            // 2. Get the most recent 50 active messages (sorted and limited in database)
            val remainingMessages = messageRepository.getRecentActiveMessages(sessionId, limit = 50).drop(1)

            // 3. Convert to MemoryMessage and reload memory
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

            // Fetch project if session belongs to one
            val project = existingSession.projectId?.let { projectId ->
                projectRepository.getProject(projectId)
            }

            // Pre-create/cache the chat client for this session asynchronously in the background
            // This is optional and doesn't block message rendering - if it fails (e.g., no model configured in tests),
            // we can still show messages. The client will be created on-demand when user sends a message.
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
     * Prepares user message with attachments and URL contents, returns UserMessage for multi-modal support.
     *
     * Handles both text-only messages and multi-modal messages containing images, file attachments,
     * and extracted URL contents. Uses VisionExtensions.toUserMessage() for automatic conversion.
     *
     * The directive (system instructions + session-specific directive) is prepended to the user message
     * to act as system-level instructions. While ideally these would be separate system messages,
     * the current LangChain4j AI Services architecture sets system messages at build time,
     * so we prepend directives to user messages to allow per-session customization.
     *
     * The format is:
     * ```
     * [System Directive]
     *
     * ---
     *
     * [Session-Specific Directive]
     *
     * ---
     *
     * [User Message with Attachments and URL Contents]
     * ```
     *
     * If attachments are present, they will be included inline in the message using file:// format.
     * If URLs are detected in the message with explicit intent, their content will be extracted and appended.
     *
     * @return UserMessage ready to send to the AI (supports text + images)
     */
    fun prepareContextAndGetPromptForChat(
        sessionId: String,
        userMessage: ChatMessageDTO,
        willSaveUserMessage: Boolean,
    ): UserMessage {
        if (willSaveUserMessage) {
            messageRepository.addMessage(
                ChatMessage(
                    id = userMessage.id!!,
                    sessionId = sessionId,
                    role = MessageRole.USER,
                    content = userMessage.content,
                    attachments = userMessage.attachments.toDomain(sessionId),
                ),
            )
        }

        sessionRepository.touchSession(sessionId)

        // Generate title only if session doesn't have one yet
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

        // Construct enriched message with attachments and URL contents
        val enrichedContent = constructMessageWithAttachmentsAndUrls(userMessage)

        // Create enriched ChatMessageDTO with combined content but preserve image attachments
        val enrichedMessage = userMessage.copy(content = enrichedContent)

        // Convert to UserMessage - this handles both text-only and multi-modal (images)
        return enrichedMessage.toUserMessage()
    }

    /**
     * Constructs a formatted message with both file attachments and extracted URL contents.
     *
     * @param userMessage The original user message
     * @return Formatted message with attachments and URL contents appended
     */
    private fun constructMessageWithAttachmentsAndUrls(
        userMessage: ChatMessageDTO,
    ): String = buildString {
        userMessage.attachments.forEach { attachment ->
            val content = when {
                attachment.content != null -> attachment.content

                attachment.filePath != null -> {
                    try {
                        val file = File(attachment.filePath)
                        if (!file.exists()) {
                            log.error("File not found: ${attachment.filePath}")
                            null
                        } else if (!FileContentExtractor.isSupported(file)) {
                            log.warn("Unsupported file type: ${attachment.fileName}")
                            null
                        } else {
                            FileContentExtractor.extractContent(file)
                        }
                    } catch (e: FileSizeExceededException) {
                        log.error("File too large: ${attachment.fileName} (${e.fileSize} bytes, max: ${e.maxAllowedSize} bytes)")
                        throw e // Re-throw to be handled by the UI
                    } catch (e: Exception) {
                        log.error("Failed to extract content from ${attachment.fileName}: ${e.message}", e)
                        null
                    }
                }

                else -> {
                    log.error("Attachment has neither content nor filePath: ${attachment.fileName}")
                    null
                }
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
