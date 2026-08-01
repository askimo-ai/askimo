/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.openaicompatible

import io.askimo.core.providers.HasApiKey
import io.askimo.core.providers.HasBaseUrl
import io.askimo.core.providers.ProviderConfigField
import io.askimo.core.providers.ProviderSettings
import io.askimo.core.providers.SelectOption
import io.askimo.core.providers.SettingField

/**
 * Settings for OpenAI API-compatible providers (custom endpoints, proxies, etc.).
 */
data class OpenAiCompatibleSettings(
    override var apiKey: String = "",
    override var baseUrl: String = "http://localhost:8000/v1",
    override val defaultModel: String = "",
    override val utilityModel: String = "",
    override val visionModel: String = "",
    override val imageModel: String = "",
    override val embeddingModel: String = "",
    /**
     * Which OpenAI API surface to target.
     * Defaults to [OpenAiApiMode.CHAT_COMPLETIONS] for broad third-party provider compatibility.
     * Existing serialised configs without this field deserialise to the default safely.
     */
    val apiMode: OpenAiApiMode = OpenAiApiMode.CHAT_COMPLETIONS,
) : ProviderSettings,
    HasApiKey,
    HasBaseUrl {

    override fun describe(): List<String> = listOf(
        "baseUrl: $baseUrl",
        "apiKey:  ${maskApiKey()}",
        "apiMode: $apiMode",
    )

    override fun toString(): String = "OpenAiCompatibleSettings(baseUrl=$baseUrl, apiKey=${maskApiKey()}, apiMode=$apiMode)"

    override fun getFields() = listOf(
        SettingField.TextField(
            name = SettingField.BASE_URL,
            label = "Base URL",
            description = "OpenAI-compatible server URL",
            value = baseUrl,
        ),
        SettingField.TextField(
            name = SettingField.API_KEY,
            label = "API Key",
            description = "API key (optional if your server does not require it)",
            value = apiKey,
            isPassword = true,
        ),
    )

    override fun updateField(fieldName: String, value: String): ProviderSettings = when (fieldName) {
        SettingField.BASE_URL -> copy(baseUrl = value)

        SettingField.API_KEY -> copy(apiKey = value)

        SettingField.DEFAULT_MODEL -> copy(defaultModel = value)

        SettingField.UTILITY_MODEL -> copy(utilityModel = value)

        SettingField.VISION_MODEL -> copy(visionModel = value)

        SettingField.IMAGE_MODEL -> copy(imageModel = value)

        SettingField.EMBEDDING_MODEL -> copy(embeddingModel = value)

        SettingField.API_MODE -> copy(
            apiMode = runCatching { OpenAiApiMode.valueOf(value) }.getOrDefault(apiMode),
        )

        else -> this
    }

    override fun validate(): Boolean = baseUrl.isNotBlank()

    override fun getSetupHelpText(messageResolver: (String) -> String): String = messageResolver("provider.openai_compatible.setup.help")

    override fun getConfigFields(messageResolver: (String) -> String): List<ProviderConfigField> {
        val hasStoredKey = apiKey.isNotBlank() &&
            (apiKey == "***keychain***" || apiKey.startsWith("encrypted:"))

        val apiKeyDescription = if (hasStoredKey) {
            messageResolver("provider.openai_compatible.apikey.stored")
        } else {
            messageResolver("provider.openai_compatible.apikey.description")
        }

        return listOf(
            ProviderConfigField.BaseUrlField(
                description = messageResolver("provider.openai_compatible.baseurl.description"),
                value = baseUrl,
            ),
            ProviderConfigField.ApiKeyField(
                description = apiKeyDescription,
                value = apiKey,
                hasExistingValue = hasStoredKey,
            ),
            ProviderConfigField.SelectField(
                name = SettingField.API_MODE,
                label = messageResolver("provider.openai_compatible.apimode.label"),
                description = messageResolver("provider.openai_compatible.apimode.description"),
                value = apiMode.name,
                options = listOf(
                    SelectOption(
                        value = OpenAiApiMode.CHAT_COMPLETIONS.name,
                        label = messageResolver("provider.openai_compatible.apimode.chat_completions"),
                        description = "/v1/chat/completions",
                    ),
                    SelectOption(
                        value = OpenAiApiMode.RESPONSES.name,
                        label = messageResolver("provider.openai_compatible.apimode.responses"),
                        description = "/v1/responses",
                    ),
                ),
            ),
        )
    }

    override fun applyConfigFields(fields: Map<String, String>): ProviderSettings {
        val newBaseUrl = fields[SettingField.BASE_URL]?.takeIf { it.isNotBlank() } ?: baseUrl
        val newApiKey = fields[SettingField.API_KEY]?.takeIf { it.isNotBlank() } ?: apiKey
        val newApiMode = fields[SettingField.API_MODE]
            ?.let { runCatching { OpenAiApiMode.valueOf(it) }.getOrNull() }
            ?: apiMode
        return copy(baseUrl = newBaseUrl, apiKey = newApiKey, apiMode = newApiMode)
    }

    override fun deepCopy(): ProviderSettings = copy()
}
