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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import io.askimo.core.chat.domain.Label
import io.askimo.core.db.DatabaseManager
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.ProjectsRefreshEvent
import io.askimo.core.event.internal.SessionsRefreshEvent
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents.dropdownMenu
import io.askimo.ui.common.theme.AppComponents.menuItem
import io.askimo.ui.common.theme.AppTextStyles
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
    var showManageLabelsDialog by remember { mutableStateOf(false) }

    val sessionRepository = remember { DatabaseManager.getInstance().getChatSessionRepository() }
    val projectRepository = remember { DatabaseManager.getInstance().getProjectRepository() }
    val labelRepository = remember { DatabaseManager.getInstance().getLabelRepository() }

    // Labels assigned to this session plus every label the user has defined, reloaded whenever
    // labelsRefreshKey bumps (after a toggle or a create/update/delete in the manage-labels dialog).
    var labelsRefreshKey by remember { mutableIntStateOf(0) }
    var allLabels by remember { mutableStateOf<List<Label>>(emptyList()) }
    var assignedLabels by remember(sessionId) { mutableStateOf<List<Label>>(emptyList()) }
    LaunchedEffect(sessionId, labelsRefreshKey) {
        allLabels = labelRepository.list()
        assignedLabels = labelRepository.labelsForSession(sessionId)
    }
    val labelUsageCounts = remember(allLabels) {
        allLabels.associate { it.id to labelRepository.sessionIdsForLabel(it.id).size }
    }

    fun toggleSessionLabel(labelId: String) {
        val isAssigned = assignedLabels.any { it.id == labelId }
        val newIds = if (isAssigned) {
            assignedLabels.filterNot { it.id == labelId }.map { it.id }
        } else {
            assignedLabels.map { it.id } + labelId
        }
        labelRepository.setLabelsForSession(sessionId, newIds)
        labelsRefreshKey++
        EventBus.post(SessionsRefreshEvent(reason = "Session $sessionId labels changed"))
    }

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
            menuItem(
                text = {
                    Text(
                        text = stringResource("session.export"),
                        style = AppTextStyles.body,
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
            )
            menuItem(
                text = {
                    Text(
                        text = stringResource("action.rename"),
                        style = AppTextStyles.body,
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
            )

            menuItem(
                text = {
                    Text(
                        text = if (isStarred) stringResource("session.unstar") else stringResource("session.star"),
                        style = AppTextStyles.body,
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

            // Labels submenu
            labelsMenuItem(
                allLabels = allLabels,
                assignedLabelIds = assignedLabels.map { it.id }.toSet(),
                onToggleLabel = ::toggleSessionLabel,
                onManageLabels = {
                    expanded = false
                    showManageLabelsDialog = true
                },
                onDismiss = { expanded = false },
            )

            // Show Session Summary - only visible in developer mode
            if (DeveloperModePreferences.isEnabled() &&
                DeveloperModePreferences.isActive.value
            ) {
                menuItem(
                    text = {
                        Text(
                            text = stringResource("developer.menu.show.session.summary"),
                            style = AppTextStyles.body,
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
                )
            }

            menuItem(
                text = {
                    Text(
                        text = stringResource("action.delete"),
                        style = AppTextStyles.body,
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

    if (showManageLabelsDialog) {
        manageLabelsDialog(
            labels = allLabels,
            usageCounts = labelUsageCounts,
            onCreate = { name, color ->
                labelRepository.create(Label(name = name.trim(), color = color))
                labelsRefreshKey++
                EventBus.post(SessionsRefreshEvent(reason = "Label created"))
            },
            onUpdate = { label ->
                labelRepository.update(label)
                labelsRefreshKey++
                EventBus.post(SessionsRefreshEvent(reason = "Label updated"))
            },
            onDelete = { labelId ->
                labelRepository.delete(labelId)
                labelsRefreshKey++
                EventBus.post(SessionsRefreshEvent(reason = "Label deleted"))
            },
            onDismiss = { showManageLabelsDialog = false },
        )
    }
}
