/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.context

import io.askimo.core.providers.HasApiKey
import io.askimo.core.providers.HasBaseUrl
import io.askimo.core.providers.ProviderSettings

enum class ParamKey(
    val key: String,
    val type: String,
    val description: String,
    val secure: Boolean = false,
    val isSupported: (ProviderSettings) -> Boolean,
    val getValue: (AppContextParams, ProviderSettings) -> Any?,
    val setValue: (AppContextParams, ProviderSettings, String) -> Unit,
    val suggestions: List<String> = emptyList(),
) {
    MODEL_NAME(
        key = "model",
        type = "String",
        description = "Model name to use (e.g., gpt-4o, grok-4, llama3)",
        isSupported = { true },
        getValue = { params, _ -> params.model },
        setValue = { params, _, v -> params.model = v },
    ),

    API_KEY(
        key = "api_key",
        type = "String",
        secure = true,
        description = "API key for the current provider",
        isSupported = { it is HasApiKey },
        getValue = { _, settings -> (settings as HasApiKey).apiKey },
        setValue = { _, settings, v -> (settings as HasApiKey).apiKey = v },
    ),

    // ✅ Generic base URL (works for OpenAI, xAI, OpenRouter, etc.)
    BASE_URL(
        key = "base_url",
        type = "String",
        description = "Base URL for the current provider",
        isSupported = { it is HasBaseUrl },
        getValue = { _, settings -> (settings as HasBaseUrl).baseUrl },
        setValue = { _, settings, v -> (settings as HasBaseUrl).baseUrl = v },
    ),
    ;

    companion object {
        fun all(): List<ParamKey> = entries

        fun fromInput(key: String): ParamKey? = entries.find { it.key.equals(key, ignoreCase = true) }

        // List only the parameters supported by THIS provider's settings
        fun supportedFor(settings: ProviderSettings): List<ParamKey> = entries.filter { it.isSupported(settings) }
    }

    fun applyTo(
        params: AppContextParams,
        providerSettings: ProviderSettings,
        value: String,
    ) {
        try {
            setValue(params, providerSettings, value)
        } catch (e: Exception) {
            throw IllegalArgumentException("❌ Failed to set '$key': expected type $type. ${e.message}", e)
        }
    }
}
