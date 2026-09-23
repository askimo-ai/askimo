/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.knowledgesource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing

/**
 * Composable for displaying a single knowledge source row with a remove button.
 *
 * Shared between the Project and Resource Collection features.
 */
@Composable
fun knowledgeSourceRow(
    source: KnowledgeSourceItem,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.extraSmall),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                source.typeInfo.icon,
                contentDescription = null,
                modifier = Modifier.padding(end = Spacing.small).size(20.dp),
                tint = AppTextStyles.primaryContent,
            )
            Text(
                text = source.displayName,
                style = AppTextStyles.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (source.isValid) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Valid",
                    tint = AppTextStyles.primaryContent,
                    modifier = Modifier.size(16.dp).padding(start = Spacing.extraSmall),
                )
            } else {
                Icon(
                    Icons.Default.Error,
                    contentDescription = "Invalid",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp).padding(start = Spacing.extraSmall),
                )
            }
        }

        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
