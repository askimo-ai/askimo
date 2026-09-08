/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.shell

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.settings.keyboardShortcutsList

/**
 * Lightweight, app-wide "cheat sheet" popup listing every keyboard shortcut — opened via
 * [io.askimo.ui.common.keymap.KeyMapManager.AppShortcut.SHOW_KEYBOARD_SHORTCUTS] (Cmd/Ctrl+/)
 * or the Help menu's "Keyboard Shortcuts" entry, from any screen.
 *
 * Exists specifically so keyboard-only and screen-reader users can discover every available
 * shortcut without leaving their current context to navigate into Settings
 */
@Composable
fun keyboardShortcutsDialog(onDismiss: () -> Unit) {
    var searchQuery by remember { mutableStateOf("") }

    AppComponents.scaffoldDialog(
        onDismissRequest = onDismiss,
        onCloseRequest = onDismiss,
        width = 640.dp,
        maxHeightFraction = 0.8f,
        title = {
            Text(
                text = stringResource("settings.shortcuts"),
                style = AppTextStyles.pageTitle,
            )
        },
    ) {
        keyboardShortcutsList(
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
        )
    }
}
