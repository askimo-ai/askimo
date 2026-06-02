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
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import io.askimo.ui.common.ui.TooltipPlacement
import io.askimo.ui.common.ui.selectableText
import io.askimo.ui.common.ui.themedTooltip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Cursor
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.time.Duration
import java.time.Instant

@Composable
fun skillsView(
    onNavigateToSkillsSettings: () -> Unit = {},
) {
    val skillRepository = remember { SkillRepository() }
    val historyRepo = remember { DatabaseManager.getInstance().getSkillRunHistoryRepository() }
    var refreshKey by remember { mutableStateOf(0) }
    val skills by remember(refreshKey) { mutableStateOf(skillRepository.getSkillsOnly()) }

    var selectedSkill by remember { mutableStateOf<SkillDefinition?>(null) }

    var allRunHistory by remember { mutableStateOf(listOf<SkillRunRecord>()) }
    var allHistoryRefreshKey by remember { mutableStateOf(0) }
    LaunchedEffect(allHistoryRefreshKey) {
        allRunHistory = withContext(Dispatchers.IO) {
            historyRepo.findAll()
        }
    }

    var pendingHistoryRecord by remember { mutableStateOf<SkillRunRecord?>(null) }
    var workDir by remember { mutableStateOf(File(System.getProperty("user.home") ?: "/tmp")) }
    var workDirRefreshKey by remember { mutableStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    // Filtered history for the selected skill
    val selectedSkillHistory = remember(selectedSkill, allRunHistory) {
        if (selectedSkill != null) {
            allRunHistory.filter { it.skillPath == selectedSkill!!.relativePath }
        } else {
            allRunHistory
        }
    }
    val lastRunRecord = remember(selectedSkillHistory) {
        selectedSkillHistory.maxByOrNull { it.createdAt }
    }

    var showOverlayPanel by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 1100.dp

        // When resizing to wide, always show the inline panel; reset overlay state
        LaunchedEffect(isWide) {
            if (isWide) showOverlayPanel = false
        }

        val panelContent: @Composable () -> Unit = {
            skillsUnifiedPanel(
                skills = skills,
                selectedSkill = selectedSkill,
                runHistory = selectedSkillHistory,
                filterSkillName = selectedSkill?.name,
                workDir = workDir,
                workDirRefreshKey = workDirRefreshKey,
                onSelectSkill = { selectedSkill = it },
                onSelectRecord = { record ->
                    val skill = skills.firstOrNull { it.relativePath == record.skillPath }
                    if (skill != null) {
                        selectedSkill = skill
                        pendingHistoryRecord = record
                    }
                },
                onDeleteRecord = { record ->
                    coroutineScope.launch(Dispatchers.IO) {
                        historyRepo.deleteById(record.id)
                        allRunHistory = historyRepo.findAll()
                    }
                },
            )
        }

        if (isWide) {
            // ── Wide: side-by-side layout ──────────────────────────────────
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    skillsMainContent(
                        skills = skills,
                        selectedSkill = selectedSkill,
                        lastRunRecord = lastRunRecord,
                        pendingHistoryRecord = pendingHistoryRecord,
                        onPreloadConsumed = { pendingHistoryRecord = null },
                        onRunCompleted = { allHistoryRefreshKey++ },
                        onWorkDirChanged = {
                            workDir = it
                            workDirRefreshKey++
                        },
                        onNavigateToSkillsSettings = onNavigateToSkillsSettings,
                    )
                }
                panelContent()
            }
        } else {
            // ── Narrow: main content fills width, panel overlays from right ─
            Box(modifier = Modifier.fillMaxSize()) {
                skillsMainContent(
                    skills = skills,
                    selectedSkill = selectedSkill,
                    lastRunRecord = lastRunRecord,
                    pendingHistoryRecord = pendingHistoryRecord,
                    onPreloadConsumed = { pendingHistoryRecord = null },
                    onRunCompleted = { allHistoryRefreshKey++ },
                    onWorkDirChanged = {
                        workDir = it
                        workDirRefreshKey++
                    },
                    onNavigateToSkillsSettings = onNavigateToSkillsSettings,
                    showPanelToggle = true,
                    panelVisible = showOverlayPanel,
                    onTogglePanel = { showOverlayPanel = !showOverlayPanel },
                )

                // Scrim — tapping outside dismisses panel
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

                // Overlay panel slides in from the right
                AnimatedVisibility(
                    visible = showOverlayPanel,
                    enter = slideInHorizontally(initialOffsetX = { it }),
                    exit = slideOutHorizontally(targetOffsetX = { it }),
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    panelContent()
                }
            }
        }
    }
}

