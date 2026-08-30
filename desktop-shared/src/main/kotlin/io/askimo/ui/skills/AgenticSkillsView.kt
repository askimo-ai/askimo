/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.skills

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.db.DatabaseManager
import io.askimo.core.skills.SkillRepository
import io.askimo.core.skills.domain.SkillDefinition
import io.askimo.core.skills.domain.SkillRunRecord
import io.askimo.core.util.AskimoHome
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
import java.awt.Cursor
import java.io.File

/**
 * Returns the user-selected workspace dir, falling back to the most recently used known
 * workspace, or the default skills-workspace dir if none are known yet.
 */
internal fun resolveSkillsWorkspaceDir(): File {
    val repo = DatabaseManager.getInstance().getWorkspaceRepository()

    val selected = ApplicationPreferences.getSkillsSelectedWorkspaceId()?.let { repo.findById(it) }
    if (selected != null) return File(selected.path)

    val mostRecent = repo.findAll().firstOrNull()
    if (mostRecent != null) {
        ApplicationPreferences.setSkillsSelectedWorkspaceId(mostRecent.id)
        return File(mostRecent.path)
    }

    val default = AskimoHome.skillsWorkspaceDir().toFile()
    val workspace = repo.upsertByPath(default, displayName = "Default")
    ApplicationPreferences.setSkillsSelectedWorkspaceId(workspace.id)
    return File(workspace.path)
}

/**
 * Registers [dir] as a known workspace (creating it on first use, or bumping its
 * last-used timestamp otherwise) and remembers it as the selected workspace.
 * Call this whenever the user opens/switches to a workspace directory.
 */
internal fun selectSkillsWorkspace(dir: File, displayName: String? = null): File {
    val repo = DatabaseManager.getInstance().getWorkspaceRepository()
    val workspace = repo.upsertByPath(dir, displayName)
    ApplicationPreferences.setSkillsSelectedWorkspaceId(workspace.id)
    return File(workspace.path)
}

/**
 * Self-contained agentic skills sub-view — top-level entry point for the Skills feature.
 * Owns its own skills loading, layout, and workspace panel.
 * The agent autonomously selects skills from the full catalog.
 */
@Composable
fun agenticSkillsView(
    onNavigateToSkillsSettings: () -> Unit = {},
) {
    val skillRepository = remember { SkillRepository() }
    val historyRepo = remember { DatabaseManager.getInstance().getSkillRunHistoryRepository() }
    val scope = rememberCoroutineScope()
    val skills by remember { mutableStateOf(skillRepository.getSkillsOnly()) }
    var allHistoryRefreshKey by remember { mutableStateOf(0) }
    var showOverlayPanel by remember { mutableStateOf(false) }

    var runHistory by remember { mutableStateOf(listOf<SkillRunRecord>()) }
    LaunchedEffect(allHistoryRefreshKey) {
        runHistory = withContext(Dispatchers.IO) { historyRepo.findBySkillPath(AGENTIC_RUN_SKILL_PATH) }
    }
    var pendingHistoryRecord by remember { mutableStateOf<SkillRunRecord?>(null) }

    fun deleteHistoryRecord(record: SkillRunRecord) {
        scope.launch {
            withContext(Dispatchers.IO) { historyRepo.deleteById(record.id) }
            allHistoryRefreshKey++
        }
    }

    // User-chosen workspace dir (persisted across sessions). Start with a cheap synchronous
    // default (no DB access) and resolve the real persisted/most-recent workspace off the UI
    // thread — resolveSkillsWorkspaceDir() does several blocking DB transactions plus a
    // preferences write, which would otherwise stall the first composition/frame.
    var workDir by remember { mutableStateOf(AskimoHome.skillsWorkspaceDir().toFile()) }
    LaunchedEffect(Unit) {
        workDir = withContext(Dispatchers.IO) { resolveSkillsWorkspaceDir() }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 1100.dp
        LaunchedEffect(isWide) { if (isWide) showOverlayPanel = false }

        val panelContent: @Composable () -> Unit = {
            agenticWorkspacePanel(
                workDir = workDir,
                workDirRefreshKey = allHistoryRefreshKey,
                runHistory = runHistory,
                onWorkDirChanged = { workDir = selectSkillsWorkspace(it) },
                onSelectRecord = { pendingHistoryRecord = it },
                onDeleteRecord = ::deleteHistoryRecord,
            )
        }

        if (isWide) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    agenticContent(
                        skills = skills,
                        workDir = workDir,
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
                    workDir = workDir,
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

// ── Agentic main content ───────────────────────────────────────────────────

@Composable
private fun agenticContent(
    skills: List<SkillDefinition>,
    workDir: File,
    onRunCompleted: () -> Unit,
    onNavigateToSkillsSettings: () -> Unit,
    showPanelToggle: Boolean = false,
    panelVisible: Boolean = false,
    onTogglePanel: () -> Unit = {},
    preloadRecord: SkillRunRecord? = null,
    onPreloadConsumed: () -> Unit = {},
) {
    val scrollState = rememberScrollState()
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── Page header ────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .widthIn(max = ThemePreferences.CONTENT_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 36.dp, top = 24.dp, bottom = 24.dp),
            ) {
                skillsPageHeader(
                    onNavigateToSkillsSettings = onNavigateToSkillsSettings,
                    showPanelToggle = showPanelToggle,
                    panelVisible = panelVisible,
                    onTogglePanel = onTogglePanel,
                )
            }

            // ── Agentic execution area ─────────────────────────────────────
            agenticRunArea(
                skills = skills,
                workDir = workDir,
                onRunCompleted = onRunCompleted,
                onNavigateToSkillsSettings = onNavigateToSkillsSettings,
                preloadRecord = preloadRecord,
                onPreloadConsumed = onPreloadConsumed,
            )
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 4.dp),
            style = AppComponents.scrollbarStyle(),
        )
    }
}

// ── Agentic workspace panel ────────────────────────────────────────────────

private enum class AgenticRightTab(
    val icon: ImageVector,
    val labelKey: String,
) {
    WORKSPACE(Icons.Default.FolderOpen, "skills.view.tab.workspace"),
    HISTORY(Icons.Default.History, "skills.view.tab.history"),
}

@Composable
private fun agenticWorkspacePanel(
    workDir: File,
    workDirRefreshKey: Int,
    runHistory: List<SkillRunRecord>,
    onWorkDirChanged: (File) -> Unit,
    onSelectRecord: (SkillRunRecord) -> Unit,
    onDeleteRecord: (SkillRunRecord) -> Unit,
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
                            text = stringResource("skills.view.panel.collapse"),
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
                                    contentDescription = stringResource("skills.view.panel.collapse"),
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
                                filterSkillName = null,
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
