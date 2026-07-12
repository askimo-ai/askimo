/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.askimo.core.chat.service.ProjectService
import io.askimo.core.logging.logger
import io.askimo.ui.common.components.inlineErrorMessage
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.rememberDialogState
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.util.FileDialogUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import java.util.UUID
import kotlin.collections.plus
import kotlin.time.Duration.Companion.milliseconds

private object NewProjectDialog
private val log = logger<NewProjectDialog>()

/** Number of seconds to wait before auto-dismissing the success screen. */
private const val SUCCESS_COUNTDOWN_SECONDS = 5

/**
 * Dialog for creating a new project with name, description, and optional knowledge sources.
 */
@Composable
fun newProjectDialog(
    onDismiss: () -> Unit,
    onCreateProject: (name: String, description: String?) -> Unit,
    onNavigateToProject: ((projectId: String) -> Unit)? = null,
    projectService: ProjectService = GlobalContext.get().get(),
) {
    var projectName by remember { mutableStateOf("") }
    var projectDescription by remember { mutableStateOf("") }
    var knowledgeSources by remember { mutableStateOf<List<KnowledgeSourceItem>>(emptyList()) }
    var showAddSourceMenu by remember { mutableStateOf(false) }
    var showUrlInputDialog by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var showSuccess by remember { mutableStateOf(false) }
    var createdProjectName by remember { mutableStateOf("") }
    var countdown by remember { mutableStateOf(SUCCESS_COUNTDOWN_SECONDS) }

    val scope = rememberCoroutineScope()
    val dialogState = rememberDialogState()

    // Retrieve string resources in composable scope
    val errorEmptyName = stringResource("project.new.dialog.error.empty.name")
    val errorCreateFailed = stringResource("project.new.dialog.error.create.failed")
    val browseFolderTitle = stringResource("project.new.dialog.folder.browse")
    val browseFileTitle = stringResource("project.new.dialog.file.browse")

    // Countdown and auto-dismiss when success is shown
    LaunchedEffect(showSuccess) {
        if (showSuccess) {
            countdown = 5
            while (countdown > 0) {
                delay(1000.milliseconds)
                countdown--
            }
            onDismiss()
        }
    }

    // Browse for folder
    fun browseForFolder() {
        scope.launch {
            val folderPath = FileDialogUtils.pickFolderPath(browseFolderTitle) ?: return@launch
            knowledgeSources = knowledgeSources + KnowledgeSourceItem.Folder(
                id = UUID.randomUUID().toString(),
                path = folderPath,
                isValid = validateFolder(folderPath),
            )
        }
    }

    // Browse for files
    fun browseForFiles() {
        scope.launch {
            val paths = FileDialogUtils.pickFilePaths(browseFileTitle)
            paths.forEach { path ->
                knowledgeSources = knowledgeSources + KnowledgeSourceItem.File(
                    id = UUID.randomUUID().toString(),
                    path = path,
                    isValid = validateFile(path),
                )
            }
        }
    }

    // Handle adding a source based on type
    fun handleAddSource(typeInfo: KnowledgeSourceItem.TypeInfo) {
        when (typeInfo) {
            KnowledgeSourceItem.TypeInfo.FOLDER -> browseForFolder()
            KnowledgeSourceItem.TypeInfo.FILE -> browseForFiles()
            KnowledgeSourceItem.TypeInfo.URL -> showUrlInputDialog = true
        }
    }

    // Validate and create project
    fun handleCreate() {
        // Validate project name
        if (projectName.isBlank()) {
            nameError = errorEmptyName
            return
        }

        // Save project to database
        scope.launch {
            try {
                // Build knowledge source configurations from UI items
                val knowledgeSourceConfigs = buildKnowledgeSourceConfigs(knowledgeSources)

                val createdProject = projectService.createProject(
                    name = projectName.trim(),
                    description = projectDescription.takeIf { it.isNotBlank() },
                    knowledgeSources = knowledgeSourceConfigs,
                )

                // Show success message
                createdProjectName = projectName.trim()
                showSuccess = true

                // Call callback with project info
                onCreateProject(
                    createdProject.name,
                    createdProject.description,
                )

                // Navigate immediately if handler provided (skips countdown screen)
                if (onNavigateToProject != null) {
                    onDismiss()
                    onNavigateToProject(createdProject.id)
                }
            } catch (e: Exception) {
                log.error("Failed to create project", e)
                dialogState.setError(e, errorCreateFailed.format(e.message ?: "Unknown error"))
            }
        }
    }

    if (showSuccess) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                modifier = Modifier.width(650.dp),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier.padding(Spacing.extraLarge),
                    verticalArrangement = Arrangement.spacedBy(Spacing.large),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Success",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(64.dp),
                    )

                    Text(
                        text = stringResource("project.new.dialog.success.title"),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Text(
                        text = stringResource("project.new.dialog.success.message", createdProjectName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        text = stringResource(
                            "project.new.dialog.success.countdown",
                            countdown,
                            if (countdown != 1) "s" else "",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(Spacing.small))

                    primaryButton(
                        onClick = onDismiss,
                    ) {
                        Text(stringResource("project.new.dialog.success.close"))
                    }
                }
            }
        }
    } else {
        AppComponents.scaffoldDialog(
            onDismissRequest = onDismiss,
            onCloseRequest = onDismiss,
            width = 650.dp,
            title = {
                Text(
                    text = stringResource("project.new.dialog.title"),
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
                    modifier = Modifier.fillMaxWidth(),
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
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { handleCreate() }),
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
                            KnowledgeSourceItem.availableTypes.forEach { typeInfo ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                typeInfo.icon,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.padding(end = Spacing.small).size(20.dp),
                                            )
                                            Text(stringResource(typeInfo.typeLabelKey))
                                        }
                                    },
                                    onClick = {
                                        showAddSourceMenu = false
                                        handleAddSource(typeInfo)
                                    },
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                )
                            }
                        }
                    }
                }

                inlineErrorMessage(errorMessage = dialogState.errorMessage)
            },
            actions = {
                secondaryButton(
                    onClick = onDismiss,
                ) {
                    Text(stringResource("project.new.dialog.button.cancel"))
                }

                Spacer(modifier = Modifier.width(8.dp))

                primaryButton(
                    onClick = { handleCreate() },
                ) {
                    Text(stringResource("project.new.dialog.button.create"))
                }
            },
        )
    }

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
