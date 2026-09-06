/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.askimo.core.agent.AgentCancelledException
import io.askimo.core.agent.AgentReadiness
import io.askimo.core.agent.AgentUsage
import io.askimo.core.agent.ExternalAgent
import io.askimo.core.agent.ExternalAgentLoader
import io.askimo.core.agent.domain.AgentRunRecord
import io.askimo.core.agent.domain.SkillDefinition
import io.askimo.core.agent.domain.Workspace
import io.askimo.core.agent.dto.AgentTurnMessageDTO
import io.askimo.core.agent.repository.AgentRunHistoryRepository
import io.askimo.core.chat.TitleGenerator
import io.askimo.core.chat.domain.SESSION_TITLE_MAX_LENGTH
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

private val log = currentFileLogger()

/** Ensures a (possibly empty) streaming AI placeholder message exists, so tool/thinking chips can render before the first token arrives. */
private fun List<AgentTurnMessageDTO>.ensureStreamingAiMessage(): List<AgentTurnMessageDTO> {
    if (any { !it.isUser && it.id == null }) return this
    return this + AgentTurnMessageDTO(id = null, content = "", isUser = false, timestamp = null)
}

/** Finalizes the trailing streaming AI message: assigns a stable id and marks failure/content/usage. */
private fun List<AgentTurnMessageDTO>.finalizeStreamingAiMessage(
    finalContent: String,
    isFailed: Boolean,
    usage: AgentUsage? = null,
    messageId: String = "ai-${System.nanoTime()}",
    isCancelled: Boolean = false,
): List<AgentTurnMessageDTO> {
    val idx = indexOfLast { !it.isUser && it.id == null }
    if (idx < 0) return this
    val updated = this[idx].copy(
        id = messageId,
        content = finalContent,
        timestamp = Instant.now(),
        // A cancelled turn is never also "failed" — it's a deliberate user action, not an
        // error, and should render with a neutral "Cancelled" label instead of the retry icon.
        isFailed = isFailed && !isCancelled,
        isCancelled = isCancelled,
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
 * One instance is created per workspace (see `agenticRunArea`'s `remember(workspace.id)`)
 * and owns its own [CoroutineScope]; callers must invoke [close] when discarding an instance.
 */
internal class AgentRunViewModel(
    private val workspace: Workspace,
    private val skills: List<SkillDefinition>,
    private val historyRepo: AgentRunHistoryRepository = DatabaseManager.getInstance().getAgentRunHistoryRepository(),
    private val onRunCompleted: () -> Unit = {},
) {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val workDir: File = File(workspace.path)

    // ── Agent selection ──────────────────────────────────────────────────────
    val allAgents: List<ExternalAgent> = ExternalAgentLoader.all()

    var agentStateMap by mutableStateOf(allAgents.associate { agent -> agent.id to agent.readiness })
        private set

    var selectedAgentId by mutableStateOf(ApplicationPreferences.getSelectedAgentId())
        private set

    /**
     * True once the active conversation has produced at least one turn — either a fresh
     * run or a re-opened history entry (see [preload]). While locked, [selectAgent] refuses
     * to switch agents so a conversation always continues with the same agent it started
     * with; only [startNewConversation] clears the lock.
     */
    var isAgentSelectionLocked by mutableStateOf(false)
        private set

    /** Resolved selected agent; falls back to the first ready one if the saved pref is unavailable. */
    val selectedAgent: ExternalAgent?
        get() = allAgents.firstOrNull { it.id == selectedAgentId && agentStateMap[it.id] == AgentReadiness.READY }
            ?: allAgents.firstOrNull { agentStateMap[it.id] == AgentReadiness.READY }

    /** The raw selected agent (or the first available one), regardless of readiness — used for setup hints. */
    val selectedAgentRaw: ExternalAgent?
        get() = allAgents.firstOrNull { it.id == selectedAgentId } ?: allAgents.firstOrNull()

    val selectedAgentReady: Boolean
        get() = agentStateMap[selectedAgent?.id] == AgentReadiness.READY

    /** Re-checks binary availability / auth for every known agent (off the UI thread). */
    fun refreshAgentStates() {
        scope.launch {
            agentStateMap = withContext(Dispatchers.IO) {
                allAgents.associate { agent -> agent.id to agent.readiness }
            }
        }
    }

    fun selectAgent(agentId: String) {
        if (isAgentSelectionLocked) return
        selectedAgentId = agentId
        ApplicationPreferences.setSelectedAgentId(agentId)
        // A resume/session id is only meaningful to the CLI that produced it.
        activeAgentSessionId = null
        refreshAgentStates()
    }

    /**
     * Restores the agent a re-opened conversation was actually run with, without persisting
     * it as the user's global preference (unlike [selectAgent]) — this is a read-only
     * reconstruction of history, not a deliberate choice by the user.
     */
    private fun restoreAgentForPreload(agentId: String?) {
        if (agentId != null && allAgents.any { it.id == agentId }) {
            selectedAgentId = agentId
        }
    }

    // ── Conversation state ───────────────────────────────────────────────────
    var messages by mutableStateOf(listOf<AgentTurnMessageDTO>())
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

    /**
     * Guards every read-modify-write of [timeline] (in [completeRunningTools] and each
     * append below). [ExternalAgent.run]'s onToken/onToolCall/onStatus/onThinking callbacks
     * fire from whatever IO thread is reading the agent process's stdout, and each one spawns
     * its own `scope.launch { }` coroutine on [Dispatchers.Default] — a multi-threaded pool
     * (this project has no `Dispatchers.Main` artifact on the classpath, see
     * [io.askimo.ui.session.SessionManager]). Under fast streaming, two such coroutines can
     * genuinely run concurrently on different pool threads; without this lock they'd race on
     * `timeline`'s unsynchronized read-modify-write and one could silently clobber the other's
     * write, dropping an entry. Mirrors [io.askimo.ui.session.SessionManager]'s own
     * `mutex.withLock` guard around its equivalent timeline mutations.
     */
    private val timelineMutex = Mutex()

    /**
     * Monotonically-increasing id of the turn currently allowed to mutate [timeline].
     * [executeAgentic] captures the value returned by [bumpTurnId] at the start of a turn and
     * closes over it in every stream callback (onToken/onToolCall/onStatus/onThinking); any
     * reset point ([startNewConversation], [preload], the next [executeAgentic]) bumps this
     * counter first. This lets [appendTimelineEntry] reject writes from a *stale* turn's
     * callback — a callback that was already in flight (queued on `scope.launch`) when the
     * reset happened — which plain mutual exclusion alone cannot prevent: a mutex only makes
     * concurrent writes atomic, it says nothing about a late write from a superseded turn
     * landing after a reset. Backed by an [java.util.concurrent.atomic.AtomicLong] (rather than
     * a plain `var`) since [bumpTurnId] is called synchronously from the UI thread while
     * [appendTimelineEntry] reads it from arbitrary [Dispatchers.Default] pool threads, and a
     * plain field gives no cross-thread visibility guarantee.
     */
    private val currentTurnId = java.util.concurrent.atomic.AtomicLong(0L)

    /** Advances [currentTurnId] and returns the new value, invalidating every earlier turn's in-flight [appendTimelineEntry] calls. */
    private fun bumpTurnId(): Long = currentTurnId.incrementAndGet()

    /**
     * Flips every still-[ToolCallStatus.RUNNING] tool entry in [timeline] to
     * [ToolCallStatus.DONE], preserving position/[ToolCallInfo.startedAtMillis]. Called right
     * before appending any other kind of event (token/thinking/status/next tool call), since
     * that arrival is the only signal [ExternalAgent.run] gives us that a tool call has
     * finished — it only reports a call at the moment it's invoked. Mirrors
     * [io.askimo.ui.session.SessionManager]'s markToolRunning/markToolDone pair for regular
     * chat, adapted to this single completion signal.
     *
     * Callers must hold [timelineMutex] — see [appendTimelineEntry].
     */
    private fun completeRunningTools() {
        if (timeline.none { it is TurnTimelineEntry.Tool && it.toolCall.status == ToolCallStatus.RUNNING }) return
        timeline = timeline.map { entry ->
            if (entry is TurnTimelineEntry.Tool && entry.toolCall.status == ToolCallStatus.RUNNING) {
                TurnTimelineEntry.Tool(entry.toolCall.copy(status = ToolCallStatus.DONE))
            } else {
                entry
            }
        }
    }

    /**
     * Atomically completes any still-running tool entry and appends [entry] to [timeline],
     * under [timelineMutex] — the single choke point every stream callback (onToken/
     * onToolCall/onStatus/onThinking) goes through, so concurrent callback coroutines can
     * never interleave their read-modify-write of [timeline].
     *
     * [turnId] must be the id captured by the caller's turn (see [bumpTurnId]); if the turn has
     * since been superseded (a reset or a new turn bumped [currentTurnId]) the write is
     * silently dropped instead of corrupting the now-unrelated timeline.
     */
    private suspend fun appendTimelineEntry(turnId: Long, entry: TurnTimelineEntry) {
        timelineMutex.withLock {
            if (turnId != currentTurnId.get()) return@withLock
            completeRunningTools()
            timeline = timeline + entry
        }
    }

    /** Resets [timeline] for a new turn/conversation, under [timelineMutex] — pairs with a preceding (or immediately following) [bumpTurnId] call so stale callbacks from the previous turn are rejected regardless of exactly when this clear runs. */
    private suspend fun resetTimeline() {
        timelineMutex.withLock {
            timeline = emptyList()
        }
    }

    // The agent instance actually running the current turn, and the coroutine `Job` executing
    // it — both needed by `cancelRun()`. `runningAgent` may differ transiently from
    // `selectedAgent` only in theory (selection is locked once a turn starts, see
    // `isAgentSelectionLocked`), but kept separate so cancellation never depends on selection
    // state still matching what's actually in flight.
    private var runningAgent: ExternalAgent? = null
    private var currentRunJob: Job? = null

    // Safety-net timer started by cancelRun() — forces UI state back to "not running" if
    // executeAgentic's coroutine somehow never observes the cancellation and finalizes on its
    // own (e.g. the OS process refuses to die). Cancelled once the run finishes normally.
    private var cancelTimeoutJob: Job? = null

    /**
     * Best-effort request to stop the turn currently in flight. Delegates to
     * [ExternalAgent.cancel] (kills the OS process) off the calling thread, since that can
     * block for a few seconds. No-op if nothing is running.
     *
     * Does **not** cancel [currentRunJob] directly — that would abort [executeAgentic]'s
     * coroutine before it can set [isRunning] to `false` and finalize/persist the turn,
     * leaving the UI stuck as "running" forever. Instead, killing the process makes `run()`
     * return a failure wrapping [AgentCancelledException], which [executeAgentic] handles via
     * its normal completion path (tagged `isCancelled = true`). The timeout below is only a
     * last-resort fallback in case that path is somehow never reached.
     */
    fun cancelRun() {
        if (!isRunning) return
        val agent = runningAgent ?: return
        log.debug("Cancel requested for active agent run (agent={})", agent.id)
        scope.launch(Dispatchers.IO) {
            agent.cancel()
        }
        cancelTimeoutJob?.cancel()
        cancelTimeoutJob = scope.launch {
            delay(8_000.milliseconds)
            if (isRunning) {
                log.warn("Agent run did not finalize within 8s of cancel(); forcing job cancellation")
                currentRunJob?.cancel()
                // Invalidate this turn so any callback coroutine still in flight (not a child
                // of currentRunJob — each is its own top-level `scope.launch`, so cancelling
                // currentRunJob above does not stop them) can no longer append to `timeline`
                // once we read it below.
                bumpTurnId()
                // Freeze any still-running tool entry as DONE, then capture the snapshot while
                // still holding the lock — reading `timeline` in a separate statement after
                // release would leave a window for one of those still-in-flight (but now
                // rejected-by-turnId) callbacks to have appended an entry after this point.
                val finalTimeline = timelineMutex.withLock {
                    completeRunningTools()
                    timeline
                }
                val finalizedMessageId = "ai-${System.nanoTime()}"
                messages = messages
                    .ensureStreamingAiMessage()
                    .finalizeStreamingAiMessage(
                        finalContent = currentTurnResponse,
                        isFailed = false,
                        messageId = finalizedMessageId,
                        isCancelled = true,
                    )
                if (finalTimeline.isNotEmpty()) {
                    completedGroups = completedGroups + (finalizedMessageId to finalTimeline.grouped())
                }
                val forcedConversationId = activeConversationId
                val forcedResponse = currentTurnResponse
                scope.launch(Dispatchers.IO) {
                    historyRepo.save(
                        AgentRunRecord(
                            workspaceId = workspace.id,
                            conversationId = forcedConversationId,
                            title = conversationTitle ?: TitleGenerator.fallbackTitle(forcedResponse),
                            userInput = messages.lastOrNull { it.isUser }?.content.orEmpty(),
                            response = forcedResponse,
                            error = null,
                            isCancelled = true,
                            agentId = agent.id,
                            agentSessionId = activeAgentSessionId,
                            activityLog = finalTimeline.collapsedEffectiveTools().filterIsInstance<TurnTimelineEntry.Tool>().map { it.toolCall.toolName },
                            contentBlocks = finalTimeline.collapsedEffectiveTools().filter { it is TurnTimelineEntry.Tool || it is TurnTimelineEntry.Token },
                        ),
                    )
                    onRunCompleted()
                }
                isRunning = false
                isWaitingForFirstEvent = false
                runningAgent = null
                currentRunJob = null
            }
            // Clear this job reference regardless of whether the branch above fired, so state
            // stays consistent with the normal completion path (which also nulls it out) instead
            // of leaving a reference to an already-completed job hanging around.
            cancelTimeoutJob = null
        }
    }

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
        completedGroups = emptyMap()
        currentTurnResponse = ""
        activeAgentSessionId = null
        activeConversationId = UUID.randomUUID().toString()
        conversationTitle = null
        isAgentSelectionLocked = false
        // Bumped synchronously (not suspend) so any callback still in flight from a
        // superseded turn is rejected by appendTimelineEntry's turnId check even before this
        // instance's own coroutine below actually clears the list.
        bumpTurnId()
        // Not a suspend function (called directly from Compose click handlers), so the
        // mutex-guarded clear is dispatched onto this instance's own scope rather than awaited.
        scope.launch { resetTimeline() }
    }

    /** Reconstructs the full multi-turn conversation for a re-opened history entry. */
    suspend fun preload(record: AgentRunRecord) {
        val turns = withContext(Dispatchers.IO) { historyRepo.findByConversationId(record.conversationId) }
        messages = turns.flatMap { r ->
            listOf(
                AgentTurnMessageDTO(
                    id = "${r.id}-user",
                    content = r.userInput,
                    isUser = true,
                    timestamp = r.createdAt,
                ),
                AgentTurnMessageDTO(
                    id = "${r.id}-ai",
                    content = r.response.ifBlank { r.error.orEmpty() },
                    isUser = false,
                    timestamp = r.createdAt,
                    isFailed = r.error != null,
                    isCancelled = r.isCancelled,
                    inputTokens = r.inputTokens,
                    outputTokens = r.outputTokens,
                    totalTokens = r.totalTokens,
                    durationMs = r.durationMs,
                ),
            )
        }
        bumpTurnId()
        resetTimeline()
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
        // Restore (without persisting to prefs) the exact agent this conversation was run
        // with, then lock the picker — a history conversation must keep using that agent.
        restoreAgentForPreload(turns.lastOrNull()?.agentId ?: turns.firstOrNull()?.agentId)
        isAgentSelectionLocked = true
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
        // Built per-agent (not once for all agents): for agents with native skill discovery
        // (e.g. Claude Code) this collapses to a one-line instruction, since materializeSkill
        // below already makes every skill discoverable via the agent's own Skill tool, right
        // alongside its own pre-installed skills — no catalog/content duplication needed.
        val systemPrompt = buildAgenticSystemPrompt(skills, agentHasNativeSkillDiscovery = agent.supportsNativeSkillDiscovery)
        isRunning = true
        isWaitingForFirstEvent = true
        currentTurnResponse = ""
        // Bumped synchronously (this function isn't suspend) before any callback closure below
        // is created, so a callback still in flight from the previous turn is rejected by
        // appendTimelineEntry's turnId check the moment it runs — even though the actual
        // `timeline` clear happens slightly later, inside the launched coroutine below.
        val turnId = bumpTurnId()
        if (isNewConversation) {
            messages = emptyList()
            activeAgentSessionId = null
            activeConversationId = UUID.randomUUID().toString()
            // Instant, deterministic title — never blank. May be replaced by a short
            // AI-generated title once generateAndPersistAgentTitle() completes below.
            conversationTitle = TitleGenerator.fallbackTitle(input)
        }

        val userMessage = AgentTurnMessageDTO(
            id = "user-${System.nanoTime()}",
            content = input,
            isUser = true,
            timestamp = Instant.now(),
        )
        messages = messages + userMessage
        // Lock the agent picker the moment this conversation has a turn in it — switching
        // agents mid-conversation would break session resume for the CLI actually running it.
        isAgentSelectionLocked = true

        startElapsedTimer()

        runningAgent = agent
        currentRunJob = scope.launch {
            // Clear the timeline for this turn now that turnId is bumped — any pending
            // callback from a superseded turn will fail the turnId check above regardless of
            // whether this clear has run yet.
            resetTimeline()
            val result = withContext(Dispatchers.IO) {
                // Materialize every skill in the catalog into the agent's own native
                // skill-discovery location (e.g. Claude Code's `.claude/skills/<name>/`) so its
                // built-in Skill tool can find and invoke them — not just rely on the full skill
                // text injected into systemPrompt below, which the agent can only read as
                // background instructions, not "run" as a discrete skill.
                val materialized = skills.map { skill -> agent.materializeSkill(skill, workDir) }
                try {
                    agent.runTracked(
                        systemPrompt = systemPrompt,
                        userInput = input,
                        workDir = workDir,
                        resumeSessionId = resumeSessionId,
                        onToken = { token ->
                            // Guarded by the same turnId check as the timeline append below —
                            // without it, a stale callback from a turn already invalidated by
                            // bumpTurnId() (e.g. a forced cancel-timeout) could keep silently
                            // accumulating post-cancellation text into currentTurnResponse even
                            // though its timeline writes are correctly rejected, so the
                            // finalized/persisted response text would diverge from the frozen
                            // finalTimeline snapshot.
                            if (turnId == currentTurnId.get()) {
                                currentTurnResponse += token
                            }
                            scope.launch {
                                isWaitingForFirstEvent = false
                                appendTimelineEntry(turnId, TurnTimelineEntry.Token(token))
                            }
                        },
                        onToolCall = { toolName, detail ->
                            scope.launch {
                                isWaitingForFirstEvent = false
                                appendTimelineEntry(
                                    turnId,
                                    TurnTimelineEntry.Tool(
                                        ToolCallInfo.truncated(toolName = toolName, status = ToolCallStatus.RUNNING, arguments = detail),
                                    ),
                                )
                            }
                        },
                        onStatus = { status ->
                            scope.launch {
                                isWaitingForFirstEvent = false
                                appendTimelineEntry(turnId, TurnTimelineEntry.Status(status))
                            }
                        },
                        onThinking = { chunk ->
                            scope.launch {
                                isWaitingForFirstEvent = false
                                appendTimelineEntry(turnId, TurnTimelineEntry.Thinking(chunk))
                            }
                        },
                    )
                } finally {
                    // Cleanup runs regardless of whether the turn completed, failed, or was
                    // cancelled — it's independent of the agent process's outcome.
                    materialized.forEach { it.close() }
                }
            }
            // Update state on the same coroutine, right after the run completes, so the
            // error (if any) is guaranteed to be captured before we build the history record
            // below — no race with a separately-launched coroutine.
            val exception = result.exceptionOrNull()
            val isCancelled = exception is AgentCancelledException
            // A cancellation is a deliberate user action, not a failure — never surface it via
            // the same error path a genuine failure would take.
            val errorText = exception?.message?.takeIf { !isCancelled }
            isRunning = false
            isWaitingForFirstEvent = false
            // Ensure the last tool call of the turn ends up DONE even if no trailing event
            // arrived to flip it, and capture the final snapshot while still holding the lock —
            // reading `timeline` in a separate statement after release would leave a window for
            // a still-in-flight callback coroutine (from this same turn) to append an entry
            // that arrived after completion, corrupting the "finalized" record below.
            val finalTimeline = timelineMutex.withLock {
                completeRunningTools()
                timeline
            }
            runningAgent = null
            currentRunJob = null
            // Normal completion path reached — the cancelRun() safety net (if one was started)
            // is no longer needed.
            cancelTimeoutJob?.cancel()
            cancelTimeoutJob = null

            // Capture (or keep) the session id so the next follow-up turn can resume this
            // exact conversation — including after a cancellation, since the underlying CLI
            // session may already have been established before the process was killed. Falls
            // back to the id we resumed with in case this agent's CLI doesn't re-emit one on
            // every turn.
            activeAgentSessionId = agent.lastExecutionSessionId ?: resumeSessionId

            // Best-effort token usage / duration reported by the agent's own stream for this
            // turn (e.g. Claude's/Antigravity's "result" event). Null fields are hidden by
            // MessageComponents' token-usage row — not every agent (e.g. Codex) exposes this.
            val usage = agent.lastExecutionUsage

            // Guarantee a bubble exists even if the run failed before any token/tool/thinking
            // event arrived, then finalize it (stable id, failure/cancelled flag) so it stops
            // "streaming".
            val finalizedMessageId = "ai-${System.nanoTime()}"
            messages = messages
                .ensureStreamingAiMessage()
                .finalizeStreamingAiMessage(
                    finalContent = currentTurnResponse.ifBlank { errorText.orEmpty() },
                    isFailed = errorText != null,
                    usage = usage,
                    messageId = finalizedMessageId,
                    isCancelled = isCancelled,
                )
            // Keep this turn's ordered tool/thinking/text trail visible for the rest of the
            // session (all kinds, including thinking) — in memory only, never written to
            // AgentRunRecord/the database.
            if (finalTimeline.isNotEmpty()) {
                completedGroups = completedGroups + (finalizedMessageId to finalTimeline.grouped())
            }

            val record = AgentRunRecord(
                workspaceId = workspace.id,
                conversationId = activeConversationId,
                title = conversationTitle ?: TitleGenerator.fallbackTitle(input),
                userInput = input,
                response = currentTurnResponse,
                error = errorText,
                isCancelled = isCancelled,
                agentId = agent.id,
                agentSessionId = activeAgentSessionId,
                activityLog = finalTimeline.collapsedEffectiveTools().filterIsInstance<TurnTimelineEntry.Tool>().map { it.toolCall.toolName },
                contentBlocks = finalTimeline.collapsedEffectiveTools().filter { it is TurnTimelineEntry.Tool || it is TurnTimelineEntry.Token },
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

            val aiTitle = utilityChatClient.sendMessage(prompt)
                .trim()
                .replace("\n", " ")
                .take(SESSION_TITLE_MAX_LENGTH)

            if (aiTitle.isNotBlank()) {
                withContext(Dispatchers.IO) { historyRepo.updateTitleForConversation(conversationId, aiTitle) }
                EventBus.post(AgentRunTitleUpdatedEvent(conversationId = conversationId, newTitle = aiTitle))
                log.debug("AI title generated for agent conversation {}: {}", conversationId, aiTitle)
            }
        } catch (e: Exception) {
            log.debug("Failed to generate AI title for agent conversation {}: {}", conversationId, e.message)
        }
    }

    /**
     * Cancels every coroutine this instance owns (title events, in-flight runs, the elapsed
     * timer). Owns its own [CoroutineScope] instead of the composable's `rememberCoroutineScope()`
     * — that scope outlives a single instance, so reusing it would leak coroutines whenever a
     * workspace switch replaces this instance (see `agenticRunArea`'s `remember(workspace.id)`).
     * Callers must invoke this on disposal, e.g. `DisposableEffect(viewModel) { onDispose { viewModel.close() } }`.
     */
    fun close() {
        // Kill any orphaned OS process, same as cancelRun(). Runs on a plain thread (not
        // scope.launch, since scope.cancel() below would race with/abandon it) because this is
        // invoked synchronously from onDispose on the UI thread, and cancel() can block a few
        // seconds — fire-and-forget, we don't wait for it.
        runningAgent?.let { agent ->
            Thread({ agent.cancel() }, "agent-run-close-cancel").apply { isDaemon = true }.start()
        }
        scope.cancel()
    }
}
