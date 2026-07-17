/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import java.util.UUID

/**
 * Represents a named, user-defined configuration instance for a specific AI provider type.
 *
 * Decouples the *provider type* ([ModelProvider] enum) from a *provider instance* —
 * allowing multiple independent configurations of the same provider type to coexist.
 *
 * Example: two Ollama instances pointing at different hosts:
 * ```
 * ProviderInstance(
 *     id = "a1b2c3d4-...",
 *     displayName = "ollama-macbook",
 *     providerType = ModelProvider.OLLAMA,
 *     settings = OllamaSettings(baseUrl = "http://localhost:11434", defaultModel = "llama3")
 * )
 * ProviderInstance(
 *     id = "e5f6g7h8-...",
 *     displayName = "ollama-server",
 *     providerType = ModelProvider.OLLAMA,
 *     settings = OllamaSettings(baseUrl = "http://machine-b:11434", defaultModel = "llama3")
 * )
 * ```
 *
 * @property id           Stable UUID string uniquely identifying this instance across restarts.
 * @property displayName  User-visible name shown in the provider switcher (e.g. "Work OpenAI", "ollama-local").
 * @property providerType The [ModelProvider] type — determines which [ChatModelFactory] handles this instance.
 * @property settings     Provider-specific configuration (API key, base URL, model name, etc.).
 */
data class ProviderInstance(
    val id: String,
    val displayName: String,
    val providerType: ModelProvider,
    val settings: ProviderSettings,
) {
    companion object {
        /**
         * Creates a new [ProviderInstance] with a randomly generated stable UUID.
         *
         * @param displayName   User-visible name for this instance.
         * @param providerType  The provider type.
         * @param settings      Provider-specific settings; defaults to the factory's [ChatModelFactory.defaultSettings]
         *                      if not provided (requires a factory to be registered in [ProviderRegistry]).
         */
        fun create(
            displayName: String,
            providerType: ModelProvider,
            settings: ProviderSettings? = null,
        ): ProviderInstance {
            val resolvedSettings = settings
                ?: ProviderRegistry.getFactory(providerType)?.defaultSettings()
                ?: NoopProviderSettings
            return ProviderInstance(
                id = UUID.randomUUID().toString(),
                displayName = displayName,
                providerType = providerType,
                settings = resolvedSettings,
            )
        }

        /**
         * Migrates a legacy [ModelProvider] → [ProviderSettings] map entry into a
         * [ProviderInstance]. The generated instance ID is deterministic (derived from
         * the provider name) so that the same entry always migrates to the same ID,
         * enabling idempotent migrations.
         *
         * @param providerType The provider type from the old map key.
         * @param settings     The settings from the old map value.
         * @return A [ProviderInstance] with a stable, deterministic ID.
         */
        fun fromLegacy(providerType: ModelProvider, settings: ProviderSettings): ProviderInstance = ProviderInstance(
            id = UUID.nameUUIDFromBytes(providerType.name.toByteArray()).toString(),
            displayName = providerType.providerKey(),
            providerType = providerType,
            settings = settings,
        )
    }

    /**
     * Returns a copy of this instance with [settings] updated via [ProviderSettings.updateField].
     */
    fun withUpdatedField(fieldName: String, value: String): ProviderInstance = copy(settings = settings.updateField(fieldName, value))

    /**
     * Returns a copy of this instance with the given config fields applied to [settings].
     */
    fun withAppliedConfigFields(fields: Map<String, String>): ProviderInstance = copy(settings = settings.applyConfigFields(fields))

    override fun toString(): String = "ProviderInstance(id=$id, displayName='$displayName', providerType=$providerType)"
}
