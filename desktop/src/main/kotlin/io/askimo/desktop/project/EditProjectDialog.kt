/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.domain.Project
import io.askimo.core.db.DatabaseManager
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.ProjectIndexRemovalEvent
import io.askimo.core.event.internal.ProjectIndexingRequestedEvent
import io.askimo.ui.common.components.inlineErrorMessage
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.rememberDialogState
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.collections.plus

/**
 * Dialog for editing an existing project.
 */
@Composable
fun editProjectDialog(
    projectId: String,
    onDismiss: () -> Unit,
    onSave: (projectId: String, name: String, description: String?, knowledgeSources: List<KnowledgeSourceConfig>) -> Unit,
) {
    val projectRepository = remember { DatabaseManager.getInstance().getProjectRepository() }

    var project by remember { mutableStateOf<Project?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val dialogState = rememberDialogState()

    // Load project from database
    LaunchedEffect(projectId) {
        isLoading = true
        dialogState.clearError()
        try {
            val loadedProject = withContext(Dispatchers.IO) {
                projectRepository.getProject(projectId)
            }
            if (loadedProject != null) {
                project = loadedProject
            } else {
                dialogState.setError("Project not found")
            }
        } catch (e: Exception) {
            dialogState.setError(e, "Failed to load project")
        } finally {
            isLoading = false
        }
    }

    when {
        isLoading -> {
            Dialog(
                onDismissRequest = onDismiss,
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(
                    modifier = Modifier
                        .width(800.dp)
                        .padding(Spacing.large),
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 8.dp,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }

        dialogState.errorMessage != null -> {
            Dialog(
                onDismissRequest = onDismiss,
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(
                    modifier = Modifier
                        .width(800.dp)
                        .padding(Spacing.large),
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 8.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.extraLarge),
                        verticalArrangement = Arrangement.spacedBy(Spacing.large),
                    ) {
                        Text(
                            text = "Error",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        inlineErrorMessage(errorMessage = dialogState.errorMessage)
                        primaryButton(
                            onClick = onDismiss,
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(stringResource("dialog.close"))
                        }
                    }
                }
            }
        }

        project != null -> {
            editProjectFormDialog(
                project = project!!,
                onDismiss = onDismiss,
                onSave = onSave,
            )
        }
    }
}

/**
 * The actual form for editing a project (extracted to separate composable).
 */
@Composable
private fun editProjectFormDialog(
    project: Project,
    onDismiss: () -> Unit,
    onSave: (projectId: String, name: String, description: String?, knowledgeSources: List<KnowledgeSourceConfig>) -> Unit,
) {
    var projectName by remember { mutableStateOf(project.name) }
    var projectDescription by remember { mutableStateOf(project.description ?: "") }

    // Parse existing knowledge sources into UI items
    val initialSources = remember {
        parseKnowledgeSourceConfigs(project.knowledgeSources)
    }
    var knowledgeSources by remember { mutableStateOf(initialSources) }
    var showAddSourceMenu by remember { mutableStateOf(false) }
    var showUrlInputDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var nameError by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    val emptyNameError = stringResource("project.new.dialog.name.error.empty")
    val browseFolderTitle = stringResource("project.new.dialog.folder.browse")
    val browseFileTitle = stringResource("project.new.dialog.file.browse")

    // Shared knowledge source browser helper
    val sourceBrowser = remember(browseFolderTitle, browseFileTitle) {
        KnowledgeSourceBrowser(
            browseFolderTitle = browseFolderTitle,
            browseFileTitle = browseFileTitle,
        )
    }

    // Handle adding a source based on type
    fun handleAddSource(typeInfo: KnowledgeSourceItem.TypeInfo) {
        scope.launch {
            val newSources = sourceBrowser.handleAddSource(typeInfo) {
                showUrlInputDialog = true
            }
            knowledgeSources = knowledgeSources + newSources
        }
    }

    // Extract save logic to reuse in button and Enter key handler
    fun performSave() {
        // Validate project name
        if (projectName.trim().isEmpty()) {
            nameError = emptyNameError
            return
        }

        // Build knowledge source configurations from UI items
        val knowledgeSourceConfigs = buildKnowledgeSourceConfigs(knowledgeSources)

        // Detect added and removed knowledge sources
        val oldSources = project.knowledgeSources.toSet()
        val newSources = knowledgeSourceConfigs.toSet()
        val addedSources = newSources - oldSources
        val removedSources = oldSources - newSources

        // Save the project
        onSave(
            project.id,
            projectName.trim(),
            projectDescription.trim().takeIf { it.isNotEmpty() },
            knowledgeSourceConfigs,
        )

        // Emit removal events for deleted sources
        removedSources.forEach { source ->
            EventBus.post(
                ProjectIndexRemovalEvent(
                    projectId = project.id,
                    knowledgeSource = source,
                    reason = "Knowledge source removed by user",
                ),
            )
        }

        // Emit indexing events for newly added sources
        if (addedSources.isNotEmpty()) {
            EventBus.post(
                ProjectIndexingRequestedEvent(
                    projectId = project.id,
                    knowledgeSources = addedSources.toList(),
                    watchForChanges = true,
                ),
            )
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AppComponents.scaffoldDialog(
        onDismissRequest = onDismiss,
        onCloseRequest = onDismiss,
        width = 800.dp,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Text(
                text = stringResource("project.edit.dialog.title"),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        content = {
            OutlinedTextField(
                value = projectName,
                onValueChange = {
                    projectName = it
                    nameError = null
                },
                label = { Text(stringResource("project.new.dialog.name.label")) },
                placeholder = { Text(stringResource("project.new.dialog.name.placeholder")) },
                isError = nameError != null,
                supportingText = nameError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                colors = AppComponents.outlinedTextFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )

            OutlinedTextField(
                value = projectDescription,
                onValueChange = { projectDescription = it },
                label = { Text(stringResource("project.new.dialog.description.label")) },
                placeholder = { Text(stringResource("project.new.dialog.description.placeholder")) },
                minLines = 3,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
                colors = AppComponents.outlinedTextFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Text(
                    text = stringResource("project.new.dialog.sources.label"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                knowledgeSources.forEach { source ->
                    knowledgeSourceRow(
                        source = source,
                        onRemove = { knowledgeSources = knowledgeSources - source },
                    )
                }

                Box {
                    OutlinedButton(
                        onClick = { showAddSourceMenu = true },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.small))
                        Text(stringResource("project.new.dialog.sources.add"))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }

                    AppComponents.dropdownMenu(
                        expanded = showAddSourceMenu,
                        onDismissRequest = { showAddSourceMenu = false },
                    ) {
                        KnowledgeSourceItem.availableTypes.forEachIndexed { index, typeInfo ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showAddSourceMenu = false
                                        handleAddSource(typeInfo)
                                    }
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                            ) {
                                Icon(
                                    typeInfo.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = stringResource(typeInfo.typeLabelKey),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            if (index < KnowledgeSourceItem.availableTypes.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        },
        actions = {
            secondaryButton(
                onClick = onDismiss,
            ) {
                Text(stringResource("action.cancel"))
            }

            Spacer(modifier = Modifier.width(Spacing.small))

            primaryButton(
                onClick = ::performSave,
                enabled = projectName.trim().isNotEmpty(),
            ) {
                Text(stringResource("action.save"))
            }
        },
    )

    // Show URL input dialog when requested
    if (showUrlInputDialog) {
        urlInputDialog(
            onDismiss = { showUrlInputDialog = false },
            onUrlAdded = { url ->
                knowledgeSources = knowledgeSources + KnowledgeSourceItem.Url(
                    id = UUID.randomUUID().toString(),
                    url = url,
                    isValid = validateUrl(url),
                )
            },
        )
    }
}

/**
 * Composable for displaying a single knowledge source row with remove button
 */
@Composable
fun knowledgeSourceRow(
    source: KnowledgeSourceItem,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.extraSmall),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                source.typeInfo.icon,
                contentDescription = null,
                modifier = Modifier.padding(end = Spacing.small).size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = source.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (source.isValid) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Valid",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp).padding(start = 4.dp),
                )
            } else {
                Icon(
                    Icons.Default.Error,
                    contentDescription = "Invalid",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp).padding(start = 4.dp),
                )
            }
        }

        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
