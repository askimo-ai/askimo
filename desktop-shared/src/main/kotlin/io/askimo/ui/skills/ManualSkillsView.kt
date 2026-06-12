/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.skills

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ScrollbarStyle
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.db.DatabaseManager
import io.askimo.core.skills.SkillRepository
import io.askimo.core.skills.agent.ExternalAgentLoader
import io.askimo.core.skills.domain.SkillDefinition
import io.askimo.core.skills.domain.SkillRunRecord
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.preferences.ApplicationPreferences
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.selectableText
import io.askimo.ui.common.ui.themedTooltip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Cursor
import java.awt.Desktop
import java.io.File
import java.net.URI

/**
 * Self-contained manual skills sub-view.
 *
 * Layout:  [Execution content] | [Right panel: Skills tab + Workspace tab]
 *
 * Skill selection lives in the right panel's Skills tab — no extra left sidebar,
 * so the app navigation is never crowded. The Workspace tab shows output files.
 */
@Composable
internal fun manualSkillsView(
    onSwitchToAgentic: () -> Unit,
    onNavigateToSkillsSettings: () -> Unit = {},
) {
    val skillRepository = remember { SkillRepository() }
    val historyRepo = remember { DatabaseManager.getInstance().getSkillRunHistoryRepository() }
    val skills by remember { mutableStateOf(skillRepository.getSkillsOnly()) }

    var selectedSkill by remember { mutableStateOf<SkillDefinition?>(null) }
    var allRunHistory by remember { mutableStateOf(listOf<SkillRunRecord>()) }
    var allHistoryRefreshKey by remember { mutableStateOf(0) }
    LaunchedEffect(allHistoryRefreshKey) {
        allRunHistory = withContext(Dispatchers.IO) { historyRepo.findAll() }
    }

    var pendingHistoryRecord by remember { mutableStateOf<SkillRunRecord?>(null) }
    var workDir by remember { mutableStateOf(resolveSkillsWorkspaceDir()) }
    var workDirRefreshKey by remember { mutableStateOf(0) }

    val selectedSkillHistory = remember(selectedSkill, allRunHistory) {
        if (selectedSkill != null) {
            allRunHistory.filter { it.skillPath == selectedSkill!!.relativePath }
        } else {
            emptyList()
        }
    }
    val lastRunRecord = remember(selectedSkillHistory) { selectedSkillHistory.maxByOrNull { it.createdAt } }
    var showOverlayPanel by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 1100.dp
        LaunchedEffect(isWide) { if (isWide) showOverlayPanel = false }

        val rightPanelContent: @Composable () -> Unit = {
            manualRightPanel(
                skills = skills,
                selectedSkill = selectedSkill,
                workDir = workDir,
                workDirRefreshKey = workDirRefreshKey,
                onSelectSkill = { selectedSkill = it },
                onWorkDirChanged = { workDir = it },
            )
        }

        if (isWide) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    manualContent(
                        skills = skills,
                        selectedSkill = selectedSkill,
                        lastRunRecord = lastRunRecord,
                        pendingHistoryRecord = pendingHistoryRecord,
                        workDir = workDir,
                        onSwitchToAgentic = onSwitchToAgentic,
                        onPreloadConsumed = { pendingHistoryRecord = null },
                        onRunCompleted = {
                            allHistoryRefreshKey++
                            workDirRefreshKey++
                        },
                        onNavigateToSkillsSettings = onNavigateToSkillsSettings,
                    )
                }
                rightPanelContent()
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                manualContent(
                    skills = skills,
                    selectedSkill = selectedSkill,
                    lastRunRecord = lastRunRecord,
                    pendingHistoryRecord = pendingHistoryRecord,
                    workDir = workDir,
                    onSwitchToAgentic = onSwitchToAgentic,
                    onPreloadConsumed = { pendingHistoryRecord = null },
                    onRunCompleted = {
                        allHistoryRefreshKey++
                        workDirRefreshKey++
                    },
                    onNavigateToSkillsSettings = onNavigateToSkillsSettings,
                    showPanelToggle = true,
                    panelVisible = showOverlayPanel,
                    onTogglePanel = { showOverlayPanel = !showOverlayPanel },
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
                androidx.compose.animation.AnimatedVisibility(
                    visible = showOverlayPanel,
                    enter = slideInHorizontally(initialOffsetX = { it }),
                    exit = slideOutHorizontally(targetOffsetX = { it }),
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) { rightPanelContent() }
            }
        }
    }
}

