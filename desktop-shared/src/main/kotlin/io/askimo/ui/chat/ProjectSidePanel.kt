/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2025 Hai Nguyen
 */
package io.askimo.ui.chat

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.domain.Project
import io.askimo.core.db.DatabaseManager
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.ProjectIndexRemovalEvent
import io.askimo.core.event.internal.ProjectIndexingRequestedEvent
import io.askimo.core.event.internal.ProjectReIndexEvent
import io.askimo.core.event.internal.ProjectRefreshEvent
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.preferences.ApplicationPreferences
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.project.addReferenceMaterialDialog
import io.askimo.ui.project.buildKnowledgeSourceConfigs
import io.askimo.ui.project.mergeKnowledgeSourceConfigs
import java.awt.Cursor

/**
 * Tab types for the side panel
 */
enum class PanelTab(
    val icon: ImageVector,
    val labelKey: String, // Localization key instead of label
) {
    RAG_SOURCES(Icons.Default.AutoAwesome, "panel.tab.rag.sources"),
    MCP(Icons.Default.Extension, "panel.tab.mcp"),
}

/**
 * Side panel with tabs for RAG sources, MCP, and more.
 * Shows as icon bar when collapsed, full panel when expanded.
 * Supports drag-to-resize when expanded.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun projectSidePanel(
    project: Project?,
    ragIndexingStatus: String?,
    ragIndexingPercentage: Int?,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableStateOf(PanelTab.RAG_SOURCES) }
    var showAddMaterialDialog by remember { mutableStateOf(false) }

    // Load panel width from preferences (default 400dp if not set)
    var panelWidth by remember {
        mutableStateOf(ApplicationPreferences.getProjectSidePanelWidth().dp)
    }

    val targetWidth = if (isExpanded) panelWidth else 56.dp
    val animatedWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = tween(durationMillis = 300),
    )

    // Unified card containing both content panel and icon bar
    Card(
        modifier = modifier
            .width(animatedWidth)
            .fillMaxHeight(),
        shape = RectangleShape,
        colors = CardDefaults.cardColors(
            containerColor = AppComponents.sidebarSurfaceColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // Draggable resize handle (left edge) - only when expanded
            if (isExpanded) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant)
                        .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val newWidth = panelWidth - dragAmount.x.toDp()
                                    // Constrain width between 250dp and 600dp
                                    panelWidth = newWidth.coerceIn(250.dp, 600.dp)
                                },
                                onDragEnd = {
                                    // Save to preferences when drag ends
                                    ApplicationPreferences.setProjectSidePanelWidth(panelWidth.value.toInt())
                                },
                            )
                        },
                )
            }

            if (isExpanded) {
                Column(
                    modifier = Modifier
                        .weight(1f) // Take remaining space after resize handle and icon bar
                        .fillMaxHeight()
                        .padding(16.dp),
                ) {
                    var showContextMenu by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(selectedTab.labelKey),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (selectedTab == PanelTab.RAG_SOURCES) {
                                Box {
                                    IconButton(
                                        onClick = { showContextMenu = true },
                                        modifier = Modifier
                                            .size(32.dp)
                                            .pointerHoverIcon(PointerIcon.Hand),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = stringResource("panel.context.menu"),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }

                                    // Dropdown menu
                                    AppComponents.dropdownMenu(
                                        expanded = showContextMenu,
                                        onDismissRequest = { showContextMenu = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource("panel.context.add.material")) },
                                            onClick = {
                                                showContextMenu = false
                                                if (project != null) {
                                                    showAddMaterialDialog = true
                                                }
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.Add,
                                                    contentDescription = null,
                                                )
                                            },
                                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource("panel.context.reindex")) },
                                            onClick = {
                                                showContextMenu = false
                                                project?.let {
                                                    EventBus.post(
                                                        ProjectReIndexEvent(
                                                            projectId = it.id,
                                                            reason = "Manual re-index requested by user from side panel",
                                                        ),
                                                    )
                                                }
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.Refresh,
                                                    contentDescription = null,
                                                )
                                            },
                                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                        )
                                    }
                                }
                            }

                            // Minimize button
                            IconButton(
                                onClick = { onExpandedChange(false) },
                                modifier = Modifier
                                    .size(32.dp)
                                    .pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Remove,
                                    contentDescription = stringResource("panel.collapse"),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    // Tab content
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        when (selectedTab) {
                            PanelTab.RAG_SOURCES -> {
                                ragSourcesTabContent(
                                    project = project,
                                    ragIndexingStatus = ragIndexingStatus,
                                    ragIndexingPercentage = ragIndexingPercentage,
                                    onAddMaterial = { showAddMaterialDialog = true },
                                    onRemove = { source ->
                                        if (project != null) {
                                            val projectRepository = DatabaseManager.getInstance().getProjectRepository()
                                            val updatedSources = project.knowledgeSources.filter { it != source }

                                            projectRepository.updateProject(
                                                projectId = project.id,
                                                name = project.name,
                                                description = project.description,
                                                knowledgeSources = updatedSources,
                                            )

                                            EventBus.post(
                                                ProjectIndexRemovalEvent(
                                                    projectId = project.id,
                                                    knowledgeSource = source,
                                                    reason = "Knowledge source removed by user from side panel",
                                                ),
                                            )

                                            EventBus.post(
                                                ProjectRefreshEvent(
                                                    projectId = project.id,
                                                    reason = "Knowledge source removed from project",
                                                ),
                                            )
                                        }
                                    },
                                )
                            }
                            PanelTab.MCP -> {
                                mcpTabContent(project = project)
                            }
                        }
                    }
                }
            }

            // Icon bar (right side) - always visible
            Column(
                modifier = Modifier
                    .width(56.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(vertical = 16.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Tab icons
                PanelTab.entries.forEach { tab ->
                    tabIcon(
                        tab = tab,
                        isSelected = selectedTab == tab,
                        onClick = {
                            selectedTab = tab
                            if (!isExpanded) {
                                onExpandedChange(true)
                            }
                        },
                        ragIndexingStatus = if (tab == PanelTab.RAG_SOURCES) ragIndexingStatus else null,
                        ragIndexingPercentage = if (tab == PanelTab.RAG_SOURCES) ragIndexingPercentage else null,
                        project = project,
                    )
                }
            }
        }
    }

    // Add Reference Material Dialog
    if (showAddMaterialDialog && project != null) {
        val projectRepository = remember { DatabaseManager.getInstance().getProjectRepository() }
        addReferenceMaterialDialog(
            projectId = project.id,
            onDismiss = { showAddMaterialDialog = false },
            onAdd = { newSources ->
                // Build knowledge source configs from the new items
                val newConfigs = buildKnowledgeSourceConfigs(newSources)

                // Merge with existing knowledge sources
                val mergedConfigs = mergeKnowledgeSourceConfigs(
                    existing = project.knowledgeSources,
                    new = newConfigs,
                )

                // Update the project
                projectRepository.updateProject(
                    projectId = project.id,
                    name = project.name,
                    description = project.description,
                    knowledgeSources = mergedConfigs,
                )

                // Trigger re-indexing for the new sources
                EventBus.post(
                    ProjectIndexingRequestedEvent(
                        projectId = project.id,
                        knowledgeSources = newConfigs,
                        watchForChanges = true,
                    ),
                )

                showAddMaterialDialog = false
            },
        )
    }
}

/**
 * Tab icon with tooltip and selection state
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun tabIcon(
    tab: PanelTab,
    isSelected: Boolean,
    onClick: () -> Unit,
    ragIndexingStatus: String?,
    ragIndexingPercentage: Int?,
    project: Project?,
) {
    val tooltipText = when (tab) {
        PanelTab.RAG_SOURCES -> stringResource("panel.tab.rag.sources")
        PanelTab.MCP -> stringResource("panel.tab.mcp")
    }

    TooltipArea(
        tooltip = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = tooltipText,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    } else {
                        Color.Transparent
                    },
                )
                .clickable(
                    onClick = onClick,
                    indication = null, // Remove ripple/focus effect
                    interactionSource = remember { MutableInteractionSource() },
                )
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = stringResource(tab.labelKey),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * RAG Sources tab content
 */
