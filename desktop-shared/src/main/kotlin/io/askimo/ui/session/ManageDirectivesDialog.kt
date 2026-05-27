/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.session

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.askimo.core.chat.domain.ChatDirective
import io.askimo.core.util.TimeUtil
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents

@Composable
fun manageDirectivesDialog(
    directives: List<ChatDirective>,
    onDismiss: () -> Unit,
    onAdd: (name: String, content: String, applyToCurrent: Boolean) -> Unit,
    onUpdate: (id: String, newName: String, newContent: String) -> Unit,
    onDelete: (id: String) -> Unit,
) {
    var editingDirective by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    var editContent by remember { mutableStateOf("") }
    var editError by remember { mutableStateOf<String?>(null) }

    var showNewDirectiveDialog by remember { mutableStateOf(false) }

    // Track which directives have expanded content
    var expandedDirectives by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Pre-load error messages in composable context
    val nameRequiredError = stringResource("directive.edit.name.required")
    val contentRequiredError = stringResource("directive.edit.content.required")

    if (showNewDirectiveDialog) {
        newDirectiveDialog(
            onDismiss = { showNewDirectiveDialog = false },
            onConfirm = { name, content, applyToCurrent ->
                onAdd(name, content, applyToCurrent)
                showNewDirectiveDialog = false
            },
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .width(900.dp)
                .height(700.dp)
                .padding(16.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource("directive.manage.title"),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = stringResource("action.close"))
                    }
                }

                HorizontalDivider()

                // Directives list
                if (directives.isEmpty()) {
                    Text(
                        text = stringResource("directive.manage.empty"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(vertical = 32.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(directives, key = { it.id }) { directive ->
                            val isExpanded = directive.id in expandedDirectives
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = AppComponents.sidebarSurfaceColor(),
                                ),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                        .animateContentSize(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Surface(
                                                shape = MaterialTheme.shapes.small,
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                modifier = Modifier.size(36.dp),
                                            ) {
                                                Icon(
                                                    Icons.Outlined.Description,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    modifier = Modifier.padding(8.dp),
                                                )
                                            }
                                            Text(
                                                text = directive.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }

                                        if (editingDirective != directive.id) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                IconButton(
                                                    onClick = {
                                                        editingDirective = directive.id
                                                        editName = directive.name
                                                        editContent = directive.content
                                                        editError = null
                                                    },
                                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                                ) {
                                                    Icon(Icons.Default.Edit, contentDescription = stringResource("action.edit"), tint = MaterialTheme.colorScheme.onSurface)
                                                }
                                                IconButton(
                                                    onClick = { onDelete(directive.id) },
                                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                                ) {
                                                    Icon(Icons.Default.Delete, contentDescription = stringResource("action.delete"), tint = MaterialTheme.colorScheme.error)
                                                }
                                            }
                                        }
                                    }

                                    if (editingDirective == directive.id) {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedTextField(
                                                value = editName,
                                                onValueChange = {
                                                    editName = it
                                                    editError = null
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                label = { Text(stringResource("directive.edit.name.label")) },
                                                placeholder = { Text(stringResource("directive.edit.name.placeholder")) },
                                                singleLine = true,
                                                isError = editError != null && editName.isBlank(),
                                                colors = AppComponents.outlinedTextFieldColors(),
                                            )
                                            OutlinedTextField(
                                                value = editContent,
                                                onValueChange = {
                                                    editContent = it
                                                    editError = null
                                                },
                                                modifier = Modifier.fillMaxWidth().height(200.dp),
                                                label = { Text(stringResource("directive.edit.content.label")) },
                                                placeholder = { Text(stringResource("directive.edit.content.placeholder")) },
                                                maxLines = 10,
                                                isError = editError != null,
                                                supportingText = editError?.let { { Text(it) } },
                                                colors = AppComponents.outlinedTextFieldColors(),
                                            )
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                secondaryButton(onClick = {
                                                    editingDirective = null
                                                    editName = ""
                                                    editContent = ""
                                                    editError = null
                                                }) { Text(stringResource("settings.cancel")) }

                                                primaryButton(onClick = {
                                                    if (editName.isBlank()) {
                                                        editError = nameRequiredError
                                                    } else if (editContent.isBlank()) {
                                                        editError = contentRequiredError
                                                    } else {
                                                        onUpdate(directive.id, editName.trim(), editContent.trim())
                                                        editingDirective = null
                                                        editName = ""
                                                        editContent = ""
                                                        editError = null
                                                    }
                                                }) { Text(stringResource("settings.save")) }
                                            }
                                        }
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(
                                                text = directive.content,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = if (isExpanded) Int.MAX_VALUE else 3,
                                                overflow = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                                            )
                                            if (directive.content.length > 120 || directive.content.lines().size > 3) {
                                                Text(
                                                    text = if (isExpanded) stringResource("action.show.less") else stringResource("action.show.more"),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier
                                                        .pointerHoverIcon(PointerIcon.Hand)
                                                        .clickable {
                                                            expandedDirectives = if (isExpanded) {
                                                                expandedDirectives - directive.id
                                                            } else {
                                                                expandedDirectives + directive.id
                                                            }
                                                        },
                                                )
                                            }
                                            AssistChip(
                                                onClick = {},
                                                label = {
                                                    Text(
                                                        text = stringResource("directive.created", TimeUtil.formatDisplay(directive.createdAt)),
                                                        style = MaterialTheme.typography.labelSmall,
                                                    )
                                                },
                                                leadingIcon = {
                                                    Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(14.dp))
                                                },
                                                modifier = Modifier.height(28.dp),
                                                colors = AssistChipDefaults.assistChipColors(
                                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                ),
                                                border = AssistChipDefaults.assistChipBorder(
                                                    enabled = true,
                                                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Bottom bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    primaryButton(onClick = { showNewDirectiveDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(stringResource("directive.new.title"))
                    }
                    secondaryButton(onClick = onDismiss) {
                        Text(stringResource("action.close"))
                    }
                }
            }
        }
    }
}
