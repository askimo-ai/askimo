/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.session

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.askimo.core.chat.domain.Project
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.LocalFontScale
import io.askimo.ui.shell.DeveloperModePreferences

/**
 * Reusable session action menu items.
 */
object SessionActionMenu {

    @Composable
    fun exportMenuItem(
        onExport: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        val fontScale = LocalFontScale.current
        AppComponents.menuItem(
            text = { Text(stringResource("session.export")) },
            onClick = {
                onDismiss()
                onExport()
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Share,
                    contentDescription = null,
                    modifier = Modifier.size((20 * fontScale).dp),
                )
            },
        )
    }

    @Composable
    fun renameMenuItem(
        onRename: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        val fontScale = LocalFontScale.current
        AppComponents.menuItem(
            text = { Text(stringResource("session.rename.title")) },
            onClick = {
                onDismiss()
                onRename()
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size((20 * fontScale).dp),
                )
            },
        )
    }

    @Composable
    fun starMenuItem(
        isStarred: Boolean,
        onStar: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        val fontScale = LocalFontScale.current
        AppComponents.menuItem(
            text = {
                Text(
                    if (isStarred) {
                        stringResource("session.unstar")
                    } else {
                        stringResource("session.star")
                    },
                )
            },
            onClick = {
                onDismiss()
                onStar()
            },
            leadingIcon = {
                Icon(
                    if (isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = null,
                    tint = if (isStarred) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size((20 * fontScale).dp),
                )
            },
        )
    }

    @Composable
    fun deleteMenuItem(
        onDelete: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        val fontScale = LocalFontScale.current
        AppComponents.menuItem(
            text = { Text(stringResource("action.delete")) },
            onClick = {
                onDismiss()
                onDelete()
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size((20 * fontScale).dp),
                )
            },
        )
    }

    @Composable
    fun showSessionSummaryMenuItem(
        onShowSessionSummary: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        val fontScale = LocalFontScale.current
        AppComponents.menuItem(
            text = { Text(stringResource("developer.menu.show.session.summary")) },
            onClick = {
                onDismiss()
                onShowSessionSummary()
            },
            leadingIcon = {
                Icon(
                    Icons.Default.DeveloperMode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size((20 * fontScale).dp),
                )
            },
        )
    }

    /**
     * Menu for sidebar sessions (includes star/unstar and developer mode options).
     */
    @Composable
    fun sidebarMenu(
        isStarred: Boolean,
        projects: List<Project>,
        onExport: () -> Unit,
        onRename: () -> Unit,
        onStar: () -> Unit,
        onDelete: () -> Unit,
        onMoveToNewProject: () -> Unit,
        onMoveToExistingProject: (Project) -> Unit,
        onShowSessionSummary: () -> Unit = {},
        onDismiss: () -> Unit,
    ) {
        exportMenuItem(onExport = onExport, onDismiss = onDismiss)
        renameMenuItem(onRename = onRename, onDismiss = onDismiss)
        starMenuItem(isStarred = isStarred, onStar = onStar, onDismiss = onDismiss)

        // Move to Project submenu
        moveToProjectMenuItem(
            projects = projects,
            onNewProject = onMoveToNewProject,
            onSelectProject = onMoveToExistingProject,
            onDismiss = onDismiss,
        )

        // Show Session Summary - only in developer mode
        if (DeveloperModePreferences.isEnabled() &&
            DeveloperModePreferences.isActive.value
        ) {
            showSessionSummaryMenuItem(onShowSessionSummary = onShowSessionSummary, onDismiss = onDismiss)
        }

        deleteMenuItem(onDelete = onDelete, onDismiss = onDismiss)
    }

    /**
     * Menu for project view sessions (no star/unstar).
     * Includes move to project functionality.
     */
    @Composable
    fun projectViewMenu(
        currentProjectId: String,
        currentProjectName: String,
        availableProjects: List<Project>,
        onExport: () -> Unit,
        onRename: () -> Unit,
        onDelete: () -> Unit,
        onMoveToNewProject: () -> Unit,
        onMoveToExistingProject: (Project) -> Unit,
        onRemoveFromProject: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        exportMenuItem(onExport = onExport, onDismiss = onDismiss)
        renameMenuItem(onRename = onRename, onDismiss = onDismiss)

        // Move to Project submenu - exclude current project
        val projectsToShow = availableProjects.filter { it.id != currentProjectId }
        moveToProjectMenuItem(
            projects = projectsToShow,
            onNewProject = onMoveToNewProject,
            onSelectProject = onMoveToExistingProject,
            onDismiss = onDismiss,
        )

        // Remove from Project - separate menu item at same level
        removeFromProjectMenuItem(
            projectName = currentProjectName,
            onRemoveFromProject = onRemoveFromProject,
            onDismiss = onDismiss,
        )

        deleteMenuItem(onDelete = onDelete, onDismiss = onDismiss)
    }

    /**
     * Menu for project header actions (edit, delete, reindex for developers).
     */
    @Composable
    fun projectActionMenu(
        onEditProject: () -> Unit,
        onDeleteProject: () -> Unit,
        onReindexProject: (() -> Unit)? = null,
        onDismiss: () -> Unit,
    ) {
        val fontScale = LocalFontScale.current
        AppComponents.menuItem(
            text = { Text(stringResource("project.edit")) },
            onClick = {
                onDismiss()
                onEditProject()
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size((20 * fontScale).dp),
                )
            },
        )

        // Re-index Project - only shown when developer mode is enabled
        onReindexProject?.let { reindex ->
            AppComponents.menuItem(
                text = { Text(stringResource("project.reindex")) },
                onClick = {
                    onDismiss()
                    reindex()
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.DeveloperMode,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size((20 * fontScale).dp),
                    )
                },
            )
        }

        AppComponents.menuItem(
            text = { Text(stringResource("project.delete")) },
            onClick = {
                onDismiss()
                onDeleteProject()
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size((20 * fontScale).dp),
                )
            },
        )
    }
}
