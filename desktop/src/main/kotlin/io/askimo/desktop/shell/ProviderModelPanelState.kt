/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.askimo.core.context.AppContext
import io.askimo.core.event.EventBus
import io.askimo.core.event.error.AppErrorEvent
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.logging.logger
import io.askimo.core.providers.ChatModelFactory
import io.askimo.core.providers.ModelDTO
import io.askimo.core.providers.ProviderConfigField
import io.askimo.core.providers.ProviderInstance
import io.askimo.core.providers.ProviderInstanceService
import io.askimo.core.providers.ProviderSettings
import io.askimo.core.providers.ProviderTestResult
import io.askimo.ui.util.ErrorHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Controls what the right column of the provider panel displays. */
sealed class RightColumnMode {
    /** Shows the model list for the pending instance. */
    object Models : RightColumnMode()

    /** Shows the config edit form for [instanceId]. */
    data class EditInstance(val instanceId: String) : RightColumnMode()
}

/**
 * State holder for the two-column provider + model selection panel in the footer bar.
 *
 * ### Selection flow (two-step commit)
 * 1. Panel opens → [init] loads the instance list and pre-fetches models for the active instance.
 * 2. User clicks an instance in the left column → [selectInstanceForPreview] sets [pendingInstanceId]
 *    and lazily loads that instance's models (cached for the lifetime of the panel).
 * 3. User clicks a model in the right column → [commitSelection] atomically switches the active
 *    instance (if different) and persists the chosen model, then emits a domain event so the
 *    rest of the UI reacts.
 * 4. Panel dismisses → [reset] clears the model cache and resets to [RightColumnMode.Models].
 *
 * Nothing is written to [AppContext] until step 3 (or [saveEdit]), so dismissing without
 * picking a model is a safe no-op.
 *
 * @param scope  Composable-bound scope; coroutines are cancelled automatically on leave.
 * @param appContext  Application context used for reads/writes.
 */
