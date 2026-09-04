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
import androidx.compose.runtime.rememberUpdatedState
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
import io.askimo.core.agent.domain.AgentRunRecord
import io.askimo.core.agent.domain.SkillDefinition
import io.askimo.core.agent.domain.Workspace
import io.askimo.core.chat.dto.grouped
import io.askimo.ui.chat.messageList
import io.askimo.ui.chat.turnTimelineView
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.keymap.KeyMapManager
import io.askimo.ui.common.keymap.onImeAwarePreviewKeyEvent
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppComponents.dropdownMenu
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.themedTooltip

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
    val scope = rememberCoroutineScope()
    val latestOnRunCompleted = rememberUpdatedState(onRunCompleted)
    val viewModel = remember(workspace.id) {
        AgentRunViewModel(
            workspace = workspace,
            skills = skills,
            scope = scope,
            onRunCompleted = { latestOnRunCompleted.value() },
        )
    }

    // ── Local, pure-UI state (not part of AgentRunViewModel) ────────────────
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    var agentDropdownExpanded by remember { mutableStateOf(false) }
    var skillsListExpanded by remember { mutableStateOf(false) }

    fun sendMessage() {
        val agent = viewModel.selectedAgent ?: return
        val text = inputText.text.trim()
        if (text.isBlank() || !viewModel.selectedAgentReady || viewModel.isRunning) return
        inputText = TextFieldValue("")
        viewModel.sendMessage(agent, text)
    }

    // Report "has active conversation" up to the header whenever it changes, so the header's
    // "New chat" button can enable/disable itself without owning any transcript state.
    LaunchedEffect(viewModel.hasActiveConversation) {
        onConversationStateChanged(viewModel.hasActiveConversation)
    }

    // Parent-driven reset — the header's "New chat" button bumps this key instead of calling
    // into this composable directly, keeping AgentRunViewModel the sole owner of the transcript.
    LaunchedEffect(newConversationRequestKey) {
        if (newConversationRequestKey > 0) {
            inputText = TextFieldValue("")
            viewModel.startNewConversation()
        }
    }

    LaunchedEffect(preloadRecord) {
        if (preloadRecord != null) {
            inputText = TextFieldValue("")
            viewModel.preload(preloadRecord)
            onPreloadConsumed()
        }
    }

    // ── UI ───────────────────────────────────────────────────────────────────
    // Mirrors chatView's layout: a scrollable transcript takes the remaining
    // vertical space, with a single chat input pinned to the bottom.
    val transcriptScroll = rememberScrollState()

    // Auto-scroll to the bottom as new content streams in.
    LaunchedEffect(viewModel.messages.size, viewModel.messages.lastOrNull()?.content, viewModel.timeline.size) {
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
                    // ── Conversation title — mirrors ChatView's session title header.
                    // Shown the instant a conversation starts (deterministic fallback title),
                    // then live-updated in place once the async AI-generated title arrives.
                    viewModel.conversationTitle?.let { titleText ->
                        themedTooltip(text = titleText) {
                            Text(
                                text = titleText,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

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
                    if (viewModel.agentStateMap[viewModel.selectedAgentRaw?.id] == AgentCardState.NEEDS_SETUP) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Column(modifier = Modifier.padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                                Text(
                                    text = viewModel.selectedAgentRaw?.configurationHint ?: "",
                                    style = AppTextStyles.caption,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }

                    // ── Conversation transcript — same components as ChatView ────────────
                    if (viewModel.messages.isNotEmpty() || viewModel.isRunning) {
                        messageList(
                            messages = viewModel.messages,
                            isThinking = viewModel.isWaitingForFirstEvent,
                            thinkingElapsedSeconds = viewModel.elapsedSeconds,
                            completedGroupsByMessageId = viewModel.completedGroups,
                        )
                        // Live, chronologically-ordered view of the *current* turn — tool
                        // calls, thinking, response text, and status, interleaved exactly as
                        // the agent's stream reported them, with consecutive same-kind items
                        // collapsed into one group. Replaced by the finalized bubble in
                        // `messages` once the run completes.
                        if (viewModel.isRunning && viewModel.timeline.isNotEmpty()) {
                            turnTimelineView(viewModel.timeline.grouped())
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
                        enabled = !viewModel.isRunning,
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
                                        if (inputText.text.isNotBlank() && viewModel.selectedAgentReady && !viewModel.isRunning) {
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
                                .clickable(enabled = !viewModel.isRunning, onClick = { modelDropdownExpanded = true })
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
                                    .clickable(enabled = viewModel.allAgents.isNotEmpty() && !viewModel.isRunning, onClick = { agentDropdownExpanded = true })
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
                                                color = when (viewModel.agentStateMap[viewModel.selectedAgent?.id]) {
                                                    AgentCardState.READY -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f)
                                                    AgentCardState.NEEDS_SETUP -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                                                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                                                },
                                                shape = MaterialTheme.shapes.extraSmall,
                                            ),
                                    )
                                    Text(
                                        text = viewModel.selectedAgent?.name ?: stringResource("agents.view.no.agent"),
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
                                viewModel.allAgents.forEach { agent ->
                                    val agentState = viewModel.agentStateMap[agent.id] ?: AgentCardState.NOT_INSTALLED
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
                                            viewModel.selectAgent(agent.id)
                                            agentDropdownExpanded = false
                                        },
                                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                    )
                                }
                            }
                        }

                        // Send button
                        IconButton(
                            onClick = { sendMessage() },
                            enabled = viewModel.selectedAgentReady && inputText.text.isNotBlank() && !viewModel.isRunning,
                            colors = AppComponents.primaryIconButtonColors(),
                            modifier = Modifier
                                .size(36.dp)
                                .pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                imageVector = if (viewModel.isRunning) Icons.Default.Refresh else Icons.Default.PlayArrow,
                                contentDescription = if (viewModel.isRunning) {
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
