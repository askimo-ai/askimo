/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.skills

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.skills.agent.ExternalAgentLoader
import io.askimo.core.skills.domain.SkillRunRecord
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.markdownText
import io.askimo.ui.common.ui.themedTooltip
import java.awt.Desktop
import java.net.URI

@Composable
internal fun skillsPageHeader(
    onNavigateToSkillsSettings: () -> Unit,
    showPanelToggle: Boolean = false,
    panelVisible: Boolean = false,
    onTogglePanel: () -> Unit = {},
) {
    val runtimes = ExternalAgentLoader.displayNames()
    val runtimesLabel = runtimes.mapIndexed { i, r ->
        if (i == runtimes.lastIndex) "or $r" else r
    }.joinToString(", ")

    // ── Title row: page title + toolbar actions ────────────────────────────
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource("skills.view.title"),
            style = AppTextStyles.pageTitle,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
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

    // ── Description + runtimes: full width below the title row ─────────────
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = stringResource("settings.skills.description", runtimesLabel),
        style = AppTextStyles.bodySecondary,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource("settings.skills.runtimes"),
            style = AppTextStyles.caption,
        )
        runtimes.forEach { runtime ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            ) {
                Text(
                    text = runtime,
                    style = AppTextStyles.hint,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

// ── Shared response + follow-up section ────────────────────────────────────

/**
 * Renders the "response panel" (status/error/streamed text + collapsible activity line)
 * plus the follow-up input box below it, used by [agenticRunArea].
 *
 * Renders nothing when there is no run in progress, no response, and no error yet.
 */
@Composable
internal fun skillRunResultSection(
    isRunning: Boolean,
    hasResponse: Boolean,
    responseText: String,
    displayText: String,
    isInThinkingPhase: Boolean,
    runError: String?,
    timeline: List<RunTimelineEntry>,
    elapsedSeconds: Int,
    followUpInput: String,
    onFollowUpInputChange: (String) -> Unit,
    onFollowUpSend: (trimmedInput: String) -> Unit,
) {
    if (!(isRunning || hasResponse || runError != null)) return

    val clipboardManager = LocalClipboardManager.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 1.dp,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(Spacing.large)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.medium),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Refresh else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (runError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = when {
                            isRunning -> stringResource("skills.view.running")
                            runError != null -> stringResource("skills.view.response.error")
                            else -> stringResource("skills.view.response.title")
                        },
                        style = AppTextStyles.sectionTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (isRunning) {
                        Text(
                            text = "${elapsedSeconds}s",
                            style = AppTextStyles.hint,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        )
                    }
                }
                if (hasResponse) {
                    IconButton(
                        onClick = { clipboardManager.setText(AnnotatedString(responseText)) },
                        modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource("skills.view.copy"),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
            SelectionContainer {
                when {
                    runError != null -> Text(
                        text = runError,
                        style = AppTextStyles.body,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = Spacing.medium),
                    )

                    isRunning && isInThinkingPhase && displayText.isNotBlank() -> {
                        val blockquote = displayText.lines().joinToString("\n") { "> $it" }
                        markdownText(
                            markdown = blockquote,
                            isStreaming = true,
                            modifier = Modifier.fillMaxWidth().padding(top = Spacing.medium),
                        )
                    }

                    // Render the timeline in emission order: text segments as markdown,
                    // activity (status/tool) events as small muted lines interleaved right
                    // where they occurred — instead of grouping all activity at the bottom.
                    timeline.isNotEmpty() -> Column(
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.medium),
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        timeline.forEachIndexed { index, entry ->
                            when (entry) {
                                is RunTimelineEntry.Text -> markdownText(
                                    markdown = entry.content,
                                    isStreaming = isRunning && index == timeline.lastIndex,
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                is RunTimelineEntry.Activity -> Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = "›",
                                        style = AppTextStyles.hint,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    )
                                    Text(
                                        text = entry.message,
                                        style = AppTextStyles.hint,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    else -> Box(
                        modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = Spacing.medium),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = "▌",
                            style = AppTextStyles.body,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
    }

    // ── Follow-up ────────────────────────────────────────────────────────────
    Spacer(modifier = Modifier.height(Spacing.small))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(Spacing.large)) {
            Text(
                text = stringResource("skills.view.followup.label"),
                style = AppTextStyles.fieldLabel,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.small),
            )
            agentInputField(
                value = followUpInput,
                onValueChange = onFollowUpInputChange,
                placeholder = stringResource("skills.view.followup.placeholder"),
                enabled = hasResponse && !isRunning,
                onSend = {
                    val trimmed = followUpInput.trim()
                    if (trimmed.isNotBlank()) onFollowUpSend(trimmed)
                },
                sendContentDescription = stringResource("skills.view.followup.send"),
            )
        }
    }
}

// ── Shared side-panel tab icon — used by manual/agentic right panel icon bars ──

/**
 * A single tab icon in a collapsible right panel's always-visible icon bar
 * (e.g. Skills/Workspace/History for manual runs, Workspace/History for agentic
 * runs). Shows an optional count [badge] in the top-right corner.
 */
@Composable
internal fun sidePanelTabIcon(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    badge: String? = null,
    onClick: () -> Unit,
) {
    themedTooltip(text = label) {
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        } else {
                            Color.Transparent
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
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (isSelected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
            // Badge (e.g. skill/history count)
            if (badge != null) {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    modifier = Modifier.padding(top = 1.dp, end = 1.dp),
                ) {
                    Text(
                        text = badge,
                        style = AppTextStyles.hint,
                        modifier = Modifier.padding(horizontal = 3.dp, vertical = 0.dp),
                    )
                }
            }
        }
    }
}

/**
 * A single ordered entry in an agent run's timeline, interleaving streamed response text
 * with status/tool-activity events in the exact order they were emitted — instead of
 * grouping all activity separately at the end. Mirrors what a user would see running the
 * agent directly in a terminal: text output, then a tool call, then more text, etc.
 */
internal sealed class RunTimelineEntry {
    data class Text(val content: String) : RunTimelineEntry()
    data class Activity(val message: String) : RunTimelineEntry()
}

/**
 * Appends [token] to this timeline, merging it into the trailing [RunTimelineEntry.Text]
 * entry if one exists, or starting a new one otherwise.
 */
internal fun List<RunTimelineEntry>.appendText(token: String): List<RunTimelineEntry> {
    val last = lastOrNull()
    return if (last is RunTimelineEntry.Text) {
        dropLast(1) + RunTimelineEntry.Text(last.content + token)
    } else {
        this + RunTimelineEntry.Text(token)
    }
}

/** Appends [status] to this timeline as a new [RunTimelineEntry.Activity] entry. */
internal fun List<RunTimelineEntry>.appendActivity(status: String): List<RunTimelineEntry> = this + RunTimelineEntry.Activity(status)

/**
 * Approximates the interleaved timeline for a persisted [SkillRunRecord].
 *
 * True interleaving is not persisted (only a flat response string and a flat
 * activity log), so this approximates the original order: text first, then activity.
 */
internal fun timelineFromRecord(record: SkillRunRecord): List<RunTimelineEntry> = buildList {
    if (record.response.isNotBlank()) add(RunTimelineEntry.Text(record.response))
    addAll(record.activityLog.map { RunTimelineEntry.Activity(it) })
}
