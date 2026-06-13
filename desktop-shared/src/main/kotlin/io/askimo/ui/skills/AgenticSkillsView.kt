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
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FolderOpen
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.skills.SkillRepository
import io.askimo.core.skills.agent.ExternalAgentLoader
import io.askimo.core.skills.domain.SkillDefinition
import io.askimo.core.util.AskimoHome
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.preferences.ApplicationPreferences
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.TooltipPlacement
import io.askimo.ui.common.ui.themedTooltip
import java.awt.Cursor
import java.awt.Desktop
import java.io.File
import java.net.URI

/** Returns the user-selected workspace dir, falling back to the default skills-workspace dir. */
internal fun resolveSkillsWorkspaceDir(): File {
    val saved = ApplicationPreferences.getSkillsWorkspaceDir()
    return if (saved != null) File(saved) else AskimoHome.skillsWorkspaceDir().toFile()
}

/**
 * Self-contained agentic skills sub-view.
 * Owns its own skills loading, layout, and workspace panel.
 * The agent autonomously selects skills from the full catalog.
 */
@Composable
internal fun agenticSkillsView(
    onSwitchToManual: () -> Unit,
    onNavigateToSkillsSettings: () -> Unit = {},
) {
    val skillRepository = remember { SkillRepository() }
    val skills by remember { mutableStateOf(skillRepository.getSkillsOnly()) }
    var allHistoryRefreshKey by remember { mutableStateOf(0) }
    var showOverlayPanel by remember { mutableStateOf(false) }

    // User-chosen workspace dir (persisted across sessions)
    var workDir by remember { mutableStateOf(resolveSkillsWorkspaceDir()) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 1100.dp
        LaunchedEffect(isWide) { if (isWide) showOverlayPanel = false }

        val panelContent: @Composable () -> Unit = {
            agenticWorkspacePanel(workDir = workDir, workDirRefreshKey = allHistoryRefreshKey, onWorkDirChanged = { workDir = it })
        }

        if (isWide) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    agenticContent(
                        skills = skills,
                        workDir = workDir,
                        onSwitchToManual = onSwitchToManual,
                        onRunCompleted = { allHistoryRefreshKey++ },
                        onNavigateToSkillsSettings = onNavigateToSkillsSettings,
                    )
                }
                panelContent()
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                agenticContent(
                    skills = skills,
                    workDir = workDir,
                    onSwitchToManual = onSwitchToManual,
                    onRunCompleted = { allHistoryRefreshKey++ },
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
    onSwitchToManual: () -> Unit,
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Mode toggle — Agentic is active; clicking Manual switches sub-view
                        skillsModeToggle(agenticMode = true, onToggle = { if (!it) onSwitchToManual() })
                        themedTooltip(text = stringResource("skills.view.docs.tooltip")) {
                            IconButton(
                                onClick = {
                                    runCatching {
                                        Desktop.getDesktop().browse(URI("https://$DOMAIN/docs/desktop/skills/"))
                                    }
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
            }

            // ── Agentic execution area ─────────────────────────────────────
            agenticRunArea(
                skills = skills,
                workDir = workDir,
                onRunCompleted = onRunCompleted,
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

@Composable
private fun agenticWorkspacePanel(
    workDir: File,
    workDirRefreshKey: Int,
    onWorkDirChanged: (File) -> Unit,
) {
    var isExpanded by remember { mutableStateOf(ApplicationPreferences.getSkillsSidePanelExpanded()) }
    var panelWidth by remember { mutableStateOf(ApplicationPreferences.getSkillsSidePanelWidth().dp) }

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
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource("skills.view.workspace"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
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
                        workspaceFilesPanel(workDir = workDir, refreshKey = workDirRefreshKey, onWorkDirChanged = onWorkDirChanged)
                    }
                }
            } else {
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
