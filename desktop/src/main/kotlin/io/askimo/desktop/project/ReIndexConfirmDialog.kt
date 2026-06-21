/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.askimo.ui.common.components.dangerButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.components.warningIcon
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.Spacing

/**
 * Confirmation dialog shown when the user requests a re-index while the project is actively
 * being indexed. Warns that the current indexing will be stopped.
 *
 * @param projectName The name of the project being re-indexed
 * @param onConfirm Callback when the user confirms stopping and re-indexing
 * @param onDismiss Callback when the user cancels
 */
@Composable
fun reIndexConfirmDialog(
    projectName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(450.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(Spacing.extraLarge),
                verticalArrangement = Arrangement.spacedBy(Spacing.large),
            ) {
                // Title with warning icon
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    warningIcon(size = 32.dp)
                    Text(
                        text = stringResource("panel.reindex.confirm.title"),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // Confirmation message
                Text(
                    text = stringResource("panel.reindex.confirm.message", projectName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    secondaryButton(onClick = onDismiss) {
                        Text(stringResource("panel.reindex.confirm.cancel"))
                    }

                    Spacer(modifier = Modifier.width(Spacing.small))

                    dangerButton(onClick = onConfirm) {
                        Text(stringResource("panel.reindex.confirm.button"))
                    }
                }
            }
        }
    }
}
