/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.askimo.core.agent.AgentUsage
import io.askimo.core.agent.ExternalAgent
import io.askimo.core.agent.ExternalAgentLoader
import io.askimo.core.agent.domain.AgentRunRecord
import io.askimo.core.agent.domain.SkillDefinition
import io.askimo.core.agent.domain.Workspace
import io.askimo.core.agent.repository.AgentRunHistoryRepository
import io.askimo.core.chat.TitleGenerator
import io.askimo.core.chat.dto.ChatMessageDTO
import io.askimo.core.chat.dto.ToolCallInfo
import io.askimo.core.chat.dto.ToolCallStatus
import io.askimo.core.chat.dto.TurnTimelineEntry
import io.askimo.core.chat.dto.TurnTimelineGroup
import io.askimo.core.chat.dto.collapsedEffectiveTools
import io.askimo.core.chat.dto.grouped
import io.askimo.core.context.AppContext
import io.askimo.core.db.DatabaseManager
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.AgentRunTitleUpdatedEvent
import io.askimo.core.logging.currentFileLogger
import io.askimo.ui.common.preferences.ApplicationPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

internal enum class AgentCardState {
    NOT_INSTALLED,
    NEEDS_SETUP,
    READY,
}

private val log = currentFileLogger()

/** Ensures a (possibly empty) streaming AI placeholder message exists, so tool/thinking chips can render before the first token arrives. */
private fun List<ChatMessageDTO>.ensureStreamingAiMessage(): List<ChatMessageDTO> {
    if (any { !it.isUser && it.id == null }) return this
    return this + ChatMessageDTO(id = null, content = "", isUser = false, timestamp = null)
}

/** Finalizes the trailing streaming AI message: assigns a stable id and marks failure/content/usage. */
private fun List<ChatMessageDTO>.finalizeStreamingAiMessage(
    finalContent: String,
    isFailed: Boolean,
    usage: AgentUsage? = null,
    messageId: String = "ai-${System.nanoTime()}",
): List<ChatMessageDTO> {
    val idx = indexOfLast { !it.isUser && it.id == null }
    if (idx < 0) return this
    val updated = this[idx].copy(
        id = messageId,
        content = finalContent,
        timestamp = Instant.now(),
        isFailed = isFailed,
        inputTokens = usage?.inputTokens,
        outputTokens = usage?.outputTokens,
        totalTokens = usage?.totalTokens,
        durationMs = usage?.durationMs,
    )
    return toMutableList().also { it[idx] = updated }
}

/**
 * ViewModel backing `agenticRunArea` — owns agent selection, the live conversation
 * transcript/timeline, and conversation-title generation/persistence for a single
 * agentic-run conversation. Mirrors [io.askimo.ui.chat.ChatViewModel]'s role for regular
 * chat, so this business logic (turn execution, title generation, history preload) lives
 * outside Compose and is independently testable.
 *
 * One instance is created per workspace (see `agenticRunArea`'s `remember(workspace.id)`).
 */
