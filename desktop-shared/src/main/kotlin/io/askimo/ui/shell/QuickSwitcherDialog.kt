/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import io.askimo.core.chat.domain.ChatSession
import io.askimo.ui.common.components.clickableCard
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.session.filteredByQuery

/**
 * Lightweight "jump to session" quick switcher, triggered by [io.askimo.ui.common.keymap.KeyMapManager.AppShortcut.QUICK_OPEN_SESSION]
 * (Cmd/Ctrl+K).
 *
 * Unlike [globalSearchDialog] (which does a heavier full-text search over message content across
 * all sessions), this only matches session titles and searches within [sessions] — the sidebar's
 * already-loaded, capped recent-sessions list — so it needs no extra database query.
 *
 * @param sessions Candidate sessions to switch between (the sidebar's in-memory recent sessions)
 * @param onDismiss Called when the dialog is dismissed without a selection
 * @param onSelectSession Called with the chosen session's id
 */
@Composable
fun quickSwitcherDialog(
    sessions: List<ChatSession>,
    onDismiss: () -> Unit,
    onSelectSession: (sessionId: String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val results = sessions.filteredByQuery(query)
    LaunchedEffect(query) { selectedIndex = 0 }

    fun openSelected() {
        results.getOrNull(selectedIndex)?.let { onSelectSession(it.id) }
    }

    AppComponents.scaffoldDialogLazyColumn(
        onDismissRequest = onDismiss,
        onCloseRequest = onDismiss,
        width = 560.dp,
        maxHeightFraction = 0.6f,
        actions = {},
        stickyHeader = {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource("sidebar.quickSwitcher.placeholder")) },
                leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                colors = AppColors.outlinedTextFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onKeyEvent { keyEvent ->
                        if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (keyEvent.key) {
                            Key.DirectionDown -> {
                                if (results.isNotEmpty()) selectedIndex = (selectedIndex + 1).coerceAtMost(results.lastIndex)
                                true
                            }

                            Key.DirectionUp -> {
                                if (results.isNotEmpty()) selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                                true
                            }

                            Key.Enter -> {
                                openSelected()
                                true
                            }

                            else -> false
                        }
                    },
            )
        },
        content = {
            if (results.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource("sidebar.quickSwitcher.noResults"),
                            style = AppTextStyles.bodySecondary,
                        )
                    }
                }
            } else {
                items(results.size) { index ->
                    val session = results[index]
                    val isSelected = index == selectedIndex
                    clickableCard(
                        onClick = { onSelectSession(session.id) },
                        colors = if (isSelected) {
                            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        } else {
                            CardDefaults.cardColors()
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.extraSmall),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(Spacing.small),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = AppTextStyles.secondaryContent,
                            )
                            Column {
                                Text(text = session.title, style = AppTextStyles.body)
                            }
                        }
                    }
                }
            }
        },
    )
}
