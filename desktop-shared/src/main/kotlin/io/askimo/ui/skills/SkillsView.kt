/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.skills

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.askimo.ui.common.i18n.stringResource
import java.time.Duration
import java.time.Instant

/**
 * Top-level skills entry point.
 * Owns only the Agentic/Manual mode toggle state and delegates
 * everything else to the two self-contained sub-views.
 */
@Composable
fun skillsView(
    onNavigateToSkillsSettings: () -> Unit = {},
) {
    var agenticMode by remember { mutableStateOf(true) }
    if (agenticMode) {
        agenticSkillsView(
            onSwitchToManual = { agenticMode = false },
            onNavigateToSkillsSettings = onNavigateToSkillsSettings,
        )
    } else {
        manualSkillsView(
            onSwitchToAgentic = { agenticMode = true },
            onNavigateToSkillsSettings = onNavigateToSkillsSettings,
        )
    }
}

// ── Shared mode toggle — used by both sub-views ────────────────────────────

@Composable
internal fun skillsModeToggle(
    agenticMode: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row {
            modeTab(
                label = stringResource("skills.view.mode.agentic"),
                active = agenticMode,
                onClick = { onToggle(true) },
            )
            modeTab(
                label = stringResource("skills.view.mode.manual"),
                active = !agenticMode,
                onClick = { onToggle(false) },
            )
        }
    }
}

@Composable
private fun modeTab(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                color = if (active) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
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
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Shared time helper — used by ManualSkillsView ─────────────────────────

internal fun formatRelativeTime(instant: Instant): String {
    val seconds = Duration.between(instant, Instant.now()).seconds.coerceAtLeast(0)
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60}m ago"
        seconds < 86400 -> "${seconds / 3600}h ago"
        else -> "${seconds / 86400}d ago"
    }
}
