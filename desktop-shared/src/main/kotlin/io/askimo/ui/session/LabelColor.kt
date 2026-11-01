/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.askimo.core.chat.domain.Label
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.themedTooltip

/** Small filled circle rendering a label's color, parsed from its `#RRGGBB` hex string. */
@Composable
fun labelColorDot(colorHex: String, size: Dp = 8.dp) {
    val color = remember(colorHex) { parseLabelColor(colorHex) }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

/** Parses a `#RRGGBB` hex string into a [Color], falling back to gray on malformed input. */
fun parseLabelColor(colorHex: String): Color = try {
    val hex = colorHex.removePrefix("#")
    val rgb = hex.toLong(16) and 0xFFFFFF
    Color(0xFF000000 or rgb)
} catch (_: Exception) {
    Color.Gray
}

/**
 * Soft tinted-pill badge row for a session's assigned [labels] — shows the first label's name
 * (ellipsized) plus a "+N" overflow count, with a tooltip listing every label. Shared by the
 * sidebar session row ([io.askimo.ui.shell.NavigationSidebar]) and the chat header
 * ([io.askimo.ui.chat.ChatView]) so the two stay visually consistent.
 */
@Composable
fun labelBadgeRow(labels: List<Label>, modifier: Modifier = Modifier) {
    if (labels.isEmpty()) return
    val firstLabel = labels.first()
    val remainingCount = labels.size - 1
    val badgeColor = remember(firstLabel.color) { parseLabelColor(firstLabel.color) }
    themedTooltip(text = labels.joinToString(", ") { it.name }) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(50))
                .background(badgeColor.copy(alpha = 0.18f))
                .padding(horizontal = Spacing.extraSmall, vertical = Spacing.micro),
            horizontalArrangement = Arrangement.spacedBy(Spacing.micro),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = firstLabel.name,
                style = AppTextStyles.hint,
                color = AppTextStyles.secondaryContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 72.dp),
            )
            if (remainingCount > 0) {
                Text(
                    text = "+$remainingCount",
                    style = AppTextStyles.hint,
                    color = AppTextStyles.secondaryContent,
                )
            }
        }
    }
}

/** Curated palette offered by the label color picker — distinct, accessible hues. */
val LABEL_COLOR_PALETTE: List<String> = listOf(
    "#EF4444", // red
    "#F97316", // orange
    "#F59E0B", // amber
    "#EAB308", // yellow
    "#84CC16", // lime
    "#22C55E", // green
    "#10B981", // emerald
    "#14B8A6", // teal
    "#06B6D4", // cyan
    "#3B82F6", // blue
    "#6366F1", // indigo
    "#8B5CF6", // violet
    "#A855F7", // purple
    "#EC4899", // pink
    "#64748B", // slate
)
