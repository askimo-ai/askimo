/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.askimo.core.context.AppContext
import io.askimo.core.error.AppError
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.ProviderInstanceSavedEvent
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.logging.logger
import io.askimo.core.providers.ChatModelFactory
import io.askimo.core.providers.ModelDTO
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ProviderConfigField
import io.askimo.core.providers.ProviderEntry
import io.askimo.core.providers.ProviderInstance
import io.askimo.core.providers.ProviderInstanceService
import io.askimo.core.providers.ProviderRegistry
import io.askimo.core.providers.ProviderSettings
import io.askimo.core.providers.ProviderTestResult
import io.askimo.core.providers.SettingField
import io.askimo.core.providers.openaicompatible.OpenAiCompatibleSettings
import io.askimo.core.providers.openaicompatible.OpenAiCompatibleTemplate
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
 * ViewModel for the multi-step provider add/edit wizard (TYPE_PICKER → CONFIG → MODEL).
 *
 * Created fresh by [AIProviderViewModel] each time the wizard opens via
 * [AIProviderViewModel.openAddProviderWizard] or [AIProviderViewModel.openEditProviderWizard].
 * Torn down (set to null) when it signals close via [onClose].
 *
 * On successful save, emits [ProviderInstanceSavedEvent] on [EventBus] so that
 * [AIProviderViewModel] can refresh its active-configuration display without direct coupling.
 *
 * @param onClose Called when the wizard should be dismissed (both on cancel and after a
 *                successful save). The caller ([AIProviderViewModel]) sets its
 *                [AIProviderViewModel.wizardViewModel] reference to null in response.
 */
