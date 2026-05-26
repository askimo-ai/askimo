/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.skills

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.db.DatabaseManager
import io.askimo.core.skills.agent.AgentCommand
import io.askimo.core.skills.agent.ExternalAgent
import io.askimo.core.skills.agent.ExternalAgentLoader
import io.askimo.core.skills.domain.SkillDefinition
import io.askimo.core.skills.domain.SkillRunRecord
import io.askimo.core.util.AskimoHome
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.sendTextField
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.preferences.ApplicationPreferences
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.markdownText
import io.askimo.ui.common.ui.selectableText
import io.askimo.ui.common.ui.themedTooltip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import javax.swing.JFileChooser
import kotlin.time.Duration.Companion.milliseconds

private enum class AgentState {
    /** Binary not found on PATH. */
    NOT_INSTALLED,

    /** Binary installed, needs an API key that Askimo can collect. */
    NEEDS_KEY,

    /** Binary installed, needs external auth (e.g. `claude login`) — nothing Askimo can inject. */
    NEEDS_EXTERNAL_AUTH,

    /** Binary installed and fully configured. */
    READY,
}

@Composable
internal fun skillExecutionArea(
    skill: SkillDefinition,
    onBack: () -> Unit,
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
    var agentStatus by remember(skill.relativePath) { mutableStateOf<String?>(null) }
    var activityLog by remember(skill.relativePath) { mutableStateOf(listOf<String>()) }
    var activityLogExpanded by remember(skill.relativePath) { mutableStateOf(false) }
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

    var workDir by remember(skill.relativePath) {
        mutableStateOf(AskimoHome.skillsWorkspaceDir().toFile())
    }

    val allAgents by remember { mutableStateOf(ExternalAgentLoader.all()) }
    var selectedAgent by remember(allAgents) {
        val savedId = ApplicationPreferences.getSkillsSelectedAgentId()
        val initial = allAgents.firstOrNull { it.id == savedId }
            ?: allAgents.firstOrNull { it.isAvailable() }
            ?: allAgents.firstOrNull()
        mutableStateOf(initial)
    }
    var agentDropdownExpanded by remember { mutableStateOf(false) }

    // Re-evaluated state for the selected agent — drives hint/key-input rendering.
    // Use a mutable trigger so saving a key forces recomposition immediately.
    var agentStateVersion by remember { mutableStateOf(0) }
    val agentState: AgentState = remember(selectedAgent, agentStateVersion) {
        val agent = selectedAgent
        when {
            agent == null -> AgentState.NOT_INSTALLED
            !agent.isBinaryAvailable() -> AgentState.NOT_INSTALLED
            !agent.isConfigured() -> if (agent.requiresApiKey) AgentState.NEEDS_KEY else AgentState.NEEDS_EXTERNAL_AUTH
            else -> AgentState.READY
        }
    }
    val selectedAgentAvailable = agentState == AgentState.READY

    var apiKeyInput by remember(selectedAgent) { mutableStateOf("") }
    var apiKeySaving by remember { mutableStateOf(false) }
    var apiKeySaved by remember { mutableStateOf(false) }

    var commandPickerExpanded by remember { mutableStateOf(false) }
    val commandSuggestions: List<AgentCommand> = remember(contextInput, selectedAgent) {
        val agent = selectedAgent ?: return@remember emptyList()
        if (!contextInput.startsWith("/")) return@remember emptyList()
        val query = contextInput.trimStart('/')
        agent.commands.filter { cmd ->
            query.isBlank() || cmd.name.contains(query, ignoreCase = true) || cmd.description.contains(query, ignoreCase = true)
        }
    }

    val isCommandMode = contextInput.trimStart().startsWith("/")

    fun execute(agent: ExternalAgent, systemPrompt: String, userInput: String) {
        isRunning = true
        responseText = ""
        runError = null
        agentStatus = null
        activityLog = listOf()
        scope.launch {
            withContext(Dispatchers.IO) {
                agent.runTracked(
                    systemPrompt = systemPrompt,
                    userInput = userInput,
                    workDir = workDir,
                    onToken = { line -> scope.launch { responseText += line } },
                    onStatus = { status ->
                        scope.launch {
                            agentStatus = status
                            activityLog = activityLog + status
                        }
                    },
                ).onFailure { e ->
                    scope.launch { runError = e.message ?: "Execution failed" }
                }
            }
            agentStatus = null
            isRunning = false

            val record = SkillRunRecord(
                skillPath = skill.relativePath,
                userInput = userInput,
                response = responseText,
                error = runError,
                activityLog = activityLog,
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
            activityLog = preloadRecord.activityLog
            isRunning = false
            onPreloadConsumed()
        }
    }

    val scrollState = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Sticky header ─────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = 24.dp, end = 36.dp, top = 8.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.widthIn(max = ThemePreferences.CONTENT_MAX_WIDTH).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource("action.back"), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    TextButton(onClick = onBack, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                        Text(stringResource("skills.view.title"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                    modifier = Modifier.padding(bottom = Spacing.small),
                ) {
                    Text("🤖", style = MaterialTheme.typography.headlineLarge)
                    Column(modifier = Modifier.weight(1f)) {
                        selectableText(skill.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                        if (skill.description.isNotBlank()) {
                            selectableText(skill.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

        // ── Scrollable body ───────────────────────────────────────────────────
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = ThemePreferences.CONTENT_MAX_WIDTH)
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 36.dp, top = 16.dp, bottom = 32.dp),
                ) {
                    // Collapsible system prompt
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        val systemPromptTooltip = remember(skill.systemPrompt) {
                            skill.systemPrompt.trim().take(600).let {
                                if (skill.systemPrompt.trim().length > 600) "$it…" else it
                            }
                        }
                        Column {
                            themedTooltip(text = if (systemPromptExpanded) "" else systemPromptTooltip) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .toggleable(value = systemPromptExpanded, role = Role.Button, onValueChange = { systemPromptExpanded = it })
                                        .padding(horizontal = Spacing.large, vertical = Spacing.medium),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                                        Text(stringResource("skills.view.system.prompt.preview"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (skill.supplementalFileNames.isNotEmpty()) {
                                            Box(
                                                modifier = Modifier
                                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape = MaterialTheme.shapes.extraSmall)
                                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                                            ) {
                                                Text(
                                                    "+${skill.supplementalFileNames.size} ${if (skill.supplementalFileNames.size == 1) stringResource("skills.view.system.prompt.file") else stringResource("skills.view.system.prompt.files")}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                    Icon(if (systemPromptExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                }
                            }
                            if (systemPromptExpanded) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                                markdownText(markdown = skill.systemPrompt.trim(), modifier = Modifier.fillMaxWidth().padding(Spacing.large))
                                if (skill.supplementalFileNames.isNotEmpty()) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                                    SelectionContainer {
                                        Column(modifier = Modifier.padding(Spacing.large), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(stringResource("skills.view.system.prompt.also.included"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                            skill.supplementalFileNames.forEach { fileName ->
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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

                    // Working directory picker
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.medium, vertical = Spacing.small),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = stringResource("skills.view.workdir.label"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(text = workDir.absolutePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val workdirDialogTitle = stringResource("skills.view.workdir.dialog.title")
                            TextButton(onClick = { runCatching { Desktop.getDesktop().open(workDir) } }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                                Text(stringResource("skills.view.workdir.open"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(
                                onClick = {
                                    val chooser = JFileChooser().apply {
                                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                        currentDirectory = workDir
                                        dialogTitle = workdirDialogTitle
                                    }
                                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                        workDir = chooser.selectedFile
                                    }
                                },
                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Text(stringResource("skills.view.workdir.change"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.medium))

                    // Context input with slash-command picker
                    Box {
                        OutlinedTextField(
                            value = contextInput,
                            onValueChange = { v ->
                                contextInput = v
                                commandPickerExpanded = v.startsWith("/") && (selectedAgent?.commands?.isNotEmpty() == true)
                            },
                            placeholder = { Text(stringResource("skills.view.context.placeholder")) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 8,
                            colors = AppComponents.outlinedTextFieldColors(),
                        )
                        if (commandPickerExpanded && commandSuggestions.isNotEmpty()) {
                            AppComponents.dropdownMenu(expanded = true, onDismissRequest = { commandPickerExpanded = false }) {
                                commandSuggestions.forEach { cmd ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.CenterVertically) {
                                                    Text(cmd.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
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

                    if (isCommandMode) {
                        Text(
                            text = stringResource("skills.view.command.mode.hint"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                        )
                    }

                    Spacer(modifier = Modifier.height(Spacing.medium))

                    // Agent picker + Execute button
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.medium), verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            // ...existing agent picker Surface + dropdown...
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                                modifier = Modifier
                                    .clickable(enabled = allAgents.isNotEmpty(), onClick = { agentDropdownExpanded = true })
                                    .pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = Spacing.medium, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                                ) {
                                    Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (selectedAgentAvailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                                    Text(text = selectedAgent?.name ?: stringResource("skills.view.no.agent"), style = MaterialTheme.typography.bodyMedium, color = if (selectedAgentAvailable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                    if (allAgents.size > 1) Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            AppComponents.dropdownMenu(expanded = agentDropdownExpanded, onDismissRequest = { agentDropdownExpanded = false }) {
                                allAgents.forEach { agent ->
                                    val agentAvailable = agent.isAvailable()
                                    DropdownMenuItem(
                                        text = {
                                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (agent == selectedAgent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                                Text(agent.name, style = MaterialTheme.typography.bodyMedium, fontWeight = if (agent == selectedAgent) FontWeight.SemiBold else FontWeight.Normal)
                                                if (!agentAvailable) {
                                                    Text(
                                                        stringResource("skills.view.agent.not.installed"),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                    )
                                                }
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

                        primaryButton(
                            onClick = {
                                val agent = selectedAgent ?: return@primaryButton
                                if (isCommandMode) {
                                    execute(agent, systemPrompt = "", userInput = contextInput.trim())
                                } else {
                                    execute(agent, skill.content, contextInput)
                                }
                            },
                            enabled = selectedAgentAvailable && !isRunning,
                        ) {
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

                        TextButton(
                            onClick = { uriHandler.openUri("https://$DOMAIN/docs/desktop/skills/#supported-runtimes-today") },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Text(
                                text = stringResource("skills.view.agent.configure"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    when (agentState) {
                        AgentState.NOT_INSTALLED -> {
                            Spacer(modifier = Modifier.height(Spacing.small))
                            Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small) {
                                Text(
                                    text = stringResource("skills.view.agent.install.hint", selectedAgent?.name ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
                                )
                            }
                        }

                        AgentState.NEEDS_KEY -> {
                            Spacer(modifier = Modifier.height(Spacing.small))
                            Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f), shape = MaterialTheme.shapes.small) {
                                Column(modifier = Modifier.padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                                    Text(
                                        text = stringResource("skills.view.agent.needs.key", selectedAgent?.name ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = apiKeyInput,
                                            onValueChange = {
                                                apiKeyInput = it
                                                apiKeySaved = false
                                            },
                                            placeholder = { Text(stringResource("skills.view.agent.key.placeholder")) },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            visualTransformation = PasswordVisualTransformation(),
                                            colors = AppComponents.outlinedTextFieldColors(),
                                        )
                                        primaryButton(
                                            onClick = {
                                                val agent = selectedAgent ?: return@primaryButton
                                                val key = apiKeyInput.trim()
                                                if (key.isBlank()) return@primaryButton
                                                apiKeySaving = true
                                                scope.launch {
                                                    withContext(Dispatchers.IO) { agent.saveApiKey(key) }
                                                    apiKeySaved = true
                                                    apiKeySaving = false
                                                    agentStateVersion++ // force re-evaluation
                                                }
                                            },
                                            enabled = apiKeyInput.isNotBlank() && !apiKeySaving,
                                        ) {
                                            if (apiKeySaved) {
                                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                            } else {
                                                Text(stringResource("action.save"))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        AgentState.NEEDS_EXTERNAL_AUTH -> {
                            Spacer(modifier = Modifier.height(Spacing.small))
                            Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f), shape = MaterialTheme.shapes.small) {
                                Text(
                                    text = selectedAgent?.configurationHint ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
                                )
                            }
                        }

                        AgentState.READY -> Unit
                    }

                    Spacer(modifier = Modifier.height(Spacing.extraLarge))

                    // Response panel
                    if (isRunning || hasResponse || runError != null || activityLog.isNotEmpty()) {
                        Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, shadowElevation = 1.dp, tonalElevation = 1.dp) {
                            Column(modifier = Modifier.padding(Spacing.large)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.medium),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (isRunning) Icons.Default.Refresh else Icons.Default.CheckCircle,
                                            null,
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
                                        if (isRunning) Text(text = "${elapsedSeconds}s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                                    }
                                    if (hasResponse) {
                                        IconButton(onClick = { clipboardManager.setText(AnnotatedString(responseText)) }, modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand)) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = stringResource("skills.view.copy"), modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                                if (runError != null) {
                                    Text(text = runError!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Spacing.medium))
                                } else if (responseText.isNotBlank()) {
                                    markdownText(markdown = responseText, isStreaming = isRunning, modifier = Modifier.fillMaxWidth().padding(top = Spacing.medium))
                                    if (isRunning && agentStatus != null) {
                                        Text(text = agentStatus!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(top = Spacing.small))
                                    }
                                } else {
                                    Column(modifier = Modifier.fillMaxWidth().padding(top = Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                                        Box(modifier = Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
                                            Text(text = "▌", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                        }
                                        if (agentStatus != null) Text(text = agentStatus!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(Spacing.medium))

                        // Activity Log
                        if (activityLog.isNotEmpty()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                shape = MaterialTheme.shapes.medium,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                            ) {
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand)
                                            .toggleable(value = activityLogExpanded, role = Role.Button, onValueChange = { activityLogExpanded = it })
                                            .padding(horizontal = Spacing.large, vertical = Spacing.medium),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                                            Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(stringResource("skills.view.activity.log"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Box(
                                                modifier = Modifier
                                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape = MaterialTheme.shapes.extraSmall)
                                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                                            ) {
                                                Text("${activityLog.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        Icon(if (activityLogExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                    }
                                    if (activityLogExpanded) {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                                        SelectionContainer {
                                            Column(
                                                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.large, vertical = Spacing.medium),
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                activityLog.forEachIndexed { index, entry ->
                                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Top) {
                                                        Text("${index + 1}.", style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), modifier = Modifier.width(24.dp))
                                                        Text(entry, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(Spacing.medium))

                        // Follow-up
                        Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), shape = MaterialTheme.shapes.medium) {
                            Column(modifier = Modifier.padding(Spacing.large)) {
                                Text(stringResource("skills.view.followup.label"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Spacing.small))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Bottom) {
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
                                                execute(agent, skill.content, followUpContext)
                                            }
                                        },
                                        sendContentDescription = stringResource("skills.view.followup.send"),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scrollState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 4.dp),
                style = ScrollbarStyle(
                    minimalHeight = 16.dp,
                    thickness = 8.dp,
                    shape = MaterialTheme.shapes.small,
                    hoverDurationMillis = 300,
                    unhoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    hoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f),
                ),
            )
        }
    }
}
