/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.openaicompatible

import io.askimo.core.providers.HttpVersion

/**
 * Predefined OpenAI-compatible cloud provider templates.
 *
 * Each entry pre-fills [baseUrl] and provides onboarding copy ([tagline], [helpText])
 * so novice users can connect popular cloud providers without knowing the API URL.
 * All templates are wired through [io.askimo.core.providers.ModelProvider.OPENAI_COMPATIBLE]
 * under the hood — no separate factory or enum value is needed.
 */
enum class OpenAiCompatibleTemplate(
    /** Human-readable provider name shown in the picker list and config title. */
    val displayName: String,
    /** Two-letter initials shown in the circular badge in the provider picker. */
    val initials: String,
    /** One-sentence value proposition shown in the right-column detail panel. */
    val tagline: String,
    /** Pre-filled base URL for this provider's OpenAI-compatible endpoint. */
    val baseUrl: String,
    /** Whether the provider requires an API key. */
    val apiKeyRequired: Boolean,
    /** URL where the user can obtain an API key. Empty string if not applicable. */
    val apiKeyUrl: String,
    /** Multi-line setup hint shown in the CONFIG step info card. */
    val helpText: String,
    /** API mode pre-selected when the user connects via this template. */
    val apiMode: OpenAiApiMode = OpenAiApiMode.CHAT_COMPLETIONS,
    /** HTTP protocol version for connections to this provider. */
    val httpVersion: HttpVersion = HttpVersion.HTTP_2,
) {
    /**
     * Resource key prefix for this template's localized onboarding copy.
     * Keys follow the pattern `provider.template.<name_lowercase>.tagline` / `.help`.
     * The enum name (e.g. `CLOUDFLARE_AI`) is lowercased to `cloudflare_ai`.
     */
    val i18nKey: String get() = "provider.template.${name.lowercase()}"

    /** Resource key for the localized one-sentence value proposition. */
    val taglineKey: String get() = "$i18nKey.tagline"

    /** Resource key for the localized multi-line setup help text. */
    val helpKey: String get() = "$i18nKey.help"

    CLOUDFLARE_AI(
        displayName = "Cloudflare AI",
        initials = "CF",
        tagline = "Run AI models on Cloudflare's global edge network with Workers AI.",
        baseUrl = "https://api.cloudflare.com/client/v4/accounts/{ACCOUNT_ID}/ai/v1",
        apiKeyRequired = true,
        apiKeyUrl = "https://dash.cloudflare.com/profile/api-tokens",
        helpText = "💡 To use Cloudflare AI:\n\n" +
            "1. Find your Account ID in your Cloudflare dashboard\n" +
            "2. Replace {ACCOUNT_ID} in the Base URL with it\n" +
            "3. Create an API token with 'Workers AI' permission at:\n" +
            "   dash.cloudflare.com/profile/api-tokens",
    ),

    GROQ(
        displayName = "Groq",
        initials = "GR",
        tagline = "Ultra-fast inference for open-source models — Llama, Mixtral, Gemma and more.",
        baseUrl = "https://api.groq.com/openai/v1",
        apiKeyRequired = true,
        apiKeyUrl = "https://console.groq.com/keys",
        apiMode = OpenAiApiMode.RESPONSES,
        helpText = "💡 To use Groq:\n\n" +
            "1. Get your free API key at: console.groq.com/keys\n" +
            "2. Enter a model name, e.g. llama-3.1-8b-instant\n" +
            "3. Click Next to browse all available models",
    ),

    NVIDIA_NIM(
        displayName = "NVIDIA NIM",
        initials = "NV",
        tagline = "Run optimized AI models on NVIDIA's cloud GPU infrastructure.",
        baseUrl = "https://integrate.api.nvidia.com/v1",
        apiKeyRequired = true,
        apiKeyUrl = "https://build.nvidia.com/",
        helpText = "💡 To use NVIDIA NIM:\n\n" +
            "1. Get your API key at: build.nvidia.com\n" +
            "2. Enter a model name, e.g. meta/llama-3.1-8b-instruct\n" +
            "3. Click Next to browse all available models",
    ),

    OPENROUTER(
        displayName = "OpenRouter",
        initials = "OR",
        tagline = "Access 300+ models — GPT, Claude, Llama, Gemini, Mistral and more via one API.",
        baseUrl = "https://openrouter.ai/api/v1",
        apiKeyRequired = true,
        apiKeyUrl = "https://openrouter.ai/keys",
        apiMode = OpenAiApiMode.RESPONSES,
        helpText = "💡 To use OpenRouter:\n\n" +
            "1. Get your free API key at: openrouter.ai/keys\n" +
            "2. Click Next to browse 300+ available models\n" +
            "3. Many models have a free tier — no credit card needed",
    ),

    TOGETHER_AI(
        displayName = "Together AI",
        initials = "TA",
        tagline = "Fast, affordable inference for 100+ open-source models.",
        baseUrl = "https://api.together.xyz/v1",
        apiKeyRequired = true,
        apiKeyUrl = "https://api.together.ai/settings/api-keys",
        httpVersion = HttpVersion.HTTP_1_1,
        helpText = "💡 To use Together AI:\n\n" +
            "1. Get your API key at: api.together.ai/settings/api-keys\n" +
            "2. Click Next to browse available models,\n" +
            "   e.g. meta-llama/Llama-3-8b-chat-hf",
    ),
}
