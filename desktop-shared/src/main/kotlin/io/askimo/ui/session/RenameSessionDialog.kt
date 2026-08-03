/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.session

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles

/**
 * A styled dialog for renaming a chat session.
 *
 * @param currentTitle The current title of the session
 * @param onDismiss Callback when the dialog is dismissed
 * @param onRename Callback when a new title is confirmed, receives the new title
 */
@Composable
fun renameSessionDialog(
    currentTitle: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var newTitle by remember { mutableStateOf(currentTitle) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    val emptyErrorMessage = stringResource("session.rename.error.empty")

    // Extract rename logic to reuse in button and Enter key handler
    val performRename = {
        val trimmedTitle = newTitle.trim()
        if (trimmedTitle.isEmpty()) {
            error = emptyErrorMessage
        } else if (trimmedTitle == currentTitle) {
            onDismiss()
        } else {
            onRename(trimmedTitle)
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AppComponents.scaffoldDialog(
        onDismissRequest = onDismiss,
        onCloseRequest = onDismiss,
        width = 700.dp,
        title = {
            Text(
                text = stringResource("session.rename.title"),
                style = AppTextStyles.pageTitle,
            )
        },
        content = {
            OutlinedTextField(
                value = newTitle,
                onValueChange = {
                    newTitle = it
                    error = null
                },
                label = { Text(stringResource("session.rename.field.label")) },
                placeholder = { Text(stringResource("session.rename.field.placeholder")) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                colors = AppComponents.outlinedTextFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { performRename() }),
            )
        },
        actions = {
            secondaryButton(onClick = onDismiss) {
                Text(stringResource("action.cancel"))
            }
            Spacer(Modifier.width(8.dp))
            primaryButton(
                onClick = performRename,
                enabled = newTitle.trim().isNotEmpty(),
            ) {
                Text(stringResource("action.rename"))
            }
        },
    )
}
