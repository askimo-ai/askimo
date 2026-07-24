/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.discover

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Token
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.config.FeatureFlags
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.telemetry.TelemetryMetrics
import io.askimo.core.user.domain.UserProfile
import io.askimo.core.util.TimeUtil
import io.askimo.ui.common.components.clickableCard
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.themedTooltip
import io.askimo.ui.session.sessionTooltip
import java.awt.Desktop
import java.net.URI
import java.time.LocalTime

/**
 * Abbreviates a token count to a compact, locale-aware string.
 *
 * Examples (en-US): 1_234_567 → "1.2M", 45_000 → "45.0K", 999 → "999"
 * Examples (de-DE):            → "1,2M",          → "45,0K", 999 → "999"
 */
private fun abbreviateTokens(value: Long): String = when {
    value >= 1_000_000 -> "${LocalizationManager.formatDouble(value / 1_000_000.0, 1)}M"
    value >= 1_000 -> "${LocalizationManager.formatDouble(value / 1_000.0, 1)}K"
    else -> LocalizationManager.formatNumber(value)
}

@Composable
fun discoverView(
    userProfile: UserProfile?,
    recentSessions: List<ChatSession>,
    viewModel: DiscoverViewModel,
    onNewChat: () -> Unit,
    onResumeSession: (String) -> Unit,
    onNavigateToSessions: () -> Unit,
    onNavigateToProjects: () -> Unit,
    onNavigateToPlans: () -> Unit,
    onNavigateToSkills: () -> Unit,
    onNavigateToMcpSettings: () -> Unit,
    showTokenUsageCard: Boolean,
    onToggleTokenUsageCard: (Boolean) -> Unit,
    onOpenSystemDiagnostics: () -> Unit,
    telemetryMetrics: TelemetryMetrics,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = ThemePreferences.CONTENT_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 36.dp, top = 32.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.extraLarge),
            ) {
                headerSection(
                    userProfile = userProfile,
                    onNewChat = onNewChat,
                    showTokenUsageCard = showTokenUsageCard,
                    onToggleTokenUsageCard = onToggleTokenUsageCard,
                )

                statCardsSection(
                    totalChats = viewModel.totalChats,
                    totalProjects = viewModel.totalProjects,
                    totalMcpServers = viewModel.totalMcpServers,
                    totalPlans = viewModel.totalPlans,
                    totalSkills = viewModel.totalSkills,
                    onNavigateToSessions = onNavigateToSessions,
                    onNavigateToProjects = onNavigateToProjects,
                    onNavigateToPlans = onNavigateToPlans,
                    onNavigateToSkills = onNavigateToSkills,
                    onNavigateToMcpSettings = onNavigateToMcpSettings,
                )

                exploreFeaturesSection()

                if (showTokenUsageCard) {
                    tokenUsageSection(
                        telemetryMetrics = telemetryMetrics,
                        onOpenSystemDiagnostics = onOpenSystemDiagnostics,
                    )
                }

                recentSessionsSection(
                    sessions = recentSessions,
                    onResumeSession = onResumeSession,
                )
            }
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            style = AppComponents.scrollbarStyle(),
        )
    }
}

// ── Header ────────────────────────────────────────────────────────────────────

@Composable
private fun headerSection(
    userProfile: UserProfile?,
    onNewChat: () -> Unit,
    showTokenUsageCard: Boolean,
    onToggleTokenUsageCard: (Boolean) -> Unit,
) {
    val hour = LocalTime.now().hour
    val greetingKey = when {
        hour < 12 -> "discover.greeting.morning"
        hour < 17 -> "discover.greeting.afternoon"
        else -> "discover.greeting.evening"
    }
    val firstName = userProfile?.name?.split(" ")?.firstOrNull() ?: stringResource("user.profile.default_name")
    var showCustomizeMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(greetingKey, firstName),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                IconButton(
                    onClick = { showCustomizeMenu = true },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = stringResource("discover.customize"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AppComponents.dropdownMenu(
                    expanded = showCustomizeMenu,
                    onDismissRequest = { showCustomizeMenu = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource("discover.customize.show_token_usage"),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Spacer(modifier = Modifier.width(Spacing.large))
                                Switch(
                                    checked = showTokenUsageCard,
                                    onCheckedChange = { onToggleTokenUsageCard(it) },
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                )
                            }
                        },
                        onClick = { onToggleTokenUsageCard(!showTokenUsageCard) },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    )
                }
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
}

// ── Stat cards ────────────────────────────────────────────────────────────────

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
        horizontalArrangement = Arrangement.spacedBy(Spacing.large),
    ) {
        statCard(
            label = stringResource("discover.stat.chats"),
            value = totalChats?.let { LocalizationManager.formatNumber(it) } ?: "—",
            icon = { Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) },
            onClick = onNavigateToSessions,
            modifier = Modifier.weight(1f),
        )

        if (FeatureFlags.projectsEnabled) {
            statCard(
                label = stringResource("discover.stat.projects"),
                value = totalProjects?.let { LocalizationManager.formatNumber(it) } ?: "—",
                icon = { Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) },
                onClick = onNavigateToProjects,
                modifier = Modifier.weight(1f),
            )
        }

        if (FeatureFlags.mcpIntegrationEnabled) {
            statCard(
                label = stringResource("discover.stat.mcp"),
                value = LocalizationManager.formatNumber(totalMcpServers),
                icon = { Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) },
                onClick = onNavigateToMcpSettings,
                modifier = Modifier.weight(1f),
            )
        }

        if (FeatureFlags.plansEnabled) {
            statCard(
                label = stringResource("discover.stat.plans"),
                value = totalPlans?.let { LocalizationManager.formatNumber(it) } ?: "—",
                icon = { Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) },
                onClick = onNavigateToPlans,
                modifier = Modifier.weight(1f),
            )
        }

        if (FeatureFlags.skillsEnabled) {
            statCard(
                label = stringResource("discover.stat.skills"),
                value = totalSkills?.let { LocalizationManager.formatNumber(it) } ?: "—",
                icon = { Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) },
                onClick = onNavigateToSkills,
                modifier = Modifier.weight(1f),
            )
        }
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
    clickableCard(onClick = onClick, modifier = modifier, colors = AppComponents.primaryCardColors()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) { icon() }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
            )
        }
    }
}

