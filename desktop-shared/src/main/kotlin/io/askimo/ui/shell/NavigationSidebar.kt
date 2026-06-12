/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.shell

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.domain.Project
import io.askimo.core.db.DatabaseManager
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.ChatCompletedEvent
import io.askimo.core.event.internal.ChatInProgressEvent
import io.askimo.core.event.internal.ProjectsRefreshEvent
import io.askimo.core.event.internal.SessionsRefreshEvent
import io.askimo.core.user.domain.UserProfile
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppComponents.dropdownMenu
import io.askimo.ui.common.theme.LocalFontScale
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.themedTooltip
import io.askimo.ui.session.SessionActionMenu
import io.askimo.ui.session.SessionsViewModel
import io.askimo.ui.session.deleteSessionDialog
import io.askimo.ui.session.sessionTooltip
import io.askimo.ui.util.Platform
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import org.jetbrains.skia.Image as SkiaImage

/**
 * Read-only state contract needed by the sidebar's projects section.
 *
 * The sidebar never mutates projects — it only reads state through this interface.
 */
interface ProjectsSidebarState {
    val projects: List<Project>
    val starredProjects: List<Project> get() = projects.filter { it.isStarred }
}

/**
 * Shared navigation sidebar component with collapsible/expandable functionality.
 *
 * The caller is responsible for two variable parts:
 * - Selection state: pass `isXxxSelected` booleans derived from the module's own View enum
 * - User profile footer: pass a `userProfileContent` composable slot
 *
 * This keeps the sidebar decoupled from module-specific View enums and auth concerns
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun navigationSidebar(
    isExpanded: Boolean,
    width: Dp,
    // Selection state — callers derive these from their own View enum
    isPlansSelected: Boolean = false,
    isSkillsSelected: Boolean = false,
    isProjectsSelected: Boolean = false,
    isSessionsSelected: Boolean = false,
    // Visibility toggles controlled from View menu
    showPlansInSidebar: Boolean = true,
    showSkillsInSidebar: Boolean = true,
    showProjectsInSidebar: Boolean = true,
    // Session/project state
    isSessionsExpanded: Boolean,
    projectsState: ProjectsSidebarState,
    sessionsViewModel: SessionsViewModel,
    currentSessionId: String?,
    // Actions
    onToggleExpand: () -> Unit,
    onNewChat: () -> Unit,
    onNavigateToProjects: () -> Unit = {},
    onToggleSessions: () -> Unit,
    onNavigateToSessions: () -> Unit,
    onNavigateToPlans: () -> Unit = {},
    onNavigateToSkills: () -> Unit = {},
    onNewProject: () -> Unit = {},
    onSelectProject: (String) -> Unit = {},
    onResumeSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onStarSession: (String, Boolean) -> Unit,
    onStarProject: (String, Boolean) -> Unit = { _, _ -> },
    onRenameSession: (String, String) -> Unit,
    onExportSession: (String) -> Unit,
    onShowSessionSummary: (String) -> Unit = {},
    onEditProject: (String) -> Unit = {},
    onDeleteProject: (String) -> Unit = {},
    onMoveSessionToNewProject: (sessionId: String) -> Unit = {},
    // Slot: the caller renders the bottom user-profile row (avatar + menu)
    userProfileContent: @Composable () -> Unit,
) {
    val targetWidth = if (isExpanded) width else 72.dp
    val animatedWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = tween(durationMillis = 300),
    )

    if (isExpanded) {
        expandedNavigationSidebar(
            animatedWidth = animatedWidth,
            isPlansSelected = isPlansSelected,
            isSkillsSelected = isSkillsSelected,
            isProjectsSelected = isProjectsSelected,
            isSessionsSelected = isSessionsSelected,
            showPlansInSidebar = showPlansInSidebar,
            showSkillsInSidebar = showSkillsInSidebar,
            showProjectsInSidebar = showProjectsInSidebar,
            isSessionsExpanded = isSessionsExpanded,
            projectsState = projectsState,
            sessionsViewModel = sessionsViewModel,
            currentSessionId = currentSessionId,
            onToggleExpand = onToggleExpand,
            onNewChat = onNewChat,
            onNavigateToProjects = onNavigateToProjects,
            onToggleSessions = onToggleSessions,
            onNavigateToSessions = onNavigateToSessions,
            onNavigateToPlans = onNavigateToPlans,
            onNavigateToSkills = onNavigateToSkills,
            onNewProject = onNewProject,
            onSelectProject = onSelectProject,
            onResumeSession = onResumeSession,
            onDeleteSession = onDeleteSession,
            onStarSession = onStarSession,
            onStarProject = onStarProject,
            onRenameSession = onRenameSession,
            onExportSession = onExportSession,
            onShowSessionSummary = onShowSessionSummary,
            onEditProject = onEditProject,
            onDeleteProject = onDeleteProject,
            onMoveSessionToNewProject = onMoveSessionToNewProject,
            userProfileContent = userProfileContent,
        )
    } else {
        collapsedNavigationSidebar(
            animatedWidth = animatedWidth,
            isPlansSelected = isPlansSelected,
            isSkillsSelected = isSkillsSelected,
            isProjectsSelected = isProjectsSelected,
            isSessionsSelected = isSessionsSelected,
            showPlansInSidebar = showPlansInSidebar,
            showSkillsInSidebar = showSkillsInSidebar,
            showProjectsInSidebar = showProjectsInSidebar,
            onToggleExpand = onToggleExpand,
            onNewChat = onNewChat,
            onNavigateToProjects = onNavigateToProjects,
            onNavigateToSessions = onNavigateToSessions,
            onNavigateToPlans = onNavigateToPlans,
            onNavigateToSkills = onNavigateToSkills,
            userProfileContent = userProfileContent,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Expanded sidebar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun expandedNavigationSidebar(
    animatedWidth: Dp,
    isPlansSelected: Boolean,
    isSkillsSelected: Boolean,
    isProjectsSelected: Boolean,
    isSessionsSelected: Boolean,
    showPlansInSidebar: Boolean,
    showSkillsInSidebar: Boolean,
    showProjectsInSidebar: Boolean,
    isSessionsExpanded: Boolean,
    projectsState: ProjectsSidebarState,
    sessionsViewModel: SessionsViewModel,
    currentSessionId: String?,
    onToggleExpand: () -> Unit,
    onNewChat: () -> Unit,
    onNavigateToProjects: () -> Unit,
    onToggleSessions: () -> Unit,
    onNavigateToSessions: () -> Unit,
    onNavigateToPlans: () -> Unit,
    onNavigateToSkills: () -> Unit,
    onNewProject: () -> Unit,
    onSelectProject: (String) -> Unit,
    onResumeSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onStarSession: (String, Boolean) -> Unit,
    onStarProject: (String, Boolean) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onExportSession: (String) -> Unit,
    onShowSessionSummary: (String) -> Unit,
    onEditProject: (String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onMoveSessionToNewProject: (sessionId: String) -> Unit,
    userProfileContent: @Composable () -> Unit,
) {
    val fontScale = LocalFontScale.current

    val inProgressSessionIds = remember { mutableStateSetOf<String>() }
    LaunchedEffect(Unit) {
        EventBus.internalEvents.collect { event ->
            when (event) {
                is ChatInProgressEvent -> inProgressSessionIds.add(event.sessionId)
                is ChatCompletedEvent -> inProgressSessionIds.remove(event.sessionId)
                else -> Unit
            }
        }
    }

    Column(
        modifier = Modifier
            .width(animatedWidth)
            .fillMaxHeight()
            .background(AppComponents.sidebarSurfaceColor()),
    ) {
        // Header with logo and collapse button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppComponents.sidebarHeaderColor())
                .padding(Spacing.large),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sidebarLogoRow(fontScale = fontScale)
            themedTooltip(text = stringResource("sidebar.collapse")) {
                IconButton(
                    onClick = onToggleExpand,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.MenuOpen,
                        contentDescription = stringResource("sidebar.collapse"),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        HorizontalDivider()

        // Scrollable nav items
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = Spacing.small),
        ) {
            // New Chat
            themedTooltip(text = stringResource("chat.new.tooltip", Platform.modifierKey)) {
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    label = { Text(stringResource("chat.new"), style = MaterialTheme.typography.labelLarge) },
                    selected = false,
                    onClick = onNewChat,
                    modifier = Modifier
                        .padding(horizontal = Spacing.medium)
                        .pointerHoverIcon(PointerIcon.Hand),
                    colors = AppComponents.navigationDrawerItemColors(),
                )
            }

            // Projects
            if (showProjectsInSidebar) {
                val projectsInteractionSource = remember { MutableInteractionSource() }
                val isProjectsHovered by projectsInteractionSource.collectIsHoveredAsState()

                Box(
                    modifier = Modifier
                        .padding(horizontal = Spacing.medium)
                        .hoverable(projectsInteractionSource),
                ) {
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                        label = { Text(stringResource("project.title"), style = MaterialTheme.typography.labelLarge) },
                        selected = isProjectsSelected,
                        onClick = onNavigateToProjects,
                        badge = {
                            if (isProjectsHovered) {
                                themedTooltip(text = stringResource("project.new.dialog.title")) {
                                    IconButton(
                                        onClick = onNewProject,
                                        modifier = Modifier
                                            .size((24 * fontScale).dp)
                                            .pointerHoverIcon(PointerIcon.Hand),
                                    ) {
                                        Icon(
                                            Icons.Default.Add,
                                            contentDescription = stringResource("project.new.dialog.title"),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size((18 * fontScale).dp),
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        colors = AppComponents.navigationDrawerItemColors(),
                    )
                }
            }

            // Plans
            if (showPlansInSidebar) {
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.PlayCircle, contentDescription = null) },
                    label = { Text(stringResource("plans.nav.title"), style = MaterialTheme.typography.labelLarge) },
                    selected = isPlansSelected,
                    onClick = onNavigateToPlans,
                    modifier = Modifier
                        .padding(horizontal = Spacing.medium)
                        .pointerHoverIcon(PointerIcon.Hand),
                    colors = AppComponents.navigationDrawerItemColors(),
                )
            }

            // Skills
            if (showSkillsInSidebar) {
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Extension, contentDescription = null) },
                    label = { Text(stringResource("skills.nav.title"), style = MaterialTheme.typography.labelLarge) },
                    selected = isSkillsSelected,
                    onClick = onNavigateToSkills,
                    modifier = Modifier
                        .padding(horizontal = Spacing.medium)
                        .pointerHoverIcon(PointerIcon.Hand),
                    colors = AppComponents.navigationDrawerItemColors(),
                )
            }

            // Pinned section (starred projects + starred sessions)
            pinnedSection(
                starredProjects = projectsState.starredProjects,
                starredSessions = sessionsViewModel.recentSessions.filter { it.isStarred },
                currentSessionId = currentSessionId,
                inProgressSessionIds = inProgressSessionIds,
                onSelectProject = onSelectProject,
                onResumeSession = onResumeSession,
                onStarProject = onStarProject,
                onStarSession = onStarSession,
                onDeleteSession = onDeleteSession,
                onRenameSession = onRenameSession,
                onExportSession = onExportSession,
                onEditProject = onEditProject,
                onDeleteProject = onDeleteProject,
            )

            // Sessions header (collapsible)
            NavigationDrawerItem(
                icon = { Icon(Icons.Default.History, contentDescription = null) },
                label = { Text(stringResource("chat.sessions"), style = MaterialTheme.typography.labelLarge) },
                selected = isSessionsSelected,
                onClick = onToggleSessions,
                badge = {
                    val badgeColor = if (isSessionsSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                    ) {
                        if (!isSessionsExpanded && sessionsViewModel.totalSessionCount > 0) {
                            val count = sessionsViewModel.totalSessionCount
                            Text(
                                text = if (count > 99) "99+" else "$count",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                        Icon(
                            imageVector = if (isSessionsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isSessionsExpanded) "Collapse" else "Expand",
                            tint = badgeColor,
                        )
                    }
                },
                modifier = Modifier
                    .padding(horizontal = Spacing.medium)
                    .pointerHoverIcon(PointerIcon.Hand),
                colors = AppComponents.navigationDrawerItemColors(),
            )

            if (isSessionsExpanded) {
                sessionsList(
                    sessionsViewModel = sessionsViewModel,
                    currentSessionId = currentSessionId,
                    inProgressSessionIds = inProgressSessionIds,
                    onNavigateToSessions = onNavigateToSessions,
                    onResumeSession = onResumeSession,
                    onDeleteSession = onDeleteSession,
                    onStarSession = onStarSession,
                    onRenameSession = onRenameSession,
                    onExportSession = onExportSession,
                    onShowSessionSummary = onShowSessionSummary,
                    availableProjects = projectsState.projects,
                    onMoveSessionToNewProject = onMoveSessionToNewProject,
                )
            }
        }

        // Bottom: user profile slot
        HorizontalDivider()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        ) {
            userProfileContent()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Collapsed sidebar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun collapsedNavigationSidebar(
    animatedWidth: Dp,
    isPlansSelected: Boolean,
    isSkillsSelected: Boolean,
    isProjectsSelected: Boolean,
    isSessionsSelected: Boolean,
    showPlansInSidebar: Boolean,
    showSkillsInSidebar: Boolean,
    showProjectsInSidebar: Boolean,
    onToggleExpand: () -> Unit,
    onNewChat: () -> Unit,
    onNavigateToProjects: () -> Unit,
    onNavigateToSessions: () -> Unit,
    onNavigateToPlans: () -> Unit,
    onNavigateToSkills: () -> Unit,
    userProfileContent: @Composable () -> Unit,
) {
    val fontScale = LocalFontScale.current

    Column(
        modifier = Modifier
            .width(animatedWidth)
            .fillMaxHeight()
            .background(AppComponents.sidebarSurfaceColor()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val headerInteractionSource = remember { MutableInteractionSource() }
        val isHeaderHovered by headerInteractionSource.collectIsHoveredAsState()
        val appLogo = rememberAppLogo()

        themedTooltip(text = stringResource("sidebar.expand")) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppComponents.sidebarHeaderColor())
                    .padding(vertical = Spacing.large)
                    .hoverable(headerInteractionSource)
                    .clickable(
                        interactionSource = headerInteractionSource,
                        indication = null,
                        onClick = onToggleExpand,
                    )
                    .pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center,
            ) {
                if (isHeaderHovered) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = stringResource("sidebar.expand"),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size((32 * fontScale).dp),
                    )
                } else {
                    Icon(
                        painter = appLogo,
                        contentDescription = "Askimo",
                        modifier = Modifier.size((32 * fontScale).dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        HorizontalDivider()

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = Spacing.small)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            themedTooltip(text = stringResource("chat.new.tooltip", Platform.modifierKey)) {
                NavigationRailItem(
                    icon = { Icon(Icons.Default.Add, contentDescription = stringResource("chat.new")) },
                    label = null,
                    selected = false,
                    onClick = onNewChat,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    colors = AppComponents.navigationRailItemColors(),
                )
            }

            themedTooltip(text = stringResource("project.title")) {
                if (showProjectsInSidebar) {
                    NavigationRailItem(
                        icon = { Icon(Icons.Default.FolderOpen, contentDescription = stringResource("project.title")) },
                        label = null,
                        selected = isProjectsSelected,
                        onClick = onNavigateToProjects,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        colors = AppComponents.navigationRailItemColors(),
                    )
                }
            }

            themedTooltip(text = stringResource("plans.nav.title")) {
                if (showPlansInSidebar) {
                    NavigationRailItem(
                        icon = { Icon(Icons.Default.PlayCircle, contentDescription = stringResource("plans.nav.title")) },
                        label = null,
                        selected = isPlansSelected,
                        onClick = onNavigateToPlans,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        colors = AppComponents.navigationRailItemColors(),
                    )
                }
            }

            themedTooltip(text = stringResource("skills.nav.title")) {
                if (showSkillsInSidebar) {
                    NavigationRailItem(
                        icon = { Icon(Icons.Default.Extension, contentDescription = stringResource("skills.nav.title")) },
                        label = null,
                        selected = isSkillsSelected,
                        onClick = onNavigateToSkills,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        colors = AppComponents.navigationRailItemColors(),
                    )
                }
            }

            themedTooltip(text = stringResource("chat.sessions.tooltip")) {
                NavigationRailItem(
                    icon = { Icon(Icons.Default.History, contentDescription = stringResource("chat.sessions")) },
                    label = null,
                    selected = isSessionsSelected,
                    onClick = onNavigateToSessions,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    colors = AppComponents.navigationRailItemColors(),
                )
            }
        }

        HorizontalDivider()
        userProfileContent()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Logo helpers
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun rememberAppLogo(): BitmapPainter = remember {
    BitmapPainter(
        SkiaImage.makeFromEncoded(
            object {}.javaClass.getResourceAsStream("/images/askimo_logo_64.png")?.readBytes()
                ?: error("askimo_logo_64.png not found"),
        ).toComposeImageBitmap(),
    )
}

/**
 * Expanded header: logo icon + "Askimo" text.
 * Each module can overlay its own badge (e.g. "Pro") by wrapping the logo in a Box
 * and passing that as [logoOverlay].
 */