@Composable
private fun ragSourcesTabContent(
    project: Project?,
    ragIndexingStatus: String?,
    ragIndexingPercentage: Int?,
    onAddMaterial: () -> Unit,
    onRemove: (KnowledgeSourceConfig) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        // RAG Status indicator (only show if sources exist)
        if (project != null && project.knowledgeSources.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = stringResource("rag.status.icon"),
                    tint = when (ragIndexingStatus) {
                        "completed" -> MaterialTheme.colorScheme.onSurface
                        "failed" -> MaterialTheme.colorScheme.error
                        "inprogress" -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.size(20.dp),
                )

                Text(
                    text = when (ragIndexingStatus) {
                        "started" -> stringResource("rag.status.started")
                        "inprogress" -> ragIndexingPercentage?.let {
                            stringResource("rag.status.inprogress", it)
                        } ?: stringResource("rag.status.inprogress.unknown")
                        "completed" -> stringResource("rag.status.ready")
                        "failed" -> stringResource("rag.status.failed")
                        else -> stringResource("rag.status.not.indexed")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (ragIndexingStatus) {
                        "completed" -> MaterialTheme.colorScheme.onSurface
                        "failed" -> MaterialTheme.colorScheme.error
                        "inprogress" -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )

                if (ragIndexingStatus == "inprogress") {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        // Content area
        if (project == null || project.knowledgeSources.isEmpty()) {
            // Empty state
            ragSourcesEmptyState(
                project = project,
                onAddMaterial = onAddMaterial,
            )
        } else {
            // RAG sources tree
            ragSourcesTree(
                sources = project.knowledgeSources,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                onRemove = onRemove,
            )
        }
    }
}

/**
 * Empty state for RAG sources
 */
@Composable
private fun ragSourcesEmptyState(
    project: Project?,
    onAddMaterial: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = AppComponents.tertiaryIconColor(),
            )

            Text(
                text = stringResource("rag.empty.title"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )

            Text(
                text = stringResource("rag.empty.description"),
                style = MaterialTheme.typography.bodySmall,
                color = AppComponents.secondaryTextColor(),
                textAlign = TextAlign.Center,
            )

            Button(
                onClick = { if (project != null) onAddMaterial() },
                enabled = project != null,
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource("rag.empty.button.add"))
            }

            Text(
                text = stringResource("rag.empty.coming.soon"),
                style = MaterialTheme.typography.labelSmall,
                color = AppComponents.tertiaryTextColor(),
            )
        }
    }
}
