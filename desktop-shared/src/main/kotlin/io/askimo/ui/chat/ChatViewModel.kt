/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.askimo.core.analytics.Analytics
import io.askimo.core.analytics.AnalyticsEvent
import io.askimo.core.chat.domain.Project
import io.askimo.core.chat.dto.ChatMessageDTO
import io.askimo.core.chat.dto.FileAttachmentDTO
import io.askimo.core.chat.dto.ToolApprovalRequest
import io.askimo.core.chat.dto.TurnTimelineEntry
import io.askimo.core.chat.dto.TurnTimelineGroup
import io.askimo.core.chat.dto.grouped
import io.askimo.core.chat.mapper.ChatMessageMapper.toDTO
import io.askimo.core.chat.repository.PaginationDirection
import io.askimo.core.chat.service.ChatDirectiveService
import io.askimo.core.chat.service.ChatSessionService
import io.askimo.core.db.DatabaseManager
import io.askimo.core.event.EventBus
import io.askimo.core.event.error.SendMessageErrorEvent
import io.askimo.core.event.internal.BookmarkToggledEvent
import io.askimo.core.event.internal.ChatCompletedEvent
import io.askimo.core.event.internal.ChatInProgressEvent
import io.askimo.core.event.internal.DiagramFixedEvent
import io.askimo.core.event.internal.ProjectRefreshEvent
import io.askimo.core.event.internal.SessionTitleUpdatedEvent
import io.askimo.core.logging.logger
import io.askimo.core.memory.MemoryPressureLevel
import io.askimo.core.util.TextUtils
import io.askimo.ui.session.SessionManager
import io.askimo.ui.util.ErrorHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import kotlin.collections.plus
import kotlin.time.Duration.Companion.milliseconds

/**
 * ViewModel for chat state and interactions: messages, sending to the AI, loading/error
 * states, and resuming previous sessions.
 */
