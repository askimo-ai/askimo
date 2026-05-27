/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents

@Composable
fun newDirectiveDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, content: String, applyToCurrent: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var applyToCurrent by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var contentError by remember { mutableStateOf<String?>(null) }

    // Pre-load error messages in composable context
    val nameRequiredError = stringResource("directive.edit.name.required")
    val contentRequiredError = stringResource("directive.edit.content.required")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .width(800.dp)
                .padding(16.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Title
                Text(
                    text = stringResource("directive.new.title"),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Text(
                    text = stringResource("directive.new.guidelines"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Name field
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = null
                    },
                    label = { Text(stringResource("directive.edit.name.label")) },
                    placeholder = { Text(stringResource("directive.new.name.placeholder")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    colors = AppComponents.outlinedTextFieldColors(),
                )

                // Content field
                OutlinedTextField(
                    value = content,
                    onValueChange = {
                        content = it
                        contentError = null
                    },
                    label = { Text(stringResource("directive.edit.content.label")) },
                    placeholder = { Text(stringResource("directive.new.content.placeholder")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp),
                    maxLines = 8,
                    isError = contentError != null,
                    supportingText = contentError?.let { { Text(it) } },
                    colors = AppComponents.outlinedTextFieldColors(),
                )

                // Apply to current session checkbox
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = applyToCurrent,
                        onCheckedChange = { applyToCurrent = it },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    )
                    Text(
                        text = stringResource("directive.new.apply.current"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    secondaryButton(
                        onClick = onDismiss,
                    ) {
                        Text(stringResource("settings.cancel"))
                    }

                    primaryButton(
                        onClick = {
                            // Validate inputs
                            var hasError = false

                            if (name.isBlank()) {
                                nameError = nameRequiredError
                                hasError = true
                            }

                            if (content.isBlank()) {
                                contentError = contentRequiredError
                                hasError = true
                            }

                            if (!hasError) {
                                onConfirm(name.trim(), content.trim(), applyToCurrent)
                            }
                        },
                    ) {
                        Text(stringResource("directive.new.create"))
                    }
                }
            }
        }
    }
}
