/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.askimo.core.rag.state.IndexStatus
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors

/**
 * Localized label for a persisted [IndexStatus], suitable for badges, tooltips, and table cells.
 */
@Composable
fun indexStatusLabel(status: IndexStatus): String = when (status) {
    IndexStatus.NOT_STARTED -> stringResource("resourcecollections.status.not_started")
    IndexStatus.QUEUED -> stringResource("resourcecollections.status.queued")
    IndexStatus.INDEXING -> stringResource("resourcecollections.status.indexing")
    IndexStatus.READY -> stringResource("resourcecollections.status.ready")
    IndexStatus.WATCHING -> stringResource("resourcecollections.status.watching")
    IndexStatus.FAILED -> stringResource("resourcecollections.status.failed")
}

/**
 * Small status icon representing a persisted [IndexStatus] (e.g. shown per-row in a resource
 * collection list or dropdown, without needing a live progress subscription).
 */
@Composable
fun indexStatusIcon(
    status: IndexStatus,
    size: Dp = 16.dp,
    modifier: Modifier = Modifier,
) {
    when (status) {
        IndexStatus.READY, IndexStatus.WATCHING -> {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = indexStatusLabel(status),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = modifier.size(size),
            )
        }

        IndexStatus.INDEXING, IndexStatus.QUEUED -> {
            CircularProgressIndicator(
                modifier = modifier.size(size).padding(1.dp),
                strokeWidth = 2.dp,
            )
        }

        IndexStatus.FAILED -> {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = indexStatusLabel(status),
                tint = MaterialTheme.colorScheme.error,
                modifier = modifier.size(size),
            )
        }

        IndexStatus.NOT_STARTED -> {
            Icon(
                imageVector = Icons.Default.RadioButtonUnchecked,
                contentDescription = indexStatusLabel(status),
                tint = AppColors.warningColor(),
                modifier = modifier.size(size),
            )
        }
    }
}