class ChatViewModel(
    private val sessionManager: SessionManager,
    private val scope: CoroutineScope,
    private val chatSessionService: ChatSessionService,
    private val chatDirectiveService: ChatDirectiveService,
) : ChatActions {
    private val log = logger<ChatViewModel>()

    var messages by mutableStateOf(listOf<ChatMessageDTO>())
        private set

    var isLoading by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var currentResponse by mutableStateOf("")
        private set

    var isThinking by mutableStateOf(false)
        private set

    var thinkingElapsedSeconds by mutableStateOf(0)
        private set

    var thinkingFrameIndex by mutableStateOf(0)
        private set

    var isLoadingPrevious by mutableStateOf(false)
        private set

    var hasMoreMessages by mutableStateOf(false)
        private set

    // Incremented on each prepend, letting the UI tell a prepend (keep viewport) apart
    // from an append (scroll to bottom).
    var prependGeneration by mutableStateOf(0)
        private set

    var isSearching by mutableStateOf(false)
        private set

    var searchQuery by mutableStateOf("")
        private set

    var currentSearchResultIndex by mutableStateOf(0)
        private set

    var searchResults by mutableStateOf<List<ChatMessageDTO>>(emptyList())
        private set

    var isSearchMode by mutableStateOf(false)
        private set

    var selectedDirective by mutableStateOf<String?>(chatDirectiveService.resolveDefaultDirectiveId(projectId = null))
        private set

    /** Persistent user-selected resource collections for this session (chip state). */
    var activeResourceCollectionIds by mutableStateOf<List<String>>(emptyList())
        private set

    var sessionTitle by mutableStateOf<String?>(null)
        private set

    var project by mutableStateOf<Project?>(null)
        private set

    var activeTimeline by mutableStateOf<List<TurnTimelineEntry>>(emptyList())
        private set

    var pendingToolApproval by mutableStateOf<ToolApprovalRequest?>(null)
        private set

    // Per-message timelines (incl. thinking) for turns completed earlier in this session,
    // keyed by message id — mirrors AgentRunArea's completedGroups. Falls back to
    // ChatMessageDTO.contentBlocks (tool+text only) once no longer in this map.
    var completedTimelines by mutableStateOf<Map<String, List<TurnTimelineGroup>>>(emptyMap())
        private set

    var bookmarkedMessageIds by mutableStateOf<Set<String>>(emptySet())
        private set

    var pendingScrollToMessageId by mutableStateOf<String?>(null)
        private set

    var memoryPressureLevel by mutableStateOf(MemoryPressureLevel.NORMAL)
        private set

    var memoryUtilization by mutableStateOf(0f)
        private set

    var memoryUsedTokens by mutableStateOf(0)
        private set

    var memoryBudgetTokens by mutableStateOf(0)
        private set

    var isCompressing by mutableStateOf(false)
        private set

    var isContextSizeLearned by mutableStateOf(false)
        private set

    val state: ChatState
        get() = ChatState(
            messages = messages,
            hasMoreMessages = hasMoreMessages,
            isLoadingPrevious = isLoadingPrevious,
            prependGeneration = prependGeneration,
            isLoading = isLoading,
            isThinking = isThinking,
            thinkingElapsedSeconds = thinkingElapsedSeconds,
            spinnerFrame = getSpinnerFrame(),
            errorMessage = errorMessage,
            isSearchMode = isSearchMode,
            searchQuery = searchQuery,
            searchResults = searchResults,
            currentSearchResultIndex = currentSearchResultIndex,
            isSearching = isSearching,
            selectedDirective = selectedDirective,
            activeResourceCollectionIds = activeResourceCollectionIds,
            sessionTitle = sessionTitle ?: "",
            project = project,
            activeTimeline = activeTimeline,
            pendingToolApproval = pendingToolApproval,
            completedTimelines = completedTimelines,
            bookmarkedMessageIds = bookmarkedMessageIds,
            pendingScrollToMessageId = pendingScrollToMessageId,
            memoryPressureLevel = memoryPressureLevel,
            memoryUtilization = memoryUtilization,
            memoryUsedTokens = memoryUsedTokens,
            memoryBudgetTokens = memoryBudgetTokens,
            isCompressing = isCompressing,
            isContextSizeLearned = isContextSizeLearned,
        )

    /**
     * Refresh the session title from the database.
     * Called after sending messages to update the auto-generated title.
     */
    fun refreshSessionTitle() {
        val sessionId = currentSessionId.value ?: return
        viewModelScope.launch {
            try {
                val session = withContext(Dispatchers.IO) {
                    chatSessionService.getSessionById(sessionId)
                }
                sessionTitle = session?.title
            } catch (e: Exception) {
                log.error("Failed to refresh session title", e)
            }
        }
    }

    // Track the position where retry response should be inserted (null = append to end)
    private var retryInsertPosition: Int? = null

    private var currentJob: Job? = null
    private var thinkingJob: Job? = null
    private var animationJob: Job? = null

    // Active subscription jobs keyed by threadId (not chatId), for proper cleanup.
    private val activeSubscriptions = mutableMapOf<String, Job>()

    // Scope owned by this ViewModel, parented off the shared app-lifetime `scope` (never
    // cancelled itself). All work here launches on viewModelScope so cleanup() can cancel
    // everything in one shot when SessionManager evicts this instance.
    private val viewModelJob = SupervisorJob(scope.coroutineContext[Job])
    private val viewModelScope = CoroutineScope(scope.coroutineContext + viewModelJob)

    private var currentCursor: Instant? = null
    private val currentSessionId = MutableStateFlow<String?>(null)

    private val spinnerFrames = charArrayOf('⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏')

    companion object {
        private const val MESSAGE_PAGE_SIZE = 50
        private const val MESSAGE_BUFFER_THRESHOLD = MESSAGE_PAGE_SIZE * 2
    }

    init {
        observeProjectEvents()
        observeSessionTitleEvents()
        observeDiagramFixedEvents()
    }

    /**
     * Updates the header title as soon as async AI title generation (in createSession)
     * completes, without waiting for the chat response to finish.
     */
    private fun observeSessionTitleEvents() {
        viewModelScope.launch {
            EventBus.internalEvents
                .filterIsInstance<SessionTitleUpdatedEvent>()
                .collect { event ->
                    if (event.sessionId == currentSessionId.value) {
                        log.debug("Session title updated to: ${event.newTitle}")
                        sessionTitle = event.newTitle
                    }
                }
        }
    }

    /**
     * Persists AI-fixed diagrams (DiagramFixedEvent) to DB and in-memory state.
     */
    private fun observeDiagramFixedEvents() {
        viewModelScope.launch {
            EventBus.internalEvents
                .filterIsInstance<DiagramFixedEvent>()
                .collect { event ->
                    val messageId = event.entityId
                    val existing = messages.find { it.id == messageId } ?: return@collect
                    val updatedContent = existing.content.replace(event.originalDiagram, event.fixedDiagram)
                    if (updatedContent != existing.content) {
                        log.debug("Persisting AI-fixed diagram for message {}", messageId)
                        updateAIMessage(messageId, updatedContent)
                    }
                }
        }
    }

    /**
     * Reloads the project when its reference materials change.
     */
    private fun observeProjectEvents() {
        viewModelScope.launch {
            EventBus.internalEvents
                .collect { event ->
                    if (event is ProjectRefreshEvent) {
                        // Only reload if it's for the current session's project
                        val currentProject = project
                        if (currentProject != null && currentProject.id == event.projectId) {
                            log.debug("ProjectRefreshEvent received for project {}, reloading project data", event.projectId)
                            reloadProject(currentProject.id)
                        }
                    }
                }
        }
    }

    /**
     * Reload project data from database when reference materials change
     */
    private fun reloadProject(projectId: String) {
        viewModelScope.launch {
            try {
                val updatedProject = withContext(Dispatchers.IO) {
                    val projectRepository = DatabaseManager.getInstance().getProjectRepository()
                    projectRepository.getProject(projectId)
                }
                if (updatedProject != null) {
                    project = updatedProject
                    log.debug("Project reloaded: {} with {} knowledge sources", updatedProject.name, updatedProject.knowledgeSources.size)
                }
            } catch (e: Exception) {
                log.error("Failed to reload project {}", projectId, e)
            }
        }
    }

    /**
     * Get the current spinner frame character for the thinking indicator.
     */
    fun getSpinnerFrame(): Char = spinnerFrames[thinkingFrameIndex % spinnerFrames.size]

    /**
     * Inserts [message] at [retryInsertPosition] (retry mode) or replaces a trailing
     * AI message / appends (normal mode). Use for first-time placement of a new bubble.
     */
    private fun insertAiMessage(message: ChatMessageDTO) {
        messages = if (retryInsertPosition != null) {
            messages.toMutableList().apply { add(retryInsertPosition!!, message) }
        } else {
            val last = messages.lastOrNull()
            if (last != null && !last.isUser) messages.dropLast(1) + message else messages + message
        }
    }

    /**
     * Updates an already-present streaming bubble (id=null) in-place, or falls back to
     * [insertAiMessage] if the bubble does not exist yet. Use inside `chunks.collect`.
     */
    private fun upsertStreamingAiMessage(message: ChatMessageDTO) {
        messages = if (retryInsertPosition != null) {
            val list = messages.toMutableList()
            val pos = retryInsertPosition!!
            if (pos < list.size && !list[pos].isUser && list[pos].id == null) {
                list[pos] = message
                list
            } else {
                list.apply { add(pos, message) }
            }
        } else {
            val last = messages.lastOrNull()
            if (last != null && !last.isUser) messages.dropLast(1) + message else messages + message
        }
    }

    /**
     * Ensures a placeholder AI message (id=null) exists in [messages].
     * If one already exists this is a no-op. Otherwise inserts an empty bubble via
     * [insertAiMessage] and stops the thinking indicator.
     */
    private fun ensurePlaceholderAiMessage() {
        if (messages.any { !it.isUser && it.id == null }) return
        isThinking = false
        stopThinkingTimer()
        insertAiMessage(ChatMessageDTO(content = "", isUser = false, id = null, timestamp = null))
    }

    /**
     * Subscribe to a SPECIFIC thread by sessionId.
     * This ensures we only get chunks from THIS specific question, not old ones.
     */
    private fun subscribeToThread(sessionId: String) {
        // Cancel any existing subscription for this sessionId to prevent duplicates
        activeSubscriptions[sessionId]?.cancel()
        activeSubscriptions.remove(sessionId)

        val activeThread = sessionManager.getActiveThread(sessionId)

        if (activeThread != null) {
            // Child scope so one cancel() tears down all collectors below. Parented off
            // viewModelJob so cleanup() also cancels it as a safety net.
            val subscriptionJob = SupervisorJob(viewModelJob)
            val subscriptionScope = CoroutineScope(viewModelScope.coroutineContext + subscriptionJob)
            activeSubscriptions[sessionId] = subscriptionJob

            val hasEvents = activeThread.timeline.value.isNotEmpty()

            if (!hasEvents) {
                isThinking = true
                startThinkingTimer(activeThread.startTimeMillis)
            } else {
                val streamingContent = activeThread.timeline.value.filterIsInstance<TurnTimelineEntry.Token>().joinToString("") { it.text }
                val newAiMessage = ChatMessageDTO(
                    content = streamingContent,
                    isUser = false,
                    id = null,
                    timestamp = null,
                )

                // Insert at retry position or append to end
                insertAiMessage(newAiMessage)
            }

            // Single collector for the unified, chronological timeline (tokens, tool calls,
            // thinking) — see SessionManager.StreamingThread.timeline.
            subscriptionScope.launch {
                var firstEventReceived = hasEvents

                activeThread.timeline.collect { timeline ->
                    if (currentSessionId.value == sessionId && timeline.isNotEmpty()) {
                        if (!firstEventReceived) {
                            firstEventReceived = true
                            isThinking = false
                            stopThinkingTimer()
                        }

                        activeTimeline = timeline
                        // Ensure a placeholder bubble exists so tool/thinking chips show before the first token.
                        ensurePlaceholderAiMessage()

                        val streamingContent = timeline.filterIsInstance<TurnTimelineEntry.Token>().joinToString("") { it.text }
                        currentResponse = streamingContent

                        val newAiMessage = ChatMessageDTO(
                            content = streamingContent,
                            isUser = false,
                            id = null,
                            timestamp = null,
                        )

                        // Insert at retry position or update/append at end
                        upsertStreamingAiMessage(newAiMessage)
                    }
                }
            }

            subscriptionScope.launch {
                activeThread.pendingApproval.collect { request ->
                    if (currentSessionId.value == sessionId) {
                        pendingToolApproval = request
                    }
                }
            }

            // Monitor completion in a separate job
            subscriptionScope.launch {
                activeThread.isComplete.collect { isComplete ->
                    if (currentSessionId.value == sessionId && isComplete) {
                        isLoading = false
                        EventBus.post(
                            ChatCompletedEvent(
                                sessionId = sessionId,
                                preview = TextUtils.stripMarkdownForPreview(currentResponse),
                            ),
                        )
                        isThinking = false
                        stopThinkingTimer()

                        // Get the saved message from the StreamingThread — no DB query needed.
                        // The SessionManager already saved it and stored the result
                        val savedMessage = activeThread.savedMessage.value
                        if (savedMessage != null) {
                            // Replace temporary message with the saved one (has ID, isFailed, timestamp)
                            messages = if (retryInsertPosition != null) {
                                val list = messages.toMutableList()
                                val tempMessageIndex = list.indexOfLast { !it.isUser && it.id == null }
                                if (tempMessageIndex >= 0) {
                                    list[tempMessageIndex] = savedMessage.toDTO()
                                    list
                                } else {
                                    val lastAiIndex = list.indexOfLast { !it.isUser }
                                    if (lastAiIndex >= 0) {
                                        list[lastAiIndex] = savedMessage.toDTO()
                                        list
                                    } else {
                                        list + savedMessage.toDTO()
                                    }
                                }
                            } else {
                                val lastMessage = messages.lastOrNull()
                                if (lastMessage != null && !lastMessage.isUser) {
                                    messages.dropLast(1) + savedMessage.toDTO()
                                } else {
                                    messages + savedMessage.toDTO()
                                }
                            }
                            log.debug(
                                "Updated AI message from StreamingThread - isFailed: {}, id: {}, position: {}",
                                savedMessage.isFailed,
                                savedMessage.id,
                                retryInsertPosition ?: "end",
                            )

                            // Keep this turn's full timeline (incl. thinking) visible for the rest
                            // of the session, keyed by its stable message id — in-memory only
                            // (DB persists only Tool/Token blocks via ChatMessageDTO.contentBlocks).
                            val finalTimeline = activeThread.timeline.value
                            if (finalTimeline.isNotEmpty()) {
                                completedTimelines = completedTimelines + (savedMessage.id to finalTimeline.grouped())
                            }
                        } else {
                            log.warn("Saved message not available in StreamingThread for session $sessionId")
                        }

                        // Clear retry position tracker
                        retryInsertPosition = null

                        // activeTimeline stays set — chips remain visible until the next message is sent.

                        // Refresh session title (in case it was auto-generated from first message)
                        refreshSessionTitle()

                        // Remove the thread now that savedMessage has been read (it's kept alive
                        // past coroutine completion specifically for this).
                        sessionManager.removeThread(sessionId)

                        // Clean up all collectors (timeline, pendingApproval, isComplete) for this session.
                        activeSubscriptions[sessionId]?.cancel()
                        activeSubscriptions.remove(sessionId)
                    }
                }
            }
        }
    }

    /**
     * Send or edit a chat message.
     * This method handles both normal message sending and edit mode.
     *
     * @param creationMode The creation mode (Chat, Image, etc.)
     * @param message The message text
     * @param attachments Optional list of file attachments
     * @param editingMessage The message being edited (null for normal send)
     * @return The session ID after sending (or null if no session)
     */
    override fun sendOrEditMessage(
        creationMode: CreationMode,
        message: String,
        attachments: List<FileAttachmentDTO>,
        editingMessage: ChatMessageDTO?,
        enabledServerIds: Set<String>,
    ): String? {
        if (message.isBlank() || isLoading) return currentSessionId.value

        val currentSessionId = currentSessionId.value

        if (editingMessage?.id != null) {
            val originalMessageId = editingMessage.id ?: return currentSessionId

            viewModelScope.launch {
                editMessage(originalMessageId, message, attachments)
                sendMessage(projectId = project?.id, creationMode, message, attachments, enabledServerIds)
            }
        } else {
            sendMessage(projectId = project?.id, creationMode, message, attachments, enabledServerIds)
        }

        return currentSessionId
    }

    /**
     * Retry an AI message by regenerating the response.
     * Marks the AI message and all newer messages as outdated, then resends the previous user message.
     *
     * @param messageId The AI message ID to retry
     */
    override fun retryMessage(messageId: String, enabledServerIds: Set<String>) {
        viewModelScope.launch {
            try {
                // 1. Find the AI message to retry
                val aiMessageIndex = messages.indexOfFirst { it.id == messageId }
                if (aiMessageIndex == -1) {
                    log.error("Cannot retry: message not found")
                    errorMessage = "Cannot retry: message not found"
                    return@launch
                }

                val aiMessage = messages[aiMessageIndex]
                if (aiMessage.isUser) {
                    log.error("Cannot retry: message is not an AI message")
                    errorMessage = "Cannot retry: not an AI message"
                    return@launch
                }

                // 2. Find the last user message before the AI message
                val userMessageIndex = messages.take(aiMessageIndex)
                    .indexOfLast { it.isUser }
                if (userMessageIndex == -1) {
                    log.error("Cannot retry: no user message found before AI message")
                    errorMessage = "Cannot retry: no user message found"
                    return@launch
                }

                val userMessage = messages[userMessageIndex]

                // Get session ID
                val sessionId = currentSessionId.value ?: return@launch

                // 3. Mark the AI message and all subsequent messages as outdated in database
                withContext(Dispatchers.IO) {
                    chatSessionService.markMessagesAsOutdatedAfter(sessionId, messageId)
                }

                // 4. Update UI to show messages as outdated
                messages = messages.mapIndexed { index, message ->
                    if (index >= aiMessageIndex) {
                        message.copy(isOutdated = true)
                    } else {
                        message
                    }
                }

                // 5. Set insert position for new response (right after the outdated AI message)
                retryInsertPosition = aiMessageIndex + 1

                // Cancel any ongoing requests
                currentJob?.cancel()
                currentJob = null

                // Clear any previous error
                errorMessage = null
                isLoading = true
                EventBus.post(ChatInProgressEvent(sessionId = sessionId))
                currentResponse = ""
                isThinking = true
                thinkingElapsedSeconds = 0

                // Clear tool chips and thinking content from the previous response before retrying
                activeTimeline = emptyList()
                pendingToolApproval = null

                startThinkingTimer()

                // 6. Resend the user message
                currentJob = viewModelScope.launch {
                    try {
                        if (selectedDirective != null) {
                            Analytics.track(AnalyticsEvent.DIRECTIVE_USED)
                        }
                        val threadId = sessionManager.sendMessage(
                            projectId = project?.id,
                            mode = CreationMode.Chat,
                            sessionId = sessionId,
                            userMessage = userMessage,
                            willSaveUserMessage = false,
                            enabledServerIds = enabledServerIds,
                            directiveId = selectedDirective,
                        )

                        if (threadId == null) {
                            errorMessage = "Please wait for the current response to complete before retrying."
                            isLoading = false
                            EventBus.post(ChatCompletedEvent(sessionId = sessionId, failed = true))
                            isThinking = false
                            stopThinkingTimer()
                            return@launch
                        }

                        subscribeToThread(sessionId)
                    } catch (e: Exception) {
                        log.error("Failed to retry message", e)
                        if (currentSessionId.value == sessionId) {
                            errorMessage = ErrorHandler.getUserFriendlyError(e, "retrying message")
                            isLoading = false
                            EventBus.post(ChatCompletedEvent(sessionId = sessionId, failed = true))
                            isThinking = false
                            stopThinkingTimer()
                            // Save the failed partial response so it gets a real id and retry remains available
                            val partial = currentResponse.ifBlank { "" }
                            val failedMessage = withContext(Dispatchers.IO) {
                                chatSessionService.saveAiResponse(
                                    sessionId = sessionId,
                                    response = partial,
                                    isFailed = true,
                                )
                            }
                            messages = messages.toMutableList().apply {
                                val tempIndex = indexOfLast { !it.isUser && it.id == null }
                                if (tempIndex >= 0) {
                                    this[tempIndex] = failedMessage.toDTO()
                                } else {
                                    add(failedMessage.toDTO())
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log.error("Failed to retry message", e)
                errorMessage = ErrorHandler.getUserFriendlyError(
                    e,
                    "retrying message",
                    "Failed to retry message. Please try again.",
                )
            }
        }
    }

    /**
     * Send a message to the AI.
     *
     * @param mode The creation mode (Chat, Image, etc.)
     * @param message The user's message
     * @param attachments Optional list of file attachments
     */
    fun sendMessage(projectId: String?, mode: CreationMode, message: String, attachments: List<FileAttachmentDTO> = emptyList(), enabledServerIds: Set<String> = emptySet()) {
        if (message.isBlank() || isLoading) return

        // Clear tool chips/thinking content from the previous response.
        activeTimeline = emptyList()
        pendingToolApproval = null

        // Session ID must be set by this point (from resumeSession)
        val sessionId = currentSessionId.value ?: run {
            UUID.randomUUID().toString()
        }

        // Show the raw message as the title immediately; refreshSessionTitle() overwrites it
        // with the AI-generated summary once ready (see ChatSessionService.createSession).
        if (messages.isEmpty() && sessionTitle.isNullOrBlank()) {
            sessionTitle = message
        }

        currentJob?.cancel()
        currentJob = null

        // Clear any previous error
        errorMessage = null
        isLoading = true
        EventBus.post(ChatInProgressEvent(sessionId = sessionId))
        currentResponse = ""
        isThinking = true
        thinkingElapsedSeconds = 0

        val userMessage = ChatMessageDTO(
            content = message,
            isUser = true,
            id = UUID.randomUUID().toString(),
            timestamp = Instant.now(),
            attachments = attachments,
        )

        messages = messages + userMessage

        if (messages.size > MESSAGE_BUFFER_THRESHOLD) {
            messages = messages.takeLast(MESSAGE_PAGE_SIZE)
            currentCursor = messages.firstOrNull()?.timestamp
            hasMoreMessages = true
        }

        startThinkingTimer()

        currentJob = viewModelScope.launch {
            try {
                if (selectedDirective != null) {
                    Analytics.track(AnalyticsEvent.DIRECTIVE_USED)
                }
                val threadId = sessionManager.sendMessage(
                    projectId = projectId,
                    mode = mode,
                    sessionId = sessionId,
                    userMessage = userMessage,
                    willSaveUserMessage = true,
                    enabledServerIds = enabledServerIds,
                    directiveId = selectedDirective,
                    activeResourceCollectionIds = activeResourceCollectionIds,
                )

                if (threadId == null) {
                    errorMessage = "Please wait for the current response to complete before asking another question."
                    isLoading = false
                    EventBus.post(ChatCompletedEvent(sessionId = sessionId, failed = true))
                    isThinking = false
                    stopThinkingTimer()
                    return@launch
                }

                if (currentSessionId.value == null) {
                    currentSessionId.value = sessionId
                }

                subscribeToThread(sessionId)
            } catch (e: Exception) {
                if (currentSessionId.value == sessionId) {
                    errorMessage = ErrorHandler.getUserFriendlyError(e, "sending message")
                    isThinking = false
                    stopThinkingTimer()
                    // Save the failed partial response so it gets a real id and retry becomes available
                    val partial = currentResponse.ifBlank { "" }
                    val failedMessage = withContext(Dispatchers.IO) {
                        chatSessionService.saveAiResponse(
                            sessionId = sessionId,
                            response = partial,
                            isFailed = true,
                        )
                    }
                    // Replace the temporary id=null streaming message with the saved failed one
                    messages = messages.toMutableList().apply {
                        val tempIndex = indexOfLast { !it.isUser && it.id == null }
                        if (tempIndex >= 0) {
                            this[tempIndex] = failedMessage.toDTO()
                        } else {
                            add(failedMessage.toDTO())
                        }
                    }
                } else {
                    EventBus.emit(SendMessageErrorEvent(e))
                }
                isLoading = false
                EventBus.post(ChatCompletedEvent(sessionId = sessionId, failed = true))
            }
        }
    }

    /**
     * Cancel the current AI response.
     * Stops the stream and discards all buffered chunks (does not save to database).
     */
    override fun cancelResponse() {
        currentJob?.cancel()
        currentJob = null
        val sessionId = currentSessionId.value
        isLoading = false
        isThinking = false
        stopThinkingTimer()
        if (sessionId != null) {
            EventBus.post(ChatCompletedEvent(sessionId = sessionId, failed = false))
        }

        // Stop the stream and cancel all subscriptions for the current chat.
        val chatId = currentSessionId.value
        if (chatId != null) {
            activeSubscriptions.values.forEach { it.cancel() }
            activeSubscriptions.clear()

            // Stop the streaming thread
            sessionManager.stopStream(chatId)
        }
    }

    private fun startThinkingTimer(startTimeMillis: Long = System.currentTimeMillis()) {
        // Compute elapsed from the real start time so re-subscribing to an already-running
        // thread shows correct elapsed time instead of restarting from 0.
        thinkingElapsedSeconds = ((System.currentTimeMillis() - startTimeMillis) / 1000).toInt()
        thinkingFrameIndex = 0

        // Timer for elapsed seconds
        thinkingJob = viewModelScope.launch {
            while (isThinking) {
                delay(1000)
                thinkingElapsedSeconds = ((System.currentTimeMillis() - startTimeMillis) / 1000).toInt()
            }
        }

        // Animation for spinner frames (200ms interval like CLI)
        animationJob = viewModelScope.launch {
            while (isThinking) {
                delay(200)
                thinkingFrameIndex++
            }
        }
    }

    private fun stopThinkingTimer() {
        thinkingJob?.cancel()
        thinkingJob = null
        animationJob?.cancel()
        animationJob = null
    }

    /**
     * Binds this ViewModel to a brand-new, already-persisted-but-empty session directly,
     * skipping the async DB reload [resumeSession] does.
     *
     * Used right before [sendMessage] for a session's very first message. Calling
     * [resumeSession] here would race an async DB read against sendMessage's synchronous
     * in-memory `messages` mutation, and could wipe out the just-appended user
     * message/response by overwriting `messages` with the still-empty DB state. Since
     * there's nothing to load yet, we just set the fields directly.
     *
     * @param defaultDirectiveId Directive id to pre-select, already resolved by the caller
     *   on `Dispatchers.IO` (resolving it here would run blocking DB transactions, unsafe
     *   inside a Compose `Snapshot.withMutableSnapshot` block).
     * @param initialActiveResourceCollectionIds Resource collection chip selection carried
     *   over from the composer that created this session, if any.
     */
    fun bindNewSession(
        sessionId: String,
        title: String,
        project: Project?,
        defaultDirectiveId: String?,
        initialActiveResourceCollectionIds: List<String> = emptyList(),
    ) {
        currentSessionId.value = sessionId
        sessionTitle = title
        this.project = project
        messages = emptyList()
        currentCursor = null
        hasMoreMessages = false
        bookmarkedMessageIds = emptySet()
        selectedDirective = defaultDirectiveId
        activeResourceCollectionIds = initialActiveResourceCollectionIds
        errorMessage = null
    }

    /**
     * Resume a chat session by ID and load the most recent messages.
     * If the session is actively streaming, continue displaying the stream.
     *
     * @param sessionId The ID of the session to resume
     * @return true if successful, false otherwise
     */
    fun resumeSession(sessionId: String): Boolean {
        currentSessionId.value = sessionId

        viewModelScope.launch {
            try {
                activeSubscriptions.values.forEach { it.cancel() }
                activeSubscriptions.clear()

                isLoading = true
                errorMessage = null

                clearSearch()

                val result = withContext(Dispatchers.IO) {
                    chatSessionService.resumeSessionPaginated(
                        sessionId,
                        MESSAGE_PAGE_SIZE,
                    )
                }

                if (result.success) {
                    messages = result.messages
                    // Store pagination state
                    currentCursor = result.cursor
                    hasMoreMessages = result.hasMore

                    // Check for an interrupted AI response, only once all messages are loaded.
                    if (!hasMoreMessages && messages.isNotEmpty()) {
                        val lastNonOutdatedMessage = messages.lastOrNull { !it.isOutdated }
                        val activeThread = sessionManager.getActiveThread(sessionId)

                        // If last non-outdated message is from user and session is not running, add interrupted message
                        if (lastNonOutdatedMessage != null && lastNonOutdatedMessage.isUser && activeThread == null) {
                            val interruptedMessage = withContext(Dispatchers.IO) {
                                chatSessionService.saveAiResponse(
                                    sessionId = sessionId,
                                    response = "Response was interrupted. Please retry.",
                                    isFailed = true,
                                )
                            }

                            // Add to the messages list for UI display
                            messages = messages + interruptedMessage.toDTO()
                        }
                    }

                    // Load directive from the resumed session
                    selectedDirective = result.directiveId

                    // Load resource collections chip selection from the resumed session
                    activeResourceCollectionIds = result.activeResourceCollectionIds

                    // Load session title and project
                    sessionTitle = result.title
                    project = result.project

                    // Load bookmark IDs for this session so the UI can mark pinned messages
                    bookmarkedMessageIds = withContext(Dispatchers.IO) {
                        chatSessionService.getBookmarkedMessages(sessionId)
                            .mapNotNull { it.id }
                            .toSet()
                    }

                    // Subscribe to memory pressure for this session
                    subscribeToMemoryPressure(sessionId)

                    // Reset thinking state
                    isThinking = false
                    stopThinkingTimer()

                    val activeThread = sessionManager.getActiveThread(sessionId)
                    if (activeThread != null) {
                        subscribeToThread(sessionId)
                    } else {
                        isLoading = false
                    }
                } else {
                    errorMessage = result.errorMessage
                    isLoading = false
                }
            } catch (e: Exception) {
                errorMessage = ErrorHandler.getUserFriendlyError(e, "resuming session", "Failed to load session. Please try again.")
                isLoading = false
            }
        }
        return true
    }

    /**
     * Load previous messages when scrolling to the top.
     * Only loads if there are more messages available.
     */
    override fun loadPrevious() {
        if (isLoadingPrevious || !hasMoreMessages || currentCursor == null || currentSessionId.value == null) {
            return
        }

        viewModelScope.launch {
            try {
                isLoadingPrevious = true

                val (previousMessages, nextCursor) = withContext(Dispatchers.IO) {
                    chatSessionService.loadPreviousMessages(
                        currentSessionId.value!!,
                        currentCursor!!,
                        MESSAGE_PAGE_SIZE,
                    )
                }

                messages = previousMessages + messages

                // Signals the UI that messages were prepended (restore viewport, don't scroll to bottom).
                prependGeneration++

                // Update pagination state
                currentCursor = nextCursor
                hasMoreMessages = nextCursor != null

                isLoadingPrevious = false
            } catch (e: Exception) {
                log.error("Failed to load previous messages", e)
                errorMessage = ErrorHandler.getUserFriendlyError(e, "loading previous messages", "Failed to load previous messages. Please try again.")
                isLoadingPrevious = false
            }
        }
    }

    /**
     * Search for messages in the current session.
     *
     * @param query The search query
     */
    override fun searchMessages(query: String) {
        if (currentSessionId.value == null) {
            return
        }

        searchQuery = query

        if (query.isBlank()) {
            searchResults = emptyList()
            currentSearchResultIndex = 0
            return
        }

        viewModelScope.launch {
            try {
                isSearching = true
                isSearchMode = true

                val results = withContext(Dispatchers.IO) {
                    chatSessionService.searchMessages(currentSessionId.value!!, query, 100)
                }

                searchResults = results

                // Reset to first result
                currentSearchResultIndex = 0

                // Auto-jump to first result if available
                if (searchResults.isNotEmpty()) {
                    val firstResult = searchResults[0]
                    val id = firstResult.id
                    val timestamp = firstResult.timestamp
                    if (id != null && timestamp != null) {
                        jumpToMessage(id, timestamp)
                    }
                }

                isSearching = false
            } catch (e: Exception) {
                log.error("Failed to search messages", e)
                errorMessage = ErrorHandler.getUserFriendlyError(e, "searching messages", "Search failed. Please try again.")
                isSearching = false
            }
        }
    }

    /**
     * Enable search mode without performing a search.
     */
    fun enableSearchMode() {
        isSearchMode = true
    }

    /**
     * Clear the search and return to normal view.
     */
    override fun clearSearch() {
        searchQuery = ""
        searchResults = emptyList()
        currentSearchResultIndex = 0
        isSearchMode = false
    }

    /**
     * Navigate to the next search result.
     */
    override fun nextSearchResult() {
        if (searchResults.isEmpty()) return
        currentSearchResultIndex = (currentSearchResultIndex + 1) % searchResults.size
        // Jump to the message
        val result = searchResults[currentSearchResultIndex]
        val id = result.id
        val timestamp = result.timestamp
        if (id != null && timestamp != null) {
            jumpToMessage(id, timestamp)
        }
    }

    /**
     * Navigate to the previous search result.
     */
    override fun previousSearchResult() {
        if (searchResults.isEmpty()) return
        currentSearchResultIndex = if (currentSearchResultIndex == 0) {
            searchResults.size - 1
        } else {
            currentSearchResultIndex - 1
        }
        // Jump to the message
        val result = searchResults[currentSearchResultIndex]
        val id = result.id
        val timestamp = result.timestamp
        if (id != null && timestamp != null) {
            jumpToMessage(id, timestamp)
        }
    }

    /**
     * Jump to a specific message in the conversation by loading context around it.
     * This exits search mode and loads messages around the target message.
     *
     * @param messageId The ID of the message to jump to
     * @param messageTimestamp The timestamp of the message
     */
    fun jumpToMessage(messageId: String, messageTimestamp: Instant) {
        if (currentSessionId.value == null) return

        viewModelScope.launch {
            try {
                isLoading = true

                // Don't clear search mode here — jumping might be to a search result
                // (clearSearch() was removed: it made the search box disappear).

                // Load MESSAGE_PAGE_SIZE/2 messages before and after the target.
                val halfPageSize = MESSAGE_PAGE_SIZE / 2

                val (beforeMessages, _) = withContext(Dispatchers.IO) {
                    chatSessionService.loadPreviousMessages(
                        currentSessionId.value!!,
                        messageTimestamp,
                        halfPageSize,
                    )
                }

                val afterMessages = withContext(Dispatchers.IO) {
                    // Load messages after the target
                    val (after, _) = chatSessionService.getMessagesPaginated(
                        sessionId = currentSessionId.value!!,
                        limit = halfPageSize,
                        cursor = messageTimestamp,
                        direction = PaginationDirection.FORWARD,
                    )
                    after
                }

                // Get the target message itself
                val allSessionMessages = withContext(Dispatchers.IO) {
                    chatSessionService.getMessages(currentSessionId.value!!)
                }
                val targetMessage = allSessionMessages.find { it.id == messageId }

                // Combine messages
                val contextMessages = if (targetMessage != null) {
                    beforeMessages + listOf(targetMessage) + afterMessages
                } else {
                    beforeMessages + afterMessages
                }

                messages = contextMessages

                // Update pagination state
                currentCursor = if (beforeMessages.isNotEmpty()) {
                    beforeMessages.first().timestamp
                } else {
                    null
                }
                hasMoreMessages = currentCursor != null

                isLoading = false
                // Signal ChatView to scroll to the target message after recomposition
                pendingScrollToMessageId = messageId
            } catch (e: Exception) {
                errorMessage = ErrorHandler.getUserFriendlyError(e, "jumping to message", "Failed to navigate to message. Please try again.")
                isLoading = false
            }
        }
    }

    /**
     * Mark the original message and all subsequent messages as outdated.
     * This should be called BEFORE creating the new edited message.
     * Does NOT reload messages - caller should reload after creating new message.
     *
     * @param originalMessageId The ID of the original message being edited
     * @return true if successful, false otherwise
     */
    suspend fun markOriginalAndSubsequentAsOutdated(originalMessageId: String): Boolean {
        val sessionId = currentSessionId.value ?: return false

        return try {
            withContext(Dispatchers.IO) {
                val subsequentCount = chatSessionService.markMessagesAsOutdatedAfter(sessionId, originalMessageId)
                log.debug("Marked original message '$originalMessageId' and $subsequentCount subsequent messages as outdated")

                true
            }
        } catch (e: Exception) {
            errorMessage = ErrorHandler.getUserFriendlyError(
                e,
                "editing message",
                "Failed to mark messages as outdated.",
            )
            false
        }
    }

    /**
     * Edit a user message by marking the original and subsequent messages as outdated.
     * Note: This does NOT create the new message - caller must call sendMessage() separately.
     *
     * @param messageId The ID of the original message to edit
     * @param newContent The new content (not used, kept for compatibility)
     * @param attachments Optional attachments (not used, kept for compatibility)
     */
    suspend fun editMessage(messageId: String, newContent: String, attachments: List<FileAttachmentDTO> = emptyList()) {
        try {
            val success = markOriginalAndSubsequentAsOutdated(messageId)

            if (success) {
                // Update local state (mutableStateOf is thread-safe in Compose; no Dispatchers.Main needed).
                messages = messages.map { message ->
                    // Find the index of the original message
                    val originalIndex = messages.indexOfFirst { it.id == messageId }
                    val currentIndex = messages.indexOf(message)

                    // Mark original message and all subsequent messages as outdated
                    if (currentIndex >= originalIndex && originalIndex != -1) {
                        message.copy(isOutdated = true)
                    } else {
                        message
                    }
                }
            }
        } catch (e: Exception) {
            errorMessage = ErrorHandler.getUserFriendlyError(
                e,
                "editing message",
                "Failed to edit message. Please try again.",
            )
        }
    }

    /**
     * Clear all messages and start a new chat.
     * Note: A new session will be created automatically when the first message is sent.
     */
    fun clearChat() {
        // Cancel in-flight streaming so it doesn't bleed isLoading/isThinking into the new session.
        currentJob?.cancel()
        currentJob = null
        activeSubscriptions.values.forEach { it.cancel() }
        activeSubscriptions.clear()
        stopThinkingTimer()

        messages = listOf()
        errorMessage = null
        currentResponse = ""
        isLoading = false
        isThinking = false
        thinkingElapsedSeconds = 0

        // Reset pagination state
        currentCursor = null
        hasMoreMessages = false
        currentSessionId.value = null

        // Clear session title and project
        sessionTitle = null
        project = null

        // Clear ephemeral tool/thinking timeline state
        activeTimeline = emptyList()
        completedTimelines = emptyMap()

        // Clear search state
        clearSearch()

        // Reset directive to the global default (no project context here).
        selectedDirective = chatDirectiveService.resolveDefaultDirectiveId(projectId = null)

        // Clear the manager's active session so the newly created session (once the first
        // message fires SessionCreatedEvent) is adopted as active — otherwise the sidebar
        // would keep highlighting the old chat.
        sessionManager.markNewChatPending()
    }

    /**
     * Set the directive for the current or next chat session.
     * @param directiveId The directive ID to set (null to clear directive)
     */
    override fun setDirective(directiveId: String?) {
        selectedDirective = directiveId

        val sessionId = currentSessionId.value
        if (sessionId != null) {
            viewModelScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        chatSessionService.updateSessionDirective(sessionId, directiveId)
                    }
                    log.debug("Updated directive for session $sessionId to $directiveId")
                } catch (e: Exception) {
                    log.error("Failed to update session directive: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Enable or disable live web search in the RAG retrieval pipeline for the current session.
     *
     * @param enabled true to include web results in RAG context, false to exclude them.
     */
    override fun setWebSearchInRag(enabled: Boolean) {
        val sessionId = currentSessionId.value ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                chatSessionService.setWebSearchForSession(sessionId, enabled)
            }
            log.debug("Web search in RAG set to $enabled for session $sessionId")
        }
    }

    /**
     * Replaces the active Resource Collections chip selection; updates state optimistically
     * and persists in the background. Persisting invalidates the session's cached ChatClient
     * (see `ChatSessionService.updateSessionActiveResourceCollections`) so the next message
     * rebuilds the RAG retriever against the new selection.
     */
    override fun setActiveResourceCollections(collectionIds: List<String>) {
        activeResourceCollectionIds = collectionIds

        val sessionId = currentSessionId.value
        if (sessionId != null) {
            viewModelScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        chatSessionService.updateSessionActiveResourceCollections(sessionId, collectionIds)
                    }
                    log.debug("Updated active resource collections for session {} to {}", sessionId, collectionIds)
                } catch (e: Exception) {
                    log.error("Failed to update session active resource collections: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Update the content of an AI message and mark it as edited.
     * This allows users to edit AI responses after they are generated.
     *
     * @param messageId The ID of the message to update
     * @param newContent The new content for the message
     */
    override fun updateAIMessage(messageId: String, newContent: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    chatSessionService.updateMessageContent(messageId, newContent)
                }

                // Content changed — any cached TTS audio for the old content is now stale.
                VoicePlaybackController.invalidate(messageId)

                // Update the message in the local state
                messages = messages.map { message ->
                    if (message.id == messageId) {
                        message.copy(content = newContent, isEdited = true)
                    } else {
                        message
                    }
                }
            } catch (e: Exception) {
                errorMessage = ErrorHandler.getUserFriendlyError(
                    e,
                    "updating message",
                    "Failed to update message. Please try again.",
                )
            }
        }
    }

    /**
     * Clean up all resources when this ViewModel is removed from cache.
     * This is called by SessionManager when the ViewModel needs to be evicted.
     */
    fun cleanup() {
        // Cancel any ongoing operations
        currentJob?.cancel()
        currentJob = null

        // Cancel all subscriptions
        activeSubscriptions.values.forEach { it.cancel() }
        activeSubscriptions.clear()

        // Cancel this ViewModel's own scope — stops every coroutine it launched (event bus
        // observers, in-flight DB calls) so it can't keep mutating state after eviction.
        // Full cancel is safe: a cleaned-up ViewModel is always discarded; SessionManager
        // creates a fresh instance if the session is revisited.
        viewModelJob.cancel()

        // Stop timers
        stopThinkingTimer()

        // Clear state to free memory
        messages = emptyList()
        currentResponse = ""
        isLoading = false
        isThinking = false
        errorMessage = null
        searchResults = emptyList()

        log.debug("Cleaned up ChatViewModel for session: ${currentSessionId.value}")
    }

    /**
     * Toggle the bookmark state of a message.
     * Updates the DB on a background thread and reflects the change immediately in UI state
     * for instant feedback (optimistic update).
     *
     * On success, emits a [BookmarkToggledEvent] so that [SessionsViewModel] can update
     * the sidebar bookmark-count badge without a full reload.
     */
    override fun toggleBookmark(messageId: String) {
        val isAdding = messageId !in bookmarkedMessageIds
        bookmarkedMessageIds = if (isAdding) {
            bookmarkedMessageIds + messageId
        } else {
            bookmarkedMessageIds - messageId
        }

        viewModelScope.launch {
            try {
                val nowBookmarked = withContext(Dispatchers.IO) {
                    chatSessionService.toggleBookmark(messageId)
                }
                // Notify the sidebar so the bookmark-count badge updates immediately
                val sessionId = currentSessionId.value
                if (sessionId != null) {
                    EventBus.post(
                        BookmarkToggledEvent(
                            sessionId = sessionId,
                            delta = if (nowBookmarked) +1 else -1,
                        ),
                    )
                }
            } catch (e: Exception) {
                // Roll back the optimistic update on failure
                bookmarkedMessageIds = if (isAdding) {
                    bookmarkedMessageIds - messageId
                } else {
                    bookmarkedMessageIds + messageId
                }
                log.error("Failed to toggle bookmark for message {}", messageId, e)
            }
        }
    }

    /**
     * Consume the pending scroll target after ChatView has scrolled to it.
     */
    override fun clearPendingScroll() {
        pendingScrollToMessageId = null
    }

    /**
     * Forks the current session from [messageId] into a new independent session
     * (pre-populated up to and including that message) and switches to it immediately.
     *
     * Runs on [Dispatchers.IO] and is fire-and-forget — no loading state, since forking
     * is fast and doesn't block the current conversation.
     */
    override fun forkFromMessage(messageId: String) {
        val sourceSessionId = currentSessionId.value ?: return

        viewModelScope.launch {
            try {
                val forkedSession = withContext(Dispatchers.IO) {
                    chatSessionService.forkSession(sourceSessionId, messageId)
                }
                // switchToSession updates activeSessionId and resumes the new session's ViewModel.
                sessionManager.switchToSession(forkedSession.id)
                log.debug("Forked session {} → navigated to {}", sourceSessionId, forkedSession.id)
            } catch (e: Exception) {
                log.error("Failed to fork session from message {}", messageId, e)
                errorMessage = "Failed to fork conversation. Please try again."
            }
        }
    }

    /**
     * Subscribe to [TokenAwareSummarizingMemory.pressureLevel] for [sessionId].
     * Cancels any previous pressure subscription before starting a new one so that
     * switching sessions never leaves a stale collector running.
     */
    private fun subscribeToMemoryPressure(sessionId: String) {
        pressureSubscriptionJob?.cancel()
        pressureSubscriptionJob = viewModelScope.launch {
            // resumeSessionPaginated eagerly creates the memory synchronously, so this is
            // always a Caffeine cache hit — no polling needed.
            val memory = withContext(Dispatchers.IO) {
                chatSessionService.getOrCreateMemoryForSession(sessionId)
            }
            launch {
                memory.pressureLevel.collect { level ->
                    memoryPressureLevel = level
                }
            }
            launch {
                memory.usedTokens.collect { used ->
                    memoryUsedTokens = used
                }
            }
            launch {
                memory.budgetTokens.collect { budget ->
                    memoryBudgetTokens = budget
                }
            }
            launch {
                memory.isContextSizeLearned.collect { learned ->
                    isContextSizeLearned = learned
                }
            }
            memory.utilization.collect { u ->
                memoryUtilization = u.coerceIn(0f, 1f)
            }
        }
    }

    private var pressureSubscriptionJob: Job? = null

    /**
     * Trigger an aggressive (COMPACT-mode) summarization on the current session's memory.
     * Updates [isCompressing] while the cycle runs; pressure updates automatically when done.
     */
    override fun compressMemory() {
        if (isCompressing) return
        val sessionId = currentSessionId.value ?: return

        viewModelScope.launch {
            isCompressing = true
            try {
                val memory = withContext(Dispatchers.IO) {
                    chatSessionService.getMemoryForSession(sessionId)
                }
                if (memory != null) {
                    memory.forceCompact()
                    // forceCompact is async — wait a tick then stop the spinner; pressure
                    // updates automatically via its StateFlow once the cycle completes.
                    delay(500.milliseconds)
                } else {
                    log.warn("compressMemory: no memory in cache for session {}", sessionId)
                }
            } catch (e: Exception) {
                log.error("compressMemory failed for session {}", sessionId, e)
            } finally {
                isCompressing = false
            }
        }
    }
}
