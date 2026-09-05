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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.config.FeatureFlags
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.telemetry.LlmInstanceStats
import io.askimo.core.telemetry.TelemetryCollector
import io.askimo.core.user.domain.UserProfile
import io.askimo.core.util.TimeUtil
import io.askimo.ui.common.components.clickableCard
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.themedTooltip
import io.askimo.ui.session.sessionTooltip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

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
    telemetry: TelemetryCollector,
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
                        telemetry = telemetry,
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
            style = AppTextStyles.pageTitle,
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
                                    style = AppTextStyles.body,
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
            icon = { Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            onClick = onNavigateToSessions,
            modifier = Modifier.weight(1f),
        )

        if (FeatureFlags.projectsEnabled) {
            statCard(
                label = stringResource("discover.stat.projects"),
                value = totalProjects?.let { LocalizationManager.formatNumber(it) } ?: "—",
                icon = { Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = onNavigateToProjects,
                modifier = Modifier.weight(1f),
            )
        }

        if (FeatureFlags.mcpIntegrationEnabled) {
            statCard(
                label = stringResource("discover.stat.mcp"),
                value = LocalizationManager.formatNumber(totalMcpServers),
                icon = { Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = onNavigateToMcpSettings,
                modifier = Modifier.weight(1f),
            )
        }

        if (FeatureFlags.plansEnabled) {
            statCard(
                label = stringResource("discover.stat.plans"),
                value = totalPlans?.let { LocalizationManager.formatNumber(it) } ?: "—",
                icon = { Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = onNavigateToPlans,
                modifier = Modifier.weight(1f),
            )
        }

        if (FeatureFlags.skillsEnabled) {
            statCard(
                label = stringResource("discover.stat.skills"),
                value = totalSkills?.let { LocalizationManager.formatNumber(it) } ?: "—",
                icon = { Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
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
    clickableCard(onClick = onClick, modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) { icon() }
            Text(
                text = value,
                style = AppTextStyles.pageTitle,
            )
            Text(
                text = label,
                style = AppTextStyles.body,
            )
        }
    }
}

// ── Token Usage section ───────────────────────────────────────────────────────

/**
 * Represents the active time window for the token usage query.
 *
 * [Past24Hours], [PastWeek], and [PastMonth] use rolling windows ending at *now*.
 * [Custom] carries explicit `from`/`to` [Instant] boundaries chosen by the user.
 */
private sealed class TokenUsagePeriod {
    object Past24Hours : TokenUsagePeriod()
    object PastWeek : TokenUsagePeriod()
    object PastMonth : TokenUsagePeriod()
    data class Custom(val from: Instant, val to: Instant) : TokenUsagePeriod()

    /** Returns the [from, to) instant pair for this period. */
    fun toTimeRange(): Pair<Instant, Instant> {
        val now = Instant.now()
        return when (this) {
            is Past24Hours -> now.minus(24, ChronoUnit.HOURS) to now
            is PastWeek -> now.minus(7, ChronoUnit.DAYS) to now
            is PastMonth -> now.minus(30, ChronoUnit.DAYS) to now
            is Custom -> from to to
        }
    }

    val labelKey: String
        get() = when (this) {
            is Past24Hours -> "discover.tokens.period.24h"
            is PastWeek -> "discover.tokens.period.week"
            is PastMonth -> "discover.tokens.period.month"
            is Custom -> "discover.tokens.period.custom"
        }

    val emptyKey: String
        get() = when (this) {
            is Past24Hours -> "discover.tokens.empty.24h"
            is PastWeek -> "discover.tokens.empty.week"
            is PastMonth -> "discover.tokens.empty.month"
            is Custom -> "discover.tokens.empty.custom"
        }
}

/**
 * Full-width section with a time-scope selector (Past 24 h / Past 1 Week /
 * Past 1 Month / Custom), a 3-column summary strip, and a horizontal bar chart
 * of the top 5 models by token usage within the active period.
 * All numbers are formatted via [LocalizationManager] so grouping separators
 * and decimal marks follow the active locale.
 */
@Composable
private fun tokenUsageSection(
    telemetry: TelemetryCollector,
    onOpenSystemDiagnostics: () -> Unit,
) {
    val refreshSignal by telemetry.refreshSignal.collectAsState()
    var selectedPeriod by remember { mutableStateOf<TokenUsagePeriod>(TokenUsagePeriod.PastMonth) }
    var showCustomDialog by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf<List<LlmInstanceStats>>(emptyList()) }

    LaunchedEffect(refreshSignal, selectedPeriod) {
        val (from, to) = selectedPeriod.toTimeRange()
        stats = withContext(Dispatchers.IO) {
            telemetry.usageRepository.queryGroupedByInstance(from, to)
        }
    }

    val totalTokens = stats.sumOf { it.tokens }
    val totalCalls = stats.sumOf { it.calls }
    val topModels = stats.take(5) // already ordered by tokens DESC from query

    if (showCustomDialog) {
        tokenUsageCustomDateDialog(
            onConfirm = { from, to ->
                selectedPeriod = TokenUsagePeriod.Custom(from, to)
                showCustomDialog = false
            },
            onDismiss = { showCustomDialog = false },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        tokenUsageSectionHeader(
            totalTokens = totalTokens,
            onOpenSystemDiagnostics = onOpenSystemDiagnostics,
            selectedPeriod = selectedPeriod,
            onSelectPreset = { selectedPeriod = it },
            onSelectCustom = { showCustomDialog = true },
        )
        tokenUsageChartCard(
            totalTokens = totalTokens,
            totalCalls = totalCalls,
            topModels = topModels,
            selectedPeriod = selectedPeriod,
        )
    }
}

/**
 * Two-step calendar date-range picker.
 * Step 1 — user picks the *From* date; step 2 — user picks the *To* date.
 * Dismissing at any point (ESC / click-outside) closes the flow entirely;
 * the dismiss button on step 2 goes back to step 1.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun tokenUsageCustomDateDialog(
    onConfirm: (from: Instant, to: Instant) -> Unit,
    onDismiss: () -> Unit,
) {
    var step by remember { mutableStateOf(1) }
    val fromState = rememberDatePickerState(
        initialSelectedDateMillis = Instant.now().minus(30, ChronoUnit.DAYS).toEpochMilli(),
    )
    val toState = rememberDatePickerState(
        initialSelectedDateMillis = Instant.now().toEpochMilli(),
    )

    if (step == 1) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = { if (fromState.selectedDateMillis != null) step = 2 },
                    enabled = fromState.selectedDateMillis != null,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Text(stringResource("discover.tokens.custom.next"))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Text(stringResource("discover.tokens.custom.cancel"))
                }
            },
        ) {
            DatePicker(
                state = fromState,
                title = null,
                headline = {
                    Text(
                        text = stringResource("discover.tokens.custom.from"),
                        modifier = Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
                showModeToggle = true,
            )
        }
    } else {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = {
                        val fromMillis = fromState.selectedDateMillis
                        val toMillis = toState.selectedDateMillis
                        if (fromMillis != null && toMillis != null) {
                            onConfirm(
                                Instant.ofEpochMilli(fromMillis),
                                // advance by one day so the full "to" day is included in the query
                                Instant.ofEpochMilli(toMillis).plus(1, ChronoUnit.DAYS),
                            )
                        }
                    },
                    enabled = toState.selectedDateMillis != null,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Text(stringResource("discover.tokens.custom.apply"))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { step = 1 },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Text(stringResource("discover.tokens.custom.back"))
                }
            },
        ) {
            DatePicker(
                state = toState,
                title = null,
                headline = {
                    Text(
                        text = stringResource("discover.tokens.custom.to"),
                        modifier = Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
                showModeToggle = true,
            )
        }
    }
}

@Composable
private fun tokenUsageSectionHeader(
    totalTokens: Long,
    onOpenSystemDiagnostics: () -> Unit,
    selectedPeriod: TokenUsagePeriod,
    onSelectPreset: (TokenUsagePeriod) -> Unit,
    onSelectCustom: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    // Build the button label: formatted date range for Custom, i18n key for presets
    val zone = ZoneId.systemDefault()
    val shortFmt = DateTimeFormatter.ofPattern("MMM d")
    val periodLabel = when (selectedPeriod) {
        is TokenUsagePeriod.Custom -> {
            val fromStr = selectedPeriod.from.atZone(zone).format(shortFmt)
            // "to" was advanced by +1 day for inclusive querying — display the original selected day
            val toStr = selectedPeriod.to.minus(1, ChronoUnit.DAYS).atZone(zone).format(shortFmt)
            "$fromStr – $toStr"
        }

        else -> stringResource(selectedPeriod.labelKey)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: icon + title
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
                style = AppTextStyles.sectionTitle,
            )
        }

        // Right: period dropdown + optional total-tokens badge
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                TextButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Text("$periodLabel ▾", style = AppTextStyles.caption)
                }
                AppComponents.dropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    listOf(
                        TokenUsagePeriod.Past24Hours,
                        TokenUsagePeriod.PastWeek,
                        TokenUsagePeriod.PastMonth,
                    ).forEach { period ->
                        DropdownMenuItem(
                            text = { Text(stringResource(period.labelKey), style = AppTextStyles.body) },
                            onClick = {
                                onSelectPreset(period)
                                showMenu = false
                            },
                            modifier = Modifier.widthIn(min = 220.dp).pointerHoverIcon(PointerIcon.Hand),
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource("discover.tokens.period.custom"), style = AppTextStyles.body) },
                        onClick = {
                            onSelectCustom()
                            showMenu = false
                        },
                        modifier = Modifier.widthIn(min = 220.dp).pointerHoverIcon(PointerIcon.Hand),
                    )
                }
            }

            if (totalTokens > 0) {
                Text(
                    text = stringResource("discover.tokens.total", abbreviateTokens(totalTokens)),
                    style = AppTextStyles.caption,
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
}

@Composable
private fun tokenUsageChartCard(
    totalTokens: Long,
    totalCalls: Int,
    topModels: List<LlmInstanceStats>,
    selectedPeriod: TokenUsagePeriod,
) {
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
                    text = stringResource(selectedPeriod.emptyKey),
                    style = AppTextStyles.bodySecondary,
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.large),
                verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                // 3-column summary strip: Tokens used / Calls made / Top model
                val topModelName = topModels.firstOrNull()?.let { stat ->
                    stat.model.ifBlank {
                        stat.instanceKey.split(":", limit = 2).getOrElse(0) { stat.instanceKey }
                            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    }
                }
                tokenUsageSummaryStrip(totalTokens = totalTokens, totalCalls = totalCalls, topModel = topModelName)
                HorizontalDivider(color = AppColors.codeBlockBorderColor())

                topModels.forEachIndexed { index, stat ->
                    val provider = stat.instanceKey
                        .split(":", limit = 2).getOrElse(0) { stat.instanceKey }
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    val model = stat.model.ifBlank { provider }
                    val fraction = stat.tokens.toFloat() / totalTokens.toFloat()
                    val pctFormatted = LocalizationManager.formatNumber((fraction * 100).toInt()) + "%"

                    tokenBarRow(
                        provider = provider,
                        model = model,
                        tokens = stat.tokens,
                        fraction = fraction,
                        pctFormatted = pctFormatted,
                        calls = stat.calls,
                    )

                    if (index < topModels.lastIndex) {
                        HorizontalDivider(
                            color = AppColors.codeBlockBorderColor(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun tokenUsageSummaryStrip(totalTokens: Long, totalCalls: Int, topModel: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        summaryStatItem(
            value = abbreviateTokens(totalTokens),
            label = stringResource("discover.tokens.stat.tokens"),
            modifier = Modifier.weight(1f),
        )
        summaryStatItem(
            value = LocalizationManager.formatNumber(totalCalls),
            label = stringResource("discover.tokens.stat.calls"),
            modifier = Modifier.weight(1f),
        )
        summaryStatItem(
            value = topModel ?: "—",
            label = stringResource("discover.tokens.stat.top_model"),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun summaryStatItem(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = value, style = AppTextStyles.itemTitle)
        Text(text = label, style = AppTextStyles.hint, textAlign = TextAlign.Center)
    }
}

@Composable
private fun tokenBarRow(
    provider: String,
    model: String,
    tokens: Long,
    fraction: Float,
    pctFormatted: String,
    calls: Int,
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
                    style = AppTextStyles.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = provider,
                    style = AppTextStyles.hint,
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
                style = AppTextStyles.caption,
                textAlign = TextAlign.End,
                modifier = Modifier.width(52.dp),
            )
        }

        // Percentage
        Text(
            text = pctFormatted,
            style = AppTextStyles.hint,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp),
        )

        // Call count
        Text(
            text = stringResource("discover.tokens.calls", LocalizationManager.formatNumber(calls)),
            style = AppTextStyles.hint,
            textAlign = TextAlign.End,
            modifier = Modifier.width(52.dp),
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
            style = AppTextStyles.sectionTitle,
        )
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(Spacing.large),
        ) {
            enabledCards.forEach { card ->
                exploreCard(
                    icon = { Icon(card.icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
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
        colors = AppColors.cardColors(AppColors.Elevation.RAISED),
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
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(14.dp), tint = AppColors.tertiaryIconColor())
            }
            Text(text = title, style = AppTextStyles.itemTitle)
            Text(text = description, style = AppTextStyles.caption)
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
            style = AppTextStyles.sectionTitle,
        )

        if (sessions.isEmpty()) {
            Text(
                text = stringResource("discover.recent.empty"),
                style = AppTextStyles.bodySecondary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    sessions.forEachIndexed { index, session ->
                        recentSessionRow(session = session, onResumeSession = onResumeSession)
                        if (index < sessions.lastIndex) {
                            HorizontalDivider(color = AppColors.codeBlockBorderColor())
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
                    AppColors.surfaceColor(AppColors.Elevation.RAISED)
                } else {
                    Color.Transparent
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
                Text(text = session.title, style = AppTextStyles.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = TimeUtil.formatDisplay(session.updatedAt), style = AppTextStyles.caption)
    }
}
