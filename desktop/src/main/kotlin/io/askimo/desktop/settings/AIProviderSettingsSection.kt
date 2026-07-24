/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.settings

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.providers.ChatModelFactory
import io.askimo.core.providers.ModelDTO
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ProviderInstance
import io.askimo.core.providers.ProviderRegistry
import io.askimo.core.providers.ProviderSettings
import io.askimo.core.providers.SettingField
import io.askimo.core.providers.SpecialModelType
import io.askimo.core.providers.filterModelsForType
import io.askimo.ui.common.components.linkButton
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.clickableCard
import io.askimo.ui.common.ui.themedTooltip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI

@Composable
fun aiProviderSettingsSection(viewModel: SettingsViewModel) {
    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = ThemePreferences.CONTENT_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(start = 24.dp, top = 24.dp, bottom = 24.dp, end = 36.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.large),
            ) {
                Text(
                    text = stringResource("settings.ai.provider"),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(bottom = Spacing.small),
                )

                // Active provider card — Edit current instance or Add a new one
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppComponents.bannerCardColors(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.large),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        // Summary of active configuration
                        Column(
                            modifier = Modifier.weight(1f).padding(end = Spacing.medium),
                            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                        ) {
                            Text(
                                text = viewModel.instanceDisplayName.ifBlank {
                                    viewModel.provider?.name ?: stringResource("provider.not.set")
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            if (viewModel.model.isNotBlank()) {
                                Text(
                                    text = viewModel.model,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                                )
                            }
                            viewModel.settingsDescription.forEach { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                                )
                            }
                        }

                        // Actions
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                            val active = viewModel.activeInstance
                            if (active != null) {
                                secondaryButton(onClick = { viewModel.openEditProviderWizard(active) }) {
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(Spacing.extraSmall))
                                    Text(stringResource("settings.change.button"))
                                }
                            }
                            secondaryButton(onClick = { viewModel.openAddProviderWizard() }) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(Spacing.extraSmall))
                                Text(stringResource("provider.add.new"))
                            }
                        }
                    }
                }

                // Model Config Card — only shown when a provider instance is active
                viewModel.activeInstanceState?.let { instance ->
                    providerModelConfigCard(instance, viewModel)
                }
            } // end inner content Column
        } // end scrollable outer Column

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            style = AppComponents.scrollbarStyle(),
        )
    }
}

