/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.resourcecollection

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.domain.ResourceCollection
import io.askimo.core.rag.container.IndexingContainerType
import io.askimo.desktop.knowledgesource.KnowledgeSourceBrowser
import io.askimo.desktop.knowledgesource.KnowledgeSourceItem
import io.askimo.desktop.knowledgesource.applyKnowledgeSourceDiff
import io.askimo.desktop.knowledgesource.buildKnowledgeSourceConfigs
import io.askimo.desktop.knowledgesource.knowledgeSourceRow
import io.askimo.desktop.knowledgesource.parseKnowledgeSourceConfigs
import io.askimo.desktop.knowledgesource.urlInputDialog
import io.askimo.desktop.knowledgesource.validateUrl
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.collections.plus

/**
 * Dialog for editing an existing resource collection.
 *
 * Mirrors [io.askimo.desktop.project.editProjectDialog]'s UX (name, description,
 * knowledge sources) using the shared `knowledgesource` components instead of
 * depending on the `project` package.
 */
@Composable
fun editResourceCollectionDialog(
    collection: ResourceCollection,
    onDismiss: () -> Unit,
    onSave: suspend (collectionId: String, name: String, description: String?, knowledgeSources: List<KnowledgeSourceConfig>) -> Boolean,
) {
    var collectionName by remember { mutableStateOf(collection.name) }
    var collectionDescription by remember { mutableStateOf(collection.description ?: "") }

    // Parse existing knowledge sources into UI items
    val initialSources = remember {
        parseKnowledgeSourceConfigs(collection.knowledgeSources)
    }
    var knowledgeSources by remember { mutableStateOf(initialSources) }
    var showAddSourceMenu by remember { mutableStateOf(false) }
    var showUrlInputDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var nameError by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    val emptyNameError = stringResource("resourcecollection.new.dialog.error.empty.name")
    val browseFolderTitle = stringResource("resourcecollection.new.dialog.folder.browse")
    val browseFileTitle = stringResource("resourcecollection.new.dialog.file.browse")

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
        if (collectionName.trim().isEmpty()) {
            nameError = emptyNameError
            return
        }

        // Build knowledge source configurations from UI items
        val knowledgeSourceConfigs = buildKnowledgeSourceConfigs(knowledgeSources)

        scope.launch {
            // Await the save and only post the diff (which mutates the index) if it actually
            // persisted — otherwise the index would drift from the (unsaved) collection config.
            val saved = onSave(
                collection.id,
                collectionName.trim(),
                collectionDescription.trim().takeIf { it.isNotEmpty() },
                knowledgeSourceConfigs,
            )

            if (saved) {
                applyKnowledgeSourceDiff(
                    containerId = collection.id,
                    containerType = IndexingContainerType.RESOURCE_COLLECTION,
                    oldSources = collection.knowledgeSources,
                    newSources = knowledgeSourceConfigs,
                )
            }
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
        showSectionDividers = true,
        title = {
            Text(
                text = stringResource("resourcecollection.edit.dialog.title"),
                style = AppTextStyles.pageTitle,
            )
        },
        stickyHeader = {
            OutlinedTextField(
                value = collectionName,
                onValueChange = {
                    collectionName = it
                    nameError = null
                },
                label = { Text(stringResource("resourcecollection.new.dialog.name.label")) },
                placeholder = { Text(stringResource("resourcecollection.new.dialog.name.placeholder")) },
                isError = nameError != null,
                supportingText = nameError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                colors = AppColors.outlinedTextFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )

            OutlinedTextField(
                value = collectionDescription,
                onValueChange = { collectionDescription = it },
                label = { Text(stringResource("resourcecollection.new.dialog.description.label")) },
                placeholder = { Text(stringResource("resourcecollection.new.dialog.description.placeholder")) },
                minLines = 3,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedTextFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
        },
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Text(
                    text = stringResource("resourcecollection.new.dialog.materials.label"),
                    style = AppTextStyles.body,
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
                        Text(stringResource("resourcecollection.new.dialog.materials.add"))
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
                                    tint = AppTextStyles.primaryContent,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = stringResource(typeInfo.typeLabelKey),
                                    style = AppTextStyles.body,
                                )
                            }
                            if (index < KnowledgeSourceItem.availableTypes.lastIndex) {
                                HorizontalDivider(color = AppColors.codeBlockBorderColor())
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
                enabled = collectionName.trim().isNotEmpty(),
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
