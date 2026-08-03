/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.askimo.core.chat.domain.Project
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing

/**
 * Reusable component for "Move to Project" menu with popup submenu.
 * Displays a menu item that shows a submenu on hover with:
 * - "New Project" action
 * - Separator
 * - List of existing projects
 *
 * This component is reusable across different views (NavigationSidebar, ChatView, ProjectView, etc.)
 *
 * @param projects List of available projects
 * @param onNewProject Callback when "New Project" is selected
 * @param onSelectProject Callback when a project is selected
 * @param onDismiss Callback to close the parent menu
 */
@Composable
fun moveToProjectMenuItem(
    projects: List<Project>,
    onNewProject: () -> Unit,
    onSelectProject: (Project) -> Unit,
    onDismiss: () -> Unit,
) {
    var showSubmenu by remember { mutableStateOf(false) }
    var itemWidth by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    Box {
        DropdownMenuItem(
            text = { Text(stringResource("session.move.to.project")) },
            onClick = {
                showSubmenu = !showSubmenu
            },
            leadingIcon = {
                Icon(
                    Icons.AutoMirrored.Filled.DriveFileMove,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            },
            trailingIcon = {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            },
            modifier = Modifier
                .pointerHoverIcon(PointerIcon.Hand)
                .onGloballyPositioned { coordinates ->
                    itemWidth = with(density) { coordinates.size.width.toDp() }
                },
        )

        // Submenu popup
        if (showSubmenu) {
            Box(
                modifier = Modifier.offset(x = itemWidth, y = (-8).dp),
            ) {
                AppComponents.dropdownMenu(
                    expanded = showSubmenu,
                    onDismissRequest = { showSubmenu = false },
                ) {
                    Column {
                        // "New Project" option
                        DropdownMenuItem(
                            text = { Text(stringResource("session.move.to.project.new")) },
                            onClick = {
                                showSubmenu = false
                                onDismiss()
                                onNewProject()
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )

                        // Separator
                        if (projects.isNotEmpty()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = Spacing.extraSmall),
                            )
                        }

                        // List of existing projects
                        projects.forEach { project ->
                            DropdownMenuItem(
                                text = { Text(project.name) },
                                onClick = {
                                    showSubmenu = false
                                    onDismiss()
                                    onSelectProject(project)
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                            )
                        }

                        // Show message if no projects
                        if (projects.isEmpty()) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "No projects available",
                                        style = AppTextStyles.caption,
                                    )
                                },
                                onClick = { /* Do nothing */ },
                                enabled = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Reusable "Remove from Project" menu item.
 * Displays a menu item to remove a session from its current project.
 *
 * @param projectName Name of the current project
 * @param onRemoveFromProject Callback when remove is selected
 * @param onDismiss Callback to close the parent menu
 */
@Composable
fun removeFromProjectMenuItem(
    projectName: String,
    onRemoveFromProject: () -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(stringResource("session.remove.from.project", projectName))
        },
        onClick = {
            onDismiss()
            onRemoveFromProject()
        },
        leadingIcon = {
            Icon(
                Icons.Default.FolderOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
    )
}
