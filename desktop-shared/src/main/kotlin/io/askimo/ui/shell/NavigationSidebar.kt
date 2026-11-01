/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.shell

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.domain.Label
import io.askimo.core.chat.domain.Project
import io.askimo.core.db.DatabaseManager
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.ChatCompletedEvent
import io.askimo.core.event.internal.ChatInProgressEvent
import io.askimo.core.event.internal.ProjectsRefreshEvent
import io.askimo.core.event.internal.SessionsRefreshEvent
import io.askimo.core.user.domain.UserProfile
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppComponents.dropdownMenu
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.LocalFontScale
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.themedTooltip
import io.askimo.ui.session.SessionActionMenu.sidebarMenu
import io.askimo.ui.session.SessionDateBucket
import io.askimo.ui.session.SessionGroupKey
import io.askimo.ui.session.SessionGroupOption
import io.askimo.ui.session.SessionSortOption
import io.askimo.ui.session.SessionsViewModel
import io.askimo.ui.session.bulkDeleteSessionsDialog
import io.askimo.ui.session.deleteSessionDialog
import io.askimo.ui.session.groupedByOption
import io.askimo.ui.session.labelBadgeRow
import io.askimo.ui.session.labelColorDot
import io.askimo.ui.session.labelsMenuItem
import io.askimo.ui.session.manageLabelsDialog
import io.askimo.ui.session.moveToProjectMenuItem
import io.askimo.ui.session.sessionTooltip
import io.askimo.ui.util.Platform
import kotlinx.coroutines.launch
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
 * Read-only state contract for the sidebar's pinned (starred) sessions section.
 */
interface PinnedSidebarState {
    val starredSessions: List<ChatSession>
}

/**
 * Shared navigation sidebar component with collapsible/expandable functionality.
 *
 * The caller is responsible for two variable parts:
 * - Nav items: pass an ordered [navItems] list — each entry controls icon, label, selection,
 *   visibility, click action, and an optional hover-sensitive badge composable.
 * - User profile footer: pass a [userProfileContent] composable slot.
 *
 * This keeps the sidebar decoupled from module-specific View enums and auth concerns.
 */
