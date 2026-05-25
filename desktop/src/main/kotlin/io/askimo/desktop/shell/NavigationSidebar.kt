/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.askimo.core.user.domain.UserProfile
import io.askimo.desktop.View
import io.askimo.desktop.project.ProjectsViewModel
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.LocalFontScale
import io.askimo.ui.common.ui.themedTooltip
import io.askimo.ui.session.SessionsViewModel
import io.askimo.ui.shell.rememberAvatarImage
import io.askimo.ui.shell.sidebarUserAvatar
import io.askimo.ui.shell.navigationSidebar as sharedNavigationSidebar

@Composable
fun navigationSidebar(
    isExpanded: Boolean,
    width: Dp,
    currentView: View,
    isProjectsExpanded: Boolean,
    isSessionsExpanded: Boolean,
    projectsViewModel: ProjectsViewModel,
    sessionsViewModel: SessionsViewModel,
    currentSessionId: String?,
    currentProjectId: String?,
    userProfile: UserProfile?,
    showPlansInSidebar: Boolean = true,
    showSkillsInSidebar: Boolean = true,
    showProjectsInSidebar: Boolean = true,
    onToggleExpand: () -> Unit,
    onNewChat: () -> Unit,
    onToggleProjects: () -> Unit,
    onNewProject: () -> Unit,
    onSelectProject: (String) -> Unit,
    onToggleSessions: () -> Unit,
    onNavigateToSessions: () -> Unit,
    onResumeSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onStarSession: (String, Boolean) -> Unit,
    onStarProject: (String, Boolean) -> Unit = { _, _ -> },
    onRenameSession: (String, String) -> Unit,
    onExportSession: (String) -> Unit,
    onShowSessionSummary: (String) -> Unit = {},
    onEditProject: (String) -> Unit = {},
    onDeleteProject: (String) -> Unit = {},
    onEditUserProfile: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToPlans: () -> Unit = {},
    onNavigateToSkills: () -> Unit = {},
    onNavigateToDiscover: () -> Unit = {},
) {
    sharedNavigationSidebar(
        isExpanded = isExpanded,
        width = width,
        isPlansSelected = currentView == View.PLANS || currentView == View.PLAN_DETAIL,
        isSkillsSelected = currentView == View.SKILLS,
        isProjectsSelected = currentView == View.PROJECTS,
        isSessionsSelected = currentView == View.SESSIONS,
        showPlansInSidebar = showPlansInSidebar,
        showSkillsInSidebar = showSkillsInSidebar,
        showProjectsInSidebar = showProjectsInSidebar,
        isSessionsExpanded = isSessionsExpanded,
        projectsState = projectsViewModel,
        sessionsViewModel = sessionsViewModel,
        currentSessionId = currentSessionId,
        onToggleExpand = onToggleExpand,
        onNewChat = onNewChat,
        onNavigateToProjects = onToggleProjects,
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
        userProfileContent = {
            communityUserProfileSection(
                profile = userProfile,
                isExpanded = isExpanded,
                onEditUserProfile = onEditUserProfile,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToAbout = onNavigateToAbout,
            )
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Community-specific user profile footer (no logout, local avatar only)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun communityUserProfileSection(
    profile: UserProfile?,
    isExpanded: Boolean,
    onEditUserProfile: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAbout: () -> Unit,
) {
    val fontScale = LocalFontScale.current
    val avatarImage = rememberAvatarImage(profile)
    var showMenu by remember { mutableStateOf(false) }

    if (isExpanded) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showMenu = true }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding((8 * fontScale).dp),
                horizontalArrangement = Arrangement.spacedBy((12 * fontScale).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size((40 * fontScale).dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer, shape = CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    sidebarUserAvatar(profile = profile, avatarImage = avatarImage)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy((2 * fontScale).dp),
                ) {
                    Text(
                        text = profile?.name ?: stringResource("user.profile.default_name"),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    profile?.occupation?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource("user.menu.settings"),
                    modifier = Modifier.size((16 * fontScale).dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
            communityProfileMenu(
                showMenu = showMenu,
                onDismiss = { showMenu = false },
                onEditUserProfile = onEditUserProfile,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToAbout = onNavigateToAbout,
            )
        }
    } else {
        Box {
            themedTooltip(text = stringResource("user.profile.menu")) {
                NavigationRailItem(
                    icon = {
                        Box(
                            modifier = Modifier
                                .size((32 * fontScale).dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer, shape = CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            sidebarUserAvatar(
                                profile = profile,
                                avatarImage = avatarImage,
                                textStyle = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                    label = null,
                    selected = false,
                    onClick = { showMenu = true },
                    modifier = Modifier
                        .padding(vertical = (8 * fontScale).dp)
                        .pointerHoverIcon(PointerIcon.Hand),
                    colors = AppComponents.navigationRailItemColors(),
                )
            }
            communityProfileMenu(
                showMenu = showMenu,
                onDismiss = { showMenu = false },
                onEditUserProfile = onEditUserProfile,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToAbout = onNavigateToAbout,
            )
        }
    }
}

@Composable
private fun communityProfileMenu(
    showMenu: Boolean,
    onDismiss: () -> Unit,
    onEditUserProfile: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAbout: () -> Unit,
) {
    AppComponents.dropdownMenu(expanded = showMenu, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource("user.menu.edit_profile")) },
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
            onClick = {
                onDismiss()
                onEditUserProfile()
            },
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        )
        DropdownMenuItem(
            text = { Text(stringResource("user.menu.settings")) },
            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
            onClick = {
                onDismiss()
                onNavigateToSettings()
            },
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        )
        DropdownMenuItem(
            text = { Text(stringResource("user.menu.about")) },
            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
            onClick = {
                onDismiss()
                onNavigateToAbout()
            },
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        )
    }
}
