/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.askimo.core.config.AppConfig
import io.askimo.core.context.AppContext
import io.askimo.core.context.getConfigInfo
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.ModelChangedEvent
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.logging.logger
import io.askimo.core.providers.ChatModelFactory
import io.askimo.core.providers.LocalModelValidator
import io.askimo.core.providers.ModelAvailabilityResult
import io.askimo.core.providers.ModelDTO
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ProviderConfigField
import io.askimo.core.providers.ProviderRegistry
import io.askimo.core.providers.ProviderSettings
import io.askimo.core.providers.ProviderTestResult
import io.askimo.core.providers.SettingField
import io.askimo.ui.util.ErrorHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.text.isNotBlank
import kotlin.time.Duration.Companion.milliseconds

/**
 * ViewModel for managing settings state and configuration information.
 *
 * This class handles the business logic for the settings view, including:
 * - Fetching and exposing the current session configuration
 * - Provider information (OpenAI, Ollama, etc.)
 * - Model information
 * - Settings descriptions
 * - Model selection with validation
 *
 * @param scope The coroutine scope for this ViewModel
 * @param appContext The singleton Session instance injected by DI
 */
class SettingsViewModel(
    private val scope: CoroutineScope,
    private val appContext: AppContext,
) {
    private val log = logger<SettingsViewModel>()

    var provider by mutableStateOf<ModelProvider?>(null)
        private set

    var model by mutableStateOf("")
        private set

    var settingsDescription by mutableStateOf<List<String>>(emptyList())
        private set

    var showModelDialog by mutableStateOf(false)
        private set

    var availableModels by mutableStateOf<List<ModelDTO>>(emptyList())
        private set

    var isLoadingModels by mutableStateOf(false)
        private set

    var modelError by mutableStateOf<String?>(null)
        private set

    var modelErrorHelp by mutableStateOf<String?>(null)
        private set

    var showSuccessMessage by mutableStateOf(false)
        private set

    var successMessage by mutableStateOf("")
        private set

    var showSettingsDialog by mutableStateOf(false)
        private set

    var settingsFields by mutableStateOf<List<SettingField>>(emptyList())
        private set

    var showProviderDialog by mutableStateOf(false)
        private set

    var availableProviders by mutableStateOf<List<ModelProvider>>(emptyList())
        private set

    var selectedProvider by mutableStateOf<ModelProvider?>(null)
        private set

    var providerConfigFields by mutableStateOf<List<ProviderConfigField>>(emptyList())
        private set

    var providerFieldValues by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    var isTestingConnection by mutableStateOf(false)
        private set

    /**
     * True while the config screen is auto-fetching models in the background.
     * Used to show a loading indicator on the config screen instead of an explicit "Test Connection" button.
     */
    var isFetchingModelsForConfig by mutableStateOf(false)
        private set

    private var autoFetchJob: Job? = null

    var connectionError by mutableStateOf<String?>(null)
        private set

    var connectionErrorHelp by mutableStateOf<String?>(null)
        private set

    var connectionTestSuccess by mutableStateOf(false)
        private set

    var showModelSelectionInProviderDialog by mutableStateOf(false)
        private set

    var pendingModelForNewProvider by mutableStateOf<String?>(null)
        private set

    var isInitialSetup by mutableStateOf(false)
        private set

    var isCheckingEmbeddingModel by mutableStateOf(false)
        private set

    var embeddingModelWarning by mutableStateOf<String?>(null)
        private set

    var embeddingModelProvider by mutableStateOf<String?>(null)
        private set

    var canPullEmbeddingModel by mutableStateOf(false)
        private set

    init {
        loadConfiguration()

        scope.launch {
            EventBus.internalEvents.collect { event ->
                if (event is ModelChangedEvent) {
                    model = event.newModel
                }
            }
        }
    }

    /**
     * Load the current configuration from the session.
     */
    fun loadConfiguration() {
        val configInfo = appContext.getConfigInfo()
        provider = configInfo.provider
        model = configInfo.model
        settingsDescription = configInfo.settingsDescription
    }

    /**
     * Handle the "Change Provider" or "Select Provider" action.
     * Opens the provider selection dialog.
     * @param isInitialSetup true if this is the initial provider setup (no provider configured yet)
     */
    fun onChangeProvider(isInitialSetup: Boolean = false) {
        this.isInitialSetup = isInitialSetup
        availableProviders = ProviderRegistry.getSupportedProviders().toList()

        // Reset all dialog states to prevent showing cached screens
        connectionError = null
        connectionErrorHelp = null
        connectionTestSuccess = false
        showModelSelectionInProviderDialog = false
        pendingModelForNewProvider = null
        availableModels = emptyList()
        isLoadingModels = false
        modelError = null
        modelErrorHelp = null
        embeddingModelWarning = null
        embeddingModelProvider = null
        canPullEmbeddingModel = false
        isCheckingEmbeddingModel = false

        // Pre-select the current provider if it exists
        val currentProvider = provider
        if (currentProvider != null && currentProvider != ModelProvider.UNKNOWN) {
            selectedProvider = currentProvider

            // Load existing settings and configuration fields
            val existingSettings = appContext.params.providerSettings[currentProvider]
                ?: ProviderRegistry.getFactory(currentProvider)?.defaultSettings()
            providerConfigFields = existingSettings?.getConfigFields(LocalizationManager.messageResolver) ?: emptyList()

            // Initialize field values with existing or default values
            providerFieldValues = providerConfigFields.mapNotNull { field ->
                when (field) {
                    is ProviderConfigField.ApiKeyField -> field.name to field.value
                    is ProviderConfigField.BaseUrlField -> field.name to field.value
                    is ProviderConfigField.InfoField -> null
                }
            }.toMap()
        } else {
            // No provider configured yet
            selectedProvider = null
            providerConfigFields = emptyList()
            providerFieldValues = emptyMap()
        }

        showProviderDialog = true
    }

    /**
     * Select a provider and show its configuration fields.
     */
    fun selectProviderForChange(newProvider: ModelProvider) {
        selectedProvider = newProvider

        // Reset connection and model states when changing provider
        connectionError = null
        connectionErrorHelp = null
        connectionTestSuccess = false
        showModelSelectionInProviderDialog = false
        pendingModelForNewProvider = null
        availableModels = emptyList()
        isLoadingModels = false
        modelError = null
        modelErrorHelp = null
        embeddingModelWarning = null
        embeddingModelProvider = null
        canPullEmbeddingModel = false
        isCheckingEmbeddingModel = false

        // Get existing settings if available
        val existingSettings = appContext.params.providerSettings[newProvider]
            ?: ProviderRegistry.getFactory(newProvider)?.defaultSettings()

        // Get configuration fields for the provider
        providerConfigFields = existingSettings?.getConfigFields(LocalizationManager.messageResolver) ?: emptyList()

        // Initialize field values with existing or default values
        providerFieldValues = providerConfigFields.mapNotNull { field ->
            when (field) {
                is ProviderConfigField.ApiKeyField -> field.name to field.value
                is ProviderConfigField.BaseUrlField -> field.name to field.value
                is ProviderConfigField.InfoField -> null
            }
        }.toMap()

        // Auto-fetch models if pre-existing fields already satisfy all requirements
        scheduleAutoModelFetch()
    }

    /**
     * Update a provider configuration field value and reschedule the auto model fetch.
     */
    fun updateProviderField(fieldName: String, value: String) {
        providerFieldValues = providerFieldValues.toMutableMap().apply {
            put(fieldName, value)
        }
        scheduleAutoModelFetch()
    }

    /**
     * Schedule a debounced model fetch when all required fields are filled.
     * Cancels any in-flight fetch and waits 600ms after the last field change before
     * attempting to load models. On success, automatically advances to model selection.
     */
    private fun scheduleAutoModelFetch() {
        autoFetchJob?.cancel()

        // Only attempt if all required config fields are satisfied
        if (!validateConfigFields(providerFieldValues, providerConfigFields)) {
            // Fields not complete yet — reset any previous error so the UI stays clean
            connectionError = null
            connectionErrorHelp = null
            isFetchingModelsForConfig = false
            return
        }

        val provider = selectedProvider ?: return

        connectionError = null
        connectionErrorHelp = null
        isFetchingModelsForConfig = true

        autoFetchJob = scope.launch {
            delay(1000.milliseconds)

            val result = withContext(Dispatchers.IO) {
                try {
                    val existingSettings = appContext.params.providerSettings[provider]
                        ?: ProviderRegistry.getFactory(provider)?.defaultSettings()

                    val newSettings = existingSettings?.applyConfigFields(providerFieldValues)
                        ?: return@withContext ProviderTestResult.Failure("Failed to create settings")

                    if (!newSettings.validate()) {
                        return@withContext ProviderTestResult.Failure(
                            message = "Cannot connect to ${provider.name.lowercase()} provider",
                            helpText = newSettings.getSetupHelpText(LocalizationManager.messageResolver),
                        )
                    }

                    val factory = ProviderRegistry.getFactory(provider)
                        ?: return@withContext ProviderTestResult.Failure("No factory found for provider")

                    @Suppress("UNCHECKED_CAST")
                    val models = (factory as ChatModelFactory<ProviderSettings>).availableModels(newSettings)

                    if (models.isNotEmpty()) {
                        ProviderTestResult.Success
                    } else {
                        ProviderTestResult.Failure(
                            message = LocalizationManager.getString("provider.connection.failed"),
                            helpText = null,
                        )
                    }
                } catch (e: Exception) {
                    log.error("Error auto-fetching models for provider config", e)
                    ProviderTestResult.Failure(
                        ErrorHandler.getUserFriendlyError(
                            e,
                            "fetching models",
                            "Could not reach the provider. Please check your settings.",
                        ),
                    )
                }
            }

            isFetchingModelsForConfig = false

            when (result) {
                is ProviderTestResult.Success -> {
                    connectionError = null
                    connectionErrorHelp = null
                    connectionTestSuccess = true
                    showModelSelectionInProviderDialog = true
                    loadModelsForSelectedProvider()

                    val baseUrl = providerFieldValues[SettingField.BASE_URL]
                    if (baseUrl != null && baseUrl.isNotBlank()) {
                        checkEmbeddingModelAvailability(provider, baseUrl)
                    }
                }

                is ProviderTestResult.Failure -> {
                    connectionError = result.message
                    connectionErrorHelp = result.helpText
                    connectionTestSuccess = false
                }
            }
        }
    }

    /**
     * Load models for the selected provider (used in provider dialog flow).
     */
    fun loadModelsForSelectedProvider() {
        modelError = null
        modelErrorHelp = null
        isLoadingModels = true

        scope.launch {
            val provider = selectedProvider
            if (provider == null) {
                isLoadingModels = false
                availableModels = emptyList()
                modelError = "Provider not set"
                modelErrorHelp = null
                pendingModelForNewProvider = null
                return@launch
            }

            withContext(Dispatchers.IO) {
                val factory = ProviderRegistry.getFactory(provider)
                if (factory == null) {
                    isLoadingModels = false
                    availableModels = emptyList()
                    modelError = "No model factory registered for provider: ${provider.name.lowercase()}"
                    modelErrorHelp = null
                    pendingModelForNewProvider = null
                    return@withContext
                }

                // Get existing settings if available, otherwise use defaults
                val existingSettings = appContext.params.providerSettings[provider]
                    ?: factory.defaultSettings()

                // Apply current field values to get the most up-to-date settings
                val settings = existingSettings.applyConfigFields(providerFieldValues)

                @Suppress("UNCHECKED_CAST")
                val models = (factory as ChatModelFactory<ProviderSettings>)
                    .availableModels(settings)

                isLoadingModels = false

                if (models.isEmpty()) {
                    availableModels = emptyList()
                    modelError = "No models available for ${provider.name.lowercase()}"
                    modelErrorHelp = factory.getNoModelsHelpText()
                    pendingModelForNewProvider = null
                } else {
                    availableModels = models
                    modelError = null
                    modelErrorHelp = null

                    // Pre-select the previously selected model if it exists in the available models
                    val previousModel = appContext.params.getModel(provider)
                    pendingModelForNewProvider = if (previousModel.isNotBlank() && models.any { it.modelId == previousModel }) {
                        previousModel
                    } else {
                        null
                    }
                }
            }
        }
    }

    /**
     * Select a model for the new provider (in provider dialog flow).
     */
    fun selectModelForNewProvider(model: String) {
        pendingModelForNewProvider = model
    }

    /**
     * Go back from model selection to provider configuration.
     */
    fun backToProviderConfiguration() {
        autoFetchJob?.cancel()
        isFetchingModelsForConfig = false
        showModelSelectionInProviderDialog = false
        connectionTestSuccess = false
        connectionError = null
        connectionErrorHelp = null
        pendingModelForNewProvider = null
    }

    /**
     * Save the selected provider and its configuration.
     */
    fun saveProvider() {
        val provider = selectedProvider ?: return

        // Validate all required fields are filled
        if (!validateConfigFields(providerFieldValues, providerConfigFields)) {
            connectionError = "Please fill in all required fields"
            return
        }

        isTestingConnection = true
        connectionError = null
        connectionErrorHelp = null

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    // Get existing settings if available
                    val existingSettings = appContext.params.providerSettings[provider]
                        ?: ProviderRegistry.getFactory(provider)?.defaultSettings()

                    // Create updated settings
                    val newSettings = existingSettings?.applyConfigFields(providerFieldValues)
                        ?: return@withContext ProviderTestResult.Failure("Failed to create settings")

                    // Test connection (validate)
                    if (!newSettings.validate()) {
                        return@withContext ProviderTestResult.Failure(
                            message = "Cannot connect to ${provider.name.lowercase()} provider",
                            helpText = newSettings.getSetupHelpText(LocalizationManager.messageResolver),
                        )
                    }

                    // Change provider (inline logic from ProviderService)
                    try {
                        appContext.params.currentProvider = provider
                        appContext.setProviderSetting(provider, newSettings)

                        // Use the pending model selected by user, or fall back to the provider's defaultModel
                        val model = pendingModelForNewProvider?.takeIf { it.isNotBlank() }
                            ?: appContext.params.getModel(provider)
                        appContext.params.model = model

                        appContext.save()
                        CoroutineScope(Dispatchers.Default).launch {
                            EventBus.emit(ModelChangedEvent(provider, model))
                        }

                        ProviderTestResult.Success
                    } catch (e: Exception) {
                        ProviderTestResult.Failure("Failed to apply provider changes")
                        log.error("Error applying provider changes", e)
                    }
                } catch (e: Exception) {
                    val errorMsg = ErrorHandler.getUserFriendlyError(
                        e,
                        "applying provider change",
                        "Failed to apply provider settings. Please try again.",
                    )
                    ProviderTestResult.Failure(errorMsg)
                    log.error("Error applying provider change", e)
                }
            }

            isTestingConnection = false

            when (result) {
                is ProviderTestResult.Success -> {
                    // Update local state
                    loadConfiguration()

                    // Close dialog and show success
                    showProviderDialog = false
                    successMessage = "Provider changed to ${provider.name.lowercase()}"
                    showSuccessMessage = true
                }

                is ProviderTestResult.Failure -> {
                    connectionError = result.message
                    connectionErrorHelp = result.helpText
                }
            }
        }
    }

    /**
     * Close the provider selection dialog.
     */
    fun closeProviderDialog() {
        autoFetchJob?.cancel()
        isFetchingModelsForConfig = false
        showProviderDialog = false
        selectedProvider = null
        providerConfigFields = emptyList()
        providerFieldValues = emptyMap()
        connectionError = null
        connectionErrorHelp = null
        connectionTestSuccess = false
        showModelSelectionInProviderDialog = false
        pendingModelForNewProvider = null
        availableModels = emptyList()
        isLoadingModels = false
        modelError = null
        modelErrorHelp = null
        embeddingModelWarning = null
        embeddingModelProvider = null
        canPullEmbeddingModel = false
        isCheckingEmbeddingModel = false
    }

    /**
     * Handle the "Change Model" action.
     * Opens the model selection dialog.
     */
    fun onChangeModel() {
        modelError = null
        modelErrorHelp = null
        isLoadingModels = true
        showModelDialog = true

        scope.launch {
            val currentProvider = provider
            if (currentProvider == null) {
                isLoadingModels = false
                availableModels = emptyList()
                modelError = "Provider not set"
                modelErrorHelp = null
                return@launch
            }

            withContext(Dispatchers.IO) {
                val factory = ProviderRegistry.getFactory(currentProvider)
                if (factory == null) {
                    isLoadingModels = false
                    availableModels = emptyList()
                    modelError = "No model factory registered for provider: ${currentProvider.name.lowercase()}"
                    modelErrorHelp = null
                    return@withContext
                }

                val settings = appContext.params.providerSettings[currentProvider] ?: factory.defaultSettings()

                @Suppress("UNCHECKED_CAST")
                val models = (factory as ChatModelFactory<ProviderSettings>)
                    .availableModels(settings)

                isLoadingModels = false

                if (models.isEmpty()) {
                    availableModels = emptyList()
                    modelError = "No models available for ${currentProvider.name.lowercase()}"
                    modelErrorHelp = factory.getNoModelsHelpText()
                } else {
                    availableModels = models
                    modelError = null
                    modelErrorHelp = null
                }
            }
        }
    }

    /**
     * Handle the "Change Settings" action.
     * Opens the settings configuration dialog.
     */
    fun onChangeSettings() {
        provider?.let { currentProvider ->
            val currentSettings = appContext.getCurrentProviderSettings()
            settingsFields = currentSettings.getFields()
            showSettingsDialog = true
        }
    }

    /**
     * Select a new model and update the session.
     */
    fun selectModel(newModel: String) {
        scope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    // Update the model in session params
                    appContext.params.model = newModel

                    // Persist the change to disk
                    appContext.save()

                    CoroutineScope(Dispatchers.Default).launch {
                        EventBus.emit(ModelChangedEvent(appContext.getActiveProvider(), newModel))
                    }

                    true
                } catch (_: Exception) {
                    false
                }
            }

            if (success) {
                model = newModel
                loadConfiguration() // Reload to get updated settings
                showModelDialog = false
                successMessage = "Model updated to: $newModel"
                showSuccessMessage = true
            } else {
                modelError = "Failed to change model to: $newModel"
            }
        }
    }

    /**
     * Close the model selection dialog.
     */
    fun closeModelDialog() {
        showModelDialog = false
        modelError = null
        modelErrorHelp = null
    }

    /**
     * Update a settings field value.
     */
    fun updateSettingsField(fieldName: String, value: String) {
        provider?.let { currentProvider ->
            scope.launch {
                val currentSettings = appContext.getCurrentProviderSettings()
                val updatedSettings = withContext(Dispatchers.IO) {
                    currentSettings.updateField(fieldName, value)
                }

                // Update session with new settings
                appContext.setProviderSetting(currentProvider, updatedSettings)

                CoroutineScope(Dispatchers.Default).launch {
                    EventBus.emit(ModelChangedEvent(currentProvider, ""))
                }

                // Reload configuration to refresh UI
                loadConfiguration()

                // Refresh settings fields in dialog
                settingsFields = updatedSettings.getFields()
            }
        }
    }

    /**
     * Close the settings dialog and show success message.
     */
    fun closeSettingsDialog() {
        showSettingsDialog = false
        successMessage = "Settings updated successfully"
        showSuccessMessage = true
    }

    /**
     * Validates that all required fields are filled.
     */
    private fun validateConfigFields(fields: Map<String, String>, configFields: List<ProviderConfigField>): Boolean = configFields.all { field ->
        if (field.required) {
            // For API key fields with existing values, blank is acceptable (means keep existing)
            if (field is ProviderConfigField.ApiKeyField && field.hasExistingValue) {
                true
            } else {
                val value = fields[field.name]
                !value.isNullOrBlank()
            }
        } else {
            true
        }
    }

    /**
     * Check if the embedding model is available for the selected provider.
     * This is only relevant for RAG features with local providers.
     */
    fun checkEmbeddingModelAvailability(provider: ModelProvider, baseUrl: String) {
        isCheckingEmbeddingModel = true
        embeddingModelWarning = null
        embeddingModelProvider = null
        canPullEmbeddingModel = false

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    when (provider) {
                        ModelProvider.OLLAMA -> {
                            val modelName = AppConfig.models[ModelProvider.OLLAMA].embeddingModel
                            LocalModelValidator.checkModelExists(
                                provider,
                                baseUrl,
                                modelName,
                            )
                        }

                        ModelProvider.DOCKER -> {
                            val modelName = AppConfig.models[ModelProvider.DOCKER].embeddingModel
                            LocalModelValidator.checkModelExists(
                                provider,
                                baseUrl,
                                modelName,
                            )
                        }

                        ModelProvider.LOCALAI -> {
                            val modelName = AppConfig.models[ModelProvider.LOCALAI].embeddingModel
                            LocalModelValidator.checkModelExists(
                                provider,
                                baseUrl,
                                modelName,
                            )
                        }

                        ModelProvider.LMSTUDIO -> {
                            val modelName = AppConfig.models[ModelProvider.LMSTUDIO].embeddingModel
                            LocalModelValidator.checkModelExists(
                                provider,
                                baseUrl,
                                modelName,
                            )
                        }

                        ModelProvider.ANTHROPIC -> {
                            ModelAvailabilityResult.NotAvailable(
                                reason = LocalizationManager.getString("settings.embedding.anthropic_no_embedding"),
                                canAutoPull = false,
                            )
                        }

                        ModelProvider.XAI -> {
                            ModelAvailabilityResult.NotAvailable(
                                reason = LocalizationManager.getString("settings.embedding.xai_no_embedding"),
                                canAutoPull = false,
                            )
                        }

                        else -> ModelAvailabilityResult.Available
                    }
                }

                when (result) {
                    is ModelAvailabilityResult.Available -> {
                        // Model is available, no warning needed
                        embeddingModelWarning = null
                    }

                    is ModelAvailabilityResult.NotAvailable -> {
                        embeddingModelWarning = LocalizationManager.getString(
                            "settings.embedding.not_available_rag_only",
                            result.reason,
                        )
                        embeddingModelProvider = provider.name
                        canPullEmbeddingModel = result.canAutoPull
                    }

                    is ModelAvailabilityResult.ProviderUnreachable -> {
                        embeddingModelWarning = LocalizationManager.getString(
                            "settings.embedding.provider_unreachable",
                            result.error,
                        )
                        embeddingModelProvider = provider.name
                        canPullEmbeddingModel = false
                    }
                }
            } catch (e: Exception) {
                log.error("Error checking embedding model availability", e)
                embeddingModelWarning = LocalizationManager.getString(
                    "settings.embedding.check_failed",
                    e.message ?: "Unknown error",
                )
            } finally {
                isCheckingEmbeddingModel = false
            }
        }
    }

    /**
     * Attempt to pull/download the embedding model (for Ollama)
     */
    fun pullEmbeddingModel(provider: ModelProvider, baseUrl: String) {
        if (provider != ModelProvider.OLLAMA) return

        isCheckingEmbeddingModel = true
        scope.launch {
            try {
                val modelName = AppConfig.models[ModelProvider.OLLAMA].embeddingModel
                val success = withContext(Dispatchers.IO) {
                    LocalModelValidator.pullOllamaModel(baseUrl, modelName)
                }

                if (success) {
                    embeddingModelWarning = null
                    successMessage = LocalizationManager.getString("settings.embedding.download_success", modelName)
                    showSuccessMessage = true
                } else {
                    embeddingModelWarning = LocalizationManager.getString(
                        "settings.embedding.download_failed",
                        modelName,
                    )
                }
            } catch (e: Exception) {
                log.error("Error pulling embedding model", e)
                embeddingModelWarning = LocalizationManager.getString(
                    "settings.embedding.download_error",
                    e.message ?: "Unknown error",
                )
            } finally {
                isCheckingEmbeddingModel = false
            }
        }
    }

    /**
     * Clear embedding model warning
     */
    fun clearEmbeddingWarning() {
        embeddingModelWarning = null
        embeddingModelProvider = null
        canPullEmbeddingModel = false
    }
}
