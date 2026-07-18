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
import io.askimo.core.error.AppError
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
import io.askimo.core.providers.ProviderInstance
import io.askimo.core.providers.ProviderInstanceService
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
import kotlin.time.Duration.Companion.milliseconds

/** Wizard navigation steps for the provider add/edit wizard. */
enum class WizardStep { TYPE_PICKER, CONFIG, MODEL }

/**
 * ViewModel for managing settings state and configuration information.
 *
 * Provider dialog flow:
 * - [showProviderWizard] — wizard for adding or editing a provider instance
 *   - Add flow:  TYPE_PICKER → CONFIG → MODEL
 *   - Edit flow: CONFIG → MODEL (no type picker)
 *
 * Domain changes (model switch, save) emit [ModelChangedEvent] on [EventBus].
 * UI navigation (open/close dialogs) is pure ViewModel state — no callbacks.
 */
class SettingsViewModel(
    private val scope: CoroutineScope,
    private val appContext: AppContext,
    private val providerInstanceService: ProviderInstanceService,
) {
    private val log = logger<SettingsViewModel>()

    // ── Active-configuration display ─────────────────────────────────────────────────────────

    var provider by mutableStateOf<ModelProvider?>(null)
        private set

    var model by mutableStateOf("")
        private set

    var instanceDisplayName by mutableStateOf("")
        private set

    var instanceId by mutableStateOf("")
        private set

    /** The currently active [ProviderInstance], or null if none is configured. */
    val activeInstance get() = providerInstanceService.findById(instanceId)

    var settingsDescription by mutableStateOf<List<String>>(emptyList())
        private set

    // ── Feedback ─────────────────────────────────────────────────────────────────────────────

    var showSuccessMessage by mutableStateOf(false)
        private set

    var successMessage by mutableStateOf("")
        private set

    // ── Model list state (used by wizard model picker) ────────────────────────────────────────

    var availableModels by mutableStateOf<List<ModelDTO>>(emptyList())
        private set

    var isLoadingModels by mutableStateOf(false)
        private set

    var modelError by mutableStateOf<String?>(null)
        private set

    var modelErrorHelp by mutableStateOf<String?>(null)
        private set

    // ── Settings (advanced fields) dialog ────────────────────────────────────────────────────

    var showSettingsDialog by mutableStateOf(false)
        private set

    var settingsFields by mutableStateOf<List<SettingField>>(emptyList())
        private set

    // ── Manage providers dialog ───────────────────────────────────────────────────────────────

    /** All configured instances shown in the manage dialog. */
    var availableInstances by mutableStateOf<List<ProviderInstance>>(emptyList())
        private set

    // ── Provider wizard (add & edit) ──────────────────────────────────────────────────────────

    /** True while the add/edit provider wizard is open. */
    var showProviderWizard by mutableStateOf(false)
        private set

    /** Current step within the wizard. */
    var wizardStep by mutableStateOf(WizardStep.TYPE_PICKER)
        private set

    /** All supported provider types — shown in the TYPE_PICKER step. */
    var availableProviders by mutableStateOf<List<ModelProvider>>(emptyList())
        private set

    /**
     * Non-null when the wizard is in **edit** mode; null when adding a new instance.
     * Derive [isAddingNewInstance] from this.
     */
    var editingInstance by mutableStateOf<ProviderInstance?>(null)
        private set

    /** True when the wizard is in add mode (no instance being edited). */
    val isAddingNewInstance: Boolean get() = editingInstance == null && showProviderWizard

    /** The provider type chosen in the wizard (TYPE_PICKER) or taken from the edited instance. */
    var selectedProvider by mutableStateOf<ModelProvider?>(null)
        private set

    /** Editable display name for a **new** instance. */
    var newInstanceDisplayName by mutableStateOf("")
        private set

    /** Editable display name when **editing** an existing instance. */
    var editingInstanceDisplayName by mutableStateOf("")
        private set

    var providerConfigFields by mutableStateOf<List<ProviderConfigField>>(emptyList())
        private set

    var providerFieldValues by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    var isTestingConnection by mutableStateOf(false)
        private set

    var isFetchingModelsForConfig by mutableStateOf(false)
        private set

    private var autoFetchJob: Job? = null

    var connectionError by mutableStateOf<String?>(null)
        private set

    var connectionErrorHelp by mutableStateOf<String?>(null)
        private set

    var connectionTestSuccess by mutableStateOf(false)
        private set

    var pendingModelForNewProvider by mutableStateOf<String?>(null)
        private set

    var isCheckingEmbeddingModel by mutableStateOf(false)
        private set

    var embeddingModelWarning by mutableStateOf<String?>(null)
        private set

    var embeddingModelProvider by mutableStateOf<String?>(null)
        private set

    var canPullEmbeddingModel by mutableStateOf(false)
        private set

    var displayNameError by mutableStateOf<String?>(null)
        private set

    init {
        loadConfiguration()

        scope.launch {
            EventBus.internalEvents.collect { event ->
                if (event is ModelChangedEvent) {
                    model = event.newModel
                    loadConfiguration()
                }
            }
        }
    }

    // ── Active-configuration ─────────────────────────────────────────────────────────────────

    fun loadConfiguration() {
        val configInfo = appContext.getConfigInfo()
        provider = configInfo.provider
        model = configInfo.model
        instanceDisplayName = configInfo.instanceDisplayName
        instanceId = configInfo.instanceId
        settingsDescription = configInfo.settingsDescription
    }

    // ── Instance switching (from manage dialog, no wizard) ───────────────────────────────────

    fun switchToInstance(targetInstanceId: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                providerInstanceService.setActive(targetInstanceId)
            }
            loadConfiguration()
        }
    }

    fun deleteInstance(targetInstanceId: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                providerInstanceService.delete(targetInstanceId)
            }
            availableInstances = providerInstanceService.all
            loadConfiguration()
            successMessage = "Provider instance removed"
            showSuccessMessage = true
        }
    }

    // ── Manage providers dialog ───────────────────────────────────────────────────────────────

    /**
     * Entry point — called when the user clicks the "Change provider" button in settings.
     * Always opens the add provider wizard directly.
     */
    fun onChangeProvider() {
        openAddProviderWizard()
    }

    // ── Provider wizard — open ────────────────────────────────────────────────────────────────

    /** Opens the wizard in add mode (starts at TYPE_PICKER step). */
    fun openAddProviderWizard() {
        availableInstances = providerInstanceService.all
        availableProviders = ProviderRegistry.getSupportedProviders()
            .filter { it != ModelProvider.UNKNOWN && it != ModelProvider.ASKIMO_PRO }
            .sortedBy { ProviderRegistry.getProviderDisplayName(it) }
        editingInstance = null
        selectedProvider = null
        newInstanceDisplayName = ""
        wizardStep = WizardStep.TYPE_PICKER
        resetWizardFormState()
        showProviderWizard = true
    }

    /** Opens the wizard in edit mode (starts at CONFIG step, pre-populated). */
    fun openEditProviderWizard(instance: ProviderInstance) {
        editingInstance = instance
        selectedProvider = instance.providerType
        editingInstanceDisplayName = instance.displayName
        wizardStep = WizardStep.CONFIG
        resetWizardFormState()

        providerConfigFields = instance.settings.getConfigFields(LocalizationManager.messageResolver)
        providerFieldValues = providerConfigFields.mapNotNull { field ->
            when (field) {
                is ProviderConfigField.ApiKeyField -> field.name to field.value
                is ProviderConfigField.BaseUrlField -> field.name to field.value
                is ProviderConfigField.InfoField -> null
            }
        }.toMap()

        scheduleAutoModelFetch()
        showProviderWizard = true
    }

    // ── Provider wizard — close / back ────────────────────────────────────────────────────────

    /** Closes the wizard and discards unsaved state. */
    fun closeProviderWizard() {
        autoFetchJob?.cancel()
        showProviderWizard = false
        editingInstance = null
        selectedProvider = null
        newInstanceDisplayName = ""
        editingInstanceDisplayName = ""
        wizardStep = WizardStep.TYPE_PICKER
        resetWizardFormState()
    }

    /**
     * Navigates back within the wizard:
     * MODEL → CONFIG, CONFIG → TYPE_PICKER (add) or close (edit),
     * TYPE_PICKER → close.
     */
    fun wizardBack() {
        when (wizardStep) {
            WizardStep.MODEL -> {
                wizardStep = WizardStep.CONFIG
                pendingModelForNewProvider = null
            }

            WizardStep.CONFIG -> {
                if (isAddingNewInstance) {
                    selectedProvider = null
                    wizardStep = WizardStep.TYPE_PICKER
                    resetWizardFormState()
                } else {
                    // Edit: back = close wizard
                    closeProviderWizard()
                }
            }

            WizardStep.TYPE_PICKER -> {
                closeProviderWizard()
            }
        }
    }

    // ── Provider wizard — step actions ────────────────────────────────────────────────────────

    /** Called when the user picks a provider type in the TYPE_PICKER step. */
    fun selectProviderTypeForNewInstance(providerType: ModelProvider) {
        selectedProvider = providerType
        newInstanceDisplayName = ProviderRegistry.getProviderDisplayName(providerType)
        wizardStep = WizardStep.CONFIG
        resetWizardFormState()

        val defaultSettings = ProviderRegistry.getFactory(providerType)?.defaultSettings()
        providerConfigFields = defaultSettings?.getConfigFields(LocalizationManager.messageResolver) ?: emptyList()
        providerFieldValues = providerConfigFields.mapNotNull { field ->
            when (field) {
                is ProviderConfigField.ApiKeyField -> field.name to field.value
                is ProviderConfigField.BaseUrlField -> field.name to field.value
                is ProviderConfigField.InfoField -> null
            }
        }.toMap()

        scheduleAutoModelFetch()
    }

    fun updateNewInstanceDisplayName(name: String) {
        newInstanceDisplayName = name
        displayNameError = if (name.isNotBlank() && !providerInstanceService.isDisplayNameAvailable(name)) {
            LocalizationManager.getString("provider.instance.name.duplicate", name.trim())
        } else {
            null
        }
    }

    fun updateEditingInstanceDisplayName(name: String) {
        editingInstanceDisplayName = name
        displayNameError = if (name.isNotBlank() && !providerInstanceService.isDisplayNameAvailable(name, excludingId = editingInstance?.id)) {
            LocalizationManager.getString("provider.instance.name.duplicate", name.trim())
        } else {
            null
        }
    }

    fun updateProviderField(fieldName: String, value: String) {
        providerFieldValues = providerFieldValues.toMutableMap().apply { put(fieldName, value) }
        scheduleAutoModelFetch()
    }

    fun selectModelForNewProvider(model: String) {
        pendingModelForNewProvider = model
    }

    /** Saves the instance and closes the wizard on success. */
    fun saveProvider() {
        val provider = selectedProvider ?: return

        if (!validateConfigFields(providerFieldValues, providerConfigFields)) {
            connectionError = "Please fill in all required fields"
            return
        }

        // Guard: reject duplicate display names before hitting IO
        val candidateName = if (editingInstance != null) {
            editingInstanceDisplayName.ifBlank { editingInstance!!.displayName }
        } else {
            newInstanceDisplayName.ifBlank { ProviderRegistry.getProviderDisplayName(provider) }
        }
        val excludingId = editingInstance?.id
        if (!providerInstanceService.isDisplayNameAvailable(candidateName, excludingId = excludingId)) {
            displayNameError = LocalizationManager.getString("provider.instance.name.duplicate", candidateName.trim())
            return
        }

        isTestingConnection = true
        connectionError = null
        connectionErrorHelp = null

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val baseSettings = editingInstance?.settings
                        ?: ProviderRegistry.getFactory(provider)?.defaultSettings()

                    val newSettings = baseSettings?.applyConfigFields(providerFieldValues)
                        ?: return@withContext ProviderTestResult.Failure("Failed to create settings")

                    if (!newSettings.validate()) {
                        return@withContext ProviderTestResult.Failure(
                            message = "Cannot connect to ${provider.name.lowercase()} provider",
                            helpText = newSettings.getSetupHelpText(LocalizationManager.messageResolver),
                        )
                    }

                    val pendingModel = pendingModelForNewProvider?.takeIf { it.isNotBlank() }
                        ?: newSettings.defaultModel
                    val settingsWithModel = if (pendingModel.isNotBlank()) {
                        newSettings.updateField(SettingField.DEFAULT_MODEL, pendingModel)
                    } else {
                        newSettings
                    }

                    try {
                        if (editingInstance != null) {
                            val displayName = editingInstanceDisplayName.ifBlank { editingInstance!!.displayName }
                            val updated = editingInstance!!.copy(displayName = displayName, settings = settingsWithModel)
                            providerInstanceService.update(updated).getOrThrow()
                        } else {
                            val displayName = newInstanceDisplayName.ifBlank { ProviderRegistry.getProviderDisplayName(provider) }
                            val newInstance = ProviderRegistry.createInstance(providerType = provider, displayName = displayName, settings = settingsWithModel)
                            providerInstanceService.add(newInstance).getOrThrow()
                        }
                        ProviderTestResult.Success
                    } catch (e: Exception) {
                        log.error("Error saving instance", e)
                        val appError = (e as? AppError) ?: (e.cause as? AppError)
                        if (appError is AppError.DuplicateEntry) {
                            ProviderTestResult.Failure("A provider named \"${appError.value.trim()}\" already exists")
                        } else {
                            ProviderTestResult.Failure("Failed to save provider instance")
                        }
                    }
                } catch (e: Exception) {
                    log.error("Error saving instance", e)
                    ProviderTestResult.Failure(ErrorHandler.getUserFriendlyError(e, "saving instance", "Failed to apply provider settings. Please try again."))
                }
            }

            isTestingConnection = false

            when (result) {
                is ProviderTestResult.Success -> {
                    val wasAdding = isAddingNewInstance
                    val savedName = if (wasAdding) {
                        newInstanceDisplayName.ifBlank { provider.name.lowercase() }
                    } else {
                        editingInstanceDisplayName.ifBlank { editingInstance?.displayName ?: "" }
                    }
                    closeProviderWizard()
                    loadConfiguration()
                    successMessage = if (wasAdding) "Provider \"$savedName\" added" else "Provider settings updated"
                    showSuccessMessage = true
                }

                is ProviderTestResult.Failure -> {
                    connectionError = result.message
                    connectionErrorHelp = result.helpText
                }
            }
        }
    }

    // ── Advanced settings dialog ──────────────────────────────────────────────────────────────

    fun onChangeSettings() {
        provider?.let {
            settingsFields = appContext.getCurrentProviderSettings().getFields()
            showSettingsDialog = true
        }
    }

    fun updateSettingsField(fieldName: String, value: String) {
        provider?.let { currentProvider ->
            scope.launch {
                val updatedSettings = withContext(Dispatchers.IO) {
                    appContext.getCurrentProviderSettings().updateField(fieldName, value)
                }
                val activeInstanceId = appContext.params.currentInstanceId
                appContext.setInstanceSettings(activeInstanceId, updatedSettings)
                EventBus.emit(ModelChangedEvent(currentProvider, "", activeInstanceId))
                loadConfiguration()
                settingsFields = updatedSettings.getFields()
            }
        }
    }

    fun closeSettingsDialog() {
        showSettingsDialog = false
        successMessage = "Settings updated successfully"
        showSuccessMessage = true
    }

    // ── Embedding model availability ──────────────────────────────────────────────────────────

    fun checkEmbeddingModelAvailability(provider: ModelProvider, baseUrl: String) {
        isCheckingEmbeddingModel = true
        embeddingModelWarning = null
        embeddingModelProvider = null
        canPullEmbeddingModel = false

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    when (provider) {
                        ModelProvider.OLLAMA -> LocalModelValidator.checkModelExists(provider, baseUrl, AppConfig.models[ModelProvider.OLLAMA].embeddingModel)
                        ModelProvider.DOCKER -> LocalModelValidator.checkModelExists(provider, baseUrl, AppConfig.models[ModelProvider.DOCKER].embeddingModel)
                        ModelProvider.LOCALAI -> LocalModelValidator.checkModelExists(provider, baseUrl, AppConfig.models[ModelProvider.LOCALAI].embeddingModel)
                        ModelProvider.LMSTUDIO -> LocalModelValidator.checkModelExists(provider, baseUrl, AppConfig.models[ModelProvider.LMSTUDIO].embeddingModel)
                        ModelProvider.ANTHROPIC -> ModelAvailabilityResult.NotAvailable(reason = LocalizationManager.getString("settings.embedding.anthropic_no_embedding"), canAutoPull = false)
                        ModelProvider.XAI -> ModelAvailabilityResult.NotAvailable(reason = LocalizationManager.getString("settings.embedding.xai_no_embedding"), canAutoPull = false)
                        else -> ModelAvailabilityResult.Available
                    }
                }
                when (result) {
                    is ModelAvailabilityResult.Available -> embeddingModelWarning = null

                    is ModelAvailabilityResult.NotAvailable -> {
                        embeddingModelWarning = LocalizationManager.getString("settings.embedding.not_available_rag_only", result.reason)
                        embeddingModelProvider = provider.name
                        canPullEmbeddingModel = result.canAutoPull
                    }

                    is ModelAvailabilityResult.ProviderUnreachable -> {
                        embeddingModelWarning = LocalizationManager.getString("settings.embedding.provider_unreachable", result.error)
                        embeddingModelProvider = provider.name
                        canPullEmbeddingModel = false
                    }
                }
            } catch (e: Exception) {
                log.error("Error checking embedding model availability", e)
                embeddingModelWarning = LocalizationManager.getString("settings.embedding.check_failed", e.message ?: "Unknown error")
            } finally {
                isCheckingEmbeddingModel = false
            }
        }
    }

    fun pullEmbeddingModel(provider: ModelProvider, baseUrl: String) {
        if (provider != ModelProvider.OLLAMA) return
        isCheckingEmbeddingModel = true
        scope.launch {
            try {
                val modelName = AppConfig.models[ModelProvider.OLLAMA].embeddingModel
                val success = withContext(Dispatchers.IO) { LocalModelValidator.pullOllamaModel(baseUrl, modelName) }
                if (success) {
                    embeddingModelWarning = null
                    successMessage = LocalizationManager.getString("settings.embedding.download_success", modelName)
                    showSuccessMessage = true
                } else {
                    embeddingModelWarning = LocalizationManager.getString("settings.embedding.download_failed", modelName)
                }
            } catch (e: Exception) {
                log.error("Error pulling embedding model", e)
                embeddingModelWarning = LocalizationManager.getString("settings.embedding.download_error", e.message ?: "Unknown error")
            } finally {
                isCheckingEmbeddingModel = false
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────────────────────

    /** Debounced model fetch; on success advances wizard to MODEL step. */
    private fun scheduleAutoModelFetch() {
        autoFetchJob?.cancel()

        if (!validateConfigFields(providerFieldValues, providerConfigFields)) {
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
                    val baseSettings = editingInstance?.settings ?: ProviderRegistry.getFactory(provider)?.defaultSettings()
                    val newSettings = baseSettings?.applyConfigFields(providerFieldValues)
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
                    val models = (factory as ChatModelFactory<ProviderSettings>)
                        .availableModels((editingInstance?.settings ?: factory.defaultSettings()).applyConfigFields(providerFieldValues))

                    isLoadingModels = false
                    if (models.isNotEmpty()) {
                        ProviderTestResult.Success
                    } else {
                        ProviderTestResult.Failure(message = LocalizationManager.getString("provider.connection.failed"), helpText = null)
                    }
                } catch (e: Exception) {
                    log.error("Error auto-fetching models for provider config", e)
                    ProviderTestResult.Failure(ErrorHandler.getUserFriendlyError(e, "fetching models", "Could not reach the provider. Please check your settings."))
                }
            }

            isFetchingModelsForConfig = false

            when (result) {
                is ProviderTestResult.Success -> {
                    connectionError = null
                    connectionErrorHelp = null
                    connectionTestSuccess = true
                    // Pre-load models in background so they're ready when user clicks Next
                    loadModelsForSelectedProvider()
                    val baseUrl = providerFieldValues[SettingField.BASE_URL]
                    if (!baseUrl.isNullOrBlank()) checkEmbeddingModelAvailability(provider, baseUrl)
                }

                is ProviderTestResult.Failure -> {
                    connectionError = result.message
                    connectionErrorHelp = result.helpText
                    connectionTestSuccess = false
                }
            }
        }
    }

    /** Called when user explicitly clicks "Next" on the CONFIG step (add mode). */
    fun advanceToModelPicker() {
        val candidateName = newInstanceDisplayName.ifBlank {
            selectedProvider?.let { ProviderRegistry.getProviderDisplayName(it) } ?: ""
        }
        if (!providerInstanceService.isDisplayNameAvailable(candidateName)) {
            displayNameError = LocalizationManager.getString("provider.instance.name.duplicate", candidateName.trim())
            return
        }
        displayNameError = null
        wizardStep = WizardStep.MODEL
    }

    private fun loadModelsForSelectedProvider() {
        modelError = null
        modelErrorHelp = null
        isLoadingModels = true

        scope.launch {
            val provider = selectedProvider ?: run {
                isLoadingModels = false
                availableModels = emptyList()
                modelError = "Provider not set"
                pendingModelForNewProvider = null
                return@launch
            }

            withContext(Dispatchers.IO) {
                val factory = ProviderRegistry.getFactory(provider) ?: run {
                    isLoadingModels = false
                    availableModels = emptyList()
                    modelError = "No model factory registered for provider: ${provider.name.lowercase()}"
                    pendingModelForNewProvider = null
                    return@withContext
                }

                @Suppress("UNCHECKED_CAST")
                val models = (factory as ChatModelFactory<ProviderSettings>)
                    .availableModels((editingInstance?.settings ?: factory.defaultSettings()).applyConfigFields(providerFieldValues))

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
                    val prev = editingInstance?.settings?.defaultModel ?: ""
                    pendingModelForNewProvider = prev.takeIf { it.isNotBlank() && models.any { m -> m.modelId == it } }
                }
            }
        }
    }

    private fun resetWizardFormState() {
        autoFetchJob?.cancel()
        connectionError = null
        connectionErrorHelp = null
        connectionTestSuccess = false
        pendingModelForNewProvider = null
        availableModels = emptyList()
        isLoadingModels = false
        modelError = null
        modelErrorHelp = null
        embeddingModelWarning = null
        embeddingModelProvider = null
        canPullEmbeddingModel = false
        isCheckingEmbeddingModel = false
        providerConfigFields = emptyList()
        providerFieldValues = emptyMap()
        isFetchingModelsForConfig = false
        isTestingConnection = false
        displayNameError = null
    }

    private fun validateConfigFields(fields: Map<String, String>, configFields: List<ProviderConfigField>): Boolean = configFields.all { field ->
        if (field.required) {
            if (field is ProviderConfigField.ApiKeyField && field.hasExistingValue) {
                true
            } else {
                !fields[field.name].isNullOrBlank()
            }
        } else {
            true
        }
    }
}
