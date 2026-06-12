/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.skills

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.askimo.core.db.DatabaseManager
import io.askimo.core.skills.agent.ExternalAgent
import io.askimo.core.skills.agent.ExternalAgentLoader
import io.askimo.core.skills.domain.SkillDefinition
import io.askimo.core.skills.domain.SkillRunRecord
import io.askimo.ui.common.components.sendTextField
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.preferences.ApplicationPreferences
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppComponents.dropdownMenu
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.markdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/** Sentinel skill path used for agentic runs that span multiple skills. */
internal const val AGENTIC_RUN_SKILL_PATH = "__agentic__"

private enum class AgentCardState {
    NOT_INSTALLED,
    NEEDS_SETUP,
    READY,
}

/**
 * Autonomous run area — user selects an agent and describes a goal;
 * the agent decides which skills to apply based on the full skills catalog
 * injected as its system prompt context.
 */
@Composable
internal fun agenticRunArea(
    skills: List<SkillDefinition>,
    workDir: File,
    onRunCompleted: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val historyRepo = remember { DatabaseManager.getInstance().getSkillRunHistoryRepository() }

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
        mutableStateOf(ApplicationPreferences.getSkillsSelectedAgentId())
    }
    // Resolve selected agent; fall back to first ready one if saved pref is unavailable
    val selectedAgent = remember(selectedAgentId, allAgents, agentStateMap) {
        allAgents.firstOrNull { it.id == selectedAgentId && agentStateMap[it.id] == AgentCardState.READY }
            ?: allAgents.firstOrNull { agentStateMap[it.id] == AgentCardState.READY }
    }
    val selectedAgentReady = agentStateMap[selectedAgent?.id] == AgentCardState.READY
    var agentDropdownExpanded by remember { mutableStateOf(false) }

    // ── Run state ────────────────────────────────────────────────────────────
    var goalInput by remember { mutableStateOf("") }
    var followUpInput by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }
    var responseText by remember { mutableStateOf("") }
    var runError by remember { mutableStateOf<String?>(null) }
    var currentStatusLine by remember { mutableStateOf<String?>(null) }
    var displayText by remember { mutableStateOf("") }
    var isInThinkingPhase by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableStateOf(0) }
    val hasResponse = responseText.isNotBlank()

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
    fun executeAgentic(agent: ExternalAgent, goal: String) {
        isRunning = true
        responseText = ""
        displayText = ""
        isInThinkingPhase = false
        runError = null
        currentStatusLine = null
        scope.launch {
            withContext(Dispatchers.IO) {
                agent.runTracked(
                    systemPrompt = agenticSystemPrompt,
                    userInput = goal,
                    workDir = workDir,
                    onToken = { token ->
                        scope.launch {
                            if (isInThinkingPhase) {
                                displayText = ""
                                isInThinkingPhase = false
                            }
                            responseText += token
                            displayText += token
                        }
                    },
                    onStatus = { status ->
                        scope.launch {
                            if (isInThinkingPhase) {
                                displayText = ""
                                isInThinkingPhase = false
                            }
                            currentStatusLine = status
                        }
                    },
                    onThinking = { chunk ->
                        scope.launch {
                            if (!isInThinkingPhase) {
                                displayText = ""
                                isInThinkingPhase = true
                            }
                            displayText += chunk
                        }
                    },
                ).onFailure { e ->
                    scope.launch { runError = e.message ?: "Execution failed" }
                }
            }
            currentStatusLine = null
            isRunning = false
            isInThinkingPhase = false
            val record = SkillRunRecord(
                skillPath = AGENTIC_RUN_SKILL_PATH,
                userInput = goal,
                response = responseText,
                error = runError,
                agentSessionId = agent.lastExecutionSessionId,
                workspaceDir = agent.lastExecutionWorkspaceDir ?: workDir.absolutePath,
                activityLog = emptyList(),
            )
            withContext(Dispatchers.IO) { historyRepo.save(record) }
            onRunCompleted()
        }
    }

    // ── UI ───────────────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .widthIn(max = ThemePreferences.CONTENT_MAX_WIDTH)
            .fillMaxWidth()
            .padding(start = 24.dp, end = 36.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        // ── Skills-as-context pill row ───────────────────────────────────────
        if (skills.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
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
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource("skills.agentic.skills.available", skills.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                maxLines = 1,
                            )
                        }
                    }
                    if (skills.size > maxVisible) {
                        Text(
                            text = "+${skills.size - maxVisible}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
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
                        text = stringResource("skills.agentic.no.skills.hint"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        }

        // ── Goal input with inline Run icon at bottom-right ─────────────────
        Column {
            var modelDropdownExpanded by remember { mutableStateOf(false) }

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = goalInput,
                    onValueChange = { goalInput = it },
                    placeholder = { Text(stringResource("skills.agentic.goal.placeholder")) },
                    modifier = Modifier.fillMaxWidth(),
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
                                text = stringResource("skills.agentic.model.default"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        // TODO: populate with real models once model-selection API is wired
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource("skills.agentic.model.default"),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            onClick = { modelDropdownExpanded = false },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )
                    }
                }

                // ── Agent picker + elapsed timer + Run button — overlaid bottom-right ──
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 6.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Elapsed timer while running
                    if (isRunning) {
                        Text(
                            text = "${elapsedSeconds}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        )
                    }

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
                                    text = selectedAgent?.name ?: stringResource("skills.view.no.agent"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (agent.id == selectedAgent?.id) FontWeight.SemiBold else FontWeight.Normal,
                                            )
                                            if (!agentReady) {
                                                Text(
                                                    text = stringResource("skills.view.agent.not.installed"),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        selectedAgentId = agent.id
                                        ApplicationPreferences.setSkillsSelectedAgentId(agent.id)
                                        agentDropdownExpanded = false
                                        agentStateVersion++
                                    },
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                )
                            }
                        }
                    }

                    // Run button
                    IconButton(
                        onClick = {
                            val agent = selectedAgent ?: return@IconButton
                            if (goalInput.isNotBlank()) executeAgentic(agent, goalInput.trim())
                        },
                        enabled = selectedAgentReady && goalInput.isNotBlank() && !isRunning,
                        colors = AppComponents.primaryIconButtonColors(),
                        modifier = Modifier
                            .size(36.dp)
                            .pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            imageVector = if (isRunning) Icons.Default.Refresh else Icons.Default.PlayArrow,
                            contentDescription = if (isRunning) {
                                stringResource("skills.view.running")
                            } else {
                                stringResource("skills.agentic.run")
                            },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        // ── Response panel ───────────────────────────────────────────────────
        if (isRunning || hasResponse || runError != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                shadowElevation = 1.dp,
                tonalElevation = 1.dp,
            ) {
                Column(modifier = Modifier.padding(Spacing.large)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.medium),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (isRunning) Icons.Default.Refresh else Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = if (runError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = when {
                                    isRunning -> stringResource("skills.view.running")
                                    runError != null -> stringResource("skills.view.response.error")
                                    else -> stringResource("skills.view.response.title")
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (isRunning) {
                                Text(
                                    text = "${elapsedSeconds}s",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                )
                            }
                        }
                        if (hasResponse) {
                            IconButton(
                                onClick = { clipboardManager.setText(AnnotatedString(responseText)) },
                                modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = stringResource("skills.view.copy"),
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                    when {
                        runError != null -> Text(
                            text = runError!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = Spacing.medium),
                        )

                        isRunning && isInThinkingPhase && displayText.isNotBlank() -> {
                            val blockquote = displayText.lines().joinToString("\n") { "> $it" }
                            markdownText(
                                markdown = blockquote,
                                isStreaming = true,
                                modifier = Modifier.fillMaxWidth().padding(top = Spacing.medium),
                            )
                        }

                        responseText.isNotBlank() -> markdownText(
                            markdown = responseText,
                            isStreaming = isRunning,
                            modifier = Modifier.fillMaxWidth().padding(top = Spacing.medium),
                        )

                        else -> Box(
                            modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = Spacing.medium),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = "▌",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                        }
                    }
                    if (isRunning && currentStatusLine != null) {
                        Text(
                            text = currentStatusLine!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                            modifier = Modifier.padding(top = Spacing.small),
                        )
                    }
                }
            }

            // ── Follow-up ────────────────────────────────────────────────────
            Spacer(modifier = Modifier.height(Spacing.small))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(modifier = Modifier.padding(Spacing.large)) {
                    Text(
                        text = stringResource("skills.view.followup.label"),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Spacing.small),
                    )
                    sendTextField(
                        value = followUpInput,
                        onValueChange = { followUpInput = it },
                        placeholder = stringResource("skills.view.followup.placeholder"),
                        enabled = hasResponse && !isRunning,
                        onSend = {
                            val agent = selectedAgent ?: return@sendTextField
                            if (followUpInput.isNotBlank()) {
                                val followUpContext = "$responseText\n\n---\n\n${followUpInput.trim()}"
                                followUpInput = ""
                                executeAgentic(agent, followUpContext)
                            }
                        },
                        sendContentDescription = stringResource("skills.view.followup.send"),
                    )
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