// ── Main content ───────────────────────────────────────────────────────────

@Composable
private fun skillsMainContent(
    skills: List<SkillDefinition>,
    selectedSkill: SkillDefinition?,
    lastRunRecord: SkillRunRecord?,
    pendingHistoryRecord: SkillRunRecord?,
    onPreloadConsumed: () -> Unit,
    onRunCompleted: () -> Unit,
    onWorkDirChanged: (File) -> Unit = {},
    onNavigateToSkillsSettings: () -> Unit = {},
    showPanelToggle: Boolean = false,
    panelVisible: Boolean = false,
    onTogglePanel: () -> Unit = {},
) {
    val scrollState = rememberScrollState()
    Box(modifier = Modifier.fillMaxSize()) {
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        themedTooltip(text = stringResource("skills.view.docs.tooltip")) {
                            IconButton(
                                onClick = { runCatching { Desktop.getDesktop().browse(URI("https://$DOMAIN/docs/desktop/skills/")) } },
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
                Spacer(modifier = Modifier.height(16.dp))

                if (selectedSkill != null) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
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
                            // ── Last run status chip ───────────────────────
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
                                        text = stringResource("skills.view.last.run", formatRelativeTime(lastRunRecord.createdAt)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                } else if (skills.isEmpty()) {
                    Spacer(modifier = Modifier.height(48.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Icon(
                            Icons.Default.Extension,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                        )
                        Spacer(modifier = Modifier.height(Spacing.medium))
                        Text(
                            text = stringResource("skills.view.empty"),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(Spacing.small))
                        Text(
                            text = stringResource("skills.view.empty.hint"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                        Spacer(modifier = Modifier.height(Spacing.medium))
                    }
                } else {
                    Spacer(modifier = Modifier.height(64.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Icon(
                            Icons.Default.Extension,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                        )
                        Spacer(modifier = Modifier.height(Spacing.medium))
                        Text(
                            text = stringResource("skills.view.select.prompt"),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(Spacing.small))
                        Text(
                            text = stringResource("skills.view.select.hint"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            }

            if (selectedSkill != null) {
                skillExecutionArea(
                    skill = selectedSkill,
                    onRunCompleted = onRunCompleted,
                    preloadRecord = pendingHistoryRecord,
                    onPreloadConsumed = onPreloadConsumed,
                    onWorkDirChanged = onWorkDirChanged,
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

// ── Unified right panel ────────────────────────────────────────────────────

@Composable
private fun skillsUnifiedPanel(
    skills: List<SkillDefinition>,
    selectedSkill: SkillDefinition?,
    runHistory: List<SkillRunRecord>,
    filterSkillName: String?,
    workDir: File,
    workDirRefreshKey: Int,
    onSelectSkill: (SkillDefinition) -> Unit,
    onSelectRecord: (SkillRunRecord) -> Unit,
    onDeleteRecord: (SkillRunRecord) -> Unit,
) {
    var isExpanded by remember { mutableStateOf(ApplicationPreferences.getSkillsSidePanelExpanded()) }
    var panelWidth by remember { mutableStateOf(ApplicationPreferences.getSkillsSidePanelWidth().dp) }
    var skillsSectionExpanded by remember { mutableStateOf(true) }
    var historySectionExpanded by remember { mutableStateOf(true) }
    var workspaceSectionExpanded by remember { mutableStateOf(false) }

    // Auto-expand history section when a new run is added
    var prevHistorySize by remember { mutableStateOf(runHistory.size) }
    LaunchedEffect(runHistory.size) {
        if (runHistory.size > prevHistorySize) {
            historySectionExpanded = true
        }
        prevHistorySize = runHistory.size
    }

    val animatedWidth by animateDpAsState(
        targetValue = if (isExpanded) panelWidth else 48.dp,
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
        Row(modifier = Modifier.fillMaxSize()) {
            // ── Drag-to-resize handle ──────────────────────────────────────
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
                                    panelWidth = newWidth.coerceIn(220.dp, 560.dp)
                                },
                                onDragEnd = {
                                    ApplicationPreferences.setSkillsSidePanelWidth(panelWidth.value.toInt())
                                },
                            )
                        },
                )
            }

            if (isExpanded) {
                // ── Expanded: three stacked sections ──────────────────────
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // ── SKILLS section ─────────────────────────────────────
                    railSectionHeader(
                        title = stringResource("skills.view.title"),
                        count = skills.size.takeIf { it > 0 },
                        isExpanded = skillsSectionExpanded,
                        onToggle = { skillsSectionExpanded = !skillsSectionExpanded },
                        trailingContent = {
                            // Collapse whole panel button
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
                        },
                    )
                    if (skillsSectionExpanded) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            skillsCompactList(
                                skills = skills,
                                selectedSkill = selectedSkill,
                                onSelectSkill = onSelectSkill,
                            )
                        }
                    }

                    // ── RECENT RUNS section ────────────────────────────────
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                    railSectionHeader(
                        title = filterSkillName ?: stringResource("skills.view.history.all"),
                        icon = Icons.Default.History,
                        count = runHistory.size.takeIf { it > 0 },
                        isExpanded = historySectionExpanded,
                        onToggle = { historySectionExpanded = !historySectionExpanded },
                    )
                    if (historySectionExpanded) {
                        Box(modifier = Modifier.weight(0.6f).fillMaxWidth()) {
                            skillsHistoryContent(
                                runHistory = runHistory,
                                filterSkillName = filterSkillName,
                                onSelectRecord = onSelectRecord,
                                onDeleteRecord = onDeleteRecord,
                            )
                        }
                    }

                    // ── WORKSPACE section ──────────────────────────────────
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                    railSectionHeader(
                        title = stringResource("skills.view.workspace"),
                        icon = Icons.Default.FolderOpen,
                        isExpanded = workspaceSectionExpanded,
                        onToggle = { workspaceSectionExpanded = !workspaceSectionExpanded },
                    )
                    if (workspaceSectionExpanded) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            workspaceFilesPanel(
                                workDir = workDir,
                                refreshKey = workDirRefreshKey,
                            )
                        }
                    }
                }
            } else {
                // ── Collapsed: single expand button ───────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    themedTooltip(
                        text = stringResource("skills.view.panel.expand"),
                        placement = TooltipPlacement.LEFT,
                    ) {
                        IconButton(
                            onClick = {
                                isExpanded = true
                                ApplicationPreferences.setSkillsSidePanelExpanded(true)
                            },
                            modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                Icons.Default.ChevronLeft,
                                contentDescription = stringResource("skills.view.panel.expand"),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Section header helper ──────────────────────────────────────────────────

@Composable
private fun railSectionHeader(
    title: String,
    icon: ImageVector? = null,
    count: Int? = null,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onToggle,
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(start = 8.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count != null) {
            Box(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                        shape = MaterialTheme.shapes.extraSmall,
                    )
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    "$count",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailingContent?.invoke()
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────

private fun formatRelativeTime(instant: Instant): String {
    val seconds = Duration.between(instant, Instant.now()).seconds.coerceAtLeast(0)
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60}m ago"
        seconds < 86400 -> "${seconds / 3600}h ago"
        else -> "${seconds / 86400}d ago"
    }
}