internal class AgentRunViewModel(
    private val workspace: Workspace,
    private val skills: List<SkillDefinition>,
    private val scope: CoroutineScope,
    private val historyRepo: AgentRunHistoryRepository = DatabaseManager.getInstance().getAgentRunHistoryRepository(),
    private val onRunCompleted: () -> Unit = {},
) {
    val workDir: File = File(workspace.path)
    val agenticSystemPrompt: String = buildAgenticSystemPrompt(skills)

    // ── Agent selection ──────────────────────────────────────────────────────
    val allAgents: List<ExternalAgent> = ExternalAgentLoader.all()

    var agentStateMap by mutableStateOf(mapOf<String, AgentCardState>())
        private set

    var selectedAgentId by mutableStateOf(ApplicationPreferences.getSelectedAgentId())
        private set

    /** Resolved selected agent; falls back to the first ready one if the saved pref is unavailable. */
    val selectedAgent: ExternalAgent?
        get() = allAgents.firstOrNull { it.id == selectedAgentId && agentStateMap[it.id] == AgentCardState.READY }
            ?: allAgents.firstOrNull { agentStateMap[it.id] == AgentCardState.READY }

    /** The raw selected agent (or the first available one), regardless of readiness — used for setup hints. */
    val selectedAgentRaw: ExternalAgent?
        get() = allAgents.firstOrNull { it.id == selectedAgentId } ?: allAgents.firstOrNull()

    val selectedAgentReady: Boolean
        get() = agentStateMap[selectedAgent?.id] == AgentCardState.READY

    /** Re-checks binary availability / auth for every known agent (off the UI thread). */
    fun refreshAgentStates() {
        scope.launch {
            agentStateMap = withContext(Dispatchers.IO) {
                allAgents.associate { agent ->
                    agent.id to when {
                        !agent.isBinaryAvailable() -> AgentCardState.NOT_INSTALLED
                        !agent.isConfigured() -> AgentCardState.NEEDS_SETUP
                        else -> AgentCardState.READY
                    }
                }
            }
        }
    }

    fun selectAgent(agentId: String) {
        selectedAgentId = agentId
        ApplicationPreferences.setSelectedAgentId(agentId)
        // A resume/session id is only meaningful to the CLI that produced it.
        activeAgentSessionId = null
        refreshAgentStates()
    }

    // ── Conversation state ───────────────────────────────────────────────────
    var messages by mutableStateOf(listOf<ChatMessageDTO>())
        private set

    var isRunning by mutableStateOf(false)
        private set

    /** Chronologically-ordered timeline for the *current* turn only — see [TurnTimelineEntry]. */
    var timeline by mutableStateOf(listOf<TurnTimelineEntry>())
        private set

    /** Completed turns' groups, keyed by that turn's finalized AI message id. */
    var completedGroups by mutableStateOf(mapOf<String, List<TurnTimelineGroup>>())
        private set

    /** True until the first token/tool-call/thinking chunk arrives — drives the "Thinking…" row. */
    var isWaitingForFirstEvent by mutableStateOf(false)
        private set

    var elapsedSeconds by mutableStateOf(0)
        private set

    /** Native session id of the currently active conversation (e.g. Claude Code's `session_id`). */
    var activeAgentSessionId by mutableStateOf<String?>(null)
        private set

    /** Askimo-side identifier grouping every turn of the current conversation in history. */
    var activeConversationId by mutableStateOf(UUID.randomUUID().toString())
        private set

    /**
     * Human-readable title for the current conversation — set synchronously to a deterministic
     * truncation of the first turn's input the instant a new conversation starts (see
     * [TitleGenerator.fallbackTitle]), then optionally replaced by a short AI-generated title
     * once [generateAndPersistAgentTitle] completes. Null only when there is no active
     * conversation yet.
     */
    var conversationTitle by mutableStateOf<String?>(null)
        private set

    val hasActiveConversation: Boolean
        get() = messages.isNotEmpty()

    // Accumulates this turn's raw response text — used only to build the AgentRunRecord
    // saved to history; the displayed transcript is `messages`.
    private var currentTurnResponse = ""
    private var elapsedTimerJob: Job? = null

    init {
        refreshAgentStates()
        observeTitleEvents()
    }

    /** Picks up AI-refined titles for the conversation currently active, wherever generated. */
    private fun observeTitleEvents() {
        scope.launch {
            EventBus.internalEvents
                .filterIsInstance<AgentRunTitleUpdatedEvent>()
                .collect { event ->
                    if (event.conversationId == activeConversationId) {
                        conversationTitle = event.newTitle
                    }
                }
        }
    }

    /**
     * Sends [text] as the next turn of the current conversation (or starts a new one if
     * the transcript is currently empty), using [agent].
     */
    fun sendMessage(agent: ExternalAgent, text: String) {
        if (text.isBlank() || isRunning) return
        val isNewConversation = messages.isEmpty()
        executeAgentic(agent, text, isNewConversation)
    }

    /** Resets the transcript so the next [sendMessage] starts a brand-new agent conversation. */
    fun startNewConversation() {
        messages = emptyList()
        timeline = emptyList()
        completedGroups = emptyMap()
        currentTurnResponse = ""
        activeAgentSessionId = null
        activeConversationId = UUID.randomUUID().toString()
        conversationTitle = null
    }

    /** Reconstructs the full multi-turn conversation for a re-opened history entry. */
    suspend fun preload(record: AgentRunRecord) {
        val turns = withContext(Dispatchers.IO) { historyRepo.findByConversationId(record.conversationId) }
        messages = turns.flatMap { r ->
            listOf(
                ChatMessageDTO(
                    id = "${r.id}-user",
                    content = r.userInput,
                    isUser = true,
                    timestamp = r.createdAt,
                ),
                ChatMessageDTO(
                    id = "${r.id}-ai",
                    content = r.response.ifBlank { r.error.orEmpty() },
                    isUser = false,
                    timestamp = r.createdAt,
                    isFailed = r.error != null,
                    inputTokens = r.inputTokens,
                    outputTokens = r.outputTokens,
                    totalTokens = r.totalTokens,
                    durationMs = r.durationMs,
                ),
            )
        }
        timeline = emptyList()
        // Reconstruct each turn's ordered tool/text groups from its persisted
        // `contentBlocks` — thinking/status were never persisted, so those groups simply
        // won't reappear here (only for turns still live in this session).
        completedGroups = turns.associate { r -> "${r.id}-ai" to r.contentBlocks.grouped() }.filterValues { it.isNotEmpty() }
        currentTurnResponse = turns.lastOrNull()?.response.orEmpty()
        activeConversationId = record.conversationId
        conversationTitle = turns.firstOrNull()?.title ?: record.title
        // Restore the agent's own session id so a follow-up on a re-opened history
        // entry continues that same conversation rather than starting a new one.
        activeAgentSessionId = turns.lastOrNull()?.agentSessionId
        isRunning = false
        isWaitingForFirstEvent = false
    }

    /**
     * Runs one turn of an agentic conversation.
     *
     * When [isNewConversation] is `true` this starts a brand-new conversation (fresh
     * transcript, no resume id). Otherwise it continues the conversation identified by
     * [activeAgentSessionId] via the agent's native resume mechanism — Askimo does not
     * replay prior [messages] back to the agent as text; the agent's own CLI session
     * owns that context.
     */
    private fun executeAgentic(agent: ExternalAgent, input: String, isNewConversation: Boolean) {
        val resumeSessionId = if (isNewConversation) null else activeAgentSessionId
        isRunning = true
        isWaitingForFirstEvent = true
        timeline = emptyList()
        currentTurnResponse = ""
        if (isNewConversation) {
            messages = emptyList()
            activeAgentSessionId = null
            activeConversationId = UUID.randomUUID().toString()
            // Instant, deterministic title — never blank. May be replaced by a short
            // AI-generated title once generateAndPersistAgentTitle() completes below.
            conversationTitle = TitleGenerator.fallbackTitle(input)
        }

        val userMessage = ChatMessageDTO(
            id = "user-${System.nanoTime()}",
            content = input,
            isUser = true,
            timestamp = Instant.now(),
        )
        messages = messages + userMessage

        startElapsedTimer()

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                // Materialize every skill in the catalog into the agent's own native
                // skill-discovery location (e.g. Claude Code's `.claude/skills/<name>/`) so its
                // built-in Skill tool can find and invoke them — not just rely on the full skill
                // text injected into agenticSystemPrompt below, which the agent can only read as
                // background instructions, not "run" as a discrete skill.
                val materialized = skills.map { skill -> agent.materializeSkill(skill, workDir) }
                try {
                    agent.runTracked(
                        systemPrompt = agenticSystemPrompt,
                        userInput = input,
                        workDir = workDir,
                        resumeSessionId = resumeSessionId,
                        onToken = { token ->
                            scope.launch {
                                isWaitingForFirstEvent = false
                                currentTurnResponse += token
                                timeline = timeline + TurnTimelineEntry.Token(token)
                            }
                        },
                        onToolCall = { toolName, detail ->
                            scope.launch {
                                isWaitingForFirstEvent = false
                                timeline = timeline + TurnTimelineEntry.Tool(
                                    ToolCallInfo.truncated(toolName = toolName, status = ToolCallStatus.DONE, arguments = detail),
                                )
                            }
                        },
                        onStatus = { status ->
                            scope.launch {
                                isWaitingForFirstEvent = false
                                timeline = timeline + TurnTimelineEntry.Status(status)
                            }
                        },
                        onThinking = { chunk ->
                            scope.launch {
                                isWaitingForFirstEvent = false
                                timeline = timeline + TurnTimelineEntry.Thinking(chunk)
                            }
                        },
                    )
                } finally {
                    materialized.forEach { it.close() }
                }
            }
            // Update state on the same coroutine, right after the run completes, so the
            // error (if any) is guaranteed to be captured before we build the history record
            // below — no race with a separately-launched coroutine.
            val errorText = result.exceptionOrNull()?.message
            isRunning = false
            isWaitingForFirstEvent = false

            // Capture (or keep) the session id so the next follow-up turn can resume this
            // exact conversation. Falls back to the id we resumed with in case this agent's
            // CLI doesn't re-emit one on every turn.
            activeAgentSessionId = agent.lastExecutionSessionId ?: resumeSessionId

            // Best-effort token usage / duration reported by the agent's own stream for this
            // turn (e.g. Claude's/Antigravity's "result" event). Null fields are hidden by
            // MessageComponents' token-usage row — not every agent (e.g. Codex) exposes this.
            val usage = agent.lastExecutionUsage

            // Guarantee a bubble exists even if the run failed before any token/tool/thinking
            // event arrived, then finalize it (stable id, failure flag) so it stops "streaming".
            val finalizedMessageId = "ai-${System.nanoTime()}"
            messages = messages
                .ensureStreamingAiMessage()
                .finalizeStreamingAiMessage(
                    finalContent = currentTurnResponse.ifBlank { errorText.orEmpty() },
                    isFailed = errorText != null,
                    usage = usage,
                    messageId = finalizedMessageId,
                )
            // Keep this turn's ordered tool/thinking/text trail visible for the rest of the
            // session (all kinds, including thinking) — in memory only, never written to
            // AgentRunRecord/the database.
            if (timeline.isNotEmpty()) {
                completedGroups = completedGroups + (finalizedMessageId to timeline.grouped())
            }

            val record = AgentRunRecord(
                workspaceId = workspace.id,
                conversationId = activeConversationId,
                title = conversationTitle ?: TitleGenerator.fallbackTitle(input),
                userInput = input,
                response = currentTurnResponse,
                error = errorText,
                agentSessionId = activeAgentSessionId,
                activityLog = timeline.collapsedEffectiveTools().filterIsInstance<TurnTimelineEntry.Tool>().map { it.toolCall.toolName },
                contentBlocks = timeline.collapsedEffectiveTools().filter { it is TurnTimelineEntry.Tool || it is TurnTimelineEntry.Token },
                inputTokens = usage?.inputTokens,
                outputTokens = usage?.outputTokens,
                totalTokens = usage?.totalTokens,
                durationMs = usage?.durationMs,
            )
            withContext(Dispatchers.IO) { historyRepo.save(record) }
            onRunCompleted()

            // Best-effort AI title refinement — only for the first turn of a conversation,
            // fired after the fallback title/record are already persisted so it never blocks
            // (or risks corrupting) the visible run.
            if (isNewConversation) {
                scope.launch(Dispatchers.IO) {
                    generateAndPersistAgentTitle(record.conversationId, input)
                }
            }
        }
    }

    private fun startElapsedTimer() {
        elapsedSeconds = 0
        elapsedTimerJob?.cancel()
        elapsedTimerJob = scope.launch {
            while (isRunning) {
                delay(1_000.milliseconds)
                elapsedSeconds++
            }
        }
    }

    /**
     * Best-effort async AI title generation for this conversation — mirrors
     * `ChatSessionService.createSession`'s "AI title" block. On success, persists the new
     * title across every turn of [conversationId] and broadcasts [AgentRunTitleUpdatedEvent]
     * so any listening UI (this view model, the history panel) can pick it up without a full
     * reload. Silently no-ops on any failure — the deterministic fallback title remains.
     */
    private suspend fun generateAndPersistAgentTitle(conversationId: String, firstMessage: String) {
        try {
            val prompt = """
                Generate a short, concise title (150 words max, no quotes, no punctuation at end)
                for a conversation that starts with this user message:
                "$firstMessage"
                Respond with only the title, nothing else.
            """.trimIndent()
            val utilityChatClient = AppContext.getInstance().createUtilityClient()
            val aiTitle = utilityChatClient.sendMessage(prompt).trim()

            if (aiTitle.isNotBlank()) {
                withContext(Dispatchers.IO) { historyRepo.updateTitleForConversation(conversationId, aiTitle) }
                EventBus.post(AgentRunTitleUpdatedEvent(conversationId = conversationId, newTitle = aiTitle))
                log.debug("AI title generated for agent conversation {}: {}", conversationId, aiTitle)
            }
        } catch (e: Exception) {
            log.debug("Failed to generate AI title for agent conversation {}: {}", conversationId, e.message)
        }
    }
}
