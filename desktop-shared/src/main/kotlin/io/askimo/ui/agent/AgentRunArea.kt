/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.agent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.askimo.core.agent.AgentUsage
import io.askimo.core.agent.ExternalAgent
import io.askimo.core.agent.ExternalAgentLoader
import io.askimo.core.agent.domain.AgentRunRecord
import io.askimo.core.agent.domain.SkillDefinition
import io.askimo.core.agent.domain.Workspace
import io.askimo.core.chat.dto.ChatMessageDTO
import io.askimo.core.chat.dto.ToolCallInfo
import io.askimo.core.chat.dto.ToolCallStatus
import io.askimo.core.chat.dto.TurnTimelineEntry
import io.askimo.core.chat.dto.TurnTimelineGroup
import io.askimo.core.chat.dto.collapsedEffectiveTools
import io.askimo.core.chat.dto.grouped
import io.askimo.core.db.DatabaseManager
import io.askimo.ui.chat.messageList
import io.askimo.ui.chat.turnTimelineView
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.keymap.KeyMapManager
import io.askimo.ui.common.keymap.onImeAwarePreviewKeyEvent
import io.askimo.ui.common.preferences.ApplicationPreferences
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppComponents.dropdownMenu
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

private enum class AgentCardState {
    NOT_INSTALLED,
    NEEDS_SETUP,
    READY,
}

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
 * Autonomous run area — user selects an agent and describes a goal;
 * the agent decides which skills to apply based on the full skills catalog
 * injected as its system prompt context.
 */
