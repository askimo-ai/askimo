/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.knowledgesource

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import io.askimo.core.chat.domain.KnowledgeSourcePriority
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.themedTooltip

/**
 * Compact priority picker shown on each knowledge source row (Project and Resource
 * Collection views). Shows the current [priority] and reports the chosen level through
 * [onPriorityChange].
 */
@Composable
fun knowledgeSourcePrioritySelector(
    priority: KnowledgeSourcePriority,
    onPriorityChange: (KnowledgeSourcePriority) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Box {
        themedTooltip(text = stringResource("projects.sources.priority.tooltip")) {
            TextButton(
                onClick = { showMenu = true },
                contentPadding = PaddingValues(horizontal = Spacing.small),
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) {
                Text(
                    text = stringResource(priority.labelKey()),
                    style = AppTextStyles.caption,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        AppComponents.dropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            KnowledgeSourcePriority.entries.forEach { level ->
                DropdownMenuItem(
                    text = { Text(stringResource(level.labelKey())) },
                    onClick = {
                        showMenu = false
                        if (level != priority) onPriorityChange(level)
                    },
                    leadingIcon = {
                        if (level == priority) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        } else {
                            Spacer(modifier = Modifier.width(16.dp))
                        }
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                )
            }
        }
    }
}

private fun KnowledgeSourcePriority.labelKey(): String = when (this) {
    KnowledgeSourcePriority.LOW -> "projects.sources.priority.low"
    KnowledgeSourcePriority.NORMAL -> "projects.sources.priority.normal"
    KnowledgeSourcePriority.HIGH -> "projects.sources.priority.high"
    KnowledgeSourcePriority.ALWAYS_PRIORITIZE -> "projects.sources.priority.always"
}