@Composable
fun navigationSidebar(
    isExpanded: Boolean,
    width: Dp,
    // Primary nav items — ordered, visibility-controlled, injected by caller
    navItems: List<SidebarNavItem>,
    // Session state
    isSessionsExpanded: Boolean,
    isSessionsSelected: Boolean = false,
    // Session/project state
    projectsState: ProjectsSidebarState,
    pinnedState: PinnedSidebarState,
    sessionsViewModel: SessionsViewModel,
    currentSessionId: String?,
    currentProjectId: String? = null,
    // Actions
    onToggleExpand: () -> Unit,
    onNewChat: () -> Unit,
    onToggleSessions: () -> Unit,
    onNavigateToSessions: () -> Unit,
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

    val scope = rememberCoroutineScope()

    // Optimistic UI override for the selected session.
    //
    // `onResumeSession` is async and the caller's `currentSessionId` state only updates once
    // that navigation completes, which can make the sidebar selection feel laggy on click.
    // To avoid that, `navigateToSession` sets this immediately on click so the item is
    // highlighted instantly, while the real navigation happens in the background. Once the
    // caller's `currentSessionId` catches up (see the LaunchedEffect below), this override is
    // cleared and the real state takes over again.
    var optimisticSessionId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentSessionId) {
        // Once the real state (from the caller) catches up, drop the optimistic override.
        optimisticSessionId = null
    }
    val effectiveSessionId = optimisticSessionId ?: currentSessionId
    val navigateToSession = remember(onResumeSession) {
        { sessionId: String ->
            optimisticSessionId = sessionId // 1. set selected background — instant
            scope.launch { onResumeSession(sessionId) } // 2. async "navigation" event — loads later
            Unit
        }
    }

    if (isExpanded) {
        expandedNavigationSidebar(
            animatedWidth = animatedWidth,
            navItems = navItems,
            isSessionsExpanded = isSessionsExpanded,
            isSessionsSelected = isSessionsSelected,
            projectsState = projectsState,
            pinnedState = pinnedState,
            sessionsViewModel = sessionsViewModel,
            currentSessionId = effectiveSessionId,
            currentProjectId = currentProjectId,
            onToggleExpand = onToggleExpand,
            onNewChat = onNewChat,
            onToggleSessions = onToggleSessions,
            onNavigateToSessions = onNavigateToSessions,
            onSelectProject = onSelectProject,
            onResumeSession = navigateToSession,
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
            navItems = navItems,
            isSessionsSelected = isSessionsSelected,
            onToggleExpand = onToggleExpand,
            onNewChat = onNewChat,
            onNavigateToSessions = onNavigateToSessions,
            userProfileContent = userProfileContent,
        )
    }

    // Rendered here (not inside expandedNavigationSidebar) so it's available regardless of
    // sidebar expand/collapse state, including when opened from SessionsView's table via
    // sessionsViewModel.openManageLabelsDialog with the sidebar collapsed.
    if (sessionsViewModel.showManageLabelsDialog) {
        manageLabelsDialog(
            labels = sessionsViewModel.allLabels,
            usageCounts = sessionsViewModel.labelUsageCounts,
            onCreate = sessionsViewModel::createLabel,
            onUpdate = sessionsViewModel::updateLabel,
            onDelete = sessionsViewModel::deleteLabel,
            onDismiss = sessionsViewModel::closeManageLabelsDialog,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Expanded sidebar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun expandedNavigationSidebar(
    animatedWidth: Dp,
    navItems: List<SidebarNavItem>,
    isSessionsExpanded: Boolean,
    isSessionsSelected: Boolean,
    projectsState: ProjectsSidebarState,
    pinnedState: PinnedSidebarState,
    sessionsViewModel: SessionsViewModel,
    currentSessionId: String?,
    currentProjectId: String? = null,
    onToggleExpand: () -> Unit,
    onNewChat: () -> Unit,
    onToggleSessions: () -> Unit,
    onNavigateToSessions: () -> Unit,
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
    var showSessionsSearchField by remember { mutableStateOf(false) }

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
            .background(AppColors.sidebarSurfaceColor()),
    ) {
        // Header with logo and collapse button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.sidebarHeaderColor())
                .padding(Spacing.large),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sidebarLogoRow(fontScale = fontScale, contentColor = AppColors.sidebarHeaderContentColor())
            themedTooltip(text = stringResource("sidebar.collapse")) {
                IconButton(
                    onClick = onToggleExpand,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    sidebarPanelToggleIcon(
                        isExpanded = true,
                        tint = AppColors.sidebarHeaderContentColor(),
                        modifier = Modifier.size(24.dp),
                        contentDescription = stringResource("sidebar.collapse"),
                    )
                }
            }
        }
        HorizontalDivider()

        // Scrollable nav items, with the Sessions header pinned via stickyHeader while the
        // rest of the sidebar (nav items, Pinned section, session rows) scrolls underneath it.
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = Spacing.small),
        ) {
            item {
                // New Chat
                themedTooltip(text = stringResource("chat.new.tooltip", Platform.modifierKey)) {
                    sidebarItem(
                        icon = { Icon(Icons.Default.EditNote, contentDescription = null) },
                        label = { Text(stringResource("chat.new"), style = AppTextStyles.groupTitle) },
                        selected = false,
                        onClick = onNewChat,
                        modifier = Modifier
                            .padding(horizontal = Spacing.small)
                            .pointerHoverIcon(PointerIcon.Hand),
                    )
                }
            }

            item {
                // Primary nav items (injected by caller — order and visibility controlled externally)
                Column {
                    navItems.filter { it.isVisible }.forEach { navItem ->
                        sidebarNavItemRow(item = navItem)
                    }
                }
            }

            item {
                // Pinned section (starred projects + starred sessions)
                pinnedSection(
                    starredProjects = projectsState.starredProjects,
                    starredSessions = pinnedState.starredSessions,
                    currentSessionId = currentSessionId,
                    currentProjectId = currentProjectId,
                    inProgressSessionIds = inProgressSessionIds,
                    bookmarkCountsBySession = sessionsViewModel.bookmarkCountsBySession,
                    onSelectProject = onSelectProject,
                    onResumeSession = onResumeSession,
                    onStarProject = onStarProject,
                    onStarSession = onStarSession,
                    onDeleteSession = onDeleteSession,
                    onRenameSession = onRenameSession,
                    onExportSession = onExportSession,
                    onEditProject = onEditProject,
                    onDeleteProject = onDeleteProject,
                    labelsBySessionId = sessionsViewModel.labelsBySessionId,
                    allLabels = sessionsViewModel.allLabels,
                    onToggleLabel = sessionsViewModel::toggleSessionLabel,
                    onManageLabels = sessionsViewModel::openManageLabelsDialog,
                )
            }

            item {
                // Visual separator between pinned and sessions zones
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = Spacing.small, vertical = Spacing.extraSmall),
                    color = AppColors.codeBlockBorderColor(),
                )
            }

            stickyHeader {
                Column(modifier = Modifier.background(AppColors.sidebarSurfaceColor())) {
                    // Sessions header (collapsible)
                    sidebarItem(
                        icon = {
                            Icon(
                                Icons.Default.History,
                                contentDescription = null,
                                modifier = Modifier.size((16 * fontScale).dp),
                                tint = if (isSessionsSelected) MaterialTheme.colorScheme.onPrimaryContainer else AppTextStyles.secondaryContent,
                            )
                        },
                        label = {
                            val headerColor = if (isSessionsSelected) MaterialTheme.colorScheme.onPrimaryContainer else AppTextStyles.secondaryContent
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource("chat.sessions").uppercase(),
                                    style = AppTextStyles.sidebarSectionHeader,
                                    color = headerColor,
                                )
                                Icon(
                                    imageVector = if (isSessionsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (isSessionsExpanded) "Collapse" else "Expand",
                                    tint = headerColor,
                                    modifier = Modifier.size((18 * fontScale).dp),
                                )
                            }
                        },
                        selected = isSessionsSelected,
                        onClick = onToggleSessions,
                        badge = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                            ) {
                                if (!isSessionsExpanded && sessionsViewModel.totalSessionCount > 0) {
                                    val count = sessionsViewModel.totalSessionCount
                                    Text(
                                        text = if (count > 99) "99+" else "$count",
                                        style = AppTextStyles.hint,
                                    )
                                }
                                if (isSessionsExpanded) {
                                    themedTooltip(text = stringResource("sidebar.sessions.search.tooltip")) {
                                        Box(
                                            modifier = Modifier
                                                .size((22 * fontScale).dp)
                                                .clip(CircleShape)
                                                .clickable {
                                                    showSessionsSearchField = !showSessionsSearchField
                                                    if (!showSessionsSearchField) {
                                                        sessionsViewModel.sidebarSearchQuery = ""
                                                    }
                                                }
                                                .pointerHoverIcon(PointerIcon.Hand),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                Icons.Default.Search,
                                                contentDescription = stringResource("sidebar.sessions.search.tooltip"),
                                                modifier = Modifier.size((16 * fontScale).dp),
                                                tint = AppTextStyles.secondaryContent,
                                            )
                                        }
                                    }
                                    sessionsOrganizeMenu(
                                        sortOption = sessionsViewModel.sortOption,
                                        onSelectSort = sessionsViewModel::selectSortOption,
                                        groupOption = sessionsViewModel.groupOption,
                                        onSelectGroup = sessionsViewModel::selectGroupOption,
                                        selectionMode = sessionsViewModel.selectionMode,
                                        onToggleSelectionMode = sessionsViewModel::toggleSelectionMode,
                                        allLabels = sessionsViewModel.allLabels,
                                        selectedLabelFilterIds = sessionsViewModel.selectedLabelFilterIds,
                                        onToggleLabelFilter = sessionsViewModel::toggleLabelFilter,
                                        onClearLabelFilter = sessionsViewModel::clearLabelFilter,
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .padding(horizontal = Spacing.small, vertical = Spacing.extraSmall)
                            .pointerHoverIcon(PointerIcon.Hand),
                    )

                    if (isSessionsExpanded) {
                        if (showSessionsSearchField) {
                            sessionsSearchField(
                                query = sessionsViewModel.sidebarSearchQuery,
                                onQueryChange = { sessionsViewModel.sidebarSearchQuery = it },
                                onClear = {
                                    sessionsViewModel.sidebarSearchQuery = ""
                                    showSessionsSearchField = false
                                },
                            )
                        }

                        if (sessionsViewModel.selectionMode) {
                            sessionsBulkActionBar(
                                sessionsViewModel = sessionsViewModel,
                                availableProjects = projectsState.projects,
                                onMoveSessionToNewProject = { onMoveSessionToNewProject(it) },
                            )
                        }
                    }
                }
            }

            if (isSessionsExpanded) {
                item {
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

// ──────────────────────────────────────────────────────────────────────────��──
// Collapsed sidebar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun collapsedNavigationSidebar(
    animatedWidth: Dp,
    navItems: List<SidebarNavItem>,
    isSessionsSelected: Boolean,
    onToggleExpand: () -> Unit,
    onNewChat: () -> Unit,
    onNavigateToSessions: () -> Unit,
    userProfileContent: @Composable () -> Unit,
) {
    val fontScale = LocalFontScale.current

    Column(
        modifier = Modifier
            .width(animatedWidth)
            .fillMaxHeight()
            .background(AppColors.sidebarSurfaceColor()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val headerInteractionSource = remember { MutableInteractionSource() }
        val isHeaderHovered by headerInteractionSource.collectIsHoveredAsState()
        val appLogo = rememberAppLogo()

        themedTooltip(text = stringResource("sidebar.expand")) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.sidebarHeaderColor())
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
                    sidebarPanelToggleIcon(
                        isExpanded = false,
                        tint = AppColors.sidebarHeaderContentColor(),
                        modifier = Modifier.size((32 * fontScale).dp),
                        contentDescription = stringResource("sidebar.expand"),
                    )
                } else {
                    Icon(
                        painter = appLogo,
                        contentDescription = "Askimo",
                        modifier = Modifier.size((32 * fontScale).dp),
                        tint = AppColors.sidebarHeaderContentColor(),
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
                    icon = { Icon(Icons.Default.EditNote, contentDescription = stringResource("chat.new")) },
                    label = null,
                    selected = false,
                    onClick = onNewChat,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    colors = AppColors.navigationRailItemColors(),
                )
            }

            navItems.filter { it.isVisible }.forEach { item ->
                themedTooltip(text = stringResource(item.labelRes)) {
                    NavigationRailItem(
                        icon = { Icon(item.icon, contentDescription = stringResource(item.labelRes)) },
                        label = null,
                        selected = item.isSelected,
                        onClick = item.onClick,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        colors = AppColors.navigationRailItemColors(),
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
                    colors = AppColors.navigationRailItemColors(),
                )
            }
        }

        HorizontalDivider()
        userProfileContent()
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Nav item row (expanded sidebar)
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun sidebarNavItemRow(item: SidebarNavItem) {
    val interactionSource = remember(item.id) { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .padding(horizontal = Spacing.small)
            .hoverable(interactionSource),
    ) {
        sidebarItem(
            icon = { Icon(item.icon, contentDescription = null) },
            label = {
                Text(
                    stringResource(item.labelRes),
                    style = AppTextStyles.groupTitle,
                    color = if (item.isSelected) MaterialTheme.colorScheme.onPrimaryContainer else AppTextStyles.primaryContent,
                )
            },
            selected = item.isSelected,
            onClick = item.onClick,
            badge = item.badge?.let { badgeFn -> { badgeFn(isHovered) } },
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        )
    }
}

@Composable
private fun sidebarItem(
    label: @Composable () -> Unit,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    shape: Shape = AppComponents.navigationItemShape,
) {
    val containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(color = containerColor, shape = shape)
                .clickable(onClick = onClick)
                .padding(horizontal = Spacing.small, vertical = Spacing.medium),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    icon()
                }
                Box(modifier = Modifier.weight(1f)) {
                    label()
                }
                if (badge != null) {
                    badge()
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sidebar toggle icon
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Custom "sidebar panel" toggle icon — a rounded rectangle frame split by a vertical divider,
 * with the left portion shaded solid to represent the sidebar. Deliberately hand-drawn instead
 * of using a static Material icon: this project's classic Material Icons set has no open/close
 * pair for this ("dock_to_left"/"dock_to_right" only exist in the newer Material Symbols set),
 * and the whole point of this icon is that it visually *flips* between states — [isExpanded]
 * swaps which side is the "big door" vs. the "small door", giving a clear before/after cue
 * that a single static icon (e.g. a plain hamburger) can't convey.
 *
 * @param isExpanded When `true`, the shaded sidebar portion is the *large* left section (mirrors
 *   the sidebar currently taking up most of the visible space) — used on the expand**ed**
 *   header's collapse button. When `false`, it shrinks to a thin strip on the left (mirrors the
 *   collapsed rail) — used on the collapsed header's hover-to-expand icon — so the two toggle
 *   buttons read as mirror images of each other rather than two unrelated icons.
 */
@Composable
private fun sidebarPanelToggleIcon(
    isExpanded: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Canvas(
        modifier = modifier.semantics {
            this.contentDescription = contentDescription ?: ""
            role = Role.Image
        },
    ) {
        val strokeWidth = size.minDimension * 0.09f
        val cornerRadius = CornerRadius(size.minDimension * 00.16f)
        val inset = strokeWidth / 2f
        val frameSize = Size(size.width - strokeWidth, size.height - strokeWidth)
        val frameTopLeft = Offset(inset, inset)

        // "Big door" side takes ~62% of the width; "small door" side takes ~38%. Toggling
        // isExpanded swaps which side (left) gets the big vs. small share.
        val bigShare = 0.62f
        val smallShare = 0.38f
        val dividerX = frameTopLeft.x + frameSize.width * (if (isExpanded) bigShare else smallShare)

        val framePath = Path().apply {
            addRoundRect(
                RoundRect(
                    left = frameTopLeft.x,
                    top = frameTopLeft.y,
                    right = frameTopLeft.x + frameSize.width,
                    bottom = frameTopLeft.y + frameSize.height,
                    cornerRadius = cornerRadius,
                ),
            )
        }

        // Shaded left portion — clipped to the frame's rounded shape so its corners match.
        clipPath(framePath) {
            drawRect(
                color = tint,
                topLeft = frameTopLeft,
                size = Size(dividerX - frameTopLeft.x, frameSize.height),
            )
        }

        // Frame outline
        drawRoundRect(
            color = tint,
            topLeft = frameTopLeft,
            size = frameSize,
            cornerRadius = cornerRadius,
            style = Stroke(width = strokeWidth),
        )

        // Divider between the two "doors"
        drawLine(
            color = tint,
            start = Offset(dividerX, frameTopLeft.y),
            end = Offset(dividerX, frameTopLeft.y + frameSize.height),
            strokeWidth = strokeWidth,
        )
    }
}

// ──────────────────────────────────────────────────────────────────
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
    contentColor: Color = AppTextStyles.primaryContent,
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
                tint = contentColor,
            )
            logoOverlay(Modifier.align(Alignment.TopEnd))
        }
        Text(
            text = "Askimo",
            style = AppTextStyles.pageTitle.copy(color = contentColor),
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
    currentProjectId: String? = null,
    inProgressSessionIds: Set<String>,
    bookmarkCountsBySession: Map<String, Int> = emptyMap(),
    onSelectProject: (String) -> Unit,
    onResumeSession: (String) -> Unit,
    onStarProject: (String, Boolean) -> Unit,
    onStarSession: (String, Boolean) -> Unit,
    onDeleteSession: (String) -> Unit = {},
    onRenameSession: (String, String) -> Unit = { _, _ -> },
    onExportSession: (String) -> Unit = {},
    onEditProject: (String) -> Unit = {},
    onDeleteProject: (String) -> Unit = {},
    labelsBySessionId: Map<String, List<Label>> = emptyMap(),
    allLabels: List<Label> = emptyList(),
    onToggleLabel: (sessionId: String, labelId: String) -> Unit = { _, _ -> },
    onManageLabels: () -> Unit = {},
) {
    if (starredProjects.isEmpty() && starredSessions.isEmpty()) return

    var isExpanded by remember { mutableStateOf(true) }
    val fontScale = LocalFontScale.current

    Column(
        modifier = Modifier.padding(
            start = Spacing.small,
            end = Spacing.small,
            top = Spacing.small,
        ),
    ) {
        sidebarItem(
            icon = {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    modifier = Modifier.size((16 * fontScale).dp),
                    tint = AppTextStyles.secondaryContent,
                )
            },
            label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource("sidebar.pinned").uppercase(),
                        style = AppTextStyles.sidebarSectionHeader,
                    )
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = AppTextStyles.secondaryContent,
                        modifier = Modifier.size((18 * fontScale).dp),
                    )
                }
            },
            selected = false,
            onClick = { isExpanded = !isExpanded },
            modifier = Modifier
                .padding(vertical = Spacing.extraSmall)
                .pointerHoverIcon(PointerIcon.Hand),
        )

        if (isExpanded) {
            Column(
                modifier = Modifier.padding(start = Spacing.extraLarge),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                starredProjects.forEach { project ->
                    pinnedProjectItem(
                        project = project,
                        isSelected = project.id == currentProjectId,
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
                        bookmarkCount = bookmarkCountsBySession[session.id] ?: 0,
                        onResumeSession = onResumeSession,
                        onUnpin = { onStarSession(session.id, false) },
                        onDelete = { onDeleteSession(session.id) },
                        onRename = { onRenameSession(session.id, session.title) },
                        onExport = { onExportSession(session.id) },
                        labels = labelsBySessionId[session.id].orEmpty(),
                        allLabels = allLabels,
                        onToggleLabel = { labelId -> onToggleLabel(session.id, labelId) },
                        onManageLabels = onManageLabels,
                    )
                }
            }
        }
    }
}

