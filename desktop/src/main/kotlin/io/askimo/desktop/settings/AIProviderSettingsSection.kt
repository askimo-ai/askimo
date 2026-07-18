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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.config.AppConfig
import io.askimo.core.context.AppContext
import io.askimo.core.providers.ChatModelFactory
import io.askimo.core.providers.ModelDTO
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ProviderRegistry
import io.askimo.core.providers.ProviderSettings
import io.askimo.core.providers.SpecialModelType
import io.askimo.core.providers.filterModelsForType
import io.askimo.ui.common.components.linkButton
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
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

                // Model Config Card — only shown when a provider is selected
                viewModel.provider?.let { provider ->
                    providerModelConfigCard(provider)
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
private fun providerModelConfigCard(provider: ModelProvider) {
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
                value = getProviderUtilityModel(provider),
                placeholder = if (isLocalProvider) {
                    stringResource("settings.utility.models.uses.selected")
                } else {
                    stringResource("settings.model.placeholder.enter", "utility")
                },
                onClick = { showUtilityModelDialog = true },
            )

            if (showUtilityModelDialog) {
                providerModelTypePickerDialog(
                    provider = provider,
                    modelType = SpecialModelType.UTILITY,
                    currentValue = getProviderUtilityModel(provider),
                    onDismiss = { showUtilityModelDialog = false },
                    onSelect = { model ->
                        AppConfig.updateField("models.${provider.name.lowercase()}.utilityModel", model)
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
                value = getProviderVisionModel(provider),
                placeholder = stringResource("settings.model.placeholder.enter", "vision"),
                onClick = { showVisionModelDialog = true },
            )

            if (showVisionModelDialog) {
                providerModelTypePickerDialog(
                    provider = provider,
                    modelType = SpecialModelType.VISION,
                    currentValue = getProviderVisionModel(provider),
                    onDismiss = { showVisionModelDialog = false },
                    onSelect = { model ->
                        AppConfig.updateField("models.${provider.name.lowercase()}.visionModel", model)
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
                value = getProviderImageModel(provider),
                placeholder = stringResource("settings.model.placeholder.enter", "image"),
                onClick = { showImageModelDialog = true },
            )

            if (showImageModelDialog) {
                providerModelTypePickerDialog(
                    provider = provider,
                    modelType = SpecialModelType.IMAGE,
                    currentValue = getProviderImageModel(provider),
                    onDismiss = { showImageModelDialog = false },
                    onSelect = { model ->
                        AppConfig.updateField("models.${provider.name.lowercase()}.imageModel", model)
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
                    value = getProviderEmbeddingModel(provider),
                    placeholder = stringResource("settings.model.placeholder.enter", "embedding"),
                    onClick = { showEmbeddingModelDialog = true },
                )

                if (showEmbeddingModelDialog) {
                    providerModelTypePickerDialog(
                        provider = provider,
                        modelType = SpecialModelType.EMBEDDING,
                        currentValue = getProviderEmbeddingModel(provider),
                        onDismiss = { showEmbeddingModelDialog = false },
                        onSelect = { model ->
                            AppConfig.updateField("models.${provider.name.lowercase()}.embeddingModel", model)
                            showEmbeddingModelDialog = false
                        },
                    )
                }
            }
        }
    }
}

private fun getProviderUtilityModel(provider: ModelProvider): String = AppConfig.models[provider].utilityModel

private fun getProviderVisionModel(provider: ModelProvider): String = AppConfig.models[provider].visionModel

private fun getProviderImageModel(provider: ModelProvider): String = AppConfig.models[provider].imageModel

private fun getProviderEmbeddingModel(provider: ModelProvider): String = AppConfig.models[provider].embeddingModel

@Composable
private fun providerModelSelectorField(
    label: String,
    hint: String,
    value: String,
    placeholder: String,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )

        secondaryButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = value.ifBlank { placeholder },
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Normal,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource("settings.model.change.button"),
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun providerModelTypePickerDialog(
    provider: ModelProvider,
    modelType: SpecialModelType,
    currentValue: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var selectedModel by remember(currentValue) { mutableStateOf(currentValue.takeIf { it.isNotBlank() }) }
    var searchQuery by remember { mutableStateOf("") }
    var availableModels by remember { mutableStateOf<List<ModelDTO>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var errorHelp by remember { mutableStateOf<String?>(null) }
    var showAll by remember { mutableStateOf(false) }

    val appContext = remember { AppContext.getInstance() }

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
                val settings = appContext.params.instancesForType(provider).firstOrNull()?.settings
                    ?: factory.defaultSettings()

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

    AppComponents.alertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource("settings.model.select.title") + " (${modelType.label})",
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.large),
            ) {
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

                        Column(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                        ) {
                            if (filteredModels.isEmpty()) {
                                Text(text = stringResource("settings.model.no.match"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Spacing.large))
                            } else {
                                if (searchQuery.isNotBlank()) {
                                    Text(text = stringResource("settings.model.filtered", filteredModels.size, displayModels.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Spacing.small))
                                }
                                groupedModelListAsCards(models = filteredModels, selectedModelId = selectedModel, onModelClick = { selectedModel = it }, showHeaders = true)
                            }

                            // "Show all" toggle — visible only when list is filtered and user hasn't expanded yet
                            if (isFiltered && !showAll && searchQuery.isBlank()) {
                                linkButton(onClick = { showAll = true }, modifier = Modifier.padding(top = Spacing.small)) {
                                    Text(text = "Show all ${availableModels.size} models", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            primaryButton(onClick = { selectedModel?.let { onSelect(it) } }, enabled = selectedModel != null && !isLoading) {
                Text(stringResource("action.save"))
            }
        },
        dismissButton = {
            secondaryButton(onClick = onDismiss) { Text(stringResource("action.cancel")) }
        },
    )
}
