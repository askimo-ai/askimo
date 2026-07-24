/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.askimo.core.providers.anthropic.AnthropicSettings
import io.askimo.core.providers.docker.DockerAiSettings
import io.askimo.core.providers.gemini.GeminiSettings
import io.askimo.core.providers.lmstudio.LmStudioSettings
import io.askimo.core.providers.localai.LocalAiSettings
import io.askimo.core.providers.ollama.OllamaSettings
import io.askimo.core.providers.openai.OpenAiSettings
import io.askimo.core.providers.openaicompatible.OpenAiCompatibleSettings
import io.askimo.core.providers.xai.XAiSettings

/**
 * Marker interface for model provider-specific configuration settings.
 *
 * This interface is implemented by various provider-specific settings classes
 * that contain configuration parameters needed for different LLM providers
 * (like OpenAI, Ollama, etc.). Each implementation contains the specific
 * parameters required by its respective provider.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type", defaultImpl = OpenAiCompatibleSettings::class)
@JsonSubTypes(
    JsonSubTypes.Type(value = OpenAiSettings::class, name = "openai"),
    JsonSubTypes.Type(value = AnthropicSettings::class, name = "anthropic"),
    JsonSubTypes.Type(value = GeminiSettings::class, name = "gemini"),
    JsonSubTypes.Type(value = XAiSettings::class, name = "xai"),
    JsonSubTypes.Type(value = OllamaSettings::class, name = "ollama"),
    JsonSubTypes.Type(value = DockerAiSettings::class, name = "docker"),
    JsonSubTypes.Type(value = LocalAiSettings::class, name = "localai"),
    JsonSubTypes.Type(value = LmStudioSettings::class, name = "lmstudio"),
    JsonSubTypes.Type(value = OpenAiCompatibleSettings::class, name = "openai_compatible"),
)
interface ProviderSettings {
    val defaultModel: String

    /**
     * Per-instance override for the utility/secondary model.
     * Blank means "use the provider-type default from AppConfig.models".
     */
    val utilityModel: String get() = ""

    /**
     * Per-instance override for the vision model.
     * Blank means "use the provider-type default from AppConfig.models".
     */
    val visionModel: String get() = ""

    /**
     * Per-instance override for the image-generation model.
     * Blank means "use the provider-type default from AppConfig.models".
     */
    val imageModel: String get() = ""

    /**
     * Per-instance override for the embedding model.
     * Blank means "use the provider-type default from AppConfig.models".
     */
    val embeddingModel: String get() = ""

    @JsonIgnore
    fun describe(): List<String>

    @JsonIgnore
    fun getFields(): List<SettingField>

    fun updateField(fieldName: String, value: String): ProviderSettings

    @JsonIgnore
    fun validate(): Boolean = true

    @JsonIgnore
    fun getSetupHelpText(messageResolver: (String) -> String): String = "Please check your provider configuration."

    @JsonIgnore
    fun getConfigFields(messageResolver: (String) -> String): List<ProviderConfigField> = emptyList()

    @JsonIgnore
    fun applyConfigFields(fields: Map<String, String>): ProviderSettings = this

    @JsonIgnore
    fun deepCopy(): ProviderSettings
}

interface HasApiKey {
    var apiKey: String

    /**
     * Masks the API key for safe logging and display.
     * Shows first 4 characters followed by asterisks, or just asterisks for short/special keys.
     */
    fun maskApiKey(): String {
        if (apiKey.isBlank()) return "****"
        if (apiKey == "***keychain***" || apiKey.startsWith("encrypted:")) return "****"

        return when {
            apiKey.length <= 4 -> "****"
            else -> "${apiKey.take(4)}****"
        }
    }
}

interface HasBaseUrl {
    var baseUrl: String
}