@Composable
private fun pinnedProjectItem(
    project: Project,
    isSelected: Boolean,
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
            .hoverable(interactionSource),
    ) {
        themedTooltip(text = project.name) {
            sidebarItem(
                icon = { Icon(Icons.Default.Workspaces, contentDescription = null, modifier = Modifier.size((16 * fontScale).dp)) },
                label = {
                    navigationItemLabelWithMenu(
                        text = project.name,
                        onMenuClick = { showMenu = true },
                        isHovered = isHovered || showMenu,
                        isSelected = isSelected,
                    )
                },
                selected = isSelected,
                onClick = { onSelectProject(project.id) },
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Hand),
            )
        }

        Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = Spacing.small)) {
            dropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource("action.unpin")) },
                    leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, tint = AppTextStyles.secondaryContent) },
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

@Composable
private fun pinnedSessionItem(
    session: ChatSession,
    isSelected: Boolean,
    isChatInProgress: Boolean,
    bookmarkCount: Int = 0,
    onResumeSession: (String) -> Unit,
    onUnpin: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    labels: List<Label> = emptyList(),
    allLabels: List<Label> = emptyList(),
    onToggleLabel: (labelId: String) -> Unit = {},
    onManageLabels: () -> Unit = {},
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
                onDelete()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource),
    ) {
        sessionDrawerItemContent(
            session = session,
            isSelected = isSelected,
            isChatInProgress = isChatInProgress,
            isHovered = isHovered || showMenu,
            onResumeSession = onResumeSession,
            onMenuClick = { showMenu = true },
            bookmarkCount = bookmarkCount,
            labels = labels,
        )

        Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = Spacing.small)) {
            dropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource("action.unpin")) },
                    leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, tint = AppTextStyles.secondaryContent) },
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
                labelsMenuItem(
                    allLabels = allLabels,
                    assignedLabelIds = labels.map { it.id }.toSet(),
                    onToggleLabel = onToggleLabel,
                    onManageLabels = {
                        showMenu = false
                        onManageLabels()
                    },
                    onDismiss = { showMenu = false },
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

// ────────────────────────────────────────────────────────────────────────────
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
            start = Spacing.extraLarge,
            end = Spacing.extraSmall,
            top = Spacing.extraSmall,
            bottom = Spacing.extraSmall,
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        val unstarredSessions = sessionsViewModel.sidebarVisibleSessions.filter { !it.isStarred }

        if (sessionsViewModel.recentSessions.isEmpty()) {
            Text(
                text = "No sessions yet",
                style = AppTextStyles.caption,
                modifier = Modifier.padding(
                    horizontal = Spacing.extraSmall,
                    vertical = Spacing.extraSmall,
                ),
            )
        } else if (unstarredSessions.isEmpty() && sessionsViewModel.sidebarSearchQuery.isNotBlank()) {
            Text(
                text = stringResource("sidebar.sessions.search.noResults"),
                style = AppTextStyles.caption,
                modifier = Modifier.padding(
                    horizontal = Spacing.extraSmall,
                    vertical = Spacing.extraSmall,
                ),
            )
        } else {
            val groups = unstarredSessions.groupedByOption(
                sessionsViewModel.groupOption,
                labelsBySessionId = sessionsViewModel.labelsBySessionId,
            )

            groups.forEach { (bucket, sessionsInBucket) ->
                if (bucket != null) {
                    groupHeader(key = bucket)
                }
                sessionsInBucket.forEach { session ->
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
                        bookmarkCount = sessionsViewModel.bookmarkCountsBySession[session.id] ?: 0,
                        selectionMode = sessionsViewModel.selectionMode,
                        isSessionSelected = session.id in sessionsViewModel.selectedSessionIds,
                        onToggleSessionSelected = sessionsViewModel::toggleSessionSelected,
                        labels = sessionsViewModel.labelsBySessionId[session.id].orEmpty(),
                        allLabels = sessionsViewModel.allLabels,
                        onToggleLabel = { labelId -> sessionsViewModel.toggleSessionLabel(session.id, labelId) },
                        onManageLabels = sessionsViewModel::openManageLabelsDialog,
                    )
                }
            }

            if (sessionsViewModel.totalSessionCount > SessionsViewModel.MAX_SIDEBAR_SESSIONS) {
                sidebarItem(
                    icon = null,
                    label = {
                        Text(
                            text = "More...",
                            style = AppTextStyles.caption,
                        )
                    },
                    selected = false,
                    onClick = onNavigateToSessions,
                    modifier = Modifier
                        .padding(vertical = Spacing.extraSmall)
                        .pointerHoverIcon(PointerIcon.Hand),
                )
            }
        }
    }
}

