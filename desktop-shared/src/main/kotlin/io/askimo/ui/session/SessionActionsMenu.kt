/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import io.askimo.core.db.DatabaseManager
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.ProjectsRefreshEvent
import io.askimo.core.event.internal.SessionsRefreshEvent
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents.dropdownMenu
import io.askimo.ui.shell.DeveloperModePreferences

/**
 * Reusable session actions menu component that provides a dropdown with session-related actions.
 * Currently supports Rename, Export, Move to Project, Delete, and Show Session Summary (developer mode) actions.
 *
 * @param sessionId The ID of the session to perform actions on
 * @param onRenameSession Callback invoked when the rename action is triggered
 * @param onExportSession Callback invoked when the export action is triggered
 * @param onDeleteSession Callback invoked when the delete action is triggered
 * @param onStarSession Callback invoked when the star action is triggered
 * @param onShowSessionSummary Callback invoked when the show session summary action is triggered (developer mode only)
 * @param onMoveSessionToNewProject Callback invoked when the session is moved to a new project
 * @param modifier Optional modifier for the IconButton
 */
@Composable
fun sessionActionsMenu(
    sessionId: String,
    onRenameSession: (String) -> Unit = {},
    onExportSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onStarSession: (String, Boolean) -> Unit = { _, _ -> },
    onShowSessionSummary: (String) -> Unit = {},
    onMoveSessionToNewProject: (sessionId: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val sessionRepository = remember { DatabaseManager.getInstance().getChatSessionRepository() }
    val projectRepository = remember { DatabaseManager.getInstance().getProjectRepository() }

    // Load current session to check if it belongs to a project
    val currentSession = remember(sessionId) { sessionRepository.getSession(sessionId) }
    val currentProjectId = currentSession?.projectId
    var isStarred by remember(sessionId) { mutableStateOf(currentSession?.isStarred ?: false) }
    val currentProject = remember(currentProjectId) {
        currentProjectId?.let { projectRepository.getProject(it) }
    }

    // Load available projects and filter out the project the session already belongs to
    val availableProjects = remember(sessionId) {
        val allProjects = projectRepository.getAllProjects()

        // Exclude the project the session already belongs to
        if (currentProjectId != null) {
            allProjects.filter { it.id != currentProjectId }
        } else {
            allProjects
        }
    }

    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = modifier.pointerHoverIcon(PointerIcon.Hand),
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        dropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource("session.export"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                onClick = {
                    onExportSession(sessionId)
                    expanded = false
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource("action.rename"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                onClick = {
                    onRenameSession(sessionId)
                    expanded = false
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            )

            DropdownMenuItem(
                text = {
                    Text(
                        text = if (isStarred) stringResource("session.unstar") else stringResource("session.star"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                onClick = {
                    val newStarred = !isStarred
                    isStarred = newStarred
                    onStarSession(sessionId, newStarred)
                    expanded = false
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            )

            // Move to Project submenu
            moveToProjectMenuItem(
                projects = availableProjects,
                onNewProject = {
                    expanded = false
                    onMoveSessionToNewProject(sessionId)
                },
                onSelectProject = { selectedProject ->
                    // Associate session with existing project
                    sessionRepository.updateSessionProject(sessionId, selectedProject.id)
                    // Publish events to refresh both projects and sessions
                    EventBus.post(
                        ProjectsRefreshEvent(
                            reason = "Session $sessionId moved to project ${selectedProject.id}",
                        ),
                    )
                    EventBus.post(
                        SessionsRefreshEvent(
                            reason = "Session $sessionId moved to project ${selectedProject.id}",
                        ),
                    )
                },
                onDismiss = { expanded = false },
            )

            // Remove from Project - separate menu item at same level (only if session belongs to a project)
            if (currentProject != null) {
                removeFromProjectMenuItem(
                    projectName = currentProject.name,
                    onRemoveFromProject = {
                        // Remove session from current project (set projectId to null)
                        sessionRepository.updateSessionProject(sessionId, null)
                        // Publish events to refresh both projects and sessions
                        EventBus.post(
                            ProjectsRefreshEvent(
                                reason = "Session $sessionId removed from project",
                            ),
                        )
                        EventBus.post(
                            SessionsRefreshEvent(
                                reason = "Session $sessionId removed from project",
                            ),
                        )
                    },
                    onDismiss = { expanded = false },
                )
            }

            // Show Session Summary - only visible in developer mode
            if (DeveloperModePreferences.isEnabled() &&
                DeveloperModePreferences.isActive.value
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource("developer.menu.show.session.summary"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    onClick = {
                        onShowSessionSummary(sessionId)
                        expanded = false
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.DeveloperMode,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                )
            }

            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource("action.delete"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                onClick = {
                    expanded = false
                    showDeleteDialog = true
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            )
        }
    }

    if (showDeleteDialog) {
        deleteSessionDialog(
            sessionTitle = currentSession?.title ?: sessionId,
            onConfirm = {
                showDeleteDialog = false
                onDeleteSession(sessionId)
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}
