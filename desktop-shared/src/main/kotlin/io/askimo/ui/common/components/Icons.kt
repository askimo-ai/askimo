/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ─────────────────────────────────────────────────────────────────────────────
// Status icons
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Success / completion icon (CheckCircle).
 * Uses onSurface — safe across all 8 themes including dark mode.
 *
 * @param size 24.dp standard; use 32/64.dp for hero placement in dialogs.
 */
@Composable
fun successIcon(
    contentDescription: String? = null,
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = Icons.Default.CheckCircle,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.size(size),
    )
}

/**
 * Indexed-state icon.
 * Uses checkmark to signal availability in retrieval.
 */
@Composable
fun indexedIcon(
    contentDescription: String? = null,
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = Icons.Default.CheckCircle,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.size(size),
    )
}

/**
 * Not-indexed-state icon.
 * Uses a hollow circle to avoid implying an error state.
 */
@Composable
fun notIndexedIcon(
    contentDescription: String? = null,
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = Icons.Default.RadioButtonUnchecked,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
        modifier = modifier.size(size),
    )
}