/** Localized caption rendered above each [SessionGroupKey] bucket in the sidebar. */
@Composable
private fun groupHeader(key: SessionGroupKey) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        modifier = Modifier.padding(
            start = Spacing.extraSmall,
            top = Spacing.small,
            bottom = Spacing.extraSmall,
        ),
    ) {
        if (key is SessionGroupKey.ByLabel) {
            labelColorDot(colorHex = key.label.color, size = 8.dp)
        }
        Text(
            text = key.label(),
            style = AppTextStyles.caption,
            color = AppTextStyles.secondaryContent,
        )
    }
}

/** Localized text for a [SessionGroupKey] group header. */
@Composable
private fun SessionGroupKey.label(): String = when (this) {
    is SessionGroupKey.ByDate -> when (bucket) {
        SessionDateBucket.TODAY -> stringResource("message.date.today")
        SessionDateBucket.YESTERDAY -> stringResource("message.date.yesterday")
        SessionDateBucket.PREVIOUS_7_DAYS -> stringResource("sidebar.sessions.date.previous7Days")
        SessionDateBucket.OLDER -> stringResource("sidebar.sessions.date.older")
    }

    is SessionGroupKey.ByLabel -> label.name

    SessionGroupKey.Unlabeled -> stringResource("sidebar.sessions.label.unlabeled")
}

