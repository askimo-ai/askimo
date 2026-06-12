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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.db.DatabaseManager
import io.askimo.core.skills.agent.AgentCommand
import io.askimo.core.skills.agent.ExternalAgent
import io.askimo.core.skills.agent.ExternalAgentLoader
import io.askimo.core.skills.domain.SkillDefinition
import io.askimo.core.skills.domain.SkillRunRecord
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.sendTextField
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.preferences.ApplicationPreferences
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppComponents.dropdownMenu
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.markdownText
import io.askimo.ui.common.ui.revealingMarkdownText
import io.askimo.ui.common.ui.themedTooltip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

private enum class AgentState { NOT_INSTALLED, NEEDS_KEY, NEEDS_EXTERNAL_AUTH, READY }

/**
 * Renders the skill execution UI inline — no scroll container of its own.
 * The parent (skillsMainContent) owns the scroll.
 * The working directory is provided by the caller (user-chosen, persisted via [ApplicationPreferences]).
 */
@Composable
internal fun skillExecutionArea(
    skill: SkillDefinition,
    workDir: File,
    onRunCompleted: () -> Unit = {},
    preloadRecord: SkillRunRecord? = null,
    onPreloadConsumed: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val historyRepo = remember { DatabaseManager.getInstance().getSkillRunHistoryRepository() }

    var contextInput by remember(skill.relativePath) { mutableStateOf("") }
    var followUpInput by remember(skill.relativePath) { mutableStateOf("") }
    var systemPromptExpanded by remember(skill.relativePath) { mutableStateOf(false) }
    var isRunning by remember(skill.relativePath) { mutableStateOf(false) }
    var responseText by remember(skill.relativePath) { mutableStateOf("") }
    var runError by remember(skill.relativePath) { mutableStateOf<String?>(null) }
    var currentStatusLine by remember(skill.relativePath) { mutableStateOf<String?>(null) }
    var displayText by remember(skill.relativePath) { mutableStateOf("") }
    var isInThinkingPhase by remember(skill.relativePath) { mutableStateOf(false) }
    val hasResponse = responseText.isNotBlank()
    var elapsedSeconds by remember(skill.relativePath) { mutableStateOf(0) }

    LaunchedEffect(isRunning) {
        if (isRunning) {
            elapsedSeconds = 0
            while (isRunning) {
                delay(1_000.milliseconds)
                elapsedSeconds++
            }
        }
    }

    // workDir is now passed in by the caller (user-chosen, persisted via ApplicationPreferences)

    val allAgents by remember { mutableStateOf(ExternalAgentLoader.all()) }
    var agentStateVersion by remember { mutableStateOf(0) }
    var agentAvailabilityMap by remember { mutableStateOf(mapOf<String, AgentState>()) }
    LaunchedEffect(agentStateVersion) {
        val map = withContext(Dispatchers.IO) {
            allAgents.associate { agent ->
                agent.id to when {
                    !agent.isBinaryAvailable() -> AgentState.NOT_INSTALLED
                    !agent.isConfigured() -> if (agent.requiresApiKey) AgentState.NEEDS_KEY else AgentState.NEEDS_EXTERNAL_AUTH
                    else -> AgentState.READY
                }
            }
        }
        agentAvailabilityMap = map
    }
    var selectedAgent by remember(allAgents) {
        val savedId = ApplicationPreferences.getSkillsSelectedAgentId()
        mutableStateOf(allAgents.firstOrNull { it.id == savedId } ?: allAgents.firstOrNull())
    }
    var agentDropdownExpanded by remember { mutableStateOf(false) }
    val agentState: AgentState = agentAvailabilityMap[selectedAgent?.id] ?: AgentState.NOT_INSTALLED
    val selectedAgentAvailable = agentState == AgentState.READY
    var apiKeyInput by remember(selectedAgent) { mutableStateOf("") }
    var apiKeySaving by remember { mutableStateOf(false) }
    var apiKeySaved by remember { mutableStateOf(false) }
    var commandPickerExpanded by remember { mutableStateOf(false) }
    val commandSuggestions: List<AgentCommand> = remember(contextInput, selectedAgent) {
        val agent = selectedAgent ?: return@remember emptyList()
        if (!contextInput.startsWith("/")) return@remember emptyList()
        val query = contextInput.trimStart('/')
        agent.commands.filter { cmd -> query.isBlank() || cmd.name.contains(query, ignoreCase = true) || cmd.description.contains(query, ignoreCase = true) }
    }
    val isCommandMode = contextInput.trimStart().startsWith("/")

    fun execute(agent: ExternalAgent, systemPrompt: String, userInput: String) {
        isRunning = true
        responseText = ""
        displayText = ""
        isInThinkingPhase = false
        runError = null
        currentStatusLine = null
        scope.launch {
            withContext(Dispatchers.IO) {
                agent.runTracked(
                    systemPrompt = systemPrompt,
                    userInput = userInput,
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
                ).onFailure { e -> scope.launch { runError = e.message ?: "Execution failed" } }
            }
            currentStatusLine = null
            isRunning = false
            isInThinkingPhase = false
            val record = SkillRunRecord(
                skillPath = skill.relativePath,
                userInput = userInput,
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

    LaunchedEffect(preloadRecord) {
        if (preloadRecord != null) {
            contextInput = preloadRecord.userInput
            responseText = preloadRecord.response
            runError = preloadRecord.error
            isRunning = false
            onPreloadConsumed()
        }
    }

    // ── Content ──────────────────────────────────────────────────────────────
    Column(
        modifier = Modifier.widthIn(max = ThemePreferences.CONTENT_MAX_WIDTH).fillMaxWidth()
            .padding(start = 24.dp, end = 36.dp, top = 16.dp, bottom = 32.dp),
    ) {
        // Collapsible system prompt
        Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.medium) {
            val systemPromptTooltip = remember(skill.systemPrompt) { skill.systemPrompt.trim().take(600).let { if (skill.systemPrompt.trim().length > 600) "$it…" else it } }
            Column {
                themedTooltip(text = if (systemPromptExpanded) "" else systemPromptTooltip) {
                    Row(
                        modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand)
                            .toggleable(value = systemPromptExpanded, role = Role.Button, onValueChange = { systemPromptExpanded = it })
                            .padding(horizontal = Spacing.large, vertical = Spacing.medium),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                            Text(stringResource("skills.view.system.prompt.preview"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (skill.supplementalFileNames.isNotEmpty()) {
                                Box(modifier = Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape = MaterialTheme.shapes.extraSmall).padding(horizontal = 6.dp, vertical = 1.dp)) {
                                    Text("+${skill.supplementalFileNames.size} ${if (skill.supplementalFileNames.size == 1) stringResource("skills.view.system.prompt.file") else stringResource("skills.view.system.prompt.files")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Icon(if (systemPromptExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
                if (systemPromptExpanded) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                    revealingMarkdownText(markdown = skill.systemPrompt.trim(), modifier = Modifier.fillMaxWidth().padding(Spacing.large))
                    if (skill.supplementalFileNames.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                        SelectionContainer {
                            Column(modifier = Modifier.padding(Spacing.large), verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                                Text(stringResource("skills.view.system.prompt.also.included"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                skill.supplementalFileNames.forEach { fileName ->
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                                        Text(fileName, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.extraLarge))

        // Context input + slash-command picker
        Box {
            OutlinedTextField(value = contextInput, onValueChange = { v ->
                contextInput = v
                commandPickerExpanded = v.startsWith("/") && (selectedAgent?.commands?.isNotEmpty() == true)
            }, placeholder = { Text(stringResource("skills.view.context.placeholder")) }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8, colors = AppComponents.outlinedTextFieldColors())
            if (commandPickerExpanded && commandSuggestions.isNotEmpty()) {
                dropdownMenu(expanded = true, onDismissRequest = { commandPickerExpanded = false }) {
                    commandSuggestions.forEach { cmd ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.CenterVertically) {
                                        Text(cmd.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                        if (cmd.usage.isNotBlank()) Text(cmd.usage, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(cmd.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = {
                                contextInput = cmd.name + " "
                                commandPickerExpanded = false
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )
                    }
                }
            }
        }
        if (isCommandMode) Text(text = stringResource("skills.view.command.mode.hint"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 4.dp, start = 4.dp))

        Spacer(modifier = Modifier.height(Spacing.medium))

        // Agent picker + run button
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.medium), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)), modifier = Modifier.clickable(enabled = allAgents.isNotEmpty(), onClick = { agentDropdownExpanded = true }).pointerHoverIcon(PointerIcon.Hand)) {
                    Row(modifier = Modifier.padding(horizontal = Spacing.medium, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                        Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (selectedAgentAvailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                        Text(text = selectedAgent?.name ?: stringResource("skills.view.no.agent"), style = MaterialTheme.typography.bodyMedium, color = if (selectedAgentAvailable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        if (allAgents.size > 1) Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                dropdownMenu(expanded = agentDropdownExpanded, onDismissRequest = { agentDropdownExpanded = false }) {
                    allAgents.forEach { agent ->
                        val agentAvailable = agentAvailabilityMap[agent.id] == AgentState.READY
                        DropdownMenuItem(
                            text = {
                                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (agent == selectedAgent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                    Text(agent.name, style = MaterialTheme.typography.bodyMedium, fontWeight = if (agent == selectedAgent) FontWeight.SemiBold else FontWeight.Normal)
                                    if (!agentAvailable) Text(stringResource("skills.view.agent.not.installed"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                }
                            },
                            onClick = {
                                selectedAgent = agent
                                ApplicationPreferences.setSkillsSelectedAgentId(agent.id)
                                agentDropdownExpanded = false
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )
                    }
                }
            }
            primaryButton(onClick = {
                val agent = selectedAgent ?: return@primaryButton
                if (isCommandMode) execute(agent, systemPrompt = "", userInput = contextInput.trim()) else execute(agent, skill.content, contextInput)
            }, enabled = selectedAgentAvailable && !isRunning) {
                Icon(if (isRunning) Icons.Default.Refresh else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(Spacing.small))
                Text(
                    when {
                        isRunning -> stringResource("skills.view.running")
                        isCommandMode -> stringResource("skills.view.run.command")
                        else -> stringResource("skills.view.run")
                    },
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { uriHandler.openUri("https://$DOMAIN/docs/desktop/skills/#supported-runtimes-today") }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                Text(text = stringResource("skills.view.agent.configure"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Agent setup hints
        when (agentState) {
            AgentState.NOT_INSTALLED -> {
                Spacer(modifier = Modifier.height(Spacing.small))
                Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small) { Text(text = stringResource("skills.view.agent.install.hint", selectedAgent?.name ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small)) }
            }

            AgentState.NEEDS_KEY -> {
                Spacer(modifier = Modifier.height(Spacing.small))
                Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f), shape = MaterialTheme.shapes.small) {
                    Column(modifier = Modifier.padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                        Text(text = stringResource("skills.view.agent.needs.key", selectedAgent?.name ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = apiKeyInput, onValueChange = {
                                apiKeyInput = it
                                apiKeySaved = false
                            }, placeholder = { Text(stringResource("skills.view.agent.key.placeholder")) }, modifier = Modifier.weight(1f), singleLine = true, visualTransformation = PasswordVisualTransformation(), colors = AppComponents.outlinedTextFieldColors())
                            primaryButton(onClick = {
                                val agent = selectedAgent ?: return@primaryButton
                                val key = apiKeyInput.trim()
                                if (key.isBlank()) return@primaryButton
                                apiKeySaving = true
                                scope.launch {
                                    withContext(Dispatchers.IO) { agent.saveApiKey(key) }
                                    apiKeySaved = true
                                    apiKeySaving = false
                                    agentStateVersion++
                                }
                            }, enabled = apiKeyInput.isNotBlank() && !apiKeySaving) { if (apiKeySaved) Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) else Text(stringResource("action.save")) }
                        }
                    }
                }
            }

            AgentState.NEEDS_EXTERNAL_AUTH -> {
                Spacer(modifier = Modifier.height(Spacing.small))
                Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f), shape = MaterialTheme.shapes.small) { Text(text = selectedAgent?.configurationHint ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small)) }
            }

            AgentState.READY -> Unit
        }

        Spacer(modifier = Modifier.height(Spacing.extraLarge))

        // Response panel
        if (isRunning || hasResponse || runError != null) {
            Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, shadowElevation = 1.dp, tonalElevation = 1.dp) {
                Column(modifier = Modifier.padding(Spacing.large)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.medium), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (isRunning) Icons.Default.Refresh else Icons.Default.CheckCircle, null, tint = if (runError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
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
                            if (isRunning) Text(text = "${elapsedSeconds}s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                        }
                        if (hasResponse) {
                            IconButton(onClick = { clipboardManager.setText(AnnotatedString(responseText)) }, modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand)) { Icon(Icons.Default.ContentCopy, contentDescription = stringResource("skills.view.copy"), modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                    when {
                        runError != null -> Text(text = runError!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Spacing.medium))
                        isRunning && isInThinkingPhase && displayText.isNotBlank() -> markdownText(markdown = displayText.lines().joinToString("\n") { "> $it" }, isStreaming = true, modifier = Modifier.fillMaxWidth().padding(top = Spacing.medium))
                        responseText.isNotBlank() -> markdownText(markdown = responseText, isStreaming = isRunning, modifier = Modifier.fillMaxWidth().padding(top = Spacing.medium))
                        else -> Box(modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = Spacing.medium), contentAlignment = Alignment.CenterStart) { Text("▌", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) }
                    }
                    if (isRunning && currentStatusLine != null) Text(text = currentStatusLine!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), modifier = Modifier.padding(top = Spacing.small))
                }
            }
            Spacer(modifier = Modifier.height(Spacing.medium))
            Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), shape = MaterialTheme.shapes.medium) {
                Column(modifier = Modifier.padding(Spacing.large)) {
                    Text(stringResource("skills.view.followup.label"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Spacing.small))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Bottom) {
                        sendTextField(value = followUpInput, onValueChange = { followUpInput = it }, placeholder = stringResource("skills.view.followup.placeholder"), enabled = hasResponse && !isRunning, onSend = {
                            val agent = selectedAgent ?: return@sendTextField
                            if (followUpInput.isNotBlank()) {
                                val ctx = "$responseText\n\n---\n\n${followUpInput.trim()}"
                                followUpInput = ""
                                execute(agent, skill.content, ctx)
                            }
                        }, sendContentDescription = stringResource("skills.view.followup.send"))
                    }
                }
            }
        }
    }
}
