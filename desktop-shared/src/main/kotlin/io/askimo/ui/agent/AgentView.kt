/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.agent.ExternalAgentLoader
import io.askimo.core.agent.domain.AgentRunRecord
import io.askimo.core.agent.domain.SkillDefinition
import io.askimo.core.agent.domain.Workspace
import io.askimo.core.agent.repository.SkillRepository
import io.askimo.core.agent.service.WorkspaceService
import io.askimo.core.db.DatabaseManager
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.preferences.ApplicationPreferences
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.TooltipPlacement
import io.askimo.ui.common.ui.themedTooltip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.awt.Cursor
import java.awt.Desktop
import java.io.File
import java.net.URI

/**
 * Self-contained agentic skills sub-view — top-level entry point for the Skills feature.
 * Owns its own skills loading, layout, and workspace panel.
 * The agent autonomously selects skills from the full catalog.
 */
@Composable
fun agentsView(
    onNavigateToSkillsSettings: () -> Unit = {},
) {
    val skillRepository = remember { SkillRepository() }
    val historyRepo = remember { DatabaseManager.getInstance().getAgentRunHistoryRepository() }
    val workspaceService = remember { GlobalContext.get().get<WorkspaceService>() }
    val scope = rememberCoroutineScope()
    val skills by remember { mutableStateOf(skillRepository.getSkillsOnly()) }
    var allHistoryRefreshKey by remember { mutableStateOf(0) }
    var showOverlayPanel by remember { mutableStateOf(false) }

    var runHistory by remember { mutableStateOf(listOf<AgentRunRecord>()) }
    var pendingHistoryRecord by remember { mutableStateOf<AgentRunRecord?>(null) }

    fun deleteHistoryRecord(record: AgentRunRecord) {
        scope.launch {
            withContext(Dispatchers.IO) { historyRepo.deleteByConversationId(record.conversationId) }
            allHistoryRefreshKey++
        }
    }

    // User-chosen workspace (persisted across sessions). Resolved off the UI thread since
    // workspaceService.resolveCurrent() does several blocking DB transactions.
    // Runs/history must never be saved before this resolves — the agent_run_history table
    // enforces a NOT NULL foreign key on workspace_id, so a real, persisted Workspace row
    // must exist first.
    var workspace by remember { mutableStateOf<Workspace?>(null) }
    LaunchedEffect(Unit) {
        workspace = withContext(Dispatchers.IO) { workspaceService.resolveCurrent() }
    }

    LaunchedEffect(workspace?.id, allHistoryRefreshKey) {
        workspace?.let { ws ->
            val all = withContext(Dispatchers.IO) { historyRepo.findByWorkspaceId(ws.id) }
            // One row per conversation — the latest turn represents the whole thread in the
            // list; clicking it reconstructs the full multi-turn conversation (see
            // agenticRunArea's preloadRecord handling).
            runHistory = all
                .groupBy { it.conversationId }
                .map { (_, turns) -> turns.maxBy { it.createdAt } }
                .sortedByDescending { it.createdAt }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 1100.dp
        LaunchedEffect(isWide) { if (isWide) showOverlayPanel = false }

        val currentWorkspace =
            workspace
                ?: return@BoxWithConstraints
        val workDir = remember(currentWorkspace.path) { File(currentWorkspace.path) }

        val panelContent: @Composable () -> Unit = {
            agenticWorkspacePanel(
                workDir = workDir,
                workDirRefreshKey = allHistoryRefreshKey,
                runHistory = runHistory,
                onWorkDirChanged = { workspace = workspaceService.select(it) },
                onSelectRecord = { pendingHistoryRecord = it },
                onDeleteRecord = ::deleteHistoryRecord,
            )
        }

        if (isWide) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    agenticContent(
                        skills = skills,
                        workspace = currentWorkspace,
                        onRunCompleted = { allHistoryRefreshKey++ },
                        onNavigateToSkillsSettings = onNavigateToSkillsSettings,
                        preloadRecord = pendingHistoryRecord,
                        onPreloadConsumed = { pendingHistoryRecord = null },
                    )
                }
                panelContent()
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                agenticContent(
                    skills = skills,
                    workspace = currentWorkspace,
                    onRunCompleted = { allHistoryRefreshKey++ },
                    onNavigateToSkillsSettings = onNavigateToSkillsSettings,
                    showPanelToggle = true,
                    panelVisible = showOverlayPanel,
                    onTogglePanel = { showOverlayPanel = !showOverlayPanel },
                    preloadRecord = pendingHistoryRecord,
                    onPreloadConsumed = { pendingHistoryRecord = null },
                )
                if (showOverlayPanel) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.3f))
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = { showOverlayPanel = false },
                            ),
                    )
                }
                AnimatedVisibility(
                    visible = showOverlayPanel,
                    enter = slideInHorizontally(initialOffsetX = { it }),
                    exit = slideOutHorizontally(targetOffsetX = { it }),
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) { panelContent() }
            }
        }
    }
}

