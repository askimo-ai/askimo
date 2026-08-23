/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import io.askimo.core.chat.domain.ChatMessage
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.domain.Project
import io.askimo.core.chat.dto.ChatMessageDTO
import io.askimo.core.chat.dto.FileAttachmentDTO
import io.askimo.core.chat.dto.ToolApprovalRequest
import io.askimo.core.chat.dto.ToolCallInfo
import io.askimo.core.chat.dto.ToolCallStatus
import io.askimo.core.chat.service.ChatDirectiveService
import io.askimo.core.chat.service.ChatSessionService
import io.askimo.core.context.AppContext
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.SessionCreatedEvent
import io.askimo.core.exception.ContextLengthException
import io.askimo.core.exception.ExceptionHandler
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.intent.ToolConfig
import io.askimo.core.logging.logger
import io.askimo.core.mcp.McpInstanceService
import io.askimo.core.providers.ConfigurationErrorException
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ProxyChatContext
import io.askimo.core.providers.isContextLengthError
import io.askimo.core.providers.sendStreamingMessageWithCallback
import io.askimo.core.vision.ImageProcessor
import io.askimo.ui.chat.ChatViewModel
import io.askimo.ui.chat.CreationMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages multiple ChatViewModel instances with smart memory management AND streaming infrastructure.
 *
 * This manager consolidates:
 * 1. ChatViewModel lifecycle management (caching up to maxCachedViewModels)
 * 2. Streaming thread management (one thread per active session)
 * 3. Session state coordination
 *
 * Each session gets its own isolated ChatViewModel and can have at most ONE active streaming thread.
 * ViewModels are cached up to [maxCachedViewModels], and inactive ViewModels are automatically
 * cleaned up when the limit is reached.
 *
 * A ViewModel is considered "safe to remove" when:
 * 1. It's not the currently active session
 * 2. It's not waiting for an AI response (no active streaming thread)
 */