@Composable
fun sidebarLogoRow(
    fontScale: Float = LocalFontScale.current,
    logoOverlay: @Composable (Modifier) -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        Box {
            Icon(
                painter = rememberAppLogo(),
                contentDescription = "Askimo",
                modifier = Modifier.size((48 * fontScale).dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            logoOverlay(Modifier.align(Alignment.TopEnd))
        }
        Text(
            text = "Askimo",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pinned section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun pinnedSection(
    starredProjects: List<Project>,
    starredSessions: List<ChatSession>,
    currentSessionId: String?,
    inProgressSessionIds: Set<String>,
    onSelectProject: (String) -> Unit,
    onResumeSession: (String) -> Unit,
    onStarProject: (String, Boolean) -> Unit,
    onStarSession: (String, Boolean) -> Unit,
    onDeleteSession: (String) -> Unit = {},
    onRenameSession: (String, String) -> Unit = { _, _ -> },
    onExportSession: (String) -> Unit = {},
    onEditProject: (String) -> Unit = {},
    onDeleteProject: (String) -> Unit = {},
) {
    if (starredProjects.isEmpty() && starredSessions.isEmpty()) return

    var isExpanded by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier.padding(
            start = Spacing.medium,
            end = Spacing.medium,
            top = Spacing.extraSmall,
        ),
    ) {
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Star, contentDescription = null) },
            label = {
                Text(
                    stringResource("sidebar.pinned"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            selected = false,
            onClick = { isExpanded = !isExpanded },
            badge = {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            colors = AppComponents.navigationDrawerItemColors(),
        )

        if (isExpanded) {
            Column(modifier = Modifier.padding(start = Spacing.large)) {
                starredProjects.forEach { project ->
                    pinnedProjectItem(
                        project = project,
                        onSelectProject = onSelectProject,
                        onUnpin = { onStarProject(project.id, false) },
                        onEdit = { onEditProject(project.id) },
                        onDelete = { onDeleteProject(project.id) },
                    )
                }
                starredSessions.forEach { session ->
                    pinnedSessionItem(
                        session = session,
                        isSelected = session.id == currentSessionId,
                        isChatInProgress = session.id in inProgressSessionIds,
                        onResumeSession = onResumeSession,
                        onUnpin = { onStarSession(session.id, false) },
                        onDelete = { onDeleteSession(session.id) },
                        onRename = { onRenameSession(session.id, session.title) },
                        onExport = { onExportSession(session.id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun pinnedProjectItem(
    project: Project,
    onSelectProject: (String) -> Unit,
    onUnpin: () -> Unit,
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val fontScale = LocalFontScale.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.extraSmall / 2f)
            .hoverable(interactionSource),
    ) {
        themedTooltip(text = project.name) {
            NavigationDrawerItem(
                icon = { Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size((16 * fontScale).dp)) },
                label = {
                    navigationItemLabelWithMenu(
                        text = project.name,
                        onMenuClick = { showMenu = true },
                        isHovered = isHovered || showMenu,
                    )
                },
                selected = false,
                onClick = { onSelectProject(project.id) },
                modifier = Modifier
                    .padding(vertical = Spacing.extraSmall / 2f)
                    .pointerHoverIcon(PointerIcon.Hand),
                colors = AppComponents.navigationDrawerItemColors(),
            )
        }

        Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = Spacing.small)) {
            dropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource("action.unpin")) },
                    leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = {
                        showMenu = false
                        onUnpin()
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                )
                DropdownMenuItem(
                    text = { Text(stringResource("action.open")) },
                    leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                    onClick = {
                        showMenu = false
                        onSelectProject(project.id)
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                )
                DropdownMenuItem(
                    text = { Text(stringResource("action.edit")) },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = {
                        showMenu = false
                        onEdit()
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource("action.delete"), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showMenu = false
                        onDelete()
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun pinnedSessionItem(
    session: ChatSession,
    isSelected: Boolean,
    isChatInProgress: Boolean,
    onResumeSession: (String) -> Unit,
    onUnpin: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val fontScale = LocalFontScale.current

    if (showDeleteDialog) {
        deleteSessionDialog(
            sessionTitle = session.title,
            onConfirm = {
                showDeleteDialog = false
                onDelete()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.extraSmall / 2f)
            .hoverable(interactionSource),
    ) {
        sessionTooltip(session = session) {
            NavigationDrawerItem(
                icon = if (isChatInProgress) {
                    {
                        CircularProgressIndicator(
                            modifier = Modifier.size((14 * fontScale).dp),
                            strokeWidth = (2 * fontScale).dp,
                            color = LocalContentColor.current,
                        )
                    }
                } else {
                    null
                },
                label = {
                    navigationItemLabelWithMenu(
                        text = session.title,
                        onMenuClick = { showMenu = true },
                        isHovered = isHovered || showMenu,
                    )
                },
                selected = isSelected,
                onClick = { onResumeSession(session.id) },
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerHoverIcon(PointerIcon.Hand),
                colors = AppComponents.navigationDrawerItemColors(),
            )
        }

        Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = Spacing.small)) {
            dropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource("action.unpin")) },
                    leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = {
                        showMenu = false
                        onUnpin()
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                )
                DropdownMenuItem(
                    text = { Text(stringResource("action.rename")) },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = {
                        showMenu = false
                        onRename()
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                )
                DropdownMenuItem(
                    text = { Text(stringResource("action.export")) },
                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                    onClick = {
                        showMenu = false
                        onExport()
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource("action.delete"), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showMenu = false
                        showDeleteDialog = true
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sessions list
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun sessionsList(
    sessionsViewModel: SessionsViewModel,
    currentSessionId: String?,
    inProgressSessionIds: Set<String>,
    onNavigateToSessions: () -> Unit,
    onResumeSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onStarSession: (String, Boolean) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onExportSession: (String) -> Unit,
    onShowSessionSummary: (String) -> Unit = {},
    availableProjects: List<Project>,
    onMoveSessionToNewProject: (sessionId: String) -> Unit,
) {
    Column(
        modifier = Modifier.padding(
            start = Spacing.large * 2f,
            end = Spacing.medium,
            top = Spacing.extraSmall,
            bottom = Spacing.extraSmall,
        ),
    ) {
        if (sessionsViewModel.recentSessions.isEmpty()) {
            Text(
                text = "No sessions yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = Spacing.large,
                    vertical = Spacing.small,
                ),
            )
        } else {
            val unstarredSessions = sessionsViewModel.recentSessions.filter { !it.isStarred }

            unstarredSessions.forEach { session ->
                sessionItemWithMenu(
                    session = session,
                    isSelected = session.id == currentSessionId,
                    isChatInProgress = session.id in inProgressSessionIds,
                    onResumeSession = onResumeSession,
                    onDeleteSession = onDeleteSession,
                    onStarSession = onStarSession,
                    onRenameSession = onRenameSession,
                    onExportSession = onExportSession,
                    onShowSessionSummary = onShowSessionSummary,
                    availableProjects = availableProjects,
                    onMoveSessionToNewProject = onMoveSessionToNewProject,
                )
            }

            if (sessionsViewModel.totalSessionCount > SessionsViewModel.MAX_SIDEBAR_SESSIONS) {
                NavigationDrawerItem(
                    icon = null,
                    label = {
                        Text(
                            text = "More...",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    selected = false,
                    onClick = onNavigateToSessions,
                    modifier = Modifier
                        .padding(vertical = Spacing.extraSmall / 2f)
                        .pointerHoverIcon(PointerIcon.Hand),
                    colors = AppComponents.navigationDrawerItemColors(),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Session item with context menu
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun sessionItemWithMenu(
    session: ChatSession,
    isSelected: Boolean,
    isChatInProgress: Boolean,
    onResumeSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onStarSession: (String, Boolean) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onExportSession: (String) -> Unit,
    onShowSessionSummary: (String) -> Unit = {},
    availableProjects: List<Project>,
    onMoveSessionToNewProject: (sessionId: String) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val fontScale = LocalFontScale.current
    val sessionRepository = remember { DatabaseManager.getInstance().getChatSessionRepository() }

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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.extraSmall / 2f)
            .hoverable(interactionSource),
    ) {
        sessionTooltip(session = session) {
            NavigationDrawerItem(
                icon = if (isChatInProgress) {
                    {
                        CircularProgressIndicator(
                            modifier = Modifier.size((14 * fontScale).dp),
                            strokeWidth = (2 * fontScale).dp,
                            color = LocalContentColor.current,
                        )
                    }
                } else {
                    null
                },
                label = {
                    navigationItemLabelWithMenu(
                        text = session.title,
                        onMenuClick = { showMenu = true },
                        isHovered = isHovered || showMenu,
                    )
                },
                selected = isSelected,
                onClick = { onResumeSession(session.id) },
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerHoverIcon(PointerIcon.Hand),
                colors = AppComponents.navigationDrawerItemColors(),
            )
        }

        Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = Spacing.small)) {
            dropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                SessionActionMenu.sidebarMenu(
                    isStarred = session.isStarred,
                    projects = availableProjects,
                    onExport = { onExportSession(session.id) },
                    onRename = { onRenameSession(session.id, session.title) },
                    onStar = { onStarSession(session.id, !session.isStarred) },
                    onDelete = {
                        showMenu = false
                        showDeleteDialog = true
                    },
                    onMoveToNewProject = {
                        showMenu = false
                        onMoveSessionToNewProject(session.id)
                    },
                    onMoveToExistingProject = { selectedProject ->
                        sessionRepository.updateSessionProject(session.id, selectedProject.id)
                        EventBus.post(ProjectsRefreshEvent(reason = "Session ${session.id} moved to project ${selectedProject.id}"))
                        EventBus.post(SessionsRefreshEvent(reason = "Session ${session.id} moved to project ${selectedProject.id}"))
                    },
                    onShowSessionSummary = { onShowSessionSummary(session.id) },
                    onDismiss = { showMenu = false },
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared sub-components
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun navigationItemLabelWithMenu(
    text: String,
    onMenuClick: () -> Unit,
    isHovered: Boolean,
) {
    val fontScale = LocalFontScale.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isHovered) {
            Box(modifier = Modifier.padding(start = Spacing.extraSmall)) {
                IconButton(
                    onClick = onMenuClick,
                    modifier = Modifier
                        .size((24 * fontScale).dp)
                        .pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size((18 * fontScale).dp),
                    )
                }
            }
        }
    }
}

/**
 * Shared user avatar composable — shows image if available, otherwise initials.
 */
@Composable
fun sidebarUserAvatar(
    profile: UserProfile?,
    avatarImage: ImageBitmap?,
    textStyle: TextStyle = MaterialTheme.typography.titleMedium,
) {
    if (avatarImage != null) {
        Image(
            bitmap = avatarImage,
            contentDescription = stringResource("user.profile.avatar"),
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.Crop,
        )
    } else {
        val initials = profile?.name?.split(" ")
            ?.mapNotNull { it.firstOrNull()?.uppercase() }
            ?.take(2)
            ?.joinToString("") ?: "?"
        Text(
            text = initials,
            style = textStyle,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/**
 * Loads avatar image from:
 * - Server URL (absolute or server-relative, resolved against [serverBaseUrl])
 * - Local file path
 *
 * Returns null if no avatar is configured or loading fails.
 */
@Composable
fun rememberAvatarImage(profile: UserProfile?, serverBaseUrl: String? = null): ImageBitmap? {
    val avatarPath = profile?.preferences?.get("serverAvatarUrl")
        ?: profile?.preferences?.get("avatarPath")

    return remember(avatarPath, serverBaseUrl) {
        if (avatarPath == null) return@remember null
        try {
            when {
                (avatarPath.startsWith("/") && serverBaseUrl != null) ||
                    avatarPath.startsWith("http://") || avatarPath.startsWith("https://") -> {
                    val url = if (avatarPath.startsWith("/")) "$serverBaseUrl$avatarPath" else avatarPath
                    val cleanUrl = url.substringBefore("?")
                    val http = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .version(HttpClient.Version.HTTP_1_1)
                        .build()
                    val request = HttpRequest.newBuilder()
                        .uri(URI.create(cleanUrl))
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build()
                    val response = http.send(request, BodyHandlers.ofByteArray())
                    if (response.statusCode() in 200..299) {
                        SkiaImage.makeFromEncoded(response.body()).toComposeImageBitmap()
                    } else {
                        null
                    }
                }

                else -> {
                    val file = File(avatarPath)
                    if (file.exists()) SkiaImage.makeFromEncoded(file.readBytes()).toComposeImageBitmap() else null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