// ── Skills page header — title, docs link, manage button, panel toggle ─────

@Composable
internal fun agentsPageHeader(
    onNavigateToSkillsSettings: () -> Unit,
    showPanelToggle: Boolean = false,
    panelVisible: Boolean = false,
    onTogglePanel: () -> Unit = {},
) {
    val runtimes = ExternalAgentLoader.displayNames()
    val runtimesLabel = runtimes.mapIndexed { i, r ->
        if (i == runtimes.lastIndex) "or $r" else r
    }.joinToString(", ")

    // ── Title row: page title + toolbar actions ────────────────────────────
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource("agents.view.title"),
            style = AppTextStyles.pageTitle,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            themedTooltip(text = stringResource("agents.view.docs.tooltip")) {
                IconButton(
                    onClick = {
                        runCatching { Desktop.getDesktop().browse(URI("https://$DOMAIN/docs/desktop/skills/")) }
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = stringResource("agents.view.docs.tooltip"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(
                onClick = onNavigateToSkillsSettings,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) {
                Text(
                    text = stringResource("agents.view.manage"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showPanelToggle) {
                val panelTooltip = stringResource(
                    if (panelVisible) "agents.view.panel.collapse" else "agents.view.panel.expand",
                )
                themedTooltip(text = panelTooltip) {
                    IconButton(
                        onClick = onTogglePanel,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            if (panelVisible) Icons.Default.ChevronRight else Icons.Default.ChevronLeft,
                            contentDescription = panelTooltip,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    // ── Description + runtimes: full width below the title row ─────────────
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = stringResource("agents.view.description", runtimesLabel),
        style = AppTextStyles.bodySecondary,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource("settings.agents.runtimes"),
            style = AppTextStyles.caption,
        )
        runtimes.forEach { runtime ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            ) {
                Text(
                    text = runtime,
                    style = AppTextStyles.hint,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

// ── Agentic main content ───────────────────────────────────────────────────

@Composable
private fun agenticContent(
    skills: List<SkillDefinition>,
    workspace: Workspace,
    onRunCompleted: () -> Unit,
    onNavigateToSkillsSettings: () -> Unit,
    showPanelToggle: Boolean = false,
    panelVisible: Boolean = false,
    onTogglePanel: () -> Unit = {},
    preloadRecord: AgentRunRecord? = null,
    onPreloadConsumed: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // ── Page header (fixed — does not scroll with the conversation) ───
        Column(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = ThemePreferences.CONTENT_MAX_WIDTH)
                .fillMaxWidth()
                .padding(start = 24.dp, end = 36.dp, top = 24.dp, bottom = 8.dp),
        ) {
            agentsPageHeader(
                onNavigateToSkillsSettings = onNavigateToSkillsSettings,
                showPanelToggle = showPanelToggle,
                panelVisible = panelVisible,
                onTogglePanel = onTogglePanel,
            )
        }

        // ── Agentic execution area — owns its own transcript scroll and a chat
        //    input pinned to the bottom, mirroring chatView's message-list + input layout ──
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            agenticRunArea(
                skills = skills,
                workspace = workspace,
                onRunCompleted = onRunCompleted,
                onNavigateToSkillsSettings = onNavigateToSkillsSettings,
                preloadRecord = preloadRecord,
                onPreloadConsumed = onPreloadConsumed,
            )
        }
    }
}

// ── Agentic workspace panel ────────────────────────────────────────────────

private enum class AgenticRightTab(
    val icon: ImageVector,
    val labelKey: String,
) {
    WORKSPACE(Icons.Default.FolderOpen, "agents.view.tab.workspace"),
    HISTORY(Icons.Default.History, "agents.view.tab.history"),
}

@Composable
private fun agenticWorkspacePanel(
    workDir: File,
    workDirRefreshKey: Int,
    runHistory: List<AgentRunRecord>,
    onWorkDirChanged: (File) -> Unit,
    onSelectRecord: (AgentRunRecord) -> Unit,
    onDeleteRecord: (AgentRunRecord) -> Unit,
) {
    var isExpanded by remember { mutableStateOf(ApplicationPreferences.getSkillsSidePanelExpanded()) }
    var panelWidth by remember { mutableStateOf(ApplicationPreferences.getSkillsSidePanelWidth().dp) }
    var activeTab by remember { mutableStateOf(AgenticRightTab.WORKSPACE) }

    // Auto-switch to Workspace tab when a run completes, mirroring the manual view.
    LaunchedEffect(workDirRefreshKey) {
        if (workDirRefreshKey > 0) {
            activeTab = AgenticRightTab.WORKSPACE
            isExpanded = true
            ApplicationPreferences.setSkillsSidePanelExpanded(true)
        }
    }

    val animatedWidth by animateDpAsState(
        targetValue = if (isExpanded) panelWidth else 56.dp,
        animationSpec = tween(durationMillis = 300),
    )

    Card(
        modifier = Modifier.width(animatedWidth).fillMaxHeight(),
        shape = RectangleShape,
        colors = CardDefaults.cardColors(
            containerColor = AppComponents.sidebarSurfaceColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            // ── Left drag handle (only when expanded) ─────────────────────
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
                                    panelWidth = (panelWidth - dragAmount.x.toDp()).coerceIn(180.dp, 480.dp)
                                },
                                onDragEnd = {
                                    ApplicationPreferences.setSkillsSidePanelWidth(panelWidth.value.toInt())
                                },
                            )
                        },
                )
            }

            // ── Expanded content ───────────────────────────────────────────
            if (isExpanded) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(activeTab.labelKey),
                            style = AppTextStyles.fieldLabel,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        themedTooltip(
                            text = stringResource("agents.view.panel.collapse"),
                            placement = TooltipPlacement.LEFT,
                        ) {
                            IconButton(
                                onClick = {
                                    isExpanded = false
                                    ApplicationPreferences.setSkillsSidePanelExpanded(false)
                                },
                                modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = stringResource("agents.view.panel.collapse"),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (activeTab) {
                            AgenticRightTab.WORKSPACE -> workspaceFilesPanel(
                                workDir = workDir,
                                refreshKey = workDirRefreshKey,
                                onWorkDirChanged = onWorkDirChanged,
                            )

                            AgenticRightTab.HISTORY -> skillsHistoryContent(
                                runHistory = runHistory,
                                onSelectRecord = onSelectRecord,
                                onDeleteRecord = onDeleteRecord,
                            )
                        }
                    }
                }
            }

            // ── Always-visible icon bar (right, 56 dp) ─────────────────────
            Column(
                modifier = Modifier
                    .width(56.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(vertical = 16.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AgenticRightTab.entries.forEach { tab ->
                    sidePanelTabIcon(
                        icon = tab.icon,
                        label = stringResource(tab.labelKey),
                        isSelected = isExpanded && activeTab == tab,
                        badge = if (tab == AgenticRightTab.HISTORY && runHistory.isNotEmpty()) "${runHistory.size}" else null,
                        onClick = {
                            if (isExpanded && activeTab == tab) {
                                // Tap active tab to collapse
                                isExpanded = false
                                ApplicationPreferences.setSkillsSidePanelExpanded(false)
                            } else {
                                activeTab = tab
                                isExpanded = true
                                ApplicationPreferences.setSkillsSidePanelExpanded(true)
                            }
                        },
                    )
                }
            }
        }
    }
}

// ── Side-panel tab icon — used by the workspace/history icon bar above ─────

/**
 * A single tab icon in a collapsible right panel's always-visible icon bar
 * (Workspace/History for agentic runs). Shows an optional count [badge] in
 * the top-right corner.
 */
@Composable
internal fun sidePanelTabIcon(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    badge: String? = null,
    onClick: () -> Unit,
) {
    themedTooltip(text = label) {
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        } else {
                            Color.Transparent
                        },
                        shape = MaterialTheme.shapes.small,
                    )
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onClick,
                    )
                    .pointerHoverIcon(PointerIcon.Hand),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (isSelected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
            // Badge (e.g. skill/history count)
            if (badge != null) {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    modifier = Modifier.padding(top = 1.dp, end = 1.dp),
                ) {
                    Text(
                        text = badge,
                        style = AppTextStyles.hint,
                        modifier = Modifier.padding(horizontal = 3.dp, vertical = 0.dp),
                    )
                }
            }
        }
    }
}
