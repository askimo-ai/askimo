/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.project

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.chat.domain.Project
import io.askimo.core.util.TimeUtil
import io.askimo.ui.common.components.tablePageSizeSelector
import io.askimo.ui.common.components.tablePagination
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.themedTooltip

private enum class ProjectSortColumn { CREATED, MODIFIED }
private enum class ProjectSortDirection { ASC, DESC }

@Composable
fun projectsView(
    viewModel: ProjectsViewModel,
    onSelectProject: (String) -> Unit,
    onEditProject: (String) -> Unit,
    onNewProject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    var sortColumn by remember { mutableStateOf(ProjectSortColumn.MODIFIED) }
    var sortDirection by remember { mutableStateOf(ProjectSortDirection.DESC) }
    var pageSize by remember { mutableStateOf(10) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = ThemePreferences.CONTENT_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 36.dp, top = 24.dp, bottom = 24.dp),
            ) {
                // ── Title + New Project button ─────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource("projects.title"),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Button(
                        onClick = onNewProject,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(stringResource("project.new"))
                    }
                }

                Text(
                    text = stringResource("projects.description"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ── Loading / error / empty ────────────────────────────────────
                when {
                    viewModel.isLoading -> {
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }

                    viewModel.errorMessage != null -> {
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    stringResource("projects.error", viewModel.errorMessage ?: ""),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                TextButton(onClick = {
                                    viewModel.clearError()
                                    viewModel.refresh()
                                }) {
                                    Text(stringResource("action.retry"))
                                }
                            }
                        }
                    }

                    viewModel.pagedProjects?.isEmpty == true -> {
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource("projects.empty"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource("projects.empty.hint"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    else -> {
                        projectTable(
                            projects = viewModel.pagedProjects!!.items,
                            onSelectProject = onSelectProject,
                            onEditProject = onEditProject,
                            onDeleteProject = { viewModel.deleteProject(it) },
                            onStarProject = { id, starred -> viewModel.starProject(id, starred) },
                            sortColumn = sortColumn,
                            sortDirection = sortDirection,
                            onSortChange = { col ->
                                if (sortColumn == col) {
                                    sortDirection = if (sortDirection == ProjectSortDirection.DESC) ProjectSortDirection.ASC else ProjectSortDirection.DESC
                                } else {
                                    sortColumn = col
                                    sortDirection = ProjectSortDirection.DESC
                                }
                            },
                        )
                    }
                }

                // ── Pagination ─────────────────────────────────────────────────
                val pagedProjects = viewModel.pagedProjects
                if (pagedProjects != null && pagedProjects.totalPages > 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                    tablePagination(
                        currentPage = pagedProjects.currentPage,
                        totalPages = pagedProjects.totalPages,
                        hasPrevious = pagedProjects.hasPreviousPage,
                        hasNext = pagedProjects.hasNextPage,
                        pageSize = pageSize,
                        onPrevious = { viewModel.previousPage() },
                        onNext = { viewModel.nextPage() },
                        onPageSizeChange = { size ->
                            pageSize = size
                            viewModel.setPageSize(size)
                        },
                    )
                } else if (viewModel.pagedProjects != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    tablePageSizeSelector(
                        pageSize = pageSize,
                        onPageSizeChange = { size ->
                            pageSize = size
                            viewModel.setPageSize(size)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            style = ScrollbarStyle(
                minimalHeight = 16.dp,
                thickness = 8.dp,
                shape = MaterialTheme.shapes.small,
                hoverDurationMillis = 300,
                unhoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                hoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            ),
        )
    }
}

// ── Table ─────────────────────────────────────────────────────────────────────

@Composable
private fun projectTable(
    projects: List<Project>,
    onSelectProject: (String) -> Unit,
    onEditProject: (String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onStarProject: (String, Boolean) -> Unit,
    sortColumn: ProjectSortColumn,
    sortDirection: ProjectSortDirection,
    onSortChange: (ProjectSortColumn) -> Unit,
) {
    val sortedProjects = remember(projects, sortColumn, sortDirection) {
        val comparator = when (sortColumn) {
            ProjectSortColumn.MODIFIED -> compareBy<Project> { it.updatedAt }
            ProjectSortColumn.CREATED -> compareBy<Project> { it.createdAt }
        }
        if (sortDirection == ProjectSortDirection.DESC) {
            projects.sortedWith(comparator.reversed())
        } else {
            projects.sortedWith(comparator)
        }
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Star column spacer
                Spacer(modifier = Modifier.size(36.dp))
                // Name column (non-sortable)
                Text(
                    text = stringResource("projects.col.name"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // Created sortable header
                projectSortableHeader(
                    label = stringResource("projects.col.created"),
                    column = ProjectSortColumn.CREATED,
                    currentColumn = sortColumn,
                    direction = sortDirection,
                    onClick = { onSortChange(ProjectSortColumn.CREATED) },
                    modifier = Modifier.widthIn(min = 140.dp).padding(horizontal = 16.dp),
                )
                // Modified sortable header
                projectSortableHeader(
                    label = stringResource("projects.col.modified"),
                    column = ProjectSortColumn.MODIFIED,
                    currentColumn = sortColumn,
                    direction = sortDirection,
                    onClick = { onSortChange(ProjectSortColumn.MODIFIED) },
                    modifier = Modifier.widthIn(min = 140.dp).padding(horizontal = 16.dp),
                )
                // Actions column spacer
                Spacer(modifier = Modifier.size(36.dp))
            }

            HorizontalDivider()

            sortedProjects.forEachIndexed { index, project ->
                projectRow(
                    project = project,
                    onSelectProject = onSelectProject,
                    onEditProject = onEditProject,
                    onDeleteProject = onDeleteProject,
                    onStarProject = onStarProject,
                )
                if (index < sortedProjects.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
private fun projectSortableHeader(
    label: String,
    column: ProjectSortColumn,
    currentColumn: ProjectSortColumn,
    direction: ProjectSortDirection,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isActive = currentColumn == column
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isActive) {
            Icon(
                imageVector = if (direction == ProjectSortDirection.DESC) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
private fun projectRow(
    project: Project,
    onSelectProject: (String) -> Unit,
    onEditProject: (String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onStarProject: (String, Boolean) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    if (showDeleteDialog) {
        deleteProjectDialog(
            projectName = project.name,
            onConfirm = {
                showDeleteDialog = false
                onDeleteProject(project.id)
            },
            onDismiss = { showDeleteDialog = false },
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .background(
                if (isHovered) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onSelectProject(project.id) }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Star toggle
        IconButton(
            onClick = { onStarProject(project.id, !project.isStarred) },
            modifier = Modifier.size(36.dp).pointerHoverIcon(PointerIcon.Hand),
        ) {
            Icon(
                imageVector = if (project.isStarred) Icons.Default.Star else Icons.Outlined.StarOutline,
                contentDescription = if (project.isStarred) "Unstar project" else "Star project",
                tint = if (project.isStarred) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
                modifier = Modifier.size(16.dp),
            )
        }

        // Name + description
        Column(modifier = Modifier.weight(1f)) {
            themedTooltip(text = project.name) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            project.description?.let { desc ->
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Created date
        Text(
            text = TimeUtil.formatDisplay(project.createdAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 140.dp).padding(horizontal = 16.dp),
        )

        // Modified date
        Text(
            text = TimeUtil.formatDisplay(project.updatedAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 140.dp).padding(horizontal = 16.dp),
        )

        // Actions
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(36.dp).pointerHoverIcon(PointerIcon.Hand),
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            AppComponents.dropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(if (project.isStarred) stringResource("action.unpin") else stringResource("action.pin")) },
                    onClick = {
                        showMenu = false
                        onStarProject(project.id, !project.isStarred)
                    },
                    leadingIcon = {
                        Icon(
                            if (project.isStarred) Icons.Default.StarBorder else Icons.Default.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                )
                DropdownMenuItem(
                    text = { Text(stringResource("action.edit")) },
                    onClick = {
                        showMenu = false
                        onEditProject(project.id)
                    },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                )
                DropdownMenuItem(
                    text = { Text(stringResource("action.delete"), color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showMenu = false
                        showDeleteDialog = true
                    },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                )
            }
        }
    }
}
