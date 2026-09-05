/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import io.askimo.core.providers.SettingField
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun settingsConfigDialog(
    viewModel: AIProviderViewModel,
    onDismiss: () -> Unit,
) {
    AppComponents.alertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource("settings.configure.title", viewModel.provider?.name ?: ""),
                style = AppTextStyles.pageTitle,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.large),
            ) {
                viewModel.settingsFields.forEach { field ->
                    when (field) {
                        is SettingField.TextField -> {
                            OutlinedTextField(
                                value = field.value,
                                onValueChange = { viewModel.updateSettingsField(field.name, it) },
                                label = { Text(field.label) },
                                placeholder = { Text(field.description) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = if (field.isPassword) {
                                    PasswordVisualTransformation()
                                } else {
                                    VisualTransformation.None
                                },
                                colors = AppColors.outlinedTextFieldColors(),
                            )
                        }

                        is SettingField.EnumField -> {
                            var expanded by remember { mutableStateOf(false) }

                            ExposedDropdownMenuBox(
                                expanded = expanded,
                                onExpandedChange = { expanded = it },
                            ) {
                                OutlinedTextField(
                                    value = field.options.find { it.value == field.value }?.label ?: field.value,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text(field.label) },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                                    colors = AppColors.outlinedTextFieldColors(),
                                )
                                ExposedDropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false },
                                ) {
                                    field.options.forEach { option ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(
                                                        text = option.label,
                                                        style = AppTextStyles.body,
                                                    )
                                                    Text(
                                                        text = option.description,
                                                        style = AppTextStyles.caption,
                                                    )
                                                }
                                            },
                                            onClick = {
                                                viewModel.updateSettingsField(field.name, option.value)
                                                expanded = false
                                            },
                                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                                        )
                                    }
                                }
                            }
                        }

                        is SettingField.NumberField -> {
                            OutlinedTextField(
                                value = field.value.toString(),
                                onValueChange = { viewModel.updateSettingsField(field.name, it) },
                                label = { Text(field.label) },
                                placeholder = { Text(field.description) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = AppColors.outlinedTextFieldColors(),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    viewModel.closeSettingsDialog()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) {
                Text(stringResource("action.done"))
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) {
                Text(stringResource("action.cancel"))
            }
        },
    )
}
