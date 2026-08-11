/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.openaicompatible

import io.askimo.core.providers.HttpVersion
import io.askimo.core.util.appJson
import io.askimo.core.util.httpGet
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpClient

/**
 * Predefined OpenAI-compatible cloud provider templates.
 *
 * Each entry pre-fills [baseUrl] and provides onboarding copy ([taglineKey], [helpTextKey])
 * so novice users can connect popular cloud providers without knowing the API URL.
 * All templates are wired through [io.askimo.core.providers.ModelProvider.OPENAI_COMPATIBLE]
 * under the hood — no separate factory or enum value is needed.
 */
enum class OpenAiCompatibleTemplate(
    /** Human-readable provider name shown in the picker list and config title. */
    val displayName: String,
    /** Two-letter initials shown in the circular badge in the provider picker. */
    val initials: String,
    /** i18n key for the one-sentence value proposition shown in the right-column detail panel. */
    val taglineKey: String,
    /** Pre-filled base URL for this provider's OpenAI-compatible endpoint. */
    val baseUrl: String,
    /** Whether the provider requires an API key. */
    val apiKeyRequired: Boolean,
    /** URL where the user can obtain an API key. Empty string if not applicable. */
    val apiKeyUrl: String,
    /** i18n key for the multi-line setup hint shown in the CONFIG step info card. */
    val helpTextKey: String,
    /** API mode pre-selected when the user connects via this template. */
    val apiMode: OpenAiApiMode = OpenAiApiMode.CHAT_COMPLETIONS,
    /**
     * HTTP protocol version for connections to this provider.
     * All predefined cloud providers default to [HttpVersion.HTTP_2] — they run on modern
     * infrastructure that supports multiplexing. Override to [HttpVersion.HTTP_1_1] only for
     * providers known to have HTTP/2 issues.
     */
    val httpVersion: HttpVersion = HttpVersion.HTTP_2,
    /**
     * Custom model-fetching strategy for providers whose model-list endpoint differs from the
     * standard OpenAI `GET /models`. If `null`, the default `{baseUrl}/models` fetch is used.
     *
     * Receives the resolved API key, the configured base URL, and the HTTP version;
     * returns a sorted, deduplicated list of model ID strings.
     */
    val modelFetcher: ((apiKey: String, baseUrl: String, httpVersion: HttpClient.Version) -> List<String>)? = null,
) {
    CLOUDFLARE_AI(
        displayName = "Cloudflare AI",
        initials = "CF",
        taglineKey = "provider.template.cloudflare_ai.tagline",
        baseUrl = "https://api.cloudflare.com/client/v4/accounts/{ACCOUNT_ID}/ai/v1",
        apiKeyRequired = true,
        apiKeyUrl = "https://dash.cloudflare.com/profile/api-tokens",
        helpTextKey = "provider.template.cloudflare_ai.help",
        modelFetcher = { apiKey, baseUrl, httpVersion ->
            // Cloudflare AI does not implement the standard GET /models endpoint.
            // Derive the models/search URL from the configured base URL so the user's
            // actual account ID is honoured.
            // e.g. https://api.cloudflare.com/client/v4/accounts/{ACCOUNT_ID}/ai/v1
            //   →  https://api.cloudflare.com/client/v4/accounts/{ACCOUNT_ID}/ai/models/search
            val searchUrl = baseUrl.trimEnd('/').removeSuffix("/v1") + "/models/search"
            val (_, body) = httpGet(searchUrl, headers = mapOf("Authorization" to "Bearer $apiKey"), httpVersion = httpVersion)
            val json = appJson.parseToJsonElement(body)
            json.jsonObject["result"]?.jsonArray.orEmpty()
                .mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                .distinct()
                .sorted()
        },
    ),

    GROQ(
        displayName = "Groq",
        initials = "GR",
        taglineKey = "provider.template.groq.tagline",
        baseUrl = "https://api.groq.com/openai/v1",
        apiKeyRequired = true,
        apiKeyUrl = "https://console.groq.com/keys",
        apiMode = OpenAiApiMode.RESPONSES,
        helpTextKey = "provider.template.groq.help",
    ),

    NVIDIA_NIM(
        displayName = "NVIDIA NIM",
        initials = "NV",
        taglineKey = "provider.template.nvidia_nim.tagline",
        baseUrl = "https://integrate.api.nvidia.com/v1",
        apiKeyRequired = true,
        apiKeyUrl = "https://build.nvidia.com/",
        helpTextKey = "provider.template.nvidia_nim.help",
    ),

    OPENROUTER(
        displayName = "OpenRouter",
        initials = "OR",
        taglineKey = "provider.template.openrouter.tagline",
        baseUrl = "https://openrouter.ai/api/v1",
        apiKeyRequired = true,
        apiKeyUrl = "https://openrouter.ai/keys",
        apiMode = OpenAiApiMode.RESPONSES,
        helpTextKey = "provider.template.openrouter.help",
    ),

    TOGETHER_AI(
        displayName = "Together AI",
        initials = "TA",
        taglineKey = "provider.template.together_ai.tagline",
        baseUrl = "https://api.together.xyz/v1",
        apiKeyRequired = true,
        apiKeyUrl = "https://api.together.ai/settings/api-keys",
        httpVersion = HttpVersion.HTTP_1_1,
        helpTextKey = "provider.template.together_ai.help",
    ),
}
