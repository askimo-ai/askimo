/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.context

import io.askimo.core.providers.ModelProvider

/**
 * Data class representing the current session configuration.
 *
 * @property provider           The active model provider type (e.g., OPENAI, OLLAMA).
 * @property model              The name of the current model being used.
 * @property settingsDescription A list of human-readable strings describing the provider settings.
 * @property instanceId         The stable ID of the active [io.askimo.core.providers.ProviderInstance].
 *                              Empty when no instance is configured yet.
 * @property instanceDisplayName The user-visible display name of the active instance
 *                               (e.g. "ollama-macbook"). Falls back to the provider key when empty.
 */
data class AppContextConfigInfo(
    val provider: ModelProvider,
    val model: String,
    val settingsDescription: List<String>,
    val instanceId: String = "",
    val instanceDisplayName: String = "",
)

/**
 * Extension function to get configuration info from an [AppContext].
 *
 * @return An [AppContextConfigInfo] reflecting the currently active instance.
 */
fun AppContext.getConfigInfo(): AppContextConfigInfo {
    val instance = getActiveInstance()
    val provider = instance?.providerType ?: getActiveProvider()
    val settings = instance?.settings ?: getCurrentProviderSettings()
    val model = params.model

    return AppContextConfigInfo(
        provider = provider,
        model = model,
        settingsDescription = settings.describe(),
        instanceId = instance?.id ?: "",
        instanceDisplayName = instance?.displayName ?: provider.providerKey(),
    )
}