@Composable
internal fun agenticRunArea(
    skills: List<SkillDefinition>,
    workspace: Workspace,
    onRunCompleted: () -> Unit = {},
    onNavigateToSkillsSettings: () -> Unit = {},
    preloadRecord: AgentRunRecord? = null,
    onPreloadConsumed: () -> Unit = {},
    onConversationStateChanged: (Boolean) -> Unit = {},
    newConversationRequestKey: Int = 0,
) {
    val workDir = remember(workspace.path) { File(workspace.path) }
    val scope = rememberCoroutineScope()
    val historyRepo = remember { DatabaseManager.getInstance().getAgentRunHistoryRepository() }

    // ── Agent state ──────────────────────────────────────────────────────────
    val allAgents = remember { ExternalAgentLoader.all() }
    var agentStateVersion by remember { mutableStateOf(0) }
    var agentStateMap by remember { mutableStateOf(mapOf<String, AgentCardState>()) }
    LaunchedEffect(agentStateVersion) {
        val map = withContext(Dispatchers.IO) {
            allAgents.associate { agent ->
                agent.id to when {
                    !agent.isBinaryAvailable() -> AgentCardState.NOT_INSTALLED
                    !agent.isConfigured() -> AgentCardState.NEEDS_SETUP
                    else -> AgentCardState.READY
                }
            }
        }
        agentStateMap = map
    }

    var selectedAgentId by remember {
        mutableStateOf(ApplicationPreferences.getSelectedAgentId())
    }
    // Resolve selected agent; fall back to first ready one if saved pref is unavailable
    val selectedAgent = remember(selectedAgentId, allAgents, agentStateMap) {
        allAgents.firstOrNull { it.id == selectedAgentId && agentStateMap[it.id] == AgentCardState.READY }
            ?: allAgents.firstOrNull { agentStateMap[it.id] == AgentCardState.READY }
    }

    val selectedAgentRaw = remember(selectedAgentId, allAgents) {
        allAgents.firstOrNull { it.id == selectedAgentId } ?: allAgents.firstOrNull()
    }
    val selectedAgentReady = agentStateMap[selectedAgent?.id] == AgentCardState.READY
    var agentDropdownExpanded by remember { mutableStateOf(false) }

    // ── Run state ────────────────────────────────────────────────────────────
    // Single persistent chat input — mirrors chatView's inputText: the first send
    // starts a new conversation, every subsequent send is a follow-up in the same
    // conversation (continued via the agent's own session, see activeAgentSessionId).
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    var isRunning by remember { mutableStateOf(false) }

    // The conversation transcript — rendered with ChatView's own messageList/messageBubble
    // components so an agentic run looks and behaves exactly like a normal chat.
    var messages by remember { mutableStateOf<List<ChatMessageDTO>>(emptyList()) }

    // Ephemeral streaming state for the *current* turn only (mirrors ChatViewModel's
    // Chronologically-ordered timeline of everything the agent's stream reported for the
    // *current* turn — tool calls, thinking chunks, response text chunks, and lifecycle
    // status — in the exact order they arrived, so the UI can render them interleaved
    // instead of bucketing into fixed thinking/tools/text sections regardless of timing.
    var timeline by remember { mutableStateOf<List<TurnTimelineEntry>>(emptyList()) }
    // Completed turns' groups, keyed by that turn's finalized AI message id — kept in memory
    // for the current session; also reconstructable from `AgentRunRecord.contentBlocks` when
    // preloading persisted history (tool calls + text only — thinking/status stay session-only,
    // never persisted).
    var completedGroups by remember { mutableStateOf<Map<String, List<TurnTimelineGroup>>>(emptyMap()) }
    // True from the moment a run starts until the first token/tool-call/thinking chunk
    // arrives — mirrors ChatViewModel.isThinking (shows the "Thinking…" spinner row).
    var isWaitingForFirstEvent by remember { mutableStateOf(false) }

    // Accumulates this turn's raw response text — used only to build the AgentRunRecord
    // saved to history; the displayed transcript is `messages`.
    var currentTurnResponse by remember { mutableStateOf("") }

    var elapsedSeconds by remember { mutableStateOf(0) }
    var skillsListExpanded by remember { mutableStateOf(false) }

    // Native session id of the currently active conversation, captured from the agent's own
    // execution metadata (e.g. Claude Code's session_id). The external agent — not Askimo —
    // owns the conversation memory/context for this id; follow-up turns pass it back so the
    // agent's CLI can resume that same conversation (e.g. `claude --resume <id>`) instead of
    // Askimo reconstructing/replaying prior turns as text.
    var activeAgentSessionId by remember { mutableStateOf<String?>(null) }

    // Askimo-side identifier grouping every turn of the current conversation together in
    // history (see AgentRunRecord.conversationId) — independent of the agent's own session id
    // above, so history stays grouped even for agents that don't expose a session id.
    var activeConversationId by remember { mutableStateOf(UUID.randomUUID().toString()) }

    LaunchedEffect(isRunning) {
        if (isRunning) {
            elapsedSeconds = 0
            while (isRunning) {
                delay(1_000.milliseconds)
                elapsedSeconds++
            }
        }
    }

    // Build the combined skills catalog once; re-builds only when skills list changes
    val agenticSystemPrompt = remember(skills) { buildAgenticSystemPrompt(skills) }

    // ── Execution ────────────────────────────────────────────────────────────

    /**
     * Runs one turn of an agentic conversation.
     *
     * When [isNewConversation] is `true` this starts a brand-new conversation (fresh
     * transcript, no resume id). Otherwise it continues the conversation identified by
     * [activeAgentSessionId] via the agent's native resume mechanism — Askimo does not
     * replay prior [messages] back to the agent as text; the agent's own CLI session
     * owns that context.
     */
    fun executeAgentic(agent: ExternalAgent, input: String, isNewConversation: Boolean) {
        val resumeSessionId = if (isNewConversation) null else activeAgentSessionId
        isRunning = true
        isWaitingForFirstEvent = true
        timeline = emptyList()
        currentTurnResponse = ""
        if (isNewConversation) {
            messages = emptyList()
            activeAgentSessionId = null
            activeConversationId = UUID.randomUUID().toString()
        }

        val userMessage = ChatMessageDTO(
            id = "user-${System.nanoTime()}",
            content = input,
            isUser = true,
            timestamp = Instant.now(),
        )
        messages = messages + userMessage

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
                                    ToolCallInfo(toolName = toolName, status = ToolCallStatus.DONE, arguments = detail),
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
        }
    }

    fun sendMessage() {
        val agent = selectedAgent ?: return
        val text = inputText.text.trim()
        if (text.isBlank() || !selectedAgentReady || isRunning) return

        // First message in an empty transcript starts a new conversation;
        // any message after that continues it via the agent's own resume mechanism.
        val isNewConversation = messages.isEmpty()
        inputText = TextFieldValue("")
        executeAgentic(agent, text, isNewConversation = isNewConversation)
    }

    /** Resets the transcript so the next send starts a brand-new agent conversation. */
    fun startNewConversation() {
        inputText = TextFieldValue("")
        messages = emptyList()
        timeline = emptyList()
        completedGroups = emptyMap()
        currentTurnResponse = ""
        activeAgentSessionId = null
        activeConversationId = UUID.randomUUID().toString()
    }

    // Report "has active conversation" up to the header whenever it changes, so the header's
    // "New chat" button can enable/disable itself without owning any transcript state.
    LaunchedEffect(messages.isEmpty()) {
        onConversationStateChanged(messages.isNotEmpty())
    }

    // Parent-driven reset — the header's "New chat" button bumps this key instead of calling
    // into this composable directly, keeping this view the sole owner of `messages`/`timeline`.
    LaunchedEffect(newConversationRequestKey) {
        if (newConversationRequestKey > 0) startNewConversation()
    }

    LaunchedEffect(preloadRecord) {
        if (preloadRecord != null) {
            inputText = TextFieldValue("")

            val turns = withContext(Dispatchers.IO) {
                historyRepo.findByConversationId(preloadRecord.conversationId)
            }
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
            activeConversationId = preloadRecord.conversationId
            // Restore the agent's own session id so a follow-up on a re-opened history
            // entry continues that same conversation rather than starting a new one.
            activeAgentSessionId = turns.lastOrNull()?.agentSessionId
            isRunning = false
            isWaitingForFirstEvent = false
            onPreloadConsumed()
        }
    }

    // ── UI ───────────────────────────────────────────────────────────────────
    // Mirrors chatView's layout: a scrollable transcript takes the remaining
    // vertical space, with a single chat input pinned to the bottom.
    val transcriptScroll = rememberScrollState()

    // Auto-scroll to the bottom as new content streams in.
    LaunchedEffect(messages.size, messages.lastOrNull()?.content, timeline.size) {
        transcriptScroll.animateScrollTo(transcriptScroll.maxValue)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(transcriptScroll)
                    .padding(end = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = ThemePreferences.CONTENT_MAX_WIDTH)
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.medium),
                ) {
                    // ── Skills-as-context pill row ───────────────────────────────────────
                    if (skills.isNotEmpty()) {
                        var pillHeightPx by remember { mutableStateOf(0) }
                        Box {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier
                                    .clickable(onClick = { skillsListExpanded = true })
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .onGloballyPositioned { coordinates -> pillHeightPx = coordinates.size.height },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = Spacing.medium, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                                ) {
                                    Icon(
                                        Icons.Default.Extension,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = stringResource("agents.agentic.skills.available", skills.size),
                                        style = AppTextStyles.hint,
                                        modifier = Modifier.weight(1f),
                                    )
                                    val maxVisible = 4
                                    skills.take(maxVisible).forEach { skill ->
                                        Surface(
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
                                            shape = MaterialTheme.shapes.extraSmall,
                                        ) {
                                            Text(
                                                text = skill.name,
                                                style = AppTextStyles.hint,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                    if (skills.size > maxVisible) {
                                        Text(
                                            text = "+${skills.size - maxVisible}",
                                            style = AppTextStyles.hint,
                                        )
                                    }
                                    Icon(
                                        Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            if (skillsListExpanded) {
                                val skillsListState = rememberLazyListState()
                                Popup(
                                    alignment = Alignment.TopStart,
                                    offset = IntOffset(0, pillHeightPx + with(LocalDensity.current) { 4.dp.roundToPx() }),
                                    onDismissRequest = { skillsListExpanded = false },
                                    properties = PopupProperties(focusable = true),
                                ) {
                                    MaterialTheme(colorScheme = AppComponents.popupColorScheme()) {
                                        Surface(
                                            modifier = Modifier.width(380.dp),
                                            color = AppComponents.popupContainerColor(),
                                            border = AppComponents.popupBorderStroke(),
                                            shape = RoundedCornerShape(8.dp),
                                            tonalElevation = AppComponents.popupSurfaceTonalElevation,
                                            shadowElevation = AppComponents.popupElevation,
                                        ) {
                                            Column {
                                                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                                                    LazyColumn(
                                                        state = skillsListState,
                                                        modifier = Modifier.fillMaxWidth(),
                                                        contentPadding = PaddingValues(
                                                            top = Spacing.extraSmall,
                                                            bottom = Spacing.extraSmall,
                                                            end = 10.dp,
                                                        ),
                                                    ) {
                                                        items(skills) { skill ->
                                                            Row(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .clickable(onClick = { skillsListExpanded = false })
                                                                    .pointerHoverIcon(PointerIcon.Hand)
                                                                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                                                                verticalAlignment = Alignment.Top,
                                                                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                                                            ) {
                                                                Icon(
                                                                    Icons.Default.Extension,
                                                                    contentDescription = null,
                                                                    modifier = Modifier.size(16.dp).padding(top = 2.dp),
                                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                )
                                                                Column {
                                                                    Text(
                                                                        text = skill.name,
                                                                        style = AppTextStyles.body,
                                                                        maxLines = 1,
                                                                        overflow = TextOverflow.Ellipsis,
                                                                    )
                                                                    if (skill.description.isNotBlank()) {
                                                                        Text(
                                                                            text = skill.description,
                                                                            style = AppTextStyles.hint,
                                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                            maxLines = 2,
                                                                            overflow = TextOverflow.Ellipsis,
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                    VerticalScrollbar(
                                                        adapter = rememberScrollbarAdapter(skillsListState),
                                                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp),
                                                        style = AppComponents.scrollbarStyle(),
                                                    )
                                                }

                                                // ── Sticky footer — always visible, outside the scroll area ──
                                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable(
                                                            onClick = {
                                                                skillsListExpanded = false
                                                                onNavigateToSkillsSettings()
                                                            },
                                                        )
                                                        .pointerHoverIcon(PointerIcon.Hand)
                                                        .padding(horizontal = Spacing.medium, vertical = Spacing.medium),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(
                                                        text = stringResource("agents.view.manage"),
                                                        style = AppTextStyles.body,
                                                        color = MaterialTheme.colorScheme.primary,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.medium, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                            ) {
                                Icon(
                                    Icons.Default.Extension,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                                Text(
                                    text = stringResource("agents.agentic.no.skills.hint"),
                                    style = AppTextStyles.hint,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = onNavigateToSkillsSettings,
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                ) {
                                    Text(text = stringResource("agents.view.manage"), style = AppTextStyles.hint)
                                }
                            }
                        }
                    }

                    // ── Agent setup hint (needs auth) ────────────────────────────────────
                    if (agentStateMap[selectedAgentRaw?.id] == AgentCardState.NEEDS_SETUP) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Column(modifier = Modifier.padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                                Text(
                                    text = selectedAgentRaw?.configurationHint ?: "",
                                    style = AppTextStyles.caption,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }

                    // ── Conversation transcript — same components as ChatView ────────────
                    if (messages.isNotEmpty() || isRunning) {
                        messageList(
                            messages = messages,
                            isThinking = isWaitingForFirstEvent,
                            thinkingElapsedSeconds = elapsedSeconds,
                            completedGroupsByMessageId = completedGroups,
                        )
                        // Live, chronologically-ordered view of the *current* turn — tool
                        // calls, thinking, response text, and status, interleaved exactly as
                        // the agent's stream reported them, with consecutive same-kind items
                        // collapsed into one group. Replaced by the finalized bubble in
                        // `messages` once the run completes.
                        if (isRunning && timeline.isNotEmpty()) {
                            turnTimelineView(timeline.grouped())
                        }
                    } else {
                        // ── Empty-state hint — shown before the first message is sent ────
                    }
                }
            }

            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(transcriptScroll),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp),
                style = AppComponents.scrollbarStyle(),
            )
        }

        // ── Persistent chat input — pinned to the bottom ────────────────────────
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = ThemePreferences.CONTENT_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 36.dp, top = 8.dp, bottom = 16.dp),
            ) {
                var modelDropdownExpanded by remember { mutableStateOf(false) }

                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text(stringResource("agents.agentic.goal.placeholder")) },
                        enabled = !isRunning,
                        modifier = Modifier.fillMaxWidth()
                            .onImeAwarePreviewKeyEvent(inputText.composition) { keyEvent ->
                                when (KeyMapManager.handleKeyEvent(keyEvent)) {
                                    KeyMapManager.AppShortcut.NEW_LINE -> {
                                        val cursor = inputText.selection.start
                                        val newText = inputText.text.substring(0, cursor) + "\n" + inputText.text.substring(cursor)
                                        inputText = TextFieldValue(text = newText, selection = TextRange(cursor + 1))
                                        true
                                    }

                                    KeyMapManager.AppShortcut.SEND_MESSAGE -> {
                                        if (inputText.text.isNotBlank() && selectedAgentReady && !isRunning) {
                                            sendMessage()
                                        }
                                        true
                                    }

                                    else -> false
                                }
                            },
                        minLines = 4,
                        maxLines = 10,
                        colors = AppComponents.outlinedTextFieldColors(),
                    )

                    // ── Model selector — overlaid inside the field, bottom-left ────────
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(bottom = 6.dp, start = 8.dp),
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                            modifier = Modifier
                                .clickable(enabled = !isRunning, onClick = { modelDropdownExpanded = true })
                                .pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = stringResource("agents.agentic.model.default"),
                                    style = AppTextStyles.hint,
                                )
                                Icon(
                                    Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            }
                        }
                        dropdownMenu(
                            expanded = modelDropdownExpanded,
                            onDismissRequest = { modelDropdownExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource("agents.agentic.model.default"),
                                        style = AppTextStyles.body,
                                    )
                                },
                                onClick = { modelDropdownExpanded = false },
                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                            )
                        }
                    }

                    // ── Agent picker + Send — overlaid bottom-right ──
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 6.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // Agent picker pill
                        Box {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
                                modifier = Modifier
                                    .clickable(enabled = allAgents.isNotEmpty() && !isRunning, onClick = { agentDropdownExpanded = true })
                                    .pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(
                                                color = when (agentStateMap[selectedAgent?.id]) {
                                                    AgentCardState.READY -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f)
                                                    AgentCardState.NEEDS_SETUP -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                                                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                                                },
                                                shape = MaterialTheme.shapes.extraSmall,
                                            ),
                                    )
                                    Text(
                                        text = selectedAgent?.name ?: stringResource("agents.view.no.agent"),
                                        style = AppTextStyles.hint,
                                    )
                                    Icon(
                                        Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    )
                                }
                            }
                            dropdownMenu(expanded = agentDropdownExpanded, onDismissRequest = { agentDropdownExpanded = false }) {
                                allAgents.forEach { agent ->
                                    val agentState = agentStateMap[agent.id] ?: AgentCardState.NOT_INSTALLED
                                    val agentReady = agentState == AgentCardState.READY
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(7.dp)
                                                        .background(
                                                            color = when (agentState) {
                                                                AgentCardState.READY -> MaterialTheme.colorScheme.tertiary
                                                                AgentCardState.NEEDS_SETUP -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f)
                                                                AgentCardState.NOT_INSTALLED -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                                                            },
                                                            shape = MaterialTheme.shapes.extraSmall,
                                                        ),
                                                )
                                                Text(
                                                    text = agent.name,
                                                    style = AppTextStyles.body,
                                                )
                                                if (!agentReady) {
                                                    Text(
                                                        text = stringResource("agents.view.agent.not.installed"),
                                                        style = AppTextStyles.hint,
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            selectedAgentId = agent.id
                                            ApplicationPreferences.setSelectedAgentId(agent.id)
                                            agentDropdownExpanded = false
                                            agentStateVersion++
                                            // A resume/session id is only meaningful to the CLI that produced it.
                                            activeAgentSessionId = null
                                        },
                                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                    )
                                }
                            }
                        }

                        // Send button
                        IconButton(
                            onClick = { sendMessage() },
                            enabled = selectedAgentReady && inputText.text.isNotBlank() && !isRunning,
                            colors = AppComponents.primaryIconButtonColors(),
                            modifier = Modifier
                                .size(36.dp)
                                .pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                imageVector = if (isRunning) Icons.Default.Refresh else Icons.Default.PlayArrow,
                                contentDescription = if (isRunning) {
                                    stringResource("agents.view.running")
                                } else {
                                    stringResource("agents.agentic.run")
                                },
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── System prompt builder ──────────────────────────────────────────────────

/**
 * Builds a combined system prompt that injects all available skills as a
 * named catalog. The agent reads the goal and applies the most relevant skill(s).
 */
internal fun buildAgenticSystemPrompt(skills: List<SkillDefinition>): String = buildString {
    if (skills.isEmpty()) {
        appendLine("You are an autonomous assistant. Accomplish the user's goal using your best judgment.")
        return@buildString
    }
    appendLine("You are an autonomous assistant with access to the following specialized skill sets.")
    appendLine("Review the user's goal and autonomously select and apply the most relevant skill(s) to accomplish it.")
    appendLine("You may combine multiple skills when the goal spans several areas.")
    appendLine()
    appendLine("## Available Skills")
    appendLine()
    skills.forEach { skill ->
        append("### ")
        appendLine(skill.name)
        if (skill.description.isNotBlank()) {
            append("> ")
            appendLine(skill.description)
            appendLine()
        }
        appendLine(skill.content.trim())
        appendLine()
        appendLine("---")
        appendLine()
    }
}
