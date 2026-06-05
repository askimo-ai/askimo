/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.util.TimeUtil
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.ui.TooltipPlacement
import io.askimo.ui.common.ui.themedRichTooltip

/**
 * A rich tooltip for a [ChatSession] that shows:
 * - Full session title
 * - Started date (createdAt)
 * - Last activity date (updatedAt)
 *
 * Drop-in replacement for `themedTooltip(text = session.title)` wherever
 * sessions are displayed (sidebar, sessions list, discover view, etc.).
 *
 * @param session The chat session to show info for
 * @param placement Where the tooltip appears relative to the anchor
 * @param modifier Optional modifier for the tooltip box
 * @param content The anchor composable
 */
@Composable
fun sessionTooltip(
    session: ChatSession,
    placement: TooltipPlacement = TooltipPlacement.AUTO,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    themedRichTooltip(
        placement = placement,
        modifier = modifier,
        tooltipContent = { sessionTooltipContent(session) },
        content = content,
    )
}

@Composable
private fun sessionTooltipContent(session: ChatSession) {
    Column(
        modifier = Modifier
            .widthIn(min = 220.dp, max = 340.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Full title
        Text(
            text = session.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = true,
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )

        // Started
        tooltipRow(
            label = stringResource("session.tooltip.started"),
            value = TimeUtil.formatDisplay(session.createdAt),
        )

        // Last activity
        tooltipRow(
            label = stringResource("session.tooltip.last.activity"),
            value = TimeUtil.formatDisplay(session.updatedAt),
        )
    }
}

@Composable
private fun tooltipRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