// ── Token Usage section ───────────────────────────────────────────────────────

/**
 * Full-width section with a horizontal bar chart of the top 5 models by token
 * usage. All numbers are formatted via [LocalizationManager] so grouping separators
 * and decimal marks follow the active locale. Abbreviated counts (K/M) also use
 * [LocalizationManager.formatDouble] for locale-correct decimal separators.
 */
@Composable
private fun tokenUsageSection(
    telemetryMetrics: TelemetryMetrics,
    onOpenSystemDiagnostics: () -> Unit,
) {
    val totalTokens = telemetryMetrics.totalTokensUsed
    val topModels = telemetryMetrics.llmTokensByProvider
        .entries
        .sortedByDescending { it.value }
        .take(5)

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        // ── Section header ─────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Token,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource("discover.tokens.title"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            if (totalTokens > 0) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        // abbreviateTokens uses LocalizationManager internally
                        text = stringResource("discover.tokens.total", abbreviateTokens(totalTokens)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(
                        onClick = onOpenSystemDiagnostics,
                        modifier = Modifier.size(24.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = stringResource("discover.tokens.view_details"),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ── Chart card ─────────────────────────────────────────────
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (totalTokens == 0L) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.large),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource("discover.tokens.empty"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.large),
                    verticalArrangement = Arrangement.spacedBy(Spacing.medium),
                ) {
                    topModels.forEachIndexed { index, (key, tokens) ->
                        val parts = key.split(":", limit = 2)
                        val provider = parts.getOrElse(0) { key }
                            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                        val model = parts.getOrElse(1) { "" }.ifBlank { provider }
                        val fraction = tokens.toFloat() / totalTokens.toFloat()
                        // Percentage: locale-aware integer, e.g. "62 %" in fr vs "62%" in en
                        val pctFormatted = LocalizationManager.formatNumber((fraction * 100).toInt()) + "%"

                        tokenBarRow(
                            provider = provider,
                            model = model,
                            tokens = tokens,
                            fraction = fraction,
                            pctFormatted = pctFormatted,
                        )

                        if (index < topModels.lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun tokenBarRow(
    provider: String,
    model: String,
    tokens: Long,
    fraction: Float,
    pctFormatted: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        // Model + provider name — tooltip shows full names if truncated
        themedTooltip(text = "$model · $provider") {
            Column(modifier = Modifier.width(160.dp)) {
                Text(
                    text = model,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = provider,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Horizontal bar (track + fill)
        Box(modifier = Modifier.weight(1f).height(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceAtLeast(0.02f))
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
            )
        }

        // Abbreviated token count — tooltip shows exact locale-formatted number
        themedTooltip(text = LocalizationManager.formatNumber(tokens)) {
            Text(
                text = abbreviateTokens(tokens),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
                modifier = Modifier.width(52.dp),
            )
        }

        // Percentage
        Text(
            text = pctFormatted,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp),
        )
    }
}

// ── Explore features ──────────────────────────────────────────────────────────

private data class ExploreCardData(val icon: ImageVector, val titleKey: String, val descKey: String, val url: String)

@Composable
private fun exploreFeaturesSection() {
    val enabledCards = buildList {
        if (FeatureFlags.mcpIntegrationEnabled) add(ExploreCardData(Icons.Default.Extension, "discover.explore.mcp.title", "discover.explore.mcp.desc", "https://$DOMAIN/docs/desktop/mcp-integration/"))
        if (FeatureFlags.ragEnabled) add(ExploreCardData(Icons.AutoMirrored.Filled.LibraryBooks, "discover.explore.rag.title", "discover.explore.rag.desc", "https://$DOMAIN/docs/desktop/rag/"))
        if (FeatureFlags.plansEnabled) add(ExploreCardData(Icons.Default.PlayCircle, "discover.explore.plans.title", "discover.explore.plans.desc", "https://$DOMAIN/docs/desktop/plans/"))
        if (FeatureFlags.skillsEnabled) add(ExploreCardData(Icons.Default.Extension, "discover.explore.skills.title", "discover.explore.skills.desc", "https://$DOMAIN/docs/desktop/skills/"))
    }
    if (enabledCards.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        Text(
            text = stringResource("discover.explore.title"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(Spacing.large),
        ) {
            enabledCards.forEach { card ->
                exploreCard(
                    icon = { Icon(card.icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer) },
                    title = stringResource(card.titleKey),
                    description = stringResource(card.descKey),
                    url = card.url,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
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
        colors = AppComponents.secondaryCardColors(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                icon()
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f))
            }
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.82f))
        }
    }
}

// ── Recent sessions ───────────────────────────────────────────────────────────

@Composable
private fun recentSessionsSection(
    sessions: List<ChatSession>,
    onResumeSession: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
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
            Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    sessions.forEachIndexed { index, session ->
                        recentSessionRow(session = session, onResumeSession = onResumeSession)
                        if (index < sessions.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
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
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onResumeSession(session.id) }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = Spacing.large, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            sessionTooltip(session = session) {
                Text(text = session.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = TimeUtil.formatDisplay(session.updatedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
