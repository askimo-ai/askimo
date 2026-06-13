/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ProviderConfigField
import io.askimo.ui.common.components.linkButton
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppComponents.alertDialog
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.clickableCard
import java.awt.Desktop
import java.net.URI

@Composable
fun providerSelectionDialog(
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    alertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (viewModel.showModelSelectionInProviderDialog) {
                    stringResource("settings.model.select.title")
                } else if (viewModel.isInitialSetup) {
                    stringResource("provider.select.title")
                } else {
                    stringResource("provider.change.title")
                },
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                if (viewModel.showModelSelectionInProviderDialog) {
                    var searchQuery by remember { mutableStateOf("") }

                    val filteredModels = remember(viewModel.availableModels, searchQuery) {
                        if (searchQuery.isBlank()) {
                            viewModel.availableModels
                        } else {
                            viewModel.availableModels.filter {
                                it.displayName.contains(searchQuery, ignoreCase = true) ||
                                    it.modelId.contains(searchQuery, ignoreCase = true)
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(Spacing.large),
                    ) {
                        when {
                            viewModel.isLoadingModels -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = stringResource("settings.model.loading"),
                                        modifier = Modifier.padding(start = Spacing.large),
                                    )
                                }
                            }

                            viewModel.modelError != null -> {
                                Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                                    Text(
                                        text = viewModel.modelError ?: "",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    viewModel.modelErrorHelp?.let { helpText ->
                                        Card(colors = AppComponents.surfaceVariantCardColors()) {
                                            Text(
                                                text = helpText,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(Spacing.medium),
                                            )
                                        }
                                    }
                                }
                            }

                            viewModel.availableModels.isEmpty() -> {
                                Text(
                                    text = stringResource("settings.model.none"),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            else -> {
                                Text(
                                    text = stringResource("settings.model.select", viewModel.selectedProvider?.name ?: ""),
                                    style = MaterialTheme.typography.bodyMedium,
                                )

                                // Selected model display (if any)
                                if (viewModel.pendingModelForNewProvider != null) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = AppComponents.surfaceVariantCardColors(),
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(Spacing.large),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = stringResource("settings.model.selected"),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                Text(
                                                    text = viewModel.pendingModelForNewProvider ?: "",
                                                    style = MaterialTheme.typography.bodyLarge,
                                                )
                                            }
                                        }
                                    }
                                }

                                // Simple search field
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text(stringResource("settings.model.search.placeholder")) },
                                    label = { Text(stringResource("settings.model.search")) },
                                    singleLine = true,
                                    colors = AppComponents.outlinedTextFieldColors(),
                                )

                                // Filtered models list
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 400.dp)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    if (filteredModels.isEmpty()) {
                                        Text(
                                            text = stringResource("settings.model.no.match"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(Spacing.large),
                                        )
                                    } else {
                                        if (searchQuery.isNotBlank()) {
                                            Text(
                                                text = stringResource("settings.model.filtered", filteredModels.size, viewModel.availableModels.size),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(bottom = Spacing.small),
                                            )
                                        }

                                        // Display grouped models using shared component
                                        groupedModelListAsCards(
                                            models = filteredModels,
                                            selectedModelId = viewModel.pendingModelForNewProvider,
                                            onModelClick = { model ->
                                                viewModel.selectModelForNewProvider(model)
                                            },
                                            showHeaders = true,
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Steps 1 & 2: Provider selection and configuration
                    var providerDropdownExpanded by remember { mutableStateOf(false) }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = AppComponents.bannerCardColors(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.large),
                            verticalArrangement = Arrangement.spacedBy(Spacing.large),
                        ) {
                            // Step 1: Provider selection dropdown
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                            ) {
                                Text(
                                    text = stringResource("provider.select.prompt"),
                                    style = MaterialTheme.typography.titleMedium,
                                )

                                Box(modifier = Modifier.fillMaxWidth()) {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickableCard { providerDropdownExpanded = true },
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surface,
                                        ),
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(Spacing.medium),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = viewModel.selectedProvider?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: stringResource("provider.choose.placeholder"),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                            Icon(
                                                Icons.Default.Edit,
                                                contentDescription = "Select provider",
                                                tint = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                    }

                                    AppComponents.dropdownMenu(
                                        expanded = providerDropdownExpanded,
                                        onDismissRequest = { providerDropdownExpanded = false },
                                    ) {
                                        viewModel.availableProviders.forEach { provider ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = provider.name.lowercase().replaceFirstChar { it.uppercase() },
                                                        style = MaterialTheme.typography.bodyLarge,
                                                    )
                                                },
                                                onClick = {
                                                    viewModel.selectProviderForChange(provider)
                                                    providerDropdownExpanded = false
                                                },
                                                leadingIcon = if (viewModel.selectedProvider == provider) {
                                                    {
                                                        Icon(
                                                            Icons.Default.CheckCircle,
                                                            contentDescription = "Selected",
                                                            tint = MaterialTheme.colorScheme.onSurface,
                                                        )
                                                    }
                                                } else {
                                                    null
                                                },
                                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                            )
                                        }
                                    }
                                }
                            }

                            // Help link for provider setup instructions
                            if (viewModel.selectedProvider != null) {
                                linkButton(
                                    onClick = {
                                        try {
                                            val providerName = viewModel.selectedProvider?.name?.lowercase() ?: return@linkButton
                                            Desktop.getDesktop().browse(
                                                URI("https://$DOMAIN/docs/desktop/providers/$providerName/"),
                                            )
                                        } catch (_: Exception) {
                                            // Silently fail if browser cannot be opened
                                        }
                                    },
                                    modifier = Modifier.padding(0.dp),
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Help,
                                        contentDescription = "Help",
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(Spacing.extraSmall))
                                    Text(
                                        text = stringResource("provider.setup.guide", viewModel.selectedProvider?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }

                            // Step 2: Configuration fields (shown after provider is selected)
                            if (viewModel.selectedProvider != null && viewModel.providerConfigFields.isNotEmpty()) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.small))

                                Text(
                                    text = stringResource("provider.configure.prompt"),
                                    style = MaterialTheme.typography.titleMedium,
                                )

                                viewModel.providerConfigFields.forEach { field ->
                                    when (field) {
                                        is ProviderConfigField.InfoField -> {
                                            Card(
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                                ),
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(Spacing.medium),
                                                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                                                    verticalAlignment = Alignment.Top,
                                                ) {
                                                    Icon(
                                                        Icons.Default.Info,
                                                        contentDescription = "Info",
                                                        modifier = Modifier.size(16.dp),
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                    Text(
                                                        text = field.message,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }

                                        else -> {
                                            Column(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                                            ) {
                                                Text(
                                                    text = field.label + if (field.required) " *" else "",
                                                    style = MaterialTheme.typography.labelLarge,
                                                )
                                                Text(
                                                    text = field.description,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )

                                                when (field) {
                                                    is ProviderConfigField.ApiKeyField -> {
                                                        OutlinedTextField(
                                                            value = viewModel.providerFieldValues[field.name] ?: "",
                                                            onValueChange = { viewModel.updateProviderField(field.name, it) },
                                                            modifier = Modifier.fillMaxWidth(),
                                                            singleLine = true,
                                                            visualTransformation = PasswordVisualTransformation(),
                                                            placeholder = {
                                                                Text(
                                                                    if (field.hasExistingValue) {
                                                                        stringResource("provider.apikey.stored")
                                                                    } else {
                                                                        stringResource("provider.apikey.enter")
                                                                    },
                                                                )
                                                            },
                                                            trailingIcon = {
                                                                Row {
                                                                    if (field.hasExistingValue) {
                                                                        Icon(
                                                                            Icons.Default.CheckCircle,
                                                                            contentDescription = stringResource("provider.apikey.already.stored"),
                                                                            tint = MaterialTheme.colorScheme.onSurface,
                                                                        )
                                                                    }
                                                                    Spacer(modifier = Modifier.width(8.dp))
                                                                    Icon(Icons.Default.Lock, contentDescription = "Password")
                                                                }
                                                            },
                                                            colors = AppComponents.outlinedTextFieldColors(),
                                                        )

                                                        // Security assurance message
                                                        Card(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(top = Spacing.small),
                                                            colors = CardDefaults.cardColors(
                                                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                                            ),
                                                        ) {
                                                            Column(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .padding(Spacing.medium),
                                                                verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                                                            ) {
                                                                Text(
                                                                    text = stringResource("provider.apikey.security.message"),
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                )
                                                                linkButton(
                                                                    onClick = {
                                                                        try {
                                                                            Desktop.getDesktop().browse(
                                                                                URI("https://$DOMAIN/security/"),
                                                                            )
                                                                        } catch (_: Exception) {
                                                                            // Silently fail if browser cannot be opened
                                                                        }
                                                                    },
                                                                    modifier = Modifier.padding(0.dp),
                                                                ) {
                                                                    Text(
                                                                        text = stringResource("provider.apikey.security.link"),
                                                                        style = MaterialTheme.typography.bodySmall,
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }

                                                    is ProviderConfigField.BaseUrlField -> {
                                                        OutlinedTextField(
                                                            value = viewModel.providerFieldValues[field.name] ?: "",
                                                            onValueChange = { viewModel.updateProviderField(field.name, it) },
                                                            modifier = Modifier.fillMaxWidth(),
                                                            singleLine = true,
                                                            placeholder = { Text(stringResource("settings.placeholder.baseurl")) },
                                                            colors = AppComponents.outlinedTextFieldColors(),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } // when (field)
                                }

                                // Connection error display
                                if (viewModel.connectionError != null) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer,
                                        ),
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(Spacing.medium),
                                            verticalArrangement = Arrangement.spacedBy(Spacing.small),
                                        ) {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                                                verticalAlignment = Alignment.Top,
                                            ) {
                                                Icon(
                                                    Icons.Default.Warning,
                                                    contentDescription = "Error",
                                                    tint = MaterialTheme.colorScheme.error,
                                                )
                                                Column {
                                                    Text(
                                                        text = viewModel.connectionError ?: "",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                                    )
                                                    if (viewModel.connectionErrorHelp != null) {
                                                        Spacer(modifier = Modifier.height(Spacing.extraSmall))
                                                        Text(
                                                            text = viewModel.connectionErrorHelp ?: "",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // Embedding model warning (only relevant for RAG features)
                                if (viewModel.embeddingModelWarning != null) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                        ),
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(Spacing.medium),
                                            verticalArrangement = Arrangement.spacedBy(Spacing.small),
                                        ) {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                                                verticalAlignment = Alignment.Top,
                                            ) {
                                                Icon(
                                                    Icons.Default.Info,
                                                    contentDescription = "Info",
                                                    tint = MaterialTheme.colorScheme.tertiary,
                                                )
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = stringResource("settings.embedding.rag_feature_only"),
                                                        style = MaterialTheme.typography.titleSmall,
                                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                    )
                                                    Spacer(modifier = Modifier.height(Spacing.extraSmall))
                                                    Text(
                                                        text = viewModel.embeddingModelWarning ?: "",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.9f),
                                                    )
                                                }
                                            }

                                            // Show download button for Ollama if model can be pulled
                                            if (viewModel.canPullEmbeddingModel && viewModel.embeddingModelProvider == "OLLAMA") {
                                                primaryButton(
                                                    onClick = {
                                                        val baseUrl = viewModel.providerFieldValues["baseUrl"] ?: ""
                                                        if (baseUrl.isNotBlank()) {
                                                            viewModel.pullEmbeddingModel(ModelProvider.OLLAMA, baseUrl)
                                                        }
                                                    },
                                                    enabled = !viewModel.isCheckingEmbeddingModel,
                                                ) {
                                                    if (viewModel.isCheckingEmbeddingModel) {
                                                        CircularProgressIndicator(
                                                            modifier = Modifier.size(16.dp),
                                                            strokeWidth = 2.dp,
                                                            color = MaterialTheme.colorScheme.onPrimary,
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                    }
                                                    Text(stringResource("settings.embedding.download_model"))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } // End of else block for provider configuration
            } // end Column
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (viewModel.showModelSelectionInProviderDialog) {
                    secondaryButton(
                        onClick = { viewModel.backToProviderConfiguration() },
                    ) {
                        Text(stringResource("action.back"))
                    }
                }

                // Save button
                primaryButton(
                    onClick = onSave,
                    enabled = if (viewModel.showModelSelectionInProviderDialog) {
                        viewModel.pendingModelForNewProvider != null
                    } else {
                        false
                    },
                ) {
                    Text(stringResource("settings.save"))
                }
            }
        },
        dismissButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!viewModel.showModelSelectionInProviderDialog && viewModel.isFetchingModelsForConfig) {
                    val infiniteTransition = rememberInfiniteTransition(label = "dots")
                    val tick by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 4f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 1_200, easing = LinearEasing),
                        ),
                        label = "dots_tick",
                    )
                    val dots = ".".repeat((tick.toInt() % 4).coerceAtLeast(1))
                    Text(
                        text = stringResource("settings.test.connection.testing") + dots,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                secondaryButton(onClick = onDismiss) {
                    Text(stringResource("settings.cancel"))
                }
            }
        },
    )
}
