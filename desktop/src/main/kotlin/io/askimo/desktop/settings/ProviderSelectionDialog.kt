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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.providers.ModelDTO
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ProviderConfigField
import io.askimo.core.providers.ProviderRegistry
import io.askimo.core.providers.filterChatModels
import io.askimo.ui.common.components.linkButton
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.clickableCard
import java.awt.Desktop
import java.net.URI

// ── Dialog: Provider wizard (add & edit) ──────────────────────────────────────────────────

@Composable
fun providerWizardDialog(viewModel: SettingsViewModel) {
    val title = when (viewModel.wizardStep) {
        WizardStep.MODEL -> stringResource("settings.model.select.title")

        WizardStep.TYPE_PICKER -> stringResource("provider.type.picker.title")

        WizardStep.CONFIG -> {
            if (viewModel.isAddingNewInstance) {
                stringResource("provider.configure.new.title", ProviderRegistry.getProviderDisplayName(viewModel.selectedProvider!!))
            } else {
                stringResource("provider.edit.title", viewModel.editingInstance?.displayName ?: "")
            }
        }
    }

    // ── Model-picker search state hoisted here so stickyHeader can reference it.
    // remember(wizardStep) resets both values whenever the user navigates to a new step.
    var searchQuery by remember(viewModel.wizardStep) { mutableStateOf("") }
    var showAll by remember(viewModel.wizardStep) { mutableStateOf(false) }

    val chatModels = remember(viewModel.availableModels) { filterChatModels(viewModel.availableModels) }
    val isFiltered = chatModels.size < viewModel.availableModels.size
    val displayModels = if (showAll || !isFiltered) viewModel.availableModels else chatModels
    val filteredModels = remember(displayModels, searchQuery) {
        if (searchQuery.isBlank()) displayModels
        else displayModels.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
                it.modelId.contains(searchQuery, ignoreCase = true)
        }
    }

    val modelPickerReady = viewModel.wizardStep == WizardStep.MODEL &&
        !viewModel.isLoadingModels &&
        viewModel.modelError == null &&
        viewModel.availableModels.isNotEmpty()

    AppComponents.scaffoldDialog(
        onDismissRequest = { viewModel.closeProviderWizard() },
        onCloseRequest = { viewModel.closeProviderWizard() },
        title = {
            Text(text = title, style = MaterialTheme.typography.headlineSmall)
        },
        stickyHeader = if (modelPickerReady) {
            {
                val providerDisplayName = viewModel.selectedProvider
                    ?.let { ProviderRegistry.getProviderDisplayName(it) } ?: ""
                Text(
                    text = stringResource("settings.model.select", providerDisplayName),
                    style = MaterialTheme.typography.bodyMedium,
                )

                viewModel.pendingModelForNewProvider?.let { selectedModel ->
                    Card(modifier = Modifier.fillMaxWidth(), colors = AppComponents.surfaceVariantCardColors()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(Spacing.large),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = stringResource("settings.model.selected"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(text = selectedModel, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource("settings.model.search.placeholder")) },
                    label = { Text(stringResource("settings.model.search")) },
                    singleLine = true,
                    colors = AppComponents.outlinedTextFieldColors(),
                )

                if (searchQuery.isNotBlank() && filteredModels.isNotEmpty()) {
                    Text(
                        text = stringResource("settings.model.filtered", filteredModels.size, displayModels.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else null,
        actions = {
            // Left side: testing indicator + Cancel
            if (viewModel.wizardStep == WizardStep.CONFIG && viewModel.isFetchingModelsForConfig) {
                val infiniteTransition = rememberInfiniteTransition(label = "dots")
                val tick by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 4f,
                    animationSpec = infiniteRepeatable(animation = tween(1_200, easing = LinearEasing)),
                    label = "dots_tick",
                )
                val dots = ".".repeat((tick.toInt() % 4).coerceAtLeast(1))
                Text(
                    text = stringResource("settings.test.connection.testing") + dots,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(Spacing.small))
            }
            secondaryButton(onClick = { viewModel.closeProviderWizard() }) {
                Text(stringResource("settings.cancel"))
            }

            Spacer(Modifier.weight(1f))

            // Right side: navigation + primary action
            when (viewModel.wizardStep) {
                WizardStep.MODEL -> {
                    secondaryButton(onClick = { viewModel.wizardBack() }) {
                        Text(stringResource("action.back"))
                    }
                    Spacer(Modifier.width(Spacing.small))
                    primaryButton(
                        onClick = { viewModel.saveProvider() },
                        enabled = viewModel.pendingModelForNewProvider != null,
                    ) {
                        Text(stringResource("settings.save"))
                    }
                }

                WizardStep.CONFIG -> {
                    secondaryButton(onClick = { viewModel.wizardBack() }) {
                        Text(stringResource("action.back"))
                    }
                    Spacer(Modifier.width(Spacing.small))

                    if (viewModel.isAddingNewInstance) {
                        // Add mode: explicit Next button, enabled once connection verified
                        primaryButton(
                            onClick = { viewModel.advanceToModelPicker() },
                            enabled = viewModel.connectionTestSuccess && !viewModel.isFetchingModelsForConfig,
                        ) {
                            if (viewModel.isFetchingModelsForConfig) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(Spacing.small))
                            }
                            Text(stringResource("action.next"))
                        }
                    } else {
                        // Edit mode: save directly
                        primaryButton(
                            onClick = { viewModel.saveProvider() },
                            enabled = !viewModel.isTestingConnection && !viewModel.isFetchingModelsForConfig,
                        ) {
                            if (viewModel.isTestingConnection) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(Spacing.small))
                            }
                            Text(stringResource("settings.save"))
                        }
                    }
                }

                WizardStep.TYPE_PICKER -> {
                    // No back button — type picker is the entry point
                }
            }
        },
    ) {
        when (viewModel.wizardStep) {
            WizardStep.TYPE_PICKER -> providerTypePickerScreen(viewModel)
            WizardStep.CONFIG -> instanceConfigScreen(viewModel)
            WizardStep.MODEL -> modelPickerScreen(
                viewModel = viewModel,
                filteredModels = filteredModels,
                searchQuery = searchQuery,
                isFiltered = isFiltered,
                showAll = showAll,
                onShowAll = { showAll = true },
            )
        }
    }
}

