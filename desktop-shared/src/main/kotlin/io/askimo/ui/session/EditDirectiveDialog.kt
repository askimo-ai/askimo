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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing

@Composable
fun editDirectiveDialog(
    initialName: String,
    initialContent: String,
    onDismiss: () -> Unit,
    onConfirm: (newName: String, newContent: String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var content by remember { mutableStateOf(initialContent) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var contentError by remember { mutableStateOf<String?>(null) }

    val nameRequiredError = stringResource("directive.edit.name.required")
    val contentRequiredError = stringResource("directive.edit.content.required")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .width(800.dp)
                .padding(Spacing.large),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(Spacing.extraLarge),
                verticalArrangement = Arrangement.spacedBy(Spacing.large),
            ) {
                Text(
                    text = stringResource("directive.edit.title"),
                    style = AppTextStyles.pageTitle,
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = null
                    },
                    label = { Text(stringResource("directive.edit.name.label")) },
                    placeholder = { Text(stringResource("directive.edit.name.placeholder")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    colors = AppColors.outlinedTextFieldColors(),
                )

                OutlinedTextField(
                    value = content,
                    onValueChange = {
                        content = it
                        contentError = null
                    },
                    label = { Text(stringResource("directive.edit.content.label")) },
                    placeholder = { Text(stringResource("directive.edit.content.placeholder")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp),
                    maxLines = 8,
                    isError = contentError != null,
                    supportingText = contentError?.let { { Text(it) } },
                    colors = AppColors.outlinedTextFieldColors(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    secondaryButton(onClick = onDismiss) {
                        Text(stringResource("settings.cancel"))
                    }

                    primaryButton(
                        onClick = {
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
                                onConfirm(name.trim(), content.trim())
                            }
                        },
                    ) {
                        Text(stringResource("directive.edit.save"))
                    }
                }
            }
        }
    }
}
