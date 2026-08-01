/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents supported AI model providers in the chat application.
 *
 * This enum defines the different AI service providers that can be used
 * as sources for chat models in the application.
 */
@Serializable
enum class ModelProvider {
    /**
     * Represents OpenAI's models like GPT-3.5, GPT-4, etc.
     */
    @SerialName("OPENAI")
    OPENAI,

    /**
     * Represents XAI's models like grok-3, grok3-mini, etc.
     */
    @SerialName("XAI")
    XAI,

    /**
     * Represents Gemini's models like gemini-2.5-flash, gemini-2.5-pro, etc.
     */
    @SerialName("GEMINI")
    GEMINI,

    /**
     * Represents Ollama's locally-hosted models.
     */
    @SerialName("OLLAMA")
    OLLAMA,

    /**
     * Represents Docker AI's locally-hosted models.
     */
    @SerialName("DOCKER")
    DOCKER,

    /**
     * Represents Anthropic's models
     */
    @SerialName("ANTHROPIC")
    ANTHROPIC,

    /**
     * Represents LocalAI's locally-hosted models.
     */
    @SerialName("LOCALAI")
    LOCALAI,

    /**
     * Represents LMStudio's locally-hosted models (OpenAI-compatible API).
     */
    @SerialName("LMSTUDIO")
    LMSTUDIO,

    /**
     * Represents any OpenAI API-compatible provider (e.g., custom endpoints, proxies, etc.).
     */
    @SerialName("OPENAI_COMPATIBLE")
    OPENAI_COMPATIBLE,

    /**
     * Represents the Askimo Pro managed provider.
     * The API key is managed server-side; the desktop app authenticates via JWT.
     */
    @SerialName("ASKIMO_PRO")
    ASKIMO_PRO,

    /**
     * Represents an unidentified or unsupported model provider.
     */
    @SerialName("UNKNOWN")
    UNKNOWN,
    ;

    /**
     * Returns the lowercase name of this provider for use in cache keys and logging.
     */
    fun providerKey(): String = when (this) {
        OPENAI -> "openai"
        XAI -> "xai"
        GEMINI -> "gemini"
        OLLAMA -> "ollama"
        DOCKER -> "docker-ai"
        ANTHROPIC -> "anthropic"
        LOCALAI -> "localai"
        LMSTUDIO -> "lmstudio"
        OPENAI_COMPATIBLE -> "openai-compatible"
        ASKIMO_PRO -> "askimo-pro"
        UNKNOWN -> "unknown"
    }

    /**
     * Two-letter initials shown in provider badge circles across the UI.
     */
    val initials: String get() = when (this) {
        OPENAI -> "OA"
        ANTHROPIC -> "AN"
        GEMINI -> "GM"
        XAI -> "xA"
        OLLAMA -> "OL"
        DOCKER -> "DA"
        LOCALAI -> "LA"
        LMSTUDIO -> "LM"
        OPENAI_COMPATIBLE -> "OC"
        ASKIMO_PRO -> "AP"
        UNKNOWN -> "?"
    }
}
