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
import io.askimo.core.chat.mapper.ChatMessageMapper.toDTO
import io.askimo.core.chat.repository.PaginationDirection
import io.askimo.core.chat.service.ChatSessionService
import io.askimo.core.db.DatabaseManager
import io.askimo.core.event.EventBus
import io.askimo.core.event.error.SendMessageErrorEvent
import io.askimo.core.event.internal.ChatCompletedEvent
import io.askimo.core.event.internal.ChatInProgressEvent
import io.askimo.core.event.internal.DiagramFixedEvent
import io.askimo.core.event.internal.ProjectRefreshEvent
import io.askimo.core.event.internal.SessionTitleUpdatedEvent
import io.askimo.core.logging.logger
import io.askimo.ui.session.SessionManager
import io.askimo.ui.util.ErrorHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import kotlin.collections.plus

/**
 * ViewModel for managing chat state and interactions.
 *
 * This class handles the business logic for the chat view, including:
 * - Managing the list of messages
 * - Sending messages to the AI
 * - Handling loading and error states
 * - Resuming previous chat sessions
 */
class ChatViewModel(
    private val sessionManager: SessionManager,
    private val scope: CoroutineScope,
    private val chatSessionService: ChatSessionService,
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

    // Incremented every time previous messages are prepended so the UI can
    // distinguish a prepend (restore viewport) from an append (scroll to bottom).
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

    var selectedDirective by mutableStateOf<String?>(null)
        private set

    var sessionTitle by mutableStateOf<String?>(null)
        private set

    var project by mutableStateOf<Project?>(null)
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
            sessionTitle = sessionTitle ?: "",
            project = project,
        )

    /**
     * Refresh the session title from the database.
     * Called after sending messages to update the auto-generated title.
     */
    fun refreshSessionTitle() {
        val sessionId = currentSessionId.value ?: return
        scope.launch {
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

    // Track active subscription jobs per threadId (not chatId) to ensure proper cleanup
    // Key = threadId, Value = subscription Job
    private val activeSubscriptions = mutableMapOf<String, Job>()

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
     * Observe session title update events so the header title updates immediately
     * when the async AI title generation in createSession completes — without
     * waiting for the AI chat response to finish.
     */
    private fun observeSessionTitleEvents() {
        scope.launch {
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
     * Observe DiagramFixedEvent and update the message content in DB + in-memory state
     * so the fixed diagram is persisted and shown on next load.
     */
    private fun observeDiagramFixedEvents() {
        scope.launch {
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
     * Observe project-related events and update state accordingly
     */
    private fun observeProjectEvents() {
        // Observe ProjectRefreshEvent to reload project when reference materials are added/removed
        scope.launch {
            EventBus.internalEvents
                .collect { event ->
                    if (event is ProjectRefreshEvent) {
                        // Only reload if this event is for the current session's project
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
        scope.launch {
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
     * Subscribe to a SPECIFIC thread by sessionId.
     * This ensures we only get chunks from THIS specific question, not old ones.
     */
    private fun subscribeToThread(sessionId: String) {
        // Cancel any existing subscription for this sessionId to prevent duplicates
        activeSubscriptions[sessionId]?.cancel()
        activeSubscriptions.remove(sessionId)

        val activeThread = sessionManager.getActiveThread(sessionId)

        if (activeThread != null) {
            val hasChunks = activeThread.chunks.value.isNotEmpty()

            if (!hasChunks) {
                isThinking = true
                startThinkingTimer()
            } else {
                val streamingContent = activeThread.chunks.value.joinToString("")
                val newAiMessage = ChatMessageDTO(
                    content = streamingContent,
                    isUser = false,
                    id = null,
                    timestamp = null,
                )

                // Insert at retry position or append to end
                messages = if (retryInsertPosition != null) {
                    messages.toMutableList().apply {
                        add(retryInsertPosition!!, newAiMessage)
                    }
                } else {
                    val lastMessage = messages.lastOrNull()
                    if (lastMessage != null && !lastMessage.isUser) {
                        messages.dropLast(1) + newAiMessage
                    } else {
                        messages + newAiMessage
                    }
                }
            }

            // Create a single job for this SPECIFIC threadId's subscription
            val subscriptionJob = scope.launch {
                var firstTokenReceived = hasChunks

                activeThread.chunks.collect { chunks ->
                    if (currentSessionId.value == sessionId && chunks.isNotEmpty()) {
                        if (!firstTokenReceived) {
                            firstTokenReceived = true
                            isThinking = false
                            stopThinkingTimer()
                        }

                        val streamingContent = chunks.joinToString("")
                        currentResponse = streamingContent

                        val newAiMessage = ChatMessageDTO(
                            content = streamingContent,
                            isUser = false,
                            id = null,
                            timestamp = null,
                        )

                        // Insert at retry position or update/append at end
                        messages = if (retryInsertPosition != null) {
                            // Check if message already exists at retry position
                            val messagesList = messages.toMutableList()
                            if (retryInsertPosition!! < messagesList.size &&
                                !messagesList[retryInsertPosition!!].isUser &&
                                messagesList[retryInsertPosition!!].id == null
                            ) {
                                // Replace existing streaming message
                                messagesList[retryInsertPosition!!] = newAiMessage
                                messagesList
                            } else {
                                // Insert new message
                                messagesList.apply { add(retryInsertPosition!!, newAiMessage) }
                            }
                        } else {
                            val lastMessage = messages.lastOrNull()
                            if (lastMessage != null && !lastMessage.isUser) {
                                messages.dropLast(1) + newAiMessage
                            } else {
                                messages + newAiMessage
                            }
                        }
                    }
                }
            }

            // Track this subscription by sessionId
            activeSubscriptions[sessionId] = subscriptionJob

            // Monitor completion in a separate job
            scope.launch {
                activeThread.isComplete.collect { isComplete ->
                    if (currentSessionId.value == sessionId && isComplete) {
                        isLoading = false
                        EventBus.post(ChatCompletedEvent(sessionId = sessionId))
                        isThinking = false
                        stopThinkingTimer()

                        // Get the saved message from the StreamingThread (no database query needed!)
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
                        } else {
                            log.warn("Saved message not available in StreamingThread for session $sessionId")
                        }

                        // Clear retry position tracker
                        retryInsertPosition = null

                        // Refresh session title (in case it was auto-generated from first message)
                        refreshSessionTitle()

                        // Cancel and clean up subscription when thread completes
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

        if (editingMessage != null && editingMessage.id != null) {
            val originalMessageId = editingMessage.id ?: return currentSessionId

            scope.launch {
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
        scope.launch {
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

                startThinkingTimer()

                // 6. Resend the user message
                currentJob = scope.launch {
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

        // Session ID must be set by this point (from resumeSession)
        val sessionId = currentSessionId.value ?: run {
            UUID.randomUUID().toString()
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

        currentJob = scope.launch {
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

        // Stop the streaming service and cancel ALL subscriptions for current chat
        val chatId = currentSessionId.value
        if (chatId != null) {
            // Cancel ALL subscriptions (in case there are multiple)
            activeSubscriptions.values.forEach { it.cancel() }
            activeSubscriptions.clear()

            // Stop the streaming thread
            sessionManager.stopStream(chatId)
        }
    }

    private fun startThinkingTimer() {
        thinkingElapsedSeconds = 0
        thinkingFrameIndex = 0

        // Timer for elapsed seconds
        thinkingJob = scope.launch {
            while (isThinking) {
                delay(1000)
                thinkingElapsedSeconds++
            }
        }

        // Animation for spinner frames (200ms interval like CLI)
        animationJob = scope.launch {
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
     * Resume a chat session by ID and load the most recent messages.
     * If the session is actively streaming, continue displaying the stream.
     *
     * @param sessionId The ID of the session to resume
     * @return true if successful, false otherwise
     */
    fun resumeSession(sessionId: String): Boolean {
        currentSessionId.value = sessionId

        scope.launch {
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

                    // Check for interrupted AI response
                    // Only when all messages are loaded (hasMoreMessages = false)
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

                    // Load session title and project
                    sessionTitle = result.title
                    project = result.project

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

        scope.launch {
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

                // Increment so the UI knows messages were prepended (not appended)
                // and should restore the viewport rather than scroll to bottom.
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

        scope.launch {
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

        scope.launch {
            try {
                isLoading = true

                // Don't clear search mode - we might be jumping to a search result
                // clearSearch()  // REMOVED - was causing search box to disappear

                // Load messages around the target message
                // We'll load MESSAGE_PAGE_SIZE/2 messages before and after
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
                // Update local state to reflect outdated messages
                // mutableStateOf is thread-safe in Compose, no need for Dispatchers.Main
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
        // Cancel any in-flight streaming for the previous session so it does not
        // bleed its isLoading / isThinking state into the new blank session.
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

        // Clear search state
        clearSearch()

        // Reset directive to null for new chat session
        selectedDirective = null
    }

    /**
     * Set the directive for the current or next chat session.
     * @param directiveId The directive ID to set (null to clear directive)
     */
    override fun setDirective(directiveId: String?) {
        selectedDirective = directiveId

        val sessionId = currentSessionId.value
        if (sessionId != null) {
            scope.launch {
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
     * Update the content of an AI message and mark it as edited.
     * This allows users to edit AI responses after they are generated.
     *
     * @param messageId The ID of the message to update
     * @param newContent The new content for the message
     */
    override fun updateAIMessage(messageId: String, newContent: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    chatSessionService.updateMessageContent(messageId, newContent)
                }

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
}
