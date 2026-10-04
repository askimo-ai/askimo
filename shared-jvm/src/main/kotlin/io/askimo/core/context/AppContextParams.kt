/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.context

import com.fasterxml.jackson.annotation.JsonIgnore
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ProviderInstance
import io.askimo.core.providers.SettingField

data class AppContextParams(
    /**
     * The ID of the currently active [ProviderInstance].
     * Empty string means no provider is configured yet.
     */
    var currentInstanceId: String = "",

    /**
     * All configured provider instances, ordered by creation time.
     * Multiple instances of the same [ModelProvider] type are allowed.
     */
    var providerInstances: MutableList<ProviderInstance> = mutableListOf(),
) {
    companion object {
        fun noOp(): AppContextParams = AppContextParams()
    }

    // ── Active-instance helpers ──────────────────────────────────────────────────────────────

    /**
     * Returns the currently active [ProviderInstance], or null if none is configured.
     */
    @get:JsonIgnore
    val activeInstance: ProviderInstance?
        get() = providerInstances.firstOrNull { it.id == currentInstanceId }

    /**
     * Returns the [ModelProvider] of the active instance, or [ModelProvider.UNKNOWN] if none.
     */
    @get:JsonIgnore
    val activeProviderType: ModelProvider
        get() = activeInstance?.providerType ?: ModelProvider.UNKNOWN

    /**
     * Current model for the active instance — reads/writes defaultModel on the active
     * instance's settings.
     */
    @get:JsonIgnore
    @set:JsonIgnore
    var model: String
        get() = activeInstance?.settings?.defaultModel ?: ""
        set(value) {
            val instance = activeInstance ?: return
            val updated = instance.copy(settings = instance.settings.updateField(SettingField.DEFAULT_MODEL, value))
            replaceInstance(updated)
        }

    /**
     * Gets the last-used model for a specific instance by ID.
     */
    fun getModelForInstance(instanceId: String): String = providerInstances.firstOrNull { it.id == instanceId }?.settings?.defaultModel ?: ""

    // ── Instance management helpers ──────────────────────────────────────────────────────────

    /**
     * Adds [instance] to [providerInstances] if no instance with the same [ProviderInstance.id]
     * already exists; otherwise replaces the existing entry in-place.
     */
    fun upsertInstance(instance: ProviderInstance) {
        val idx = providerInstances.indexOfFirst { it.id == instance.id }
        if (idx >= 0) {
            providerInstances[idx] = instance
        } else {
            providerInstances.add(instance)
        }
    }

    /**
     * Replaces the instance with the same [ProviderInstance.id] in [providerInstances].
     * No-op if no matching instance is found.
     */
    fun replaceInstance(instance: ProviderInstance) {
        val idx = providerInstances.indexOfFirst { it.id == instance.id }
        if (idx >= 0) providerInstances[idx] = instance
    }

    /**
     * Removes the instance with [instanceId] from [providerInstances].
     * If the removed instance was the active one, [currentInstanceId] is cleared.
     */
    fun removeInstance(instanceId: String) {
        providerInstances.removeIf { it.id == instanceId }
        if (currentInstanceId == instanceId) currentInstanceId = ""
    }

    /**
     * Returns instances grouped by [ModelProvider] type, preserving insertion order.
     */
    @get:JsonIgnore
    val instancesByType: Map<ModelProvider, List<ProviderInstance>>
        get() = providerInstances.groupBy { it.providerType }

    /**
     * Returns all instances whose [ProviderInstance.providerType] matches [provider].
     */
    fun instancesForType(provider: ModelProvider): List<ProviderInstance> = providerInstances.filter { it.providerType == provider }

    override fun toString(): String {
        val maskedInstances = providerInstances.map { instance ->
            "${instance.displayName}(${instance.providerType})"
        }
        return "AppContextParams(currentInstanceId=$currentInstanceId, instances=$maskedInstances)"
    }
}
