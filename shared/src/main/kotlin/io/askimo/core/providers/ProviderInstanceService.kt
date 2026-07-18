/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import io.askimo.core.context.AppContext
import io.askimo.core.error.AppError
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.ModelChangedEvent
import io.askimo.core.logging.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ProviderInstanceService(private val appContext: AppContext) {

    private val log = logger<ProviderInstanceService>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** All configured instances, in insertion order. */
    val all: List<ProviderInstance>
        get() = appContext.params.providerInstances.toList()

    /** The currently active instance, or null if none is configured. */
    val active: ProviderInstance?
        get() = appContext.params.activeInstance

    /** Finds an instance by its stable [id], or null if not found. */
    fun findById(id: String): ProviderInstance? = appContext.params.providerInstances.firstOrNull { it.id == id }

    /**
     * Returns `true` when [displayName] (trimmed, case-insensitive) is not already taken by
     * any instance other than [excludingId].
     * Pass [excludingId] when validating an edit so the instance's own name is not a conflict.
     */
    fun isDisplayNameAvailable(displayName: String, excludingId: String? = null): Boolean {
        val normalized = displayName.trim().lowercase()
        return normalized.isNotEmpty() && appContext.params.providerInstances.none { instance ->
            instance.id != excludingId &&
                instance.displayName.trim().lowercase() == normalized
        }
    }

    /**
     * Adds a new [instance], enforcing display-name uniqueness.
     * On success the instance becomes active and a [ModelChangedEvent] is emitted.
     *
     * @return [Result.failure] with [AppError.DuplicateEntry] if the display name is taken,
     *         or [AppError.Unexpected] on persistence failure.
     */
    fun add(instance: ProviderInstance): Result<ProviderInstance> {
        if (!isDisplayNameAvailable(instance.displayName)) {
            return Result.failure(AppError.DuplicateEntry("display name", instance.displayName))
        }
        return try {
            appContext.upsertInstance(instance)
            appContext.setCurrentInstance(instance.id)
            appContext.save()
            emitEvent(ModelChangedEvent(provider = instance.providerType, newModel = instance.settings.defaultModel, instanceId = instance.id))
            log.debug("Added provider instance '{}' ({})", instance.displayName, instance.providerType)
            Result.success(instance)
        } catch (e: Exception) {
            log.error("Failed to add provider instance '{}'", instance.displayName, e)
            Result.failure(AppError.Unexpected("Failed to add provider instance '${instance.displayName}'", e))
        }
    }

    /**
     * Updates an existing instance in-place, enforcing display-name uniqueness against all
     * *other* instances. Emits [ModelChangedEvent] if the instance is currently active.
     *
     * @return [Result.failure] with [AppError.NotFound], [AppError.DuplicateEntry], or
     *         [AppError.Unexpected] as appropriate.
     */
    fun update(instance: ProviderInstance): Result<ProviderInstance> {
        if (findById(instance.id) == null) {
            return Result.failure(AppError.NotFound(instance.id, "Provider instance"))
        }
        if (!isDisplayNameAvailable(instance.displayName, excludingId = instance.id)) {
            return Result.failure(AppError.DuplicateEntry("display name", instance.displayName))
        }
        return try {
            appContext.upsertInstance(instance)
            appContext.save()
            if (instance.id == appContext.params.currentInstanceId) {
                emitEvent(ModelChangedEvent(provider = instance.providerType, newModel = instance.settings.defaultModel, instanceId = instance.id))
            }
            log.debug("Updated provider instance '{}' ({})", instance.displayName, instance.providerType)
            Result.success(instance)
        } catch (e: Exception) {
            log.error("Failed to update provider instance '{}'", instance.id, e)
            Result.failure(AppError.Unexpected("Failed to update provider instance '${instance.id}'", e))
        }
    }

    /**
     * Removes the instance with [instanceId]. If it was active, promotes the next one
     * and emits [ModelChangedEvent].
     *
     * @return [Result.failure] with [AppError.NotFound] or [AppError.Unexpected].
     */
    fun delete(instanceId: String): Result<Unit> {
        val instance = findById(instanceId)
            ?: return Result.failure(AppError.NotFound(instanceId, "Provider instance"))
        val wasActive = instanceId == appContext.params.currentInstanceId
        return try {
            appContext.removeInstance(instanceId)
            appContext.save()
            if (wasActive) {
                val next = appContext.params.providerInstances.firstOrNull()
                if (next != null) appContext.setCurrentInstance(next.id)
                emitEvent(
                    ModelChangedEvent(
                        provider = next?.providerType ?: instance.providerType,
                        newModel = next?.settings?.defaultModel ?: "",
                        instanceId = next?.id ?: "",
                    ),
                )
            }
            log.debug("Deleted provider instance '{}' ({})", instance.displayName, instance.providerType)
            Result.success(Unit)
        } catch (e: Exception) {
            log.error("Failed to delete provider instance '{}'", instanceId, e)
            Result.failure(AppError.Unexpected("Failed to delete provider instance '$instanceId'", e))
        }
    }

    /**
     * Makes [instanceId] the active instance, persists, and emits [ModelChangedEvent].
     *
     * @return [Result.failure] with [AppError.NotFound] or [AppError.Unexpected].
     */
    fun setActive(instanceId: String): Result<Unit> {
        val instance = findById(instanceId)
            ?: return Result.failure(AppError.NotFound(instanceId, "Provider instance"))
        return try {
            appContext.setCurrentInstance(instanceId)
            appContext.save()
            emitEvent(ModelChangedEvent(provider = instance.providerType, newModel = instance.settings.defaultModel, instanceId = instanceId))
            Result.success(Unit)
        } catch (e: Exception) {
            log.error("Failed to set active instance '{}'", instanceId, e)
            Result.failure(AppError.Unexpected("Failed to activate provider instance '$instanceId'", e))
        }
    }

    /**
     * Persists [modelId] as the default model for [instanceId], makes it active if not already,
     * and emits [ModelChangedEvent].
     *
     * @return [Result.failure] with [AppError.NotFound] or [AppError.Unexpected].
     */
    fun setModel(instanceId: String, modelId: String): Result<Unit> {
        val instance = findById(instanceId)
            ?: return Result.failure(AppError.NotFound(instanceId, "Provider instance"))
        return try {
            val updated = instance.copy(
                settings = instance.settings.updateField(SettingField.DEFAULT_MODEL, modelId),
            )
            appContext.upsertInstance(updated)
            if (instanceId != appContext.params.currentInstanceId) {
                appContext.setCurrentInstance(instanceId)
            }
            appContext.save()
            emitEvent(ModelChangedEvent(provider = instance.providerType, newModel = modelId, instanceId = instanceId))
            Result.success(Unit)
        } catch (e: Exception) {
            log.error("Failed to set model '{}' for instance '{}'", modelId, instanceId, e)
            Result.failure(AppError.Unexpected("Failed to set model '$modelId' for instance '$instanceId'", e))
        }
    }

    /** Fire-and-forget event emission — [EventBus.emit] is suspend so we use a background scope. */
    private fun emitEvent(event: ModelChangedEvent) {
        serviceScope.launch { EventBus.emit(event) }
    }
}
