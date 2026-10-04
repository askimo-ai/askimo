/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.openaicompatible

import io.askimo.core.providers.HasApiKey
import io.askimo.core.providers.HasBaseUrl
import io.askimo.core.providers.HttpVersion
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
    /**
     * HTTP protocol version for connections to this endpoint.
     * Defaults to [HttpVersion.HTTP_1_1] — the safe choice for self-hosted servers
     * (uvicorn, vLLM, FastAPI). Switch to [HttpVersion.HTTP_2] for cloud endpoints.
     * Existing serialised configs without this field deserialise to the default safely.
     */
    override val httpVersion: HttpVersion = HttpVersion.HTTP_1_1,
    /**
     * Whether this instance was created from a predefined [OpenAiCompatibleTemplate].
     * When `true`, [apiMode] and [httpVersion] are locked to the template's preset
     * values and the corresponding UI fields are hidden in the config screen.
     * Set once at creation time by the wizard; never mutated through the field-map path.
     * Existing serialised configs without this field deserialise to `false` safely.
     */
    val isTemplate: Boolean = false,
    /**
     * The [OpenAiCompatibleTemplate.name] of the template this instance was created from,
     * or `null` for manually configured instances.
     * Used to look up template-specific behaviour such as a custom model-fetching strategy.
     * Existing serialised configs without this field deserialise to `null` safely.
     */
    val templateName: String? = null,
) : ProviderSettings,
    HasApiKey,
    HasBaseUrl {

    override fun describe(): List<String> = listOf(
        "baseUrl: $baseUrl",
        "apiKey:  ${maskApiKey()}",
        "apiMode: $apiMode",
        "httpVersion: $httpVersion",
    )

    override fun toString(): String = "OpenAiCompatibleSettings(baseUrl=$baseUrl, apiKey=${maskApiKey()}, apiMode=$apiMode, httpVersion=$httpVersion)"

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

        SettingField.HTTP_VERSION -> copy(
            httpVersion = runCatching { HttpVersion.valueOf(value) }.getOrDefault(httpVersion),
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

        return buildList {
            add(
                ProviderConfigField.BaseUrlField(
                    description = messageResolver("provider.openai_compatible.baseurl.description"),
                    value = baseUrl,
                ),
            )
            add(
                ProviderConfigField.ApiKeyField(
                    description = apiKeyDescription,
                    value = apiKey,
                    required = false,
                    hasExistingValue = hasStoredKey,
                ),
            )
            // API mode is always shown — power users may need to switch it even on template
            // instances (e.g. when a provider adds Responses API support after setup).
            add(
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
            // HTTP version is a low-level transport detail — hidden for template instances
            // where the correct value is already pre-set and misconfigurations silently break things.
            if (!isTemplate) {
                add(
                    ProviderConfigField.SelectField(
                        name = SettingField.HTTP_VERSION,
                        label = messageResolver("provider.openai_compatible.httpversion.label"),
                        description = messageResolver("provider.openai_compatible.httpversion.description"),
                        value = httpVersion.name,
                        options = listOf(
                            SelectOption(
                                value = HttpVersion.HTTP_1_1.name,
                                label = "HTTP/1.1",
                                description = messageResolver("provider.openai_compatible.httpversion.http1_1"),
                            ),
                            SelectOption(
                                value = HttpVersion.HTTP_2.name,
                                label = "HTTP/2",
                                description = messageResolver("provider.openai_compatible.httpversion.http2"),
                            ),
                        ),
                    ),
                )
            }
        }
    }

    override fun applyConfigFields(fields: Map<String, String>): ProviderSettings {
        val newBaseUrl = fields[SettingField.BASE_URL]?.takeIf { it.isNotBlank() } ?: baseUrl
        val newApiKey = fields[SettingField.API_KEY]?.takeIf { it.isNotBlank() } ?: apiKey
        val newApiMode = fields[SettingField.API_MODE]
            ?.let { runCatching { OpenAiApiMode.valueOf(it) }.getOrNull() }
            ?: apiMode
        val newHttpVersion = if (isTemplate) {
            httpVersion
        } else {
            fields[SettingField.HTTP_VERSION]
                ?.let { runCatching { HttpVersion.valueOf(it) }.getOrNull() }
                ?: httpVersion
        }
        return copy(baseUrl = newBaseUrl, apiKey = newApiKey, apiMode = newApiMode, httpVersion = newHttpVersion, isTemplate = isTemplate, templateName = templateName)
    }

    override fun deepCopy(): ProviderSettings = copy()
}
