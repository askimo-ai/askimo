/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.askimo.core.context.AppContext
import io.askimo.core.context.getConfigInfo
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.ModelChangedEvent
import io.askimo.core.event.internal.ProviderInstanceSavedEvent
import io.askimo.core.logging.logger
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ProviderInstance
import io.askimo.core.providers.ProviderInstanceService
import io.askimo.core.providers.SettingField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the AI Provider settings section, the settings-config dialog, and the
 * top-level settings shell that hosts the provider wizard.
 *
 * Responsibilities:
 * - Active-configuration display ([provider], [model], [instanceDisplayName], [activeInstanceState])
 * - Per-instance model overrides ([updateInstanceModelOverride])
 * - Settings-config dialog ([showSettingsDialog], [settingsFields], [updateSettingsField])
 * - Wizard lifecycle: creates a fresh [ProviderWizardViewModel] on open, tears it down on close
 *
 * Listens for [ModelChangedEvent] and [ProviderInstanceSavedEvent] on [EventBus] to keep
 * displayed configuration in sync without direct coupling to other VMs.
 */
class AIProviderViewModel(
    private val scope: CoroutineScope,
    private val appContext: AppContext,
    private val providerInstanceService: ProviderInstanceService,
) {
    private val log = logger<AIProviderViewModel>()

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

    /**
     * Observable snapshot of the active [ProviderInstance].
     * Updated by [loadConfiguration] and [updateInstanceModelOverride].
     */
    var activeInstanceState by mutableStateOf<ProviderInstance?>(null)
        private set

    var settingsDescription by mutableStateOf<List<String>>(emptyList())
        private set

    // ── Feedback ─────────────────────────────────────────────────────────────────────────────

    var showSuccessMessage by mutableStateOf(false)
        private set

    var successMessage by mutableStateOf("")
        private set

    // ── Settings-config dialog ────────────────────────────────────────────────────────────────

    var showSettingsDialog by mutableStateOf(false)
        private set

    var settingsFields by mutableStateOf<List<SettingField>>(emptyList())
        private set

    // ── Wizard ────────────────────────────────────────────────────────────────────────────────

    /**
     * Non-null while the provider add/edit wizard is open.
     * Created fresh by [openAddProviderWizard] / [openEditProviderWizard] and set back to null
     * when the wizard calls its [onClose] callback.
     */
    var wizardViewModel by mutableStateOf<ProviderWizardViewModel?>(null)
        private set

    init {
        loadConfiguration()

        scope.launch {
            EventBus.internalEvents.filterIsInstance<ModelChangedEvent>().collect { event ->
                model = event.newModel
                loadConfiguration()
            }
        }

        scope.launch {
            EventBus.internalEvents.filterIsInstance<ProviderInstanceSavedEvent>().collect { event ->
                loadConfiguration()
                successMessage = if (event.isNewInstance) {
                    "Provider \"${event.displayName}\" added"
                } else {
                    "Provider settings updated"
                }
                showSuccessMessage = true
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
        activeInstanceState = providerInstanceService.findById(configInfo.instanceId)
    }

    // ── Model override (from the model-config card) ───────────────────────────────────────────

    /**
     * Persists a per-instance special model override (utility / vision / image / embedding)
     * immediately in memory and asynchronously to disk.
     *
     * @param instanceId The ID of the instance to update.
     * @param fieldName  One of [SettingField.UTILITY_MODEL], [SettingField.VISION_MODEL],
     *                   [SettingField.IMAGE_MODEL], or [SettingField.EMBEDDING_MODEL].
     * @param value      The model name to store, or blank to clear the override.
     */
    fun updateInstanceModelOverride(instanceId: String, fieldName: String, value: String) {
        val instance = providerInstanceService.findById(instanceId) ?: return
        val updatedSettings = instance.settings.updateField(fieldName, value)

        appContext.setInstanceSettings(instanceId, updatedSettings)
        activeInstanceState = instance.copy(settings = updatedSettings)

        scope.launch {
            withContext(Dispatchers.IO) {
                appContext.save()
            }
        }
    }

    // ── Wizard lifecycle ──────────────────────────────────────────────────────────────────────

    /**
     * Entry point when the user clicks the "Change provider" / "Add" button.
     * Always opens the wizard in add mode.
     */
    fun onChangeProvider() {
        openAddProviderWizard()
    }

    /** Opens a fresh [ProviderWizardViewModel] in add mode (starts at TYPE_PICKER step). */
    fun openAddProviderWizard() {
        wizardViewModel = ProviderWizardViewModel(
            scope = scope,
            appContext = appContext,
            providerInstanceService = providerInstanceService,
            onClose = { wizardViewModel = null },
        ).also { it.initAddMode() }
    }

    /** Opens a fresh [ProviderWizardViewModel] in edit mode (starts at CONFIG step, pre-populated). */
    fun openEditProviderWizard(instance: ProviderInstance) {
        wizardViewModel = ProviderWizardViewModel(
            scope = scope,
            appContext = appContext,
            providerInstanceService = providerInstanceService,
            onClose = { wizardViewModel = null },
        ).also { it.initEditMode(instance) }
    }

    // ── Settings-config dialog ────────────────────────────────────────────────────────────────

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
}

/** Backward-compat alias — prefer [AIProviderViewModel] in new code. */
typealias SettingsViewModel = AIProviderViewModel
