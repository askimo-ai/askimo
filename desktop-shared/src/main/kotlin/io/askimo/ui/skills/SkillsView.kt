/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.skills

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.askimo.core.db.DatabaseManager
import io.askimo.core.skills.SkillRepository
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

@Composable
fun skillsView() {
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
    val coroutineScope = rememberCoroutineScope()

    Row(modifier = Modifier.fillMaxSize()) {
        // ── Left: main content area ────────────────────────────────────────
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            skillsMainContent(
                skills = skills,
                selectedSkill = selectedSkill,
                pendingHistoryRecord = pendingHistoryRecord,
                onPreloadConsumed = { pendingHistoryRecord = null },
                onRunCompleted = { allHistoryRefreshKey++ },
            )
        }

        // ── Right: tabbed rail ─────────────────────────────────────────────
        skillsRightRail(
            skills = skills,
            selectedSkill = selectedSkill,
            runHistory = if (selectedSkill != null) {
                allRunHistory.filter { it.skillPath == selectedSkill!!.relativePath }
            } else {
                allRunHistory
            },
            filterSkillName = selectedSkill?.name,
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
}

// ── Main content ───────────────────────────────────────────────────────────

@Composable
private fun skillsMainContent(
    skills: List<SkillDefinition>,
    selectedSkill: SkillDefinition?,
    pendingHistoryRecord: SkillRunRecord?,
    onPreloadConsumed: () -> Unit,
    onRunCompleted: () -> Unit,
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
                Text(
                    text = stringResource("skills.view.title"),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource("skills.view.description"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )

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
                    }
                }
            }

            if (selectedSkill != null) {
                skillExecutionArea(
                    skill = selectedSkill,
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

// ── Right rail ─────────────────────────────────────────────────────────────

private enum class RailTab(val index: Int, val icon: ImageVector) {
    SKILLS(0, Icons.Default.Extension),
    HISTORY(1, Icons.Default.History),
}

@Composable
private fun skillsRightRail(
    skills: List<SkillDefinition>,
    selectedSkill: SkillDefinition?,
    runHistory: List<SkillRunRecord>,
    filterSkillName: String?,
    onSelectSkill: (SkillDefinition) -> Unit,
    onSelectRecord: (SkillRunRecord) -> Unit,
    onDeleteRecord: (SkillRunRecord) -> Unit,
) {
    var activeTab by remember {
        mutableStateOf(
            RailTab.entries.firstOrNull { it.index == ApplicationPreferences.getSkillsRightRailTab() }
                ?: RailTab.SKILLS,
        )
    }
    var isExpanded by remember { mutableStateOf(ApplicationPreferences.getSkillsSidePanelExpanded()) }
    var panelWidth by remember { mutableStateOf(ApplicationPreferences.getSkillsSidePanelWidth().dp) }

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

            // ── Expanded content ───────────────────────────────────────────
            if (isExpanded) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        when (activeTab) {
                            RailTab.SKILLS -> skillsCompactList(
                                skills = skills,
                                selectedSkill = selectedSkill,
                                onSelectSkill = onSelectSkill,
                            )

                            RailTab.HISTORY -> Column(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                                ) {
                                    Icon(Icons.Default.History, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        filterSkillName ?: "All runs",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (runHistory.isNotEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .background(
                                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                                                    shape = MaterialTheme.shapes.extraSmall,
                                                )
                                                .padding(horizontal = 6.dp, vertical = 1.dp),
                                        ) {
                                            Text("${runHistory.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                                skillsHistoryContent(
                                    runHistory = runHistory,
                                    filterSkillName = filterSkillName,
                                    onSelectRecord = onSelectRecord,
                                    onDeleteRecord = onDeleteRecord,
                                )
                            }
                        }
                    }
                }
            }

            // ── Icon bar (always visible) ──────────────────────────────────
            Column(
                modifier = Modifier
                    .width(56.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(vertical = 16.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                RailTab.entries.forEach { tab ->
                    val isActive = isExpanded && tab == activeTab
                    themedTooltip(
                        text = if (tab == RailTab.SKILLS) "Skills" else "History",
                        placement = TooltipPlacement.LEFT,
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    if (isActive) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    } else {
                                        androidx.compose.ui.graphics.Color.Transparent
                                    },
                                )
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                ) {
                                    if (isExpanded && tab == activeTab) {
                                        // collapse if clicking the already-active tab icon
                                        isExpanded = false
                                        ApplicationPreferences.setSkillsSidePanelExpanded(false)
                                    } else {
                                        activeTab = tab
                                        ApplicationPreferences.setSkillsRightRailTab(tab.index)
                                        isExpanded = true
                                        ApplicationPreferences.setSkillsSidePanelExpanded(true)
                                    }
                                }
                                .pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                tab.icon,
                                contentDescription = if (tab == RailTab.SKILLS) "Skills" else "History",
                                tint = if (isActive) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp).alpha(if (isActive) 1f else 0.6f),
                            )
                        }
                    }
                }
            }
        }
    }
}