class ProviderWizardViewModel(
    private val scope: CoroutineScope,
    private val appContext: AppContext,
    private val providerInstanceService: ProviderInstanceService,
    private val onClose: () -> Unit,
) {
    private val log = logger<ProviderWizardViewModel>()

    // ── Step ─────────────────────────────────────────────────────────────────────────────────

    var wizardStep by mutableStateOf(WizardStep.TYPE_PICKER)
        private set

    // ── Feedback ─────────────────────────────────────────────────────────────────────────────

    var showSuccessMessage by mutableStateOf(false)
        private set

    var successMessage by mutableStateOf("")
        private set

    // ── Provider list (TYPE_PICKER step) ─────────────────────────────────────────────────────

    /**
     * Flat, alphabetically sorted list of all selectable entries in the type picker.
     * Contains [ProviderEntry.Native] for first-class providers, [ProviderEntry.Template]
     * for predefined OpenAI-compatible cloud providers, and [ProviderEntry.Custom] as the
     * last entry (separated by a divider in the UI).
     */
    var availableEntries by mutableStateOf<List<ProviderEntry>>(emptyList())
        private set

    /**
     * The entry currently highlighted in the left column of the type picker.
     * Drives the right-column detail panel; null shows the "← Select a provider" hint.
     */
    var selectedEntry by mutableStateOf<ProviderEntry?>(null)
        private set

    /** All configured instances — used to show existing-count badges in the type picker. */
    var availableInstances by mutableStateOf<List<ProviderInstance>>(emptyList())
        private set

    // ── Instance being edited ─────────────────────────────────────────────────────────────────

    /**
     * Non-null when the wizard is in **edit** mode; null when adding a new instance.
     * Derive [isAddingNewInstance] from this.
     */
    var editingInstance by mutableStateOf<ProviderInstance?>(null)
        private set

    /** True when the wizard is in add mode (no instance being edited). */
    val isAddingNewInstance: Boolean get() = editingInstance == null

    // ── Provider type ─────────────────────────────────────────────────────────────────────────

    /** The provider type chosen in the TYPE_PICKER step or taken from the edited instance. */
    var selectedProvider by mutableStateOf<ModelProvider?>(null)
        private set

    // ── Display-name fields ───────────────────────────────────────────────────────────────────

    /** Editable display name for a **new** instance. */
    var newInstanceDisplayName by mutableStateOf("")
        private set

    /** Editable display name when **editing** an existing instance. */
    var editingInstanceDisplayName by mutableStateOf("")
        private set

    var displayNameError by mutableStateOf<String?>(null)
        private set

    // ── Config fields ─────────────────────────────────────────────────────────────────────────

    var providerConfigFields by mutableStateOf<List<ProviderConfigField>>(emptyList())
        private set

    var providerFieldValues by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /**
     * Base settings pre-filled from a [OpenAiCompatibleTemplate] during the add-wizard flow.
     * Used by [buildValidatedSettings] so that template-locked values ([OpenAiCompatibleSettings.isTemplate],
     * [OpenAiCompatibleSettings.apiMode], [OpenAiCompatibleSettings.httpVersionConfig]) survive
     * the [applyConfigFields] call when saving a new template-based instance.
     * Reset to null whenever the wizard is reset or a non-template provider is selected.
     */
    private var templateBaseSettings: ProviderSettings? = null

    // ── Connection / fetch state ──────────────────────────────────────────────────────────────

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

    // ── Model picker ──────────────────────────────────────────────────────────────────────────

    var pendingModelForNewProvider by mutableStateOf<String?>(null)
        private set

    var availableModels by mutableStateOf<List<ModelDTO>>(emptyList())
        private set

    var isLoadingModels by mutableStateOf(false)
        private set

    var modelError by mutableStateOf<String?>(null)
        private set

    var modelErrorHelp by mutableStateOf<String?>(null)
        private set

    // ── Embedding model check ─────────────────────────────────────────────────────────────────

    var isCheckingEmbeddingModel by mutableStateOf(false)
        private set

    var embeddingModelWarning by mutableStateOf<String?>(null)
        private set

    var embeddingModelProvider by mutableStateOf<String?>(null)
        private set

    var canPullEmbeddingModel by mutableStateOf(false)
        private set

    // ── Init helpers ──────────────────────────────────────────────────────────────────────────

    /**
     * Initialises the wizard in **add** mode (starts at TYPE_PICKER step).
     * Called immediately after construction by [AIProviderViewModel.openAddProviderWizard].
     */
    fun initAddMode() {
        availableInstances = providerInstanceService.all

        val nativeEntries = ProviderRegistry.getSupportedProviders()
            .filter { it != ModelProvider.UNKNOWN && it != ModelProvider.ASKIMO_PRO && it != ModelProvider.OPENAI_COMPATIBLE }
            .map { ProviderEntry.Native(it) }

        val templateEntries = OpenAiCompatibleTemplate.entries.map { ProviderEntry.Template(it) }

        availableEntries = (nativeEntries + templateEntries)
            .sortedBy { entry ->
                when (entry) {
                    is ProviderEntry.Native -> ProviderRegistry.getProviderDisplayName(entry.provider).lowercase()
                    is ProviderEntry.Template -> entry.template.displayName.lowercase()
                    is ProviderEntry.Custom -> "zzz"
                }
            } + listOf(ProviderEntry.Custom)

        selectedEntry = null
        editingInstance = null
        selectedProvider = null
        newInstanceDisplayName = ""
        wizardStep = WizardStep.TYPE_PICKER
        resetWizardFormState()
    }

    /**
     * Initialises the wizard in **edit** mode (starts at CONFIG step, pre-populated).
     * Called immediately after construction by [AIProviderViewModel.openEditProviderWizard].
     */
    fun initEditMode(instance: ProviderInstance) {
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
                is ProviderConfigField.SelectField -> field.name to field.value
                is ProviderConfigField.InfoField -> null
            }
        }.toMap()

        scheduleAutoModelFetch()
    }

    // ── Navigation ────────────────────────────────────────────────────────────────────────────

    /** Resets internal state and notifies the parent via [onClose] to tear this VM down. */
    fun closeProviderWizard() {
        autoFetchJob?.cancel()
        editingInstance = null
        selectedProvider = null
        newInstanceDisplayName = ""
        editingInstanceDisplayName = ""
        wizardStep = WizardStep.TYPE_PICKER
        resetWizardFormState()
        onClose()
    }

    /**
     * Navigates back within the wizard:
     * MODEL → CONFIG, CONFIG → TYPE_PICKER (add) or close (edit), TYPE_PICKER → close.
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
                    closeProviderWizard()
                }
            }

            WizardStep.TYPE_PICKER -> closeProviderWizard()
        }
    }

    // ── Step actions ──────────────────────────────────────────────────────────────────────────

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
                is ProviderConfigField.SelectField -> field.name to field.value
                is ProviderConfigField.InfoField -> null
            }
        }.toMap()

        scheduleAutoModelFetch()
    }

    /**
     * Highlights an entry in the TYPE_PICKER left column, updating the right-column
     * detail panel. Does NOT advance to the CONFIG step.
     */
    fun selectEntryForPreview(entry: ProviderEntry) {
        selectedEntry = entry
    }

    /**
     * Advances to the CONFIG step for [selectedEntry].
     * - [ProviderEntry.Native] → same as [selectProviderTypeForNewInstance].
     * - [ProviderEntry.Template] → pre-fills [OpenAiCompatibleSettings] from the template.
     * - [ProviderEntry.Custom] → opens a blank [ModelProvider.OPENAI_COMPATIBLE] config form.
     */
    fun connectEntry() {
        when (val entry = selectedEntry ?: return) {
            is ProviderEntry.Native -> selectProviderTypeForNewInstance(entry.provider)
            is ProviderEntry.Template -> selectTemplateForNewInstance(entry.template)
            is ProviderEntry.Custom -> selectProviderTypeForNewInstance(ModelProvider.OPENAI_COMPATIBLE)
        }
    }

    /**
     * Advances to CONFIG with [OpenAiCompatibleSettings] pre-filled from [template].
     * The display name and base URL are set to the template values; the user only
     * needs to paste their API key.
     */
    fun selectTemplateForNewInstance(template: OpenAiCompatibleTemplate) {
        selectedProvider = ModelProvider.OPENAI_COMPATIBLE
        newInstanceDisplayName = template.displayName
        wizardStep = WizardStep.CONFIG
        resetWizardFormState()

        val prefilled = OpenAiCompatibleSettings(
            baseUrl = template.baseUrl,
            apiMode = template.apiMode,
            httpVersion = template.httpVersion,
            isTemplate = true,
        )
        templateBaseSettings = prefilled
        providerConfigFields = prefilled.getConfigFields(LocalizationManager.messageResolver)
        providerFieldValues = buildMap {
            providerConfigFields.forEach { field ->
                when (field) {
                    is ProviderConfigField.ApiKeyField -> put(field.name, field.value)
                    is ProviderConfigField.BaseUrlField -> put(field.name, template.baseUrl)
                    is ProviderConfigField.SelectField -> put(field.name, field.value)
                    is ProviderConfigField.InfoField -> Unit
                }
            }
        }

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

    /** Called when the user explicitly clicks "Next" on the CONFIG step (add mode). */
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

    /**
     * Saves the instance. On success:
     * 1. Sets [showSuccessMessage] with a human-readable confirmation.
     * 2. Emits [ProviderInstanceSavedEvent] on [EventBus].
     * 3. Calls [closeProviderWizard] → [onClose] to tear this VM down.
     */
    fun saveProvider() {
        val provider = selectedProvider ?: return

        if (!validateConfigFields(providerFieldValues, providerConfigFields)) {
            connectionError = "Please fill in all required fields"
            return
        }

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
            val wasAdding = isAddingNewInstance
            var savedInstanceId = ""
            var savedDisplayName = ""

            val result = withContext(Dispatchers.IO) {
                try {
                    val newSettings = try {
                        buildValidatedSettings(provider)
                    } catch (e: SettingsValidationException) {
                        return@withContext e.failure
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
                            savedInstanceId = updated.id
                            savedDisplayName = displayName
                        } else {
                            val displayName = newInstanceDisplayName.ifBlank { ProviderRegistry.getProviderDisplayName(provider) }
                            val newInstance = ProviderRegistry.createInstance(
                                providerType = provider,
                                displayName = displayName,
                                settings = settingsWithModel,
                            )
                            providerInstanceService.add(newInstance).getOrThrow()
                            savedInstanceId = newInstance.id
                            savedDisplayName = displayName
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
                    ProviderTestResult.Failure(
                        ErrorHandler.getUserFriendlyError(e, "saving instance", "Failed to apply provider settings. Please try again."),
                    )
                }
            }

            isTestingConnection = false

            when (result) {
                is ProviderTestResult.Success -> {
                    successMessage = if (wasAdding) "Provider \"$savedDisplayName\" added" else "Provider settings updated"
                    showSuccessMessage = true
                    EventBus.emit(
                        ProviderInstanceSavedEvent(
                            instanceId = savedInstanceId,
                            displayName = savedDisplayName,
                            isNewInstance = wasAdding,
                        ),
                    )
                    closeProviderWizard()
                }

                is ProviderTestResult.Failure -> {
                    connectionError = result.message
                    connectionErrorHelp = result.helpText
                }
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────────────────────

    /** Thrown by [buildValidatedSettings] when settings cannot be created or fail validation. */
    private class SettingsValidationException(val failure: ProviderTestResult.Failure) : Exception()

    /**
     * Resolves the base settings for [provider], applies the current [providerFieldValues],
     * and validates the result. Throws [SettingsValidationException] on any failure so that
     * callers inside a `withContext` block can simply `return@withContext e.failure`.
     */
    private fun buildValidatedSettings(provider: ModelProvider): ProviderSettings {
        // For new template instances, use templateBaseSettings (which carries isTemplate=true,
        // the preset apiMode, and httpVersionConfig) instead of the generic defaultSettings().
        // For editing an existing instance, the persisted settings already carry isTemplate.
        val baseSettings = editingInstance?.settings
            ?: templateBaseSettings
            ?: ProviderRegistry.getFactory(provider)?.defaultSettings()

        val settings = baseSettings?.applyConfigFields(providerFieldValues)
            ?: throw SettingsValidationException(ProviderTestResult.Failure("Failed to create settings"))

        if (!settings.validate()) {
            throw SettingsValidationException(
                ProviderTestResult.Failure(
                    message = "Cannot connect to ${provider.name.lowercase()} provider",
                    helpText = settings.getSetupHelpText(LocalizationManager.messageResolver),
                ),
            )
        }

        return settings
    }

    /** Debounced model fetch triggered on field changes; validates connection and loads models. */
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
                    try {
                        buildValidatedSettings(provider)
                    } catch (e: SettingsValidationException) {
                        return@withContext e.failure
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
                    ProviderTestResult.Failure(
                        ErrorHandler.getUserFriendlyError(e, "fetching models", "Could not reach the provider. Please check your settings."),
                    )
                }
            }

            isFetchingModelsForConfig = false

            when (result) {
                is ProviderTestResult.Success -> {
                    connectionError = null
                    connectionErrorHelp = null
                    connectionTestSuccess = true
                    loadModelsForSelectedProvider()
                }

                is ProviderTestResult.Failure -> {
                    connectionError = result.message
                    connectionErrorHelp = result.helpText
                    connectionTestSuccess = false
                }
            }
        }
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
        templateBaseSettings = null
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
