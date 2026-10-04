/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.askimo.core.providers.anthropic.AnthropicSettings
import io.askimo.core.providers.gemini.GeminiSettings
import io.askimo.core.providers.openai.OpenAiSettings
import io.askimo.core.providers.openaicompatible.OpenAiCompatibleSettings
import io.askimo.core.providers.xai.XAiSettings

/**
 * HTTP protocol version used for connections to a provider endpoint.
 *
 * - [HTTP_1_1] — safe default for self-hosted servers (uvicorn, vLLM, FastAPI, Docker AI)
 *   that have incomplete or no HTTP/2 support.
 * - [HTTP_2] — for cloud endpoints and local servers that fully support HTTP/2 multiplexing.
 */
enum class HttpVersion {
    HTTP_1_1,
    HTTP_2,
}

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
    JsonSubTypes.Type(value = OpenAiCompatibleSettings::class, name = "openai_compatible"),
)
interface ProviderSettings {
    val defaultModel: String

    /**
     * HTTP protocol version for connections to this provider's endpoint.
     * Defaults to [HttpVersion.HTTP_2]. Override in settings classes that target servers
     * without full HTTP/2 support (e.g. self-hosted local servers).
     */
    val httpVersion: HttpVersion get() = HttpVersion.HTTP_2

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

    /**
     * Returns provider-specific tuning fields that users can configure per instance
     * (e.g. maxTokens, thinkingBudgetTokens for Anthropic).
     *
     * Default is empty — only providers with extra tuneable params need to override this.
     * The UI renders these generically in the model config card without any per-provider branching.
     *
     * @param messageResolver resolves i18n keys to localized strings (same contract as [getConfigFields]).
     */
    @JsonIgnore
    fun getConfigurableFields(messageResolver: (String) -> String): List<SettingField> = emptyList()

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