@Composable
private fun providerModelConfigCard(instance: ProviderInstance, viewModel: SettingsViewModel) {
    val provider = instance.providerType
    val isLocalProvider = provider in setOf(
        ModelProvider.OLLAMA,
        ModelProvider.DOCKER,
        ModelProvider.LOCALAI,
        ModelProvider.LMSTUDIO,
    )
    val supportsEmbedding = provider in setOf(
        ModelProvider.OPENAI,
        ModelProvider.GEMINI,
        ModelProvider.OLLAMA,
        ModelProvider.DOCKER,
        ModelProvider.LOCALAI,
        ModelProvider.LMSTUDIO,
        ModelProvider.OPENAI_COMPATIBLE,
    )

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource("settings.provider.model.config.title"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                linkButton(
                    onClick = {
                        try {
                            if (Desktop.isDesktopSupported()) {
                                Desktop.getDesktop().browse(URI("https://$DOMAIN/docs/desktop/ai-model-configuration/"))
                            }
                        } catch (_: Exception) {}
                    },
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource("settings.provider.model.config.guide"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }

            Text(
                text = stringResource("settings.provider.model.config.description"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            )

            // Utility model
            var showUtilityModelDialog by remember { mutableStateOf(false) }
            providerModelSelectorField(
                label = stringResource("settings.utility.models.title"),
                hint = if (isLocalProvider) {
                    stringResource("settings.utility.models.local.note", provider.name)
                } else {
                    stringResource("settings.provider.model.utility.hint")
                },
                value = instance.settings.utilityModel,
                placeholder = if (isLocalProvider) {
                    stringResource("settings.utility.models.uses.selected")
                } else {
                    stringResource("settings.model.placeholder.enter", "utility")
                },
                onClick = { showUtilityModelDialog = true },
            )

            if (showUtilityModelDialog) {
                providerModelTypePickerDialog(
                    instance = instance,
                    modelType = SpecialModelType.UTILITY,
                    currentValue = instance.settings.utilityModel,
                    onDismiss = { showUtilityModelDialog = false },
                    onSelect = { model ->
                        viewModel.updateInstanceModelOverride(instance.id, SettingField.UTILITY_MODEL, model)
                        showUtilityModelDialog = false
                    },
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.15f))

            // Vision model
            var showVisionModelDialog by remember { mutableStateOf(false) }
            providerModelSelectorField(
                label = stringResource("settings.vision.models.title"),
                hint = stringResource("settings.provider.model.vision.hint"),
                value = instance.settings.visionModel,
                placeholder = stringResource("settings.model.placeholder.enter", "vision"),
                onClick = { showVisionModelDialog = true },
            )

            if (showVisionModelDialog) {
                providerModelTypePickerDialog(
                    instance = instance,
                    modelType = SpecialModelType.VISION,
                    currentValue = instance.settings.visionModel,
                    onDismiss = { showVisionModelDialog = false },
                    onSelect = { model ->
                        viewModel.updateInstanceModelOverride(instance.id, SettingField.VISION_MODEL, model)
                        showVisionModelDialog = false
                    },
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.15f))

            // Image model
            var showImageModelDialog by remember { mutableStateOf(false) }
            providerModelSelectorField(
                label = stringResource("settings.image.models.title"),
                hint = stringResource("settings.provider.model.image.hint"),
                value = instance.settings.imageModel,
                placeholder = stringResource("settings.model.placeholder.enter", "image"),
                onClick = { showImageModelDialog = true },
            )

            if (showImageModelDialog) {
                providerModelTypePickerDialog(
                    instance = instance,
                    modelType = SpecialModelType.IMAGE,
                    currentValue = instance.settings.imageModel,
                    onDismiss = { showImageModelDialog = false },
                    onSelect = { model ->
                        viewModel.updateInstanceModelOverride(instance.id, SettingField.IMAGE_MODEL, model)
                        showImageModelDialog = false
                    },
                )
            }

            // Embedding model — only for supported providers
            if (supportsEmbedding) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.15f))

                var showEmbeddingModelDialog by remember { mutableStateOf(false) }
                providerModelSelectorField(
                    label = stringResource("settings.rag.embedding.models"),
                    hint = stringResource("settings.provider.model.embedding.hint"),
                    value = instance.settings.embeddingModel,
                    placeholder = stringResource("settings.model.placeholder.enter", "embedding"),
                    onClick = { showEmbeddingModelDialog = true },
                )

                if (showEmbeddingModelDialog) {
                    providerModelTypePickerDialog(
                        instance = instance,
                        modelType = SpecialModelType.EMBEDDING,
                        currentValue = instance.settings.embeddingModel,
                        onDismiss = { showEmbeddingModelDialog = false },
                        onSelect = { model ->
                            viewModel.updateInstanceModelOverride(instance.id, SettingField.EMBEDDING_MODEL, model)
                            showEmbeddingModelDialog = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun providerModelSelectorField(
    label: String,
    hint: String,
    value: String,
    placeholder: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(end = Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
            )
        }

        val displayText = value.ifBlank { placeholder }
        themedTooltip(text = if (value.isNotBlank()) value else "") {
            Card(
                modifier = Modifier
                    .widthIn(min = 160.dp, max = 300.dp)
                    .clickableCard { onClick() },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.medium),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = Spacing.small),
                    )
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource("settings.model.change.button"),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun providerModelTypePickerDialog(
    instance: ProviderInstance,
    modelType: SpecialModelType,
    currentValue: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val provider = instance.providerType
    var selectedModel by remember(currentValue) { mutableStateOf(currentValue.takeIf { it.isNotBlank() }) }
    var searchQuery by remember { mutableStateOf("") }
    var availableModels by remember { mutableStateOf<List<ModelDTO>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var errorHelp by remember { mutableStateOf<String?>(null) }
    var showAll by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isLoading = true
        errorMessage = null
        errorHelp = null
        withContext(Dispatchers.IO) {
            try {
                val factory = ProviderRegistry.getFactory(provider)
                if (factory == null) {
                    errorMessage = "No model factory found for ${provider.name}"
                    isLoading = false
                    return@withContext
                }
                // Use this specific instance's settings (not the first instance of the type).
                val settings = instance.settings

                @Suppress("UNCHECKED_CAST")
                val models = (factory as ChatModelFactory<ProviderSettings>).availableModels(settings)
                if (models.isEmpty()) {
                    errorMessage = "No models available for ${provider.name.lowercase()}"
                    errorHelp = factory.getNoModelsHelpText()
                } else {
                    availableModels = models
                }
            } catch (e: Exception) {
                errorMessage = "Failed to load models: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    val suggestedModels = remember(availableModels, modelType) { filterModelsForType(availableModels, modelType) }
    val isFiltered = suggestedModels.size < availableModels.size
    val displayModels = if (showAll || !isFiltered) availableModels else suggestedModels

    val filteredModels = remember(displayModels, searchQuery) {
        if (searchQuery.isBlank()) {
            displayModels
        } else {
            displayModels.filter {
                it.displayName.contains(searchQuery, ignoreCase = true) ||
                    it.modelId.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val modelsReady = !isLoading && errorMessage == null && availableModels.isNotEmpty()

    AppComponents.scaffoldDialog(
        onDismissRequest = onDismiss,
        onCloseRequest = onDismiss,
        title = {
            Text(
                text = stringResource("settings.model.select.title") + " (${modelType.label})",
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        stickyHeader = if (modelsReady) {
            {
                if (currentValue.isNotBlank()) {
                    Card(modifier = Modifier.fillMaxWidth(), colors = AppComponents.bannerCardColors()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(Spacing.large),
                            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                        ) {
                            Text(text = stringResource("settings.model.current"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text(text = currentValue, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }

                if (selectedModel != null && selectedModel != currentValue) {
                    Card(modifier = Modifier.fillMaxWidth(), colors = AppComponents.primaryCardColors()) {
                        Row(modifier = Modifier.fillMaxWidth().padding(Spacing.large), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = stringResource("settings.model.new"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text(text = selectedModel ?: "", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
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
        } else {
            null
        },
        actions = {
            secondaryButton(onClick = onDismiss) { Text(stringResource("action.cancel")) }
            Spacer(modifier = Modifier.width(Spacing.small))
            primaryButton(
                onClick = { selectedModel?.let { onSelect(it) } },
                enabled = selectedModel != null && !isLoading,
            ) {
                Text(stringResource("action.save"))
            }
        },
    ) {
        when {
            isLoading -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = stringResource("settings.model.loading"), modifier = Modifier.padding(start = Spacing.large))
                }
            }

            errorMessage != null -> {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    Text(text = errorMessage ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    errorHelp?.let { helpText ->
                        Card(colors = AppComponents.surfaceVariantCardColors()) {
                            Text(text = helpText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(Spacing.medium))
                        }
                    }
                }
            }

            availableModels.isEmpty() -> {
                Text(text = stringResource("settings.model.none"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            else -> {
                Text(text = stringResource("settings.model.change.description"), style = MaterialTheme.typography.bodyMedium)

                if (filteredModels.isEmpty()) {
                    Text(text = stringResource("settings.model.no.match"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Spacing.large))
                } else {
                    groupedModelListAsCards(models = filteredModels, selectedModelId = selectedModel, onModelClick = { selectedModel = it }, showHeaders = true)
                }

                if (isFiltered && !showAll && searchQuery.isBlank()) {
                    linkButton(onClick = { showAll = true }, modifier = Modifier.padding(top = Spacing.small)) {
                        Text(text = "Show all ${availableModels.size} models", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
