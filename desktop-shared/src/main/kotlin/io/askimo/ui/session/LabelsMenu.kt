/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.LocalFontScale
import io.askimo.ui.common.theme.Spacing
import io.askimo.core.chat.domain.Label as ChatLabel

/**
 * Reusable "Labels" menu item with a popup submenu, following the same pattern as
 * [moveToProjectMenuItem]: a checkable list of [allLabels] (toggling [assignedLabelIds]
 * membership) followed by a "Manage labels…" entry that opens [onManageLabels].
 *
 * @param allLabels every label the user has defined
 * @param assignedLabelIds the ids currently assigned to the session this menu is for
 * @param onToggleLabel invoked with a label id when the user checks/unchecks it
 * @param onManageLabels invoked when "Manage labels…" is selected
 * @param onDismiss callback to close the parent menu
 */
@Composable
fun labelsMenuItem(
    allLabels: List<ChatLabel>,
    assignedLabelIds: Set<String>,
    onToggleLabel: (labelId: String) -> Unit,
    onManageLabels: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showSubmenu by remember { mutableStateOf(false) }
    var itemWidth by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val fontScale = LocalFontScale.current

    Box {
        AppComponents.menuItem(
            text = { Text(stringResource("session.labels")) },
            onClick = { showSubmenu = !showSubmenu },
            leadingIcon = {
                Icon(
                    Icons.Default.Label,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size((20 * fontScale).dp),
                )
            },
            trailingIcon = {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size((20 * fontScale).dp),
                )
            },
            modifier = Modifier.onGloballyPositioned { coordinates ->
                itemWidth = with(density) { coordinates.size.width.toDp() }
            },
        )

        if (showSubmenu) {
            Box(modifier = Modifier.offset(x = itemWidth, y = -Spacing.small)) {
                AppComponents.dropdownMenu(
                    expanded = showSubmenu,
                    onDismissRequest = { showSubmenu = false },
                ) {
                    Column {
                        if (allLabels.isEmpty()) {
                            AppComponents.menuItem(
                                text = {
                                    Text(
                                        stringResource("session.labels.empty"),
                                        style = AppTextStyles.caption,
                                    )
                                },
                                onClick = { /* no-op */ },
                                enabled = false,
                            )
                        } else {
                            allLabels.forEach { label ->
                                val isAssigned = label.id in assignedLabelIds
                                AppComponents.menuItem(
                                    text = { Text(label.name) },
                                    onClick = { onToggleLabel(label.id) },
                                    leadingIcon = { labelColorDot(colorHex = label.color, size = (10 * fontScale).dp) },
                                    trailingIcon = {
                                        if (isAssigned) {
                                            Icon(Icons.Default.Check, contentDescription = null)
                                        }
                                    },
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.extraSmall))
                        }

                        AppComponents.menuItem(
                            text = { Text(stringResource("session.labels.manage")) },
                            onClick = {
                                showSubmenu = false
                                onDismiss()
                                onManageLabels()
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size((20 * fontScale).dp),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