class SessionManager(
    private val chatSessionService: ChatSessionService,
    private val scope: CoroutineScope,
    private val chatDirectiveService: ChatDirectiveService,
    private val mcpInstanceService: McpInstanceService? = null,
) {
    private val log = logger<SessionManager>()

    companion object {
        private const val MAX_CONCURRENT_STREAMS = 20 // Match maxCachedViewModels
    }

    // Cache of ChatViewModel instances by session ID
    private val chatViewModels = mutableMapOf<String, ChatViewModel>()

    // Track sessions that have been created in database (to avoid redundant checks)
    private val createdSessions = mutableSetOf<String>()

    // Streaming infrastructure: sessionId -> StreamingThread
    private val activeThreads = ConcurrentHashMap<String, StreamingThread>()

    // Coroutine scope for managing streaming jobs
    private val streamingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Match the streaming capacity with ViewModel capacity
    private val maxCachedViewModels = MAX_CONCURRENT_STREAMS

    var activeSessionId by mutableStateOf<String?>(null)
        private set

    /**
     * True when the user has started a "New Chat" while an existing session was active.
     * In this state the active ViewModel has been cleared (currentSessionId = null) but
     * activeSessionId still points at the previous session so that chatViewModel stays
     * non-null and the input field remains visible.
     * When the first message is sent and a SessionCreatedEvent fires, this flag causes
     * the new session to be adopted as the active one, updating the sidebar selection.
     */
    private var isPendingNewChat = false

    init {
        Runtime.getRuntime().addShutdownHook(
            Thread {
                shutdown()
            },
        )
        subscribeToSessionEvents()
    }

    /**
     * Subscribe to internal events to keep activeSessionId in sync.
     */
    private fun subscribeToSessionEvents() {
        scope.launch {
            EventBus.internalEvents
                .filterIsInstance<SessionCreatedEvent>()
                .collect { event ->
                    if ((activeSessionId == null || isPendingNewChat) && event.projectId == null) {
                        log.debug("New session created: ${event.sessionId}, setting as active")
                        setActiveSession(event.sessionId)
                    }
                }
        }
    }

    /**
     * Represents a single streaming thread for ONE question-answer pair.
     * Thread closes automatically after completion or failure.
     */
    data class StreamingThread(
        val threadId: String,
        val sessionId: String,
        var job: Job,
        private val _chunks: MutableStateFlow<List<String>>,
        private val _isComplete: MutableStateFlow<Boolean>,
        private val _hasFailed: MutableStateFlow<Boolean>,
        private val _savedMessage: MutableStateFlow<ChatMessage?> = MutableStateFlow(null),
        private val _toolCalls: MutableStateFlow<List<ToolCallInfo>> = MutableStateFlow(emptyList()),
        private val _thinkingChunks: MutableStateFlow<List<String>> = MutableStateFlow(emptyList()),
        private val _pendingApproval: MutableStateFlow<ToolApprovalRequest?> = MutableStateFlow(null),
        // Wall-clock time the thread started "thinking" (before the first chunk arrives).
        // Used to compute the correct elapsed "thinking" time when a ViewModel re-subscribes
        // to this thread after the user switches away and back to this session, instead of
        // resetting the on-screen timer to 0.
        val startTimeMillis: Long = System.currentTimeMillis(),
    ) {
        val chunks: StateFlow<List<String>> = _chunks.asStateFlow()
        val isComplete: StateFlow<Boolean> = _isComplete.asStateFlow()
        val savedMessage: StateFlow<ChatMessage?> = _savedMessage.asStateFlow()
        val toolCalls: StateFlow<List<ToolCallInfo>> = _toolCalls.asStateFlow()
        val thinkingChunks: StateFlow<List<String>> = _thinkingChunks.asStateFlow()
        val pendingApproval: StateFlow<ToolApprovalRequest?> = _pendingApproval.asStateFlow()

        /** Sets the pending approval request, surfacing Approve/Deny buttons in the UI. */
        fun requestApproval(request: ToolApprovalRequest) {
            _pendingApproval.value = request
        }

        /** Clears the pending approval request after the user has responded. */
        fun clearApproval() {
            _pendingApproval.value = null
        }

        private val mutex = Mutex()

        suspend fun appendChunk(chunk: String) {
            mutex.withLock {
                _chunks.value += chunk
            }
        }

        suspend fun appendThinkingChunk(chunk: String) {
            mutex.withLock {
                _thinkingChunks.value += chunk
            }
        }

        suspend fun markComplete() {
            mutex.withLock {
                _isComplete.value = true
            }
        }

        suspend fun markFailed() {
            mutex.withLock {
                _hasFailed.value = true
            }
        }

        suspend fun setSavedMessage(message: ChatMessage) {
            mutex.withLock {
                _savedMessage.value = message
            }
        }

        /** Mark a tool as RUNNING. Deduplicates: no-op if already tracked. */
        suspend fun markToolRunning(toolName: String, arguments: String?) {
            mutex.withLock {
                if (_toolCalls.value.none { it.toolName == toolName }) {
                    _toolCalls.value += ToolCallInfo(
                        toolName = toolName,
                        status = ToolCallStatus.RUNNING,
                        arguments = arguments,
                    )
                }
            }
        }

        /** Mark a tool as DONE. Replaces existing RUNNING entry, or appends if not found. */
        suspend fun markToolDone(toolName: String, arguments: String?, result: String?, hasFailed: Boolean) {
            mutex.withLock {
                val existing = _toolCalls.value
                val idx = existing.indexOfFirst { it.toolName == toolName }
                val updated = ToolCallInfo(
                    toolName = toolName,
                    status = ToolCallStatus.DONE,
                    arguments = arguments,
                    result = result,
                    hasFailed = hasFailed,
                )
                _toolCalls.value = if (idx >= 0) {
                    existing.toMutableList().also { it[idx] = updated }
                } else {
                    existing + updated
                }
            }
        }

        fun getCurrentContent(): String = _chunks.value.joinToString("")
    }

    /**
     * Send a message and start streaming the AI response.
     * @return threadId if streaming started successfully, null if session already has active stream or max streams reached
     */
    fun sendMessage(
        projectId: String?,
        mode: CreationMode,
        sessionId: String,
        userMessage: ChatMessageDTO,
        willSaveUserMessage: Boolean,
        enabledServerIds: Set<String> = emptySet(),
        directiveId: String? = null,
    ): String? {
        // Create session lazily on first message (only once per session)
        if (!createdSessions.contains(sessionId)) {
            runBlocking {
                withContext(Dispatchers.IO) {
                    chatSessionService.createSession(
                        ChatSession(
                            id = sessionId,
                            title = userMessage.content,
                            createdAt = Instant.now(),
                            updatedAt = Instant.now(),
                        ),
                    )
                    createdSessions.add(sessionId)
                    log.debug("Created new session: $sessionId")

                    // Persist the selected directive (if any) to the newly created session
                    if (directiveId != null) {
                        chatSessionService.updateSessionDirective(sessionId, directiveId)
                        log.debug("Applied directive $directiveId to new session $sessionId")
                    }
                }
            }
        }

        // Check if this session already has an active (not yet complete) stream
        val existingThread = activeThreads[sessionId]
        if (existingThread != null && !existingThread.isComplete.value) {
            log.warn("Session $sessionId already has an active stream")
            return null
        }
        // If the thread is complete but not yet cleaned up by the ViewModel, remove it now
        // so a new stream can start (e.g. user retries before the completion handler fires)
        if (existingThread != null) {
            activeThreads.remove(sessionId)
            log.debug("Removed stale completed thread for session $sessionId before starting new stream")
        }

        // Check global stream limit
        if (activeThreads.size >= MAX_CONCURRENT_STREAMS) {
            log.warn("Max concurrent streams ($MAX_CONCURRENT_STREAMS) reached")
            return null
        }

        val threadId = "${sessionId}_${System.currentTimeMillis()}"

        val thread = StreamingThread(
            threadId = threadId,
            sessionId = sessionId,
            job = Job(),
            _chunks = MutableStateFlow(emptyList()),
            _isComplete = MutableStateFlow(false),
            _hasFailed = MutableStateFlow(false),
        )

        // Register this thread
        activeThreads[sessionId] = thread

        log.debug("Streaming thread $threadId for session $sessionId started. Active streams: ${activeThreads.size}")

        // Prepare context and save user message to DB
        val promptWithContext = chatSessionService.prepareContextAndGetPromptForChat(sessionId, userMessage, willSaveUserMessage)
        log.debug("Saved prompt for session $sessionId, starting streaming")

        thread.job = streamingScope.launch {
            try {
                if (mode is CreationMode.Chat) {
                    var capturedInputTokens: Int? = null
                    var capturedOutputTokens: Int? = null
                    var capturedTotalTokens: Int? = null
                    var capturedDurationMs: Long? = null

                    // Fetch resolved tools for the approval guardrail.
                    // Only fetched when MCP servers are enabled and the service is available.
                    val resolvedTools: List<ToolConfig> = if (enabledServerIds.isNotEmpty() && mcpInstanceService != null) {
                        mcpInstanceService.getGlobalTools().getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }

                    // Pre-generate the assistant message ID and set the ProxyChatContext
                    // ThreadLocal on this thread so the correlating HTTP client can inject
                    // the tracking headers into the outgoing proxy request.
                    // The ThreadLocal is safe here because sendStreamingMessageWithCallback is
                    // a blocking call that stays on this thread until the stream is complete.
                    val needsMessageCorrelation = AppContext.getInstance().getActiveProvider() == ModelProvider.ASKIMO_PRO
                    val assistantMessageId = if (needsMessageCorrelation) UUID.randomUUID().toString() else ""
                    if (needsMessageCorrelation) {
                        ProxyChatContext.set(
                            ProxyChatContext.Context(
                                sessionId = sessionId,
                                userMessageId = userMessage.id!!,
                                assistantMessageId = assistantMessageId,
                            ),
                        )
                    }

                    val fullResponse = try {
                        chatSessionService
                            .getOrCreateClientForSession(sessionId)
                            .sendStreamingMessageWithCallback(
                                projectId = projectId,
                                userContents = promptWithContext,
                                enabledServerIds = enabledServerIds,
                                onToken = { token ->
                                    streamingScope.launch {
                                        thread.appendChunk(token)
                                    }
                                },
                                onFollowUpSuggestion = { suggestion ->
                                    log.debug("Follow-up suggestion for session $sessionId: ${suggestion.question}")
                                },
                                onTokenUsage = { input, output, total, durationMs ->
                                    capturedInputTokens = input
                                    capturedOutputTokens = output
                                    capturedTotalTokens = total
                                    capturedDurationMs = durationMs
                                    log.debug("Token usage for session $sessionId: input=$input, output=$output, total=$total, duration=${durationMs}ms")
                                },
                                onToolStarted = { toolName, arguments ->
                                    streamingScope.launch {
                                        thread.markToolRunning(toolName, arguments)
                                    }
                                },
                                onToolFinished = { toolName, arguments, result, hasFailed ->
                                    streamingScope.launch {
                                        thread.markToolDone(toolName, arguments, result, hasFailed)
                                    }
                                },
                                onThinkingToken = { token ->
                                    streamingScope.launch {
                                        thread.appendThinkingChunk(token)
                                    }
                                },
                                resolvedTools = resolvedTools,
                                onToolApprovalRequired = { toolName, arguments, approve, deny ->
                                    streamingScope.launch {
                                        val argBlock = if (!arguments.isNullOrBlank()) {
                                            ":\n```json\n$arguments\n```"
                                        } else {
                                            "."
                                        }
                                        thread.appendChunk(
                                            LocalizationManager.getString("chat.tool.approval.required", toolName, argBlock),
                                        )
                                        thread.requestApproval(
                                            ToolApprovalRequest(
                                                toolName = toolName,
                                                arguments = arguments,
                                                approve = {
                                                    streamingScope.launch { thread.clearApproval() }
                                                    approve()
                                                },
                                                deny = {
                                                    streamingScope.launch { thread.clearApproval() }
                                                    deny()
                                                },
                                            ),
                                        )
                                    }
                                },
                            )
                    } finally {
                        // Always clear the ThreadLocal — prevents leaking context into
                        // subsequent requests on the same thread from the pool.
                        if (needsMessageCorrelation) ProxyChatContext.clear()
                    }

                    val savedMessage = chatSessionService.saveAiResponse(
                        sessionId = sessionId,
                        response = fullResponse,
                        // Pass the pre-generated ID so the server and local DB agree on
                        // the same message identity, and saveAiResponse can mark it synced.
                        messageId = assistantMessageId,
                        inputTokens = capturedInputTokens,
                        outputTokens = capturedOutputTokens,
                        totalTokens = capturedTotalTokens,
                        durationMs = capturedDurationMs,
                    )
                    thread.setSavedMessage(savedMessage)
                    log.debug("Streaming thread $threadId completed successfully. Saved response to session $sessionId.")
                } else {
                    // Handle other creation modes (e.g., image generation)
                    val imageModel = AppContext.getInstance().createImageModel()
                    val generateImage = imageModel.generate(userMessage.content).content()

                    // Get MIME type from AI response for logging purposes
                    val aiMimeType = generateImage.mimeType()

                    // Convert to markdown format with base64 for preview
                    // We always use image/png in markdown since we convert all images to PNG
                    val imageMarkdown = if (generateImage.url().path.isNotEmpty()) {
                        val base64Data = generateImage.base64Data()
                        val sourceUrl = generateImage.url().toString()
                        val sourceLink = "[Source]($sourceUrl)"

                        if (!base64Data.isNullOrEmpty()) {
                            "![Generated Image](data:image/png;base64,$base64Data)\n\n$sourceLink"
                        } else {
                            val downloadedBase64 = withContext(Dispatchers.IO) {
                                ImageProcessor.downloadAndProcessImageAsBase64(sourceUrl, aiMimeType)
                            }
                            if (downloadedBase64 != null) {
                                "![Generated Image](data:image/png;base64,$downloadedBase64)\n\n$sourceLink"
                            } else {
                                // Fallback to URL if download fails
                                "![Generated Image]($sourceUrl)"
                            }
                        }
                    } else {
                        val base64Data = generateImage.base64Data()
                        if (!base64Data.isNullOrEmpty()) {
                            "![Generated Image](data:image/png;base64,$base64Data)"
                        } else {
                            "Error: No image data available"
                        }
                    }

                    val savedMessage = chatSessionService.saveAiResponse(sessionId, imageMarkdown)
                    thread.setSavedMessage(savedMessage)
                }
                thread.markComplete()
            } catch (e: Exception) {
                log.error("Error while sending message to chat session $sessionId", e)
                thread.markFailed()

                val partialResponse = thread.getCurrentContent()
                val failedResponse = if (e is ConfigurationErrorException) {
                    e.displayMessage
                } else if (e.isContextLengthError()) {
                    ExceptionHandler.handleWithPartialContent(
                        throwable = ContextLengthException(cause = e),
                        partialContent = partialResponse,
                        contextId = sessionId,
                    )
                } else {
                    ExceptionHandler.handleWithPartialContent(
                        throwable = e,
                        partialContent = partialResponse,
                        contextId = sessionId,
                    )
                }

                val savedMessage = chatSessionService.saveAiResponse(sessionId, failedResponse, isFailed = true)
                thread.setSavedMessage(savedMessage)

                if (failedResponse != partialResponse && failedResponse.length > partialResponse.length) {
                    val remainingContent = failedResponse.substring(partialResponse.length)
                    if (remainingContent.isNotEmpty()) {
                        thread.appendChunk(remainingContent)
                    }
                }

                // Mark as complete so the ChatViewModel's completion monitoring triggers
                // and replaces the temporary message with the saved one from database
                thread.markComplete()
            } finally {
                // Do NOT remove from activeThreads here.
                // subscribeToThread() in ChatViewModel reads this thread to get the savedMessage
                // and replace the temp UI message with the persisted one (real ID, isFailed flag).
                // If a fast failure (e.g. JsonParseException on first token) completes and removes
                // the thread before subscribeToThread runs, the temp message is stuck with id=null
                // and the retry button silently does nothing.
                // The thread is removed by ChatViewModel after the completion handler fires.
                log.debug("Thread $threadId completed. Active streams: ${activeThreads.size}")
            }
        }

        return threadId
    }

    /**
     * Get an active streaming thread for a session.
     */
    fun getActiveThread(sessionId: String): StreamingThread? = activeThreads[sessionId]

    fun removeThread(sessionId: String) {
        activeThreads.remove(sessionId)
        log.debug("Thread removed for session $sessionId. Active streams: ${activeThreads.size}")
    }

    /**
     * Stop an active stream for a session.
     */
    fun stopStream(sessionId: String) {
        val thread = activeThreads[sessionId]
        if (thread != null) {
            log.info("Stopping stream for session $sessionId (thread ${thread.threadId})")
            thread.job.cancel()
            activeThreads.remove(sessionId)
        } else {
            log.warn("No active stream found for session $sessionId")
        }
    }

    /**
     * Get or create a ChatViewModel for a session.
     * Automatically cleans up inactive ViewModels when the cache limit is reached.
     *
     * @param sessionId The session ID
     * @return The ChatViewModel for this session
     */
    fun getOrCreateChatViewModel(sessionId: String): ChatViewModel {
        chatViewModels[sessionId]?.let { return it }

        // Check if we need to clean up before creating new one
        if (chatViewModels.size >= maxCachedViewModels) {
            cleanupInactiveViewModels()
        }

        val viewModel = ChatViewModel(
            sessionManager = this,
            scope = scope,
            chatSessionService = chatSessionService,
            chatDirectiveService = chatDirectiveService,
        )

        chatViewModels[sessionId] = viewModel
        log.debug("Created new ChatViewModel for session: $sessionId (total cached: ${chatViewModels.size})")
        return viewModel
    }

    /**
     * Switch to an existing session.
     * No cancellation needed - each ViewModel manages its own state independently.
     *
     * @param sessionId The session ID to switch to (must already exist in database)
     */
    fun switchToSession(sessionId: String) {
        activeSessionId = sessionId
        createdSessions.add(sessionId)
        val viewModel = getOrCreateChatViewModel(sessionId)
        viewModel.resumeSession(sessionId)
    }

    /**
     * Create and switch to a new session.
     * The session will be created in database lazily when the first message is sent.
     *
     * @param sessionId The new session ID to create
     */
    fun createNewSession(sessionId: String) {
        activeSessionId = sessionId
        // Don't create in DB yet - will be created lazily on first message
        val viewModel = getOrCreateChatViewModel(sessionId)
        viewModel.resumeSession(sessionId)
    }

    /**
     * Clear the active session (for "New Chat" state).
     * Sets activeSessionId to null to indicate no active session.
     */
    fun clearActiveSession() {
        activeSessionId = null
    }

    /**
     * Set the active session ID without triggering any side effects.
     * Used when a new session is created by sending a message in "New Chat" state.
     */
    fun setActiveSession(sessionId: String) {
        val wasPendingNewChat = isPendingNewChat
        isPendingNewChat = false

        // When transitioning from a "pending new chat" state, the ViewModel that sent the
        // first message is still stored under the *old* activeSessionId key. Move it to
        // the new sessionId so that getOrCreateChatViewModel(sessionId) returns the
        // already-streaming instance instead of creating a fresh empty one (which would
        // cause ChatView to show an empty conversation while the response streams in the
        // background).
        val currentActiveId = activeSessionId
        if (wasPendingNewChat && currentActiveId != null && currentActiveId != sessionId) {
            chatViewModels.remove(currentActiveId)?.let { existingViewModel ->
                chatViewModels[sessionId] = existingViewModel
            }
        }

        activeSessionId = sessionId
        createdSessions.add(sessionId)
    }

    /**
     * Create a new session associated with a project and send the first message.
     * This is used when starting a chat from ProjectView.
     *
     * @param project The project the session belongs to (already loaded by the caller;
     *   passed directly instead of being reloaded from the DB to avoid a race — see
     *   [ChatViewModel.bindNewSession])
     * @param projectId The project ID to associate with the session
     * @param message The first message to send
     * @param attachments The file attachments to include with the message
     * @param onComplete Callback when the session is ready (for navigation)
     */
    fun createProjectSessionAndSendMessage(
        project: Project?,
        projectId: String?,
        mode: CreationMode,
        message: String,
        attachments: List<FileAttachmentDTO> = emptyList(),
        enabledServerIds: Set<String> = emptySet(),
        directiveId: String? = null,
        useWebSearch: Boolean = false,
        onComplete: () -> Unit,
    ) {
        scope.launch {
            try {
                // Create a new session associated with the project. This is blocking DB I/O —
                // run it explicitly off the UI thread regardless of what dispatcher `scope`
                // happens to use.
                val newSession = withContext(Dispatchers.IO) {
                    chatSessionService.createSession(
                        ChatSession(
                            id = "",
                            title = message,
                            directiveId = directiveId,
                            projectId = projectId,
                        ),
                    )
                }

                // `scope` runs on Dispatchers.Default and this project has no Dispatchers.Main
                // artifact on the classpath, so we can't hop to Main. Snapshot.withMutableSnapshot
                // batches these state writes atomically for Compose observers instead.
                //
                // IMPORTANT: only plain State writes go here. Snapshot.withMutableSnapshot
                // throws if the snapshot fails to apply (e.g. a concurrent write conflict), in
                // which case none of the writes above become visible. Side effects like
                // onComplete() (navigation) or sendMessage() (kicks off network I/O / coroutines)
                // are NOT part of the snapshot's transactional guarantee — if they ran *inside*
                // the block, they'd fire even when apply() fails, leaving the UI
                // navigated/streaming for state that was never actually committed. So they are
                // invoked only after the block returns successfully.
                //
                // The default directive id is also resolved here, on Dispatchers.IO, BEFORE
                // entering the snapshot: ChatDirectiveService.resolveDefaultDirectiveId performs
                // blocking Exposed DB transactions (ChatDirectiveRepository.exists() /
                // UserProfileRepository.getPreference()), which must not run inside the snapshot
                // block on a non-IO dispatcher.
                val resolvedDirectiveId = directiveId ?: withContext(Dispatchers.IO) {
                    chatDirectiveService.resolveDefaultDirectiveId(project)
                }

                lateinit var viewModel: ChatViewModel
                Snapshot.withMutableSnapshot {
                    // bindNewSession (not switchToSession/resumeSession) avoids an async DB
                    // reload racing sendMessage()'s in-memory mutation below.
                    activeSessionId = newSession.id
                    createdSessions.add(newSession.id)
                    viewModel = getOrCreateChatViewModel(newSession.id)
                    viewModel.bindNewSession(sessionId = newSession.id, title = message, project = project, defaultDirectiveId = resolvedDirectiveId)
                }
                onComplete()

                // Apply web search flag before sendMessage so the retriever is built correctly
                if (useWebSearch) {
                    withContext(Dispatchers.IO) {
                        chatSessionService.setWebSearchForSession(newSession.id, true)
                    }
                }

                viewModel.sendMessage(projectId, mode, message, attachments, enabledServerIds)
            } catch (e: Exception) {
                log.error("Failed to create project session and send message", e)
            }
        }
    }

    /**
     * Clean up inactive ViewModels that are safe to remove.
     * Priority order:
     * 1. Remove inactive ViewModels (not active, not streaming)
     * 2. If all are active or streaming, remove the oldest non-active ViewModel
     */
    private fun cleanupInactiveViewModels() {
        val inactiveViewModels = chatViewModels.filter { (sessionId, _) ->
            sessionId != activeSessionId && !activeThreads.containsKey(sessionId)
        }

        if (inactiveViewModels.isEmpty()) {
            // All ViewModels are either active or streaming
            // Remove the oldest one (first in map, excluding active session)
            val oldestSession = chatViewModels.keys
                .firstOrNull { it != activeSessionId }

            if (oldestSession != null) {
                chatViewModels[oldestSession]?.cleanup()
                chatViewModels.remove(oldestSession)
                log.warn("Removed oldest ViewModel (at capacity): $oldestSession")
            }
        } else {
            // Remove one inactive ViewModel (first one found)
            val (sessionId, viewModel) = inactiveViewModels.entries.first()
            viewModel.cleanup()
            chatViewModels.remove(sessionId)
            log.debug("Removed inactive ViewModel: $sessionId (total cached: ${chatViewModels.size})")
        }
    }

    /**
     * Shutdown hook to cancel all active threads when the application closes.
     */
    private fun shutdown() {
        log.info("Shutting down SessionManager. Cancelling ${activeThreads.size} active streams.")
        activeThreads.values.forEach { it.job.cancel() }
        activeThreads.clear()
        chatViewModels.values.forEach { it.cleanup() }
        chatViewModels.clear()
    }

    /**
     * Explicitly close a session and clean up its ViewModel.
     * This should be called when the user deletes a session.
     *
     * @param sessionId The session ID to close
     */
    fun closeSession(sessionId: String) {
        // 1. Stop any active streaming thread first
        stopStream(sessionId)

        // 2. Clean up the ViewModel
        chatViewModels[sessionId]?.cleanup()
        chatViewModels.remove(sessionId)

        // 3. Remove from created sessions tracking
        createdSessions.remove(sessionId)

        log.info("Closed session: $sessionId (total cached: ${chatViewModels.size})")

        // 4. If closing the active session, clear the active session ID
        if (activeSessionId == sessionId) {
            activeSessionId = null
        }
    }

    /**
     * Mark that the user has initiated a "New Chat" while an existing session was active.
     * The next SessionCreatedEvent (triggered when the first message is sent) will update
     * activeSessionId to the new session so the sidebar highlights it correctly.
     */
    fun markNewChatPending() {
        isPendingNewChat = true
    }
}
