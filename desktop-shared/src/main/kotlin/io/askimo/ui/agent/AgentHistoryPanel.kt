/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.agent.domain.AgentRunRecord
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.TooltipPlacement
import io.askimo.ui.common.ui.themedTooltip
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Number of most-recent history records shown before the "Show all" button appears. */
private const val COLLAPSED_HISTORY_LIMIT = 5

internal val RUN_TIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, HH:mm:ss").withZone(ZoneId.systemDefault())

@Composable
private fun skillRunHistoryPanelRow(
    record: AgentRunRecord,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val isError = record.error != null
    val timeLabel = RUN_TIME_FMT.format(record.createdAt)
    val tooltipText = remember(record) {
        buildString {
            append(timeLabel)
            if (record.userInput.isNotBlank()) {
                append("\n")
                append(record.userInput)
            }
            if (isError) {
                append("\n⚠ ")
                append(record.error)
            }
        }
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    themedTooltip(text = tooltipText, placement = TooltipPlacement.LEFT) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Hand)
                .hoverable(interactionSource)
                .background(
                    color = if (isHovered) {
                        AppColors.surfaceColor(AppColors.Elevation.RAISED)
                    } else {
                        AppColors.surfaceColor(AppColors.Elevation.RECESSED)
                    },
                    shape = RoundedCornerShape(8.dp),
                )
                .clickable(onClick = onClick)
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Icon(
                if (isError) Icons.Default.Close else Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    timeLabel,
                    style = AppTextStyles.hint,
                )
                if (record.title.isNotBlank()) {
                    Text(
                        record.title,
                        style = AppTextStyles.hint,
                        color = AppColors.secondaryIconColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (isHovered) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(28.dp)
                        .pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete record",
                        tint = AppColors.destructiveIconColor(),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/**
 * Rows for a workspace's run history — used to enter (or delete) a past conversation.
 * Rendered directly inside an already-scrollable parent, such as the agentic-run "home"
 * screen (`agenticRunArea`'s empty-conversation state), so past runs are visible
 * immediately instead of hidden behind a side-panel History tab.
 *
 * Only the [COLLAPSED_HISTORY_LIMIT] most recent records are shown initially — a
 * "Show all" button reveals the rest, so a long-lived workspace's home screen doesn't
 * turn into an endless list by default.
 */
@Composable
internal fun agentRunHistoryList(
    runHistory: List<AgentRunRecord>,
    onSelectRecord: (AgentRunRecord) -> Unit = {},
    onDeleteRecord: (AgentRunRecord) -> Unit = {},
) {
    if (runHistory.isEmpty()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        ) {
            Icon(Icons.Default.History, null, modifier = Modifier.size(36.dp), tint = AppColors.surfaceColor(AppColors.Elevation.RECESSED))
            Text(stringResource("agents.view.history.empty"), style = AppTextStyles.caption, color = AppColors.tertiaryIconColor())
        }
    } else {
        var showAll by remember { mutableStateOf(false) }
        val visibleHistory = if (showAll) runHistory else runHistory.take(COLLAPSED_HISTORY_LIMIT)

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
            visibleHistory.forEach { record ->
                skillRunHistoryPanelRow(
                    record = record,
                    onClick = { onSelectRecord(record) },
                    onDelete = { onDeleteRecord(record) },
                )
            }
            if (!showAll && runHistory.size > COLLAPSED_HISTORY_LIMIT) {
                TextButton(
                    onClick = { showAll = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Text(
                        stringResource("agents.view.history.show.more", runHistory.size),
                        style = AppTextStyles.hint,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