/**
 * Compact dropdown trigger allowing the user to pick the sidebar sessions' sort and group
 * options, toggle bulk-selection mode, presented as a nested "Sort by" / "Group by" menu
 * (mirrors the [moveToProjectMenuItem] submenu pattern).
 */
@Composable
private fun sessionsOrganizeMenu(
    sortOption: SessionSortOption,
    onSelectSort: (SessionSortOption) -> Unit,
    groupOption: SessionGroupOption,
    onSelectGroup: (SessionGroupOption) -> Unit,
    selectionMode: Boolean,
    onToggleSelectionMode: () -> Unit,
    allLabels: List<Label> = emptyList(),
    selectedLabelFilterIds: Set<String> = emptySet(),
    onToggleLabelFilter: (labelId: String) -> Unit = {},
    onClearLabelFilter: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    var showSortSubmenu by remember { mutableStateOf(false) }
    var showGroupSubmenu by remember { mutableStateOf(false) }
    var showLabelFilterSubmenu by remember { mutableStateOf(false) }
    var sortItemWidth by remember { mutableStateOf(0.dp) }
    var groupItemWidth by remember { mutableStateOf(0.dp) }
    var labelFilterItemWidth by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val fontScale = LocalFontScale.current

    fun closeAll() {
        showMenu = false
        showSortSubmenu = false
        showLabelFilterSubmenu = false
        showGroupSubmenu = false
    }

    @Composable
    fun sortLabelFor(option: SessionSortOption): String = when (option) {
        SessionSortOption.LAST_UPDATED -> stringResource("sidebar.sessions.sort.lastUpdated")
        SessionSortOption.CREATED -> stringResource("sidebar.sessions.sort.created")
        SessionSortOption.TITLE_ASC -> stringResource("sidebar.sessions.sort.titleAsc")
        SessionSortOption.TITLE_DESC -> stringResource("sidebar.sessions.sort.titleDesc")
    }

    @Composable
    fun groupLabelFor(option: SessionGroupOption): String = when (option) {
        SessionGroupOption.NONE -> stringResource("sidebar.sessions.group.none")
        SessionGroupOption.BY_DATE -> stringResource("sidebar.sessions.group.byDate")
        SessionGroupOption.BY_LABEL -> stringResource("sidebar.sessions.group.byLabel")
    }

    Box {
        themedTooltip(text = stringResource("sidebar.sessions.organize.tooltip")) {
            Box(
                modifier = Modifier
                    .size((22 * fontScale).dp)
                    .clip(CircleShape)
                    .clickable { showMenu = true }
                    .pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.FilterList,
                    contentDescription = stringResource("sidebar.sessions.organize.tooltip"),
                    modifier = Modifier.size((16 * fontScale).dp),
                    tint = if (selectedLabelFilterIds.isNotEmpty()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        AppTextStyles.secondaryContent
                    },
                )
                if (selectedLabelFilterIds.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size((6 * fontScale).dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }

        dropdownMenu(expanded = showMenu, onDismissRequest = { closeAll() }) {
            Column {
                AppComponents.menuItem(
                    text = { Text(stringResource("sidebar.sessions.select")) },
                    leadingIcon = {
                        Icon(Icons.Default.Checklist, contentDescription = null)
                    },
                    trailingIcon = {
                        if (selectionMode) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        closeAll()
                        onToggleSelectionMode()
                    },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.extraSmall))

                Box {
                    AppComponents.menuItem(
                        text = { Text(stringResource("sidebar.sessions.sortBy")) },
                        onClick = {
                            showSortSubmenu = !showSortSubmenu
                            showGroupSubmenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null)
                        },
                        trailingIcon = {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size((20 * fontScale).dp),
                            )
                        },
                        modifier = Modifier.onGloballyPositioned { coordinates ->
                            sortItemWidth = with(density) { coordinates.size.width.toDp() }
                        },
                    )

                    if (showSortSubmenu) {
                        Box(modifier = Modifier.offset(x = sortItemWidth, y = -Spacing.small)) {
                            dropdownMenu(expanded = showSortSubmenu, onDismissRequest = { showSortSubmenu = false }) {
                                SessionSortOption.entries.forEach { option ->
                                    AppComponents.menuItem(
                                        text = { Text(sortLabelFor(option)) },
                                        leadingIcon = {
                                            if (option == sortOption) {
                                                Icon(Icons.Default.Check, contentDescription = null)
                                            }
                                        },
                                        onClick = {
                                            closeAll()
                                            onSelectSort(option)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.extraSmall))

                Box {
                    AppComponents.menuItem(
                        text = { Text(stringResource("sidebar.sessions.groupBy")) },
                        onClick = {
                            showGroupSubmenu = !showGroupSubmenu
                            showSortSubmenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Category, contentDescription = null)
                        },
                        trailingIcon = {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size((20 * fontScale).dp),
                            )
                        },
                        modifier = Modifier.onGloballyPositioned { coordinates ->
                            groupItemWidth = with(density) { coordinates.size.width.toDp() }
                        },
                    )

                    if (showGroupSubmenu) {
                        Box(modifier = Modifier.offset(x = groupItemWidth, y = -Spacing.small)) {
                            dropdownMenu(expanded = showGroupSubmenu, onDismissRequest = { showGroupSubmenu = false }) {
                                SessionGroupOption.entries.forEach { option ->
                                    AppComponents.menuItem(
                                        text = { Text(groupLabelFor(option)) },
                                        leadingIcon = {
                                            if (option == groupOption) {
                                                Icon(Icons.Default.Check, contentDescription = null)
                                            }
                                        },
                                        onClick = {
                                            closeAll()
                                            onSelectGroup(option)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.extraSmall))

                Box {
                    AppComponents.menuItem(
                        text = { Text(stringResource("sidebar.sessions.filterByLabel")) },
                        onClick = {
                            showLabelFilterSubmenu = !showLabelFilterSubmenu
                            showSortSubmenu = false
                            showGroupSubmenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null)
                        },
                        trailingIcon = {
                            if (selectedLabelFilterIds.isNotEmpty()) {
                                Text(
                                    "${selectedLabelFilterIds.size}",
                                    style = AppTextStyles.caption,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size((20 * fontScale).dp),
                            )
                        },
                        modifier = Modifier.onGloballyPositioned { coordinates ->
                            labelFilterItemWidth = with(density) { coordinates.size.width.toDp() }
                        },
                    )

                    if (showLabelFilterSubmenu) {
                        Box(modifier = Modifier.offset(x = labelFilterItemWidth, y = -Spacing.small)) {
                            dropdownMenu(
                                expanded = showLabelFilterSubmenu,
                                onDismissRequest = { showLabelFilterSubmenu = false },
                            ) {
                                Column {
                                    AppComponents.menuItem(
                                        text = { Text(stringResource("sidebar.sessions.filterByLabel.all")) },
                                        leadingIcon = {
                                            if (selectedLabelFilterIds.isEmpty()) {
                                                Icon(Icons.Default.Check, contentDescription = null)
                                            }
                                        },
                                        onClick = {
                                            closeAll()
                                            onClearLabelFilter()
                                        },
                                    )

                                    if (allLabels.isNotEmpty()) {
                                        HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.extraSmall))
                                        allLabels.forEach { label ->
                                            val isSelected = label.id in selectedLabelFilterIds
                                            AppComponents.menuItem(
                                                text = { Text(label.name) },
                                                leadingIcon = {
                                                    labelColorDot(colorHex = label.color, size = (10 * fontScale).dp)
                                                },
                                                trailingIcon = {
                                                    if (isSelected) {
                                                        Icon(Icons.Default.Check, contentDescription = null)
                                                    }
                                                },
                                                onClick = { onToggleLabelFilter(label.id) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Inline, non-modal search field for filtering the sidebar's sessions list by title. */
@Composable
private fun sessionsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource("sidebar.sessions.search.placeholder"), style = AppTextStyles.caption) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = onClear, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                Icon(Icons.Default.Close, contentDescription = stringResource("action.cancel"))
            }
        },
        singleLine = true,
        textStyle = AppTextStyles.caption,
        colors = AppColors.outlinedTextFieldColors(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.small, vertical = Spacing.extraSmall)
            .focusRequester(focusRequester),
    )
}

/**
 * Action bar shown above the sessions list while bulk-selection mode is active: shows the
 * current selection count plus Select All / Delete / Move to project / Cancel actions.
 */
@Composable
private fun sessionsBulkActionBar(
    sessionsViewModel: SessionsViewModel,
    availableProjects: List<Project>,
    onMoveSessionToNewProject: (sessionId: String) -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val fontScale = LocalFontScale.current
    val selectedCount = sessionsViewModel.selectedSessionIds.size
    val visibleIds = sessionsViewModel.sidebarVisibleSessions.filter { !it.isStarred }.map { it.id }

    if (showDeleteDialog) {
        bulkDeleteSessionsDialog(
            count = selectedCount,
            onConfirm = {
                showDeleteDialog = false
                sessionsViewModel.bulkDeleteSelected()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.small, vertical = Spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource("sidebar.sessions.selected.count", "$selectedCount"),
            style = AppTextStyles.caption,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            val allVisibleSelected = visibleIds.isNotEmpty() && visibleIds.all { it in sessionsViewModel.selectedSessionIds }
            val selectAllLabel = if (allVisibleSelected) {
                stringResource("sidebar.sessions.deselectAll")
            } else {
                stringResource("sidebar.sessions.selectAll")
            }
            themedTooltip(text = selectAllLabel) {
                IconButton(
                    onClick = {
                        if (allVisibleSelected) {
                            sessionsViewModel.clearSelection()
                        } else {
                            sessionsViewModel.selectAllVisible(visibleIds)
                        }
                    },
                    modifier = Modifier.size((24 * fontScale).dp).pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(
                        if (allVisibleSelected) Icons.Default.Deselect else Icons.Default.DoneAll,
                        contentDescription = selectAllLabel,
                    )
                }
            }

            Box {
                var showMoveMenu by remember { mutableStateOf(false) }
                themedTooltip(text = stringResource("session.move.to.project")) {
                    IconButton(
                        onClick = { showMoveMenu = true },
                        enabled = selectedCount > 0,
                        modifier = Modifier.size((24 * fontScale).dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = stringResource("session.move.to.project"))
                    }
                }
                dropdownMenu(expanded = showMoveMenu, onDismissRequest = { showMoveMenu = false }) {
                    moveToProjectMenuItem(
                        projects = availableProjects,
                        onNewProject = {
                            showMoveMenu = false
                            val firstSelected = sessionsViewModel.selectedSessionIds.firstOrNull()
                            if (firstSelected != null) onMoveSessionToNewProject(firstSelected)
                        },
                        onSelectProject = { project ->
                            showMoveMenu = false
                            sessionsViewModel.bulkMoveSelectedToProject(project.id)
                        },
                        onDismiss = { showMoveMenu = false },
                    )
                }
            }

            themedTooltip(text = stringResource("action.delete")) {
                IconButton(
                    onClick = { showDeleteDialog = true },
                    enabled = selectedCount > 0,
                    modifier = Modifier.size((24 * fontScale).dp).pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource("action.delete"), tint = MaterialTheme.colorScheme.error)
                }
            }

            themedTooltip(text = stringResource("action.cancel")) {
                IconButton(
                    onClick = { sessionsViewModel.toggleSelectionMode() },
                    modifier = Modifier.size((24 * fontScale).dp).pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource("action.cancel"))
                }
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = Spacing.small))
}

// ─────────────────────────────────────────────────────────────────────────────
// Session item with context menu
// ─────────────────────────────────────────────────────────────────────────────

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
    bookmarkCount: Int = 0,
    selectionMode: Boolean = false,
    isSessionSelected: Boolean = false,
    onToggleSessionSelected: (String) -> Unit = {},
    labels: List<Label> = emptyList(),
    allLabels: List<Label> = emptyList(),
    onToggleLabel: (labelId: String) -> Unit = {},
    onManageLabels: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
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
            .hoverable(interactionSource),
    ) {
        sessionDrawerItemContent(
            session = session,
            isSelected = isSelected,
            isChatInProgress = isChatInProgress,
            isHovered = isHovered || showMenu,
            onResumeSession = onResumeSession,
            onMenuClick = { showMenu = true },
            bookmarkCount = bookmarkCount,
            selectionMode = selectionMode,
            isSessionSelected = isSessionSelected,
            onToggleSessionSelected = onToggleSessionSelected,
            labels = labels,
        )

        if (!selectionMode) {
            Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = Spacing.small)) {
                dropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    sidebarMenu(
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
                        allLabels = allLabels,
                        assignedLabelIds = labels.map { it.id }.toSet(),
                        onToggleLabel = onToggleLabel,
                        onManageLabels = {
                            showMenu = false
                            onManageLabels()
                        },
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared sub-components
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun sessionDrawerItemContent(
    session: ChatSession,
    isSelected: Boolean,
    isChatInProgress: Boolean,
    isHovered: Boolean,
    onResumeSession: (String) -> Unit,
    onMenuClick: () -> Unit,
    bookmarkCount: Int = 0,
    selectionMode: Boolean = false,
    isSessionSelected: Boolean = false,
    onToggleSessionSelected: (String) -> Unit = {},
    labels: List<Label> = emptyList(),
) {
    val fontScale = LocalFontScale.current

    sessionTooltip(session = session) {
        sidebarItem(
            icon = if (selectionMode) {
                {
                    Checkbox(
                        checked = isSessionSelected,
                        onCheckedChange = { onToggleSessionSelected(session.id) },
                        modifier = Modifier.size((20 * fontScale).dp),
                    )
                }
            } else if (isChatInProgress) {
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
                    onMenuClick = onMenuClick,
                    isHovered = isHovered && !selectionMode,
                    isSelected = isSelected,
                    bookmarkCount = bookmarkCount,
                    labels = labels,
                )
            },
            selected = isSelected,
            onClick = {
                if (selectionMode) {
                    onToggleSessionSelected(session.id)
                } else {
                    onResumeSession(session.id)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Hand),
        )
    }
}

@Composable
private fun navigationItemLabelWithMenu(
    text: String,
    onMenuClick: () -> Unit,
    isHovered: Boolean,
    isSelected: Boolean = false,
    bookmarkCount: Int = 0,
    labels: List<Label> = emptyList(),
) {
    val fontScale = LocalFontScale.current
    val textColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.extraSmall),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppComponents.marqueeText(
            text = text,
            isHovered = isHovered,
            style = AppTextStyles.body,
            color = textColor,
            modifier = Modifier.weight(1f),
        )
        if (labels.isNotEmpty()) {
            labelBadgeRow(labels = labels, modifier = Modifier.padding(start = Spacing.extraSmall))
        }
        Box(
            modifier = Modifier
                .padding(start = Spacing.extraSmall)
                .heightIn(min = (24 * fontScale).dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isHovered) {
                Box(
                    modifier = Modifier
                        .size((24 * fontScale).dp)
                        .clip(CircleShape)
                        .clickable(onClick = onMenuClick)
                        .pointerHoverIcon(PointerIcon.Hand),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else AppTextStyles.secondaryContent,
                        modifier = Modifier.size((18 * fontScale).dp),
                    )
                }
            } else if (bookmarkCount > 0) {
                val bookmarkColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else AppColors.countBadgeAccentColor()
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.micro),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = null,
                        modifier = Modifier.size((10 * fontScale).dp),
                        tint = bookmarkColor,
                    )
                    Text(
                        text = "$bookmarkCount",
                        style = AppTextStyles.hint,
                        color = bookmarkColor,
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