class ProviderModelPanelState(
    private val scope: CoroutineScope,
    private val appContext: AppContext,
    private val providerInstanceService: ProviderInstanceService,
) {
    private val log = logger<ProviderModelPanelState>()

    // ── Instance list ────────────────────────────────────────────────────────────────────────

    /** All configured provider instances, refreshed on [init] and after mutations. */
    var availableInstances by mutableStateOf<List<ProviderInstance>>(emptyList())
        private set

    // ── Preview / pending selection ──────────────────────────────────────────────────────────

    /**
     * The instance currently highlighted in the left column.
     * Preview-only — not written to [AppContext] until [commitSelection].
     */
    var pendingInstanceId by mutableStateOf("")
        private set

    private val modelCache = mutableStateMapOf<String, List<ModelDTO>>()
    private var loadingInstances by mutableStateOf(setOf<String>())

    /** Models fetched for [pendingInstanceId]; empty until the fetch completes. */
    val pendingModels: List<ModelDTO>
        get() = modelCache[pendingInstanceId] ?: emptyList()

    /** True while models for [pendingInstanceId] are being fetched. */
    val isLoadingPending: Boolean
        get() = pendingInstanceId in loadingInstances

    // ── Right column mode ────────────────────────────────────────────────────────────────────

    /** Current right-column view — either the model list or the instance config edit form. */
    var rightColumnMode by mutableStateOf<RightColumnMode>(RightColumnMode.Models)
        private set

    // ── Edit form state (used when rightColumnMode is EditInstance) ──────────────────────────

    var editDisplayName by mutableStateOf("")
        private set

    var editDisplayNameError by mutableStateOf<String?>(null)
        private set

    var editConfigFields by mutableStateOf<List<ProviderConfigField>>(emptyList())
        private set

    var editFieldValues by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    var editConnectionError by mutableStateOf<String?>(null)
        private set

    var isTestingEdit by mutableStateOf(false)
        private set

    // ── Lifecycle ────────────────────────────────────────────────────────────────────────────

    fun init() {
        availableInstances = providerInstanceService.all
        pendingInstanceId = appContext.params.currentInstanceId
        if (pendingInstanceId.isNotBlank()) {
            fetchModels(pendingInstanceId)
        }
    }

    fun reset() {
        modelCache.clear()
        loadingInstances = emptySet()
        rightColumnMode = RightColumnMode.Models
        editDisplayName = ""
        editDisplayNameError = null
        editConfigFields = emptyList()
        editFieldValues = emptyMap()
        editConnectionError = null
        isTestingEdit = false
    }

    fun selectInstanceForPreview(instanceId: String) {
        // Switching instance while editing discards changes (cancel edit implicitly)
        if (rightColumnMode is RightColumnMode.EditInstance) {
            rightColumnMode = RightColumnMode.Models
        }
        pendingInstanceId = instanceId
        fetchModels(instanceId)
    }

    /**
     * Opens the config edit form in the right column for [instanceId].
     * Pre-populates [editDisplayName] and [editFieldValues] from the instance's current settings.
     */
    fun openEditForm(instanceId: String) {
        val instance = providerInstanceService.findById(instanceId) ?: return
        val fields = instance.settings.getConfigFields(LocalizationManager.messageResolver)
        editDisplayName = instance.displayName
        editDisplayNameError = null
        editConfigFields = fields
        editFieldValues = fields.mapNotNull { field ->
            when (field) {
                is ProviderConfigField.ApiKeyField -> field.name to field.value
                is ProviderConfigField.BaseUrlField -> field.name to field.value
                is ProviderConfigField.InfoField -> null
            }
        }.toMap()
        editConnectionError = null
        isTestingEdit = false
        rightColumnMode = RightColumnMode.EditInstance(instanceId)
    }

    /** Cancels the edit form and returns to the model list without saving. */
    fun cancelEdit() {
        editDisplayNameError = null
        editConnectionError = null
        isTestingEdit = false
        rightColumnMode = RightColumnMode.Models
    }

    fun updateEditDisplayName(name: String) {
        editDisplayName = name
        val instanceId = (rightColumnMode as? RightColumnMode.EditInstance)?.instanceId
        editDisplayNameError = if (name.isNotBlank() &&
            !providerInstanceService.isDisplayNameAvailable(name, excludingId = instanceId)
        ) {
            LocalizationManager.getString("provider.instance.name.duplicate", name.trim())
        } else {
            null
        }
    }

    fun updateEditField(fieldName: String, value: String) {
        editFieldValues = editFieldValues.toMutableMap().also { it[fieldName] = value }
    }

    /**
     * Validates the display name and tests the connection with the new settings before
     * persisting. Shows [editDisplayNameError] or [editConnectionError] on failure; on
     * success persists the instance and returns to [RightColumnMode.Models].
     */
    fun saveEdit(instanceId: String) {
        val instance = providerInstanceService.findById(instanceId) ?: return
        val newName = editDisplayName.trim().ifBlank { instance.displayName }

        // 1. Display name uniqueness check
        if (!providerInstanceService.isDisplayNameAvailable(newName, excludingId = instanceId)) {
            editDisplayNameError = LocalizationManager.getString("provider.instance.name.duplicate", newName)
            return
        }
        editDisplayNameError = null

        // 2. Build updated settings
        val updatedSettings = instance.settings.applyConfigFields(editFieldValues)
        val updatedInstance = instance.copy(displayName = newName, settings = updatedSettings)

        // 3. Test connection by fetching models with the new settings
        isTestingEdit = true
        editConnectionError = null

        scope.launch {
            val testResult = withContext(Dispatchers.IO) {
                try {
                    val factory = appContext.getModelFactory(instance.providerType)

                    @Suppress("UNCHECKED_CAST")
                    val models = (factory as? ChatModelFactory<ProviderSettings>)
                        ?.availableModels(updatedSettings)
                        ?: emptyList()
                    if (models.isNotEmpty()) {
                        ProviderTestResult.Success
                    } else {
                        ProviderTestResult.Failure(
                            message = LocalizationManager.getString("provider.connection.failed"),
                        )
                    }
                } catch (e: Exception) {
                    log.error("Connection test failed for instance $instanceId", e)
                    ProviderTestResult.Failure(
                        message = ErrorHandler.getUserFriendlyError(e, "testing connection", "Could not reach the provider. Please check your settings."),
                    )
                }
            }

            isTestingEdit = false

            when (testResult) {
                is ProviderTestResult.Success -> {
                    editConnectionError = null
                    try {
                        withContext(Dispatchers.IO) {
                            providerInstanceService.update(updatedInstance).getOrThrow()
                        }
                        availableInstances = providerInstanceService.all
                        // Invalidate cached models — settings may have changed, then re-fetch
                        modelCache.remove(instanceId)
                        fetchModels(instanceId)
                        rightColumnMode = RightColumnMode.Models
                    } catch (e: Exception) {
                        log.error("Failed to save edit for instance $instanceId", e)
                        editConnectionError = e.message ?: "Failed to save changes"
                    }
                }

                is ProviderTestResult.Failure -> {
                    editConnectionError = testResult.message
                }
            }
        }
    }

    fun commitSelection(instanceId: String, modelId: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    providerInstanceService.setModel(instanceId, modelId).getOrThrow()
                }
            } catch (e: Exception) {
                log.error("Failed to commit selection (instance=$instanceId, model=$modelId)", e)
            }
        }
    }

    fun deleteInstance(instanceId: String) {
        // If deleting the instance currently being edited, close the form
        if (rightColumnMode is RightColumnMode.EditInstance &&
            (rightColumnMode as RightColumnMode.EditInstance).instanceId == instanceId
        ) {
            rightColumnMode = RightColumnMode.Models
        }

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    providerInstanceService.delete(instanceId).getOrThrow()
                }
                modelCache.remove(instanceId)
                availableInstances = providerInstanceService.all

                if (pendingInstanceId == instanceId) {
                    val next = availableInstances.firstOrNull()
                    pendingInstanceId = next?.id ?: ""
                    if (next != null) fetchModels(next.id)
                }
            } catch (e: Exception) {
                log.error("Failed to delete instance $instanceId", e)
            }
        }
    }

    private fun fetchModels(instanceId: String) {
        if (modelCache.containsKey(instanceId) || instanceId in loadingInstances) return
        val instance = appContext.params.providerInstances.firstOrNull { it.id == instanceId } ?: return

        loadingInstances = loadingInstances + instanceId
        scope.launch {
            try {
                val models = withContext(Dispatchers.IO) {
                    val settings = appContext.getInstanceSettings(instanceId)
                        ?: appContext.getCurrentProviderSettings()
                    val factory = appContext.getModelFactory(instance.providerType)
                    @Suppress("UNCHECKED_CAST")
                    (factory as? ChatModelFactory<ProviderSettings>)
                        ?.availableModels(settings)
                        ?: emptyList()
                }
                modelCache[instanceId] = models
            } catch (e: Exception) {
                log.error("Failed to load models for instance $instanceId (${instance.providerType})", e)
                modelCache[instanceId] = emptyList()
                EventBus.post(
                    AppErrorEvent(
                        title = "Failed to load models",
                        message = ErrorHandler.getUserFriendlyError(e, "loading models for ${instance.providerType}"),
                        cause = e,
                    ),
                )
            } finally {
                loadingInstances = loadingInstances - instanceId
            }
        }
    }
}
