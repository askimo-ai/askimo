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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.ProjectRefreshEvent
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.collections.plus

/**
 * Dialog for adding reference materials to an existing project
 */
@Composable
fun addReferenceMaterialDialog(
    projectId: String,
    onDismiss: () -> Unit,
    onAdd: (List<KnowledgeSourceItem>) -> Unit,
) {
    var knowledgeSources by remember { mutableStateOf<List<KnowledgeSourceItem>>(emptyList()) }
    var showAddSourceMenu by remember { mutableStateOf(false) }
    var showUrlInputDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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

    AppComponents.scaffoldDialog(
        onDismissRequest = onDismiss,
        onCloseRequest = onDismiss,
        width = 650.dp,
        tonalElevation = 6.dp,
        title = {
            Text(
                text = stringResource("projects.sources.add.dialog.title"),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        content = {
            Text(
                text = stringResource("projects.sources.add.dialog.description"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Box {
                OutlinedButton(
                    onClick = { showAddSourceMenu = true },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.small))
                    Text(stringResource("projects.sources.add.button"))
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

            if (knowledgeSources.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    knowledgeSources.forEach { source ->
                        knowledgeSourceRow(
                            source = source,
                            onRemove = {
                                knowledgeSources = knowledgeSources.filter { it.id != source.id }
                            },
                        )
                    }
                }
            }
        },
        actions = {
            secondaryButton(
                onClick = onDismiss,
            ) {
                Text(stringResource("project.new.dialog.button.cancel"))
            }

            Spacer(modifier = Modifier.width(8.dp))

            primaryButton(
                onClick = {
                    if (knowledgeSources.isNotEmpty()) {
                        onAdd(knowledgeSources)

                        EventBus.post(
                            ProjectRefreshEvent(
                                projectId = projectId,
                                reason = "Knowledge sources added via dialog",
                            ),
                        )

                        onDismiss()
                    }
                },
                enabled = knowledgeSources.isNotEmpty(),
            ) {
                Text(stringResource("projects.sources.add.dialog.button.add"))
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