// ── Manual main content ────────────────────────────────────────────────────

@Composable
private fun manualContent(
    skills: List<SkillDefinition>,
    selectedSkill: SkillDefinition?,
    lastRunRecord: SkillRunRecord?,
    pendingHistoryRecord: SkillRunRecord?,
    workDir: File,
    onSwitchToAgentic: () -> Unit,
    onPreloadConsumed: () -> Unit,
    onRunCompleted: () -> Unit,
    onNavigateToSkillsSettings: () -> Unit,
    showPanelToggle: Boolean = false,
    panelVisible: Boolean = false,
    onTogglePanel: () -> Unit = {},
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource("skills.view.title"),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        val runtimes = ExternalAgentLoader.displayNames()
                        val runtimesLabel = runtimes.mapIndexed { i, r ->
                            if (i == runtimes.lastIndex) "or $r" else r
                        }.joinToString(", ")
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource("settings.skills.description", runtimesLabel),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource("settings.skills.runtimes"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            runtimes.forEach { runtime ->
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                ) {
                                    Text(
                                        text = runtime,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                            }
                        }
                    }
                    // Toolbar actions
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        skillsModeToggle(agenticMode = false, onToggle = { if (it) onSwitchToAgentic() })
                        themedTooltip(text = stringResource("skills.view.docs.tooltip")) {
                            IconButton(
                                onClick = {
                                    runCatching { Desktop.getDesktop().browse(URI("https://$DOMAIN/docs/desktop/skills/")) }
                                },
                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = stringResource("skills.view.docs.tooltip"),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TextButton(
                            onClick = onNavigateToSkillsSettings,
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Text(
                                text = stringResource("skills.view.manage"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (showPanelToggle) {
                            val panelTooltip = stringResource(
                                if (panelVisible) "skills.view.panel.collapse" else "skills.view.panel.expand",
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

                // ── Selected skill header ──────────────────────────────────
                if (selectedSkill != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                        modifier = Modifier.padding(bottom = Spacing.small),
                    ) {
                        Text("🤖", style = MaterialTheme.typography.headlineLarge)
                        Column(modifier = Modifier.weight(1f)) {
                            selectableText(
                                selectedSkill.name,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            if (selectedSkill.description.isNotBlank()) {
                                selectableText(
                                    selectedSkill.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (lastRunRecord != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        if (lastRunRecord.error != null) Icons.Default.Close else Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(11.dp),
                                        tint = if (lastRunRecord.error != null) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                    Text(
                                        text = stringResource(
                                            "skills.view.last.run",
                                            formatRelativeTime(lastRunRecord.createdAt),
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                } else {
                    // No skill selected — point to the right panel
                    Spacer(modifier = Modifier.height(64.dp))
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Default.Extension,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                        )
                        Spacer(modifier = Modifier.height(Spacing.medium))
                        Text(
                            text = if (skills.isEmpty()) {
                                stringResource("skills.view.empty")
                            } else {
                                stringResource("skills.view.select.prompt")
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(Spacing.small))
                        Text(
                            text = if (skills.isEmpty()) {
                                stringResource("skills.view.empty.hint")
                            } else {
                                stringResource("skills.view.select.hint")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            }

            // ── Execution area ─────────────────────────────────────────────
            if (selectedSkill != null) {
                skillExecutionArea(
                    skill = selectedSkill,
                    workDir = workDir,
                    onRunCompleted = onRunCompleted,
                    preloadRecord = pendingHistoryRecord,
                    onPreloadConsumed = onPreloadConsumed,
                )
            }
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 4.dp),
            style = ScrollbarStyle(
                minimalHeight = 16.dp,
                thickness = 8.dp,
                shape = MaterialTheme.shapes.small,
                hoverDurationMillis = 300,
                unhoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                hoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f),
            ),
        )
    }
}

// ── Manual right panel (Skills + Workspace tabs) ──────────────────────────

private enum class ManualRightTab(
    val icon: ImageVector,
    val labelKey: String,
) {
    SKILLS(Icons.Default.Extension, "skills.view.tab.skills"),
    WORKSPACE(Icons.Default.FolderOpen, "skills.view.tab.workspace"),
}

/**
 * Collapsible right panel that mirrors [communityProjectSidePanel]'s layout:
 * always-visible 56 dp icon bar on the right, draggable content area on the left.
 *
 * - **Skills tab** — [skillsCompactList] for picking a skill to run
 * - **Workspace tab** — [workspaceFilesPanel] for viewing output files
 *
 * Auto-switches to Workspace when [workDirRefreshKey] increments (run completed).
 */
@Composable
private fun manualRightPanel(
    skills: List<SkillDefinition>,
    selectedSkill: SkillDefinition?,
    workDir: File,
    workDirRefreshKey: Int,
    onSelectSkill: (SkillDefinition) -> Unit,
    onWorkDirChanged: (File) -> Unit,
) {
    var isExpanded by remember { mutableStateOf(ApplicationPreferences.getSkillsSidePanelExpanded()) }
    var panelWidth by remember { mutableStateOf(ApplicationPreferences.getSkillsSidePanelWidth().dp) }
    var activeTab by remember { mutableStateOf(ManualRightTab.SKILLS) }

    // Auto-switch to Workspace tab when a run completes
    LaunchedEffect(workDirRefreshKey) {
        if (workDirRefreshKey > 0) {
            activeTab = ManualRightTab.WORKSPACE
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
                                    panelWidth = (panelWidth - dragAmount.x.toDp()).coerceIn(200.dp, 520.dp)
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
                    // Header: tab title + collapse button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(activeTab.labelKey),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        IconButton(
                            onClick = {
                                isExpanded = false
                                ApplicationPreferences.setSkillsSidePanelExpanded(false)
                            },
                            modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                Icons.Default.Remove,
                                contentDescription = stringResource("skills.view.panel.collapse"),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

                    // Tab content
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (activeTab) {
                            ManualRightTab.SKILLS -> skillsCompactList(
                                skills = skills,
                                selectedSkill = selectedSkill,
                                onSelectSkill = onSelectSkill,
                            )

                            ManualRightTab.WORKSPACE -> workspaceFilesPanel(
                                workDir = workDir,
                                refreshKey = workDirRefreshKey,
                                onWorkDirChanged = onWorkDirChanged,
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
                ManualRightTab.entries.forEach { tab ->
                    manualRightTabIcon(
                        tab = tab,
                        isSelected = isExpanded && activeTab == tab,
                        badge = if (tab == ManualRightTab.SKILLS && skills.isNotEmpty()) "${skills.size}" else null,
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

@Composable
private fun manualRightTabIcon(
    tab: ManualRightTab,
    isSelected: Boolean,
    badge: String? = null,
    onClick: () -> Unit,
) {
    themedTooltip(text = stringResource(tab.labelKey)) {
        Box(
            contentAlignment = Alignment.TopEnd,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        } else {
                            androidx.compose.ui.graphics.Color.Transparent
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
                    imageVector = tab.icon,
                    contentDescription = stringResource(tab.labelKey),
                    tint = if (isSelected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
            // Badge (e.g. skill count)
            if (badge != null) {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    modifier = Modifier.padding(top = 1.dp, end = 1.dp),
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 3.dp, vertical = 0.dp),
                    )
                }
            }
        }
    }
}