// ── Screen 2: Provider type picker ────────────────────────────────────────────────────────

@Composable
private fun providerTypePickerScreen(viewModel: SettingsViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Text(
            text = stringResource("provider.type.picker.prompt"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        viewModel.availableProviders.chunked(3).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                rowItems.forEach { providerType ->
                    val existingCount = viewModel.availableInstances.count { it.providerType == providerType }
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickableCard { viewModel.selectProviderTypeForNewInstance(providerType) }
                            .pointerHoverIcon(PointerIcon.Hand),
                        colors = AppComponents.surfaceVariantCardColors(),
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().padding(Spacing.medium)) {
                            Text(
                                text = ProviderRegistry.getProviderDisplayName(providerType),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(end = if (existingCount > 0) 16.dp else 0.dp),
                            )
                            if (existingCount > 0) {
                                Canvas(modifier = Modifier.size(8.dp).align(Alignment.TopEnd)) {
                                    drawCircle(color = Color(0xFF4CAF50))
                                }
                            }
                        }
                    }
                }

                repeat(3 - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

// ── Screen 3: Instance config form ────────────────────────────────────────────────────────

@Composable
private fun instanceConfigScreen(viewModel: SettingsViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.large),
    ) {
        // Instance display-name field
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            Text(text = stringResource("provider.instance.name.label"), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = if (viewModel.isAddingNewInstance) viewModel.newInstanceDisplayName else viewModel.editingInstanceDisplayName,
                onValueChange = {
                    if (viewModel.isAddingNewInstance) {
                        viewModel.updateNewInstanceDisplayName(it)
                    } else {
                        viewModel.updateEditingInstanceDisplayName(it)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = viewModel.displayNameError != null,
                placeholder = { Text(stringResource("provider.instance.name.placeholder")) },
                supportingText = viewModel.displayNameError?.let { error ->
                    { Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                },
                colors = AppComponents.outlinedTextFieldColors(),
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.small))

        // Provider config fields (API key, base URL, etc.)
        if (viewModel.providerConfigFields.isNotEmpty()) {
            Text(text = stringResource("provider.configure.prompt"), style = MaterialTheme.typography.titleMedium)

            viewModel.providerConfigFields.forEach { field ->
                when (field) {
                    is ProviderConfigField.InfoField -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(Spacing.medium),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Icon(Icons.Default.Info, contentDescription = "Info", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(text = field.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    else -> {
                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                            Text(text = field.label + if (field.required) " *" else "", style = MaterialTheme.typography.labelLarge)
                            Text(text = field.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            when (field) {
                                is ProviderConfigField.ApiKeyField -> {
                                    AppComponents.appSecretTextField(
                                        value = viewModel.providerFieldValues[field.name] ?: "",
                                        onValueChange = { viewModel.updateProviderField(field.name, it) },
                                        modifier = Modifier.fillMaxWidth(),
                                        placeholder = {
                                            Text(if (field.hasExistingValue) stringResource("provider.apikey.stored") else stringResource("provider.apikey.enter"))
                                        },
                                    )
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.small),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    ) {
                                        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                                            Text(text = stringResource("provider.apikey.security.message"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            linkButton(onClick = {
                                                try {
                                                    Desktop.getDesktop().browse(URI("https://$DOMAIN/security/"))
                                                } catch (_: Exception) {}
                                            }, modifier = Modifier.padding(0.dp)) {
                                                Text(text = stringResource("provider.apikey.security.link"), style = MaterialTheme.typography.bodySmall)
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
                }
            }

            // Help link
            viewModel.selectedProvider?.let { provider ->
                linkButton(
                    onClick = {
                        try {
                            Desktop.getDesktop().browse(URI("https://$DOMAIN/docs/desktop/providers/${provider.name.lowercase()}/"))
                        } catch (_: Exception) {}
                    },
                    modifier = Modifier.padding(0.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Help, contentDescription = "Help", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(Spacing.extraSmall))
                    Text(
                        text = stringResource("provider.setup.guide", ProviderRegistry.getProviderDisplayName(provider)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // Connection error
            if (viewModel.connectionError != null) {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(Spacing.medium), horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Warning, contentDescription = "Error", tint = MaterialTheme.colorScheme.error)
                        Column {
                            Text(text = viewModel.connectionError ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                            viewModel.connectionErrorHelp?.let {
                                Spacer(Modifier.height(Spacing.extraSmall))
                                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f))
                            }
                        }
                    }
                }
            }

            // Embedding model warning
            if (viewModel.embeddingModelWarning != null) {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Info, contentDescription = "Info", tint = MaterialTheme.colorScheme.tertiary)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = stringResource("settings.embedding.rag_feature_only"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                Spacer(Modifier.height(Spacing.extraSmall))
                                Text(text = viewModel.embeddingModelWarning ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.9f))
                            }
                        }
                        if (viewModel.canPullEmbeddingModel && viewModel.embeddingModelProvider == "OLLAMA") {
                            primaryButton(onClick = {
                                val baseUrl = viewModel.providerFieldValues["baseUrl"] ?: ""
                                if (baseUrl.isNotBlank()) viewModel.pullEmbeddingModel(ModelProvider.OLLAMA, baseUrl)
                            }, enabled = !viewModel.isCheckingEmbeddingModel) {
                                if (viewModel.isCheckingEmbeddingModel) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                    Spacer(Modifier.width(8.dp))
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

// ── Screen 4: Model picker ─────────────────────────────────────────────────────────────────

@Composable
private fun modelPickerScreen(
    viewModel: SettingsViewModel,
    filteredModels: List<ModelDTO>,
    searchQuery: String,
    isFiltered: Boolean,
    showAll: Boolean,
    onShowAll: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.large)) {
        when {
            viewModel.isLoadingModels -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
                    Text(text = stringResource("settings.model.loading"), modifier = Modifier.padding(start = Spacing.large))
                }
            }

            viewModel.modelError != null -> {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    Text(text = viewModel.modelError ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    viewModel.modelErrorHelp?.let {
                        Card(colors = AppComponents.surfaceVariantCardColors()) {
                            Text(text = it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(Spacing.medium))
                        }
                    }
                }
            }

            viewModel.availableModels.isEmpty() -> {
                Text(text = stringResource("settings.model.none"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            else -> {
                // Description, selected-model card and search field are in stickyHeader.
                // Only the model list scrolls here.
                if (filteredModels.isEmpty()) {
                    Text(
                        text = stringResource("settings.model.no.match"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.large),
                    )
                } else {
                    groupedModelListAsCards(
                        models = filteredModels,
                        selectedModelId = viewModel.pendingModelForNewProvider,
                        onModelClick = { viewModel.selectModelForNewProvider(it) },
                        showHeaders = true,
                    )
                }

                if (isFiltered && !showAll && searchQuery.isBlank()) {
                    linkButton(onClick = onShowAll, modifier = Modifier.padding(top = Spacing.small)) {
                        Text(text = "Show all ${viewModel.availableModels.size} models", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
