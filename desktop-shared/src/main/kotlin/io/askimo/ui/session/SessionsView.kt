/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.session

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.util.TimeUtil
import io.askimo.ui.common.components.tablePageSizeSelector
import io.askimo.ui.common.components.tablePagination
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.themedTooltip

private enum class SessionSortColumn { UPDATED, CREATED }
private enum class SortDirection { ASC, DESC }

@Composable
fun sessionsView(
    viewModel: SessionsViewModel,
    onResumeSession: (String) -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    var sortColumn by remember { mutableStateOf(SessionSortColumn.UPDATED) }
    var sortDirection by remember { mutableStateOf(SortDirection.DESC) }
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
                // ── Header ────────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource("sessions.title"),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Button(
                        onClick = onNewChat,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(stringResource("chat.new"))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Success banner ────────────────────────────────────────────
                viewModel.successMessage?.let { message ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            IconButton(onClick = { viewModel.dismissSuccessMessage() }) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                }

                // ── Content ───────────────────────────────────────────────────
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
                                    text = stringResource("sessions.error", viewModel.errorMessage ?: ""),
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

                    viewModel.pagedSessions?.isEmpty == true -> {
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource("sessions.empty"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource("sessions.empty.hint"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    else -> {
                        sessionTable(
                            sessions = viewModel.pagedSessions!!.items,
                            onResumeSession = onResumeSession,
                            onDeleteSession = { viewModel.deleteSession(it) },
                            onStarSession = { id, starred -> viewModel.updateSessionStarred(id, starred) },
                            sortColumn = sortColumn,
                            sortDirection = sortDirection,
                            onSortChange = { col ->
                                if (sortColumn == col) {
                                    sortDirection = if (sortDirection == SortDirection.DESC) SortDirection.ASC else SortDirection.DESC
                                } else {
                                    sortColumn = col
                                    sortDirection = SortDirection.DESC
                                }
                            },
                        )
                    }
                }

                // ── Pagination ────────────────────────────────────────────────
                val pagedSessions = viewModel.pagedSessions
                if (pagedSessions != null && pagedSessions.totalPages > 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                    tablePagination(
                        currentPage = pagedSessions.currentPage,
                        totalPages = pagedSessions.totalPages,
                        hasPrevious = pagedSessions.hasPreviousPage,
                        hasNext = pagedSessions.hasNextPage,
                        pageSize = pageSize,
                        onPrevious = { viewModel.previousPage() },
                        onNext = { viewModel.nextPage() },
                        onPageSizeChange = { size ->
                            pageSize = size
                            viewModel.setPageSize(size)
                        },
                    )
                } else if (viewModel.pagedSessions != null) {
                    // Still show page size selector even on single page
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
private fun sessionTable(
    sessions: List<ChatSession>,
    onResumeSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onStarSession: (String, Boolean) -> Unit,
    sortColumn: SessionSortColumn,
    sortDirection: SortDirection,
    onSortChange: (SessionSortColumn) -> Unit,
) {
    val sortedSessions = remember(sessions, sortColumn, sortDirection) {
        val comparator = when (sortColumn) {
            SessionSortColumn.UPDATED -> compareBy<ChatSession> { it.updatedAt }
            SessionSortColumn.CREATED -> compareBy<ChatSession> { it.createdAt }
        }
        if (sortDirection == SortDirection.DESC) {
            sessions.sortedWith(comparator.reversed())
        } else {
            sessions.sortedWith(comparator)
        }
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                // Title column (non-sortable)
                Text(
                    text = stringResource("sessions.col.title"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // Created sortable header
                sortableHeader(
                    label = stringResource("sessions.col.created"),
                    column = SessionSortColumn.CREATED,
                    currentColumn = sortColumn,
                    direction = sortDirection,
                    onClick = { onSortChange(SessionSortColumn.CREATED) },
                    modifier = Modifier.widthIn(min = 140.dp).padding(horizontal = 16.dp),
                )
                // Updated sortable header
                sortableHeader(
                    label = stringResource("sessions.col.updated"),
                    column = SessionSortColumn.UPDATED,
                    currentColumn = sortColumn,
                    direction = sortDirection,
                    onClick = { onSortChange(SessionSortColumn.UPDATED) },
                    modifier = Modifier.widthIn(min = 140.dp).padding(horizontal = 16.dp),
                )
                // Actions column spacer
                Spacer(modifier = Modifier.size(36.dp))
            }

            HorizontalDivider()

            sortedSessions.forEachIndexed { index, session ->
                sessionRow(
                    session = session,
                    onResumeSession = onResumeSession,
                    onDeleteSession = onDeleteSession,
                    onStarSession = onStarSession,
                )
                if (index < sortedSessions.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
private fun sortableHeader(
    label: String,
    column: SessionSortColumn,
    currentColumn: SessionSortColumn,
    direction: SortDirection,
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
                imageVector = if (direction == SortDirection.DESC) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
private fun sessionRow(
    session: ChatSession,
    onResumeSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onStarSession: (String, Boolean) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    if (showDeleteDialog) {
        deleteSessionDialog(
            sessionTitle = session.title,
            onConfirm = {
                showDeleteDialog = false
                onDeleteSession(session.id)
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
            ) { onResumeSession(session.id) }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Star toggle
        IconButton(
            onClick = { onStarSession(session.id, !session.isStarred) },
            modifier = Modifier.size(36.dp).pointerHoverIcon(PointerIcon.Hand),
        ) {
            Icon(
                imageVector = if (session.isStarred) Icons.Default.Star else Icons.Outlined.StarOutline,
                contentDescription = if (session.isStarred) "Unpin session" else "Pin session",
                tint = if (session.isStarred) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
                modifier = Modifier.size(16.dp),
            )
        }

        // Title
        themedTooltip(text = session.title, modifier = Modifier.weight(1f)) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Created date
        Text(
            text = TimeUtil.formatDisplay(session.createdAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 140.dp).padding(horizontal = 16.dp),
        )

        // Updated date
        Text(
            text = TimeUtil.formatDisplay(session.updatedAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 140.dp).padding(horizontal = 16.dp),
        )

        // Actions menu
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(36.dp).pointerHoverIcon(PointerIcon.Hand),
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            AppComponents.dropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(if (session.isStarred) stringResource("action.unpin") else stringResource("action.pin")) },
                    onClick = {
                        showMenu = false
                        onStarSession(session.id, !session.isStarred)
                    },
                    leadingIcon = {
                        Icon(
                            if (session.isStarred) Icons.Default.StarBorder else Icons.Default.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                )
                DropdownMenuItem(
                    text = { Text(stringResource("action.delete"), color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showMenu = false
                        showDeleteDialog = true
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                )
            }
        }
    }
}
