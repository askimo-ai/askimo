/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.discover

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.repository.ChatSessionRepository
import io.askimo.core.chat.repository.ProjectRepository
import io.askimo.core.plan.repository.PlanDefRepository
import io.askimo.core.skills.SkillRepository
import io.askimo.core.user.domain.UserProfile
import io.askimo.core.util.TimeUtil
import io.askimo.ui.common.components.clickableCard
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.themedTooltip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI
import java.time.LocalTime

@Composable
fun discoverView(
    userProfile: UserProfile?,
    recentSessions: List<ChatSession>,
    totalMcpServers: Int,
    chatSessionRepository: ChatSessionRepository,
    projectRepository: ProjectRepository,
    planDefRepository: PlanDefRepository,
    onNewChat: () -> Unit,
    onResumeSession: (String) -> Unit,
    onNavigateToSessions: () -> Unit,
    onNavigateToProjects: () -> Unit,
    onNavigateToPlans: () -> Unit,
    onNavigateToSkills: () -> Unit,
    onNavigateToMcpSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var totalChats by remember { mutableStateOf<Int?>(null) }
    var totalProjects by remember { mutableStateOf<Int?>(null) }
    var totalPlans by remember { mutableStateOf<Int?>(null) }
    var totalSkills by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            totalChats = chatSessionRepository.countAll()
            totalProjects = projectRepository.countAll()
            totalPlans = planDefRepository.count()
            totalSkills = SkillRepository.countSkills(io.askimo.core.util.AskimoHome.skillsDir())
        }
    }

    val scrollState = rememberScrollState()

    Box(modifier = modifier.fillMaxSize()) {
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
                    .padding(start = 24.dp, end = 36.dp, top = 32.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                headerSection(
                    userProfile = userProfile,
                    onNewChat = onNewChat,
                )

                statCardsSection(
                    totalChats = totalChats,
                    totalProjects = totalProjects,
                    totalMcpServers = totalMcpServers,
                    totalPlans = totalPlans,
                    totalSkills = totalSkills,
                    onNavigateToSessions = onNavigateToSessions,
                    onNavigateToProjects = onNavigateToProjects,
                    onNavigateToPlans = onNavigateToPlans,
                    onNavigateToSkills = onNavigateToSkills,
                    onNavigateToMcpSettings = onNavigateToMcpSettings,
                )

                exploreFeaturesSection()

                recentSessionsSection(
                    sessions = recentSessions,
                    onResumeSession = onResumeSession,
                )
            }
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            style = ScrollbarStyle(
                minimalHeight = 16.dp,
                thickness = 8.dp,
                shape = MaterialTheme.shapes.small,
                hoverDurationMillis = 300,
                unhoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                hoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            ),
        )
    }
}

@Composable
private fun headerSection(
    userProfile: UserProfile?,
    onNewChat: () -> Unit,
) {
    val hour = LocalTime.now().hour
    val greetingKey = when {
        hour < 12 -> "discover.greeting.morning"
        hour < 17 -> "discover.greeting.afternoon"
        else -> "discover.greeting.evening"
    }
    val firstName = userProfile?.name?.split(" ")?.firstOrNull() ?: stringResource("user.profile.default_name")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(greetingKey, firstName),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Button(
            onClick = onNewChat,
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.size(6.dp))
            Text(stringResource("chat.new"))
        }
    }
}

@Composable
private fun statCardsSection(
    totalChats: Int?,
    totalProjects: Int?,
    totalMcpServers: Int,
    totalPlans: Int?,
    totalSkills: Int?,
    onNavigateToSessions: () -> Unit,
    onNavigateToProjects: () -> Unit,
    onNavigateToPlans: () -> Unit,
    onNavigateToSkills: () -> Unit,
    onNavigateToMcpSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        statCard(
            label = stringResource("discover.stat.chats"),
            value = totalChats?.toString() ?: "—",
            icon = { Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            onClick = onNavigateToSessions,
            modifier = Modifier.weight(1f),
        )
        statCard(
            label = stringResource("discover.stat.projects"),
            value = totalProjects?.toString() ?: "—",
            icon = { Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            onClick = onNavigateToProjects,
            modifier = Modifier.weight(1f),
        )
        statCard(
            label = stringResource("discover.stat.mcp"),
            value = totalMcpServers.toString(),
            icon = { Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            onClick = onNavigateToMcpSettings,
            modifier = Modifier.weight(1f),
        )
        statCard(
            label = stringResource("discover.stat.plans"),
            value = totalPlans?.toString() ?: "—",
            icon = { Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            onClick = onNavigateToPlans,
            modifier = Modifier.weight(1f),
        )
        statCard(
            label = stringResource("discover.stat.skills"),
            value = totalSkills?.toString() ?: "—",
            icon = { Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            onClick = onNavigateToSkills,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun statCard(
    label: String,
    value: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    clickableCard(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    icon()
                }
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun exploreFeaturesSection() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource("discover.explore.title"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            exploreCard(
                icon = { Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                title = stringResource("discover.explore.mcp.title"),
                description = stringResource("discover.explore.mcp.desc"),
                url = "https://$DOMAIN/docs/desktop/mcp-integration/",
                modifier = Modifier.weight(1f),
            )
            exploreCard(
                icon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                title = stringResource("discover.explore.rag.title"),
                description = stringResource("discover.explore.rag.desc"),
                url = "https://$DOMAIN/docs/desktop/rag/",
                modifier = Modifier.weight(1f),
            )
            exploreCard(
                icon = { Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                title = stringResource("discover.explore.plans.title"),
                description = stringResource("discover.explore.plans.desc"),
                url = "https://$DOMAIN/docs/desktop/plans/",
                modifier = Modifier.weight(1f),
            )
            exploreCard(
                icon = { Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                title = stringResource("discover.explore.skills.title"),
                description = stringResource("discover.explore.skills.desc"),
                url = "https://$DOMAIN/docs/desktop/skills/",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun exploreCard(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    url: String,
    modifier: Modifier = Modifier,
) {
    clickableCard(
        onClick = { runCatching { Desktop.getDesktop().browse(URI(url)) } },
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                icon()
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun recentSessionsSection(
    sessions: List<ChatSession>,
    onResumeSession: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource("discover.recent.title"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        if (sessions.isEmpty()) {
            Text(
                text = stringResource("discover.recent.empty"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            Surface(
                shape = MaterialTheme.shapes.large,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    sessions.forEachIndexed { index, session ->
                        recentSessionRow(
                            session = session,
                            onResumeSession = onResumeSession,
                        )
                        if (index < sessions.lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun recentSessionRow(
    session: ChatSession,
    onResumeSession: (String) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .background(
                if (isHovered) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                },
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onResumeSession(session.id) },
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                Icons.Default.ChatBubbleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            themedTooltip(text = session.title) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = TimeUtil.formatDisplay(session.updatedAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
