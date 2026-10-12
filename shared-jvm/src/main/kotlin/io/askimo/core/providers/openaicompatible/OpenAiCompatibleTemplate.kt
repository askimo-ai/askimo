/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.openaicompatible

import io.askimo.core.providers.HttpVersion
import io.askimo.core.providers.llama.LlamaServerProcessManager
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
    /**
     * Custom embedding-model-fetching strategy for providers whose embedding catalog is served
     * from a *different* endpoint than the chat/completion model catalog ([modelFetcher]).
     *
     * If `null`, callers fall back to filtering [modelFetcher] / the default `/models` listing
     * by keyword (see [io.askimo.core.providers.filterModelsForType]), which only works when
     * the provider actually includes embedding models in its standard model list.
     *
     * Receives the resolved API key, the configured base URL, and the HTTP version;
     * returns a sorted, deduplicated list of model ID strings.
     */
    val embeddingModelFetcher: ((apiKey: String, baseUrl: String, httpVersion: HttpClient.Version) -> List<String>)? = null,
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
                // `/models/search` also returns user fine-tunes / custom AI Gateway "functions",
                // whose `name` is a bare UUID (e.g. "4df48b4f-e3c5-4ade-82c7-c06b65e25d18"). Those
                // are NOT invokable through the OpenAI-compatible /ai/v1/chat/completions route —
                // calling one 404s with "Function '<uuid>': Not found for account '<id>'".
                // Only catalog models (prefixed "@cf/") work via the v1 compat endpoint.
                .filter { it.startsWith("@cf/") }
                .distinct()
                .sorted()
        },
    ),

    DEEPSEEK(
        displayName = "DeepSeek",
        initials = "DS",
        taglineKey = "provider.template.deepseek.tagline",
        baseUrl = "https://api.deepseek.com/v1",
        apiKeyRequired = true,
        apiKeyUrl = "https://platform.deepseek.com/api_keys",
        helpTextKey = "provider.template.deepseek.help",
    ),

    HUGGING_FACE(
        displayName = "Hugging Face",
        initials = "HF",
        taglineKey = "provider.template.hugging_face.tagline",
        baseUrl = "https://router.huggingface.co/v1",
        apiKeyRequired = true,
        apiKeyUrl = "https://huggingface.co/settings/tokens",
        helpTextKey = "provider.template.hugging_face.help",
        apiMode = OpenAiApiMode.RESPONSES,
        httpVersion = HttpVersion.HTTP_1_1,
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
        // OpenRouter's standard GET /models endpoint only lists chat/completion models.
        // Embedding models are served from a separate endpoint, so they must be fetched here
        // explicitly — otherwise the embedding-model picker would show 0 usable results.
        embeddingModelFetcher = { apiKey, baseUrl, httpVersion ->
            val url = baseUrl.trimEnd('/').removeSuffix("/v1") + "/v1/embeddings/models?limit=500"
            val (_, body) = httpGet(url, headers = mapOf("Authorization" to "Bearer $apiKey"), httpVersion = httpVersion)
            val json = appJson.parseToJsonElement(body)
            json.jsonObject["data"]?.jsonArray.orEmpty()
                .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                .distinct()
                .sorted()
        },
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

    LITE_LLM(
        displayName = "LiteLLM",
        initials = "LL",
        taglineKey = "provider.template.lite_llm.tagline",
        baseUrl = "http://localhost:4000/v1",
        apiKeyRequired = false,
        apiKeyUrl = "",
        helpTextKey = "provider.template.lite_llm.help",
    ),

    LMSTUDIO(
        displayName = "LM Studio",
        initials = "LM",
        taglineKey = "provider.template.lmstudio.tagline",
        baseUrl = "http://localhost:1234/v1",
        apiKeyRequired = false,
        apiKeyUrl = "",
        helpTextKey = "provider.template.lmstudio.help",
        apiMode = OpenAiApiMode.CHAT_COMPLETIONS,
        httpVersion = HttpVersion.HTTP_1_1,
    ),

    DOCKER_AI(
        displayName = "Docker Model Runner",
        initials = "DA",
        taglineKey = "provider.template.docker_ai.tagline",
        baseUrl = "http://localhost:12434/v1",
        apiKeyRequired = false,
        apiKeyUrl = "",
        helpTextKey = "provider.template.docker_ai.help",
        apiMode = OpenAiApiMode.RESPONSES,
        httpVersion = HttpVersion.HTTP_1_1,
    ),

    LOCALAI(
        displayName = "LocalAI",
        initials = "LA",
        taglineKey = "provider.template.localai.tagline",
        baseUrl = "http://localhost:8080/v1",
        apiKeyRequired = false,
        apiKeyUrl = "",
        helpTextKey = "provider.template.localai.help",
        apiMode = OpenAiApiMode.CHAT_COMPLETIONS,
        httpVersion = HttpVersion.HTTP_1_1,
    ),

    OLLAMA(
        displayName = "Ollama",
        initials = "OL",
        taglineKey = "provider.template.ollama.tagline",
        baseUrl = "http://localhost:11434/v1",
        apiKeyRequired = false,
        apiKeyUrl = "",
        helpTextKey = "provider.template.ollama.help",
        apiMode = OpenAiApiMode.RESPONSES,
        httpVersion = HttpVersion.HTTP_1_1,
    ),

    /**
     * Askimo's built-in local LLM — no external server (Ollama, LM Studio) required.
     *
     * Unlike every other entry here, [baseUrl] is a placeholder, not a connectable address.
     * [LlamaServerProcessManager] binds its reverse proxy to a fresh free port each app launch,
     * so the real connection always resolves the *live* [LlamaServerProcessManager.proxyBaseUrl]
     * lazily in [OpenAiCompatibleModelFactory.effectiveBaseUrl] instead. Reading `proxyBaseUrl`
     * here directly would start the local HTTP listener just from touching
     * [OpenAiCompatibleTemplate.entries] (e.g. opening the provider picker), since every enum
     * constant is evaluated eagerly — even if the user never selects the local LLM.
     *
     * [apiKeyRequired] is `false` — the proxy accepts any/no key. Gating on whether the
     * `llama-server` binary and at least one `.gguf` model have been downloaded yet is the
     * Settings UI's responsibility (see `io.askimo.core.providers.llama.LlamaServerDownloader`
     * and `io.askimo.core.providers.llama.LlamaModelDownloader`), not this template's.
     */
    ASKIMO_LOCAL_LLM(
        displayName = "Local LLM (built-in)",
        initials = "AI",
        taglineKey = "provider.template.askimo_local_llm.tagline",
        baseUrl = "http://127.0.0.1/v1",
        apiKeyRequired = false,
        apiKeyUrl = "",
        helpTextKey = "provider.template.askimo_local_llm.help",
        apiMode = OpenAiApiMode.CHAT_COMPLETIONS,
        httpVersion = HttpVersion.HTTP_1_1,
    ),
}
