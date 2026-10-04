/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import io.askimo.core.providers.openaicompatible.OpenAiCompatibleTemplate

/**
 * Represents a single selectable entry in the provider type-picker.
 *
 * Combines first-class native providers ([Native]), pre-configured OpenAI-compatible
 * cloud-provider templates ([Template]), and the raw custom-endpoint fallback ([Custom])
 * into one flat, sortable list — so novice users never need to know what
 * "OpenAI-compatible" means to connect OpenRouter, NVIDIA NIM, etc.
 */
sealed class ProviderEntry {

    /**
     * A native, first-class provider (OpenAI, Anthropic, Gemini, Ollama, xAI, etc.)
     * that has its own [ModelProvider] enum value and factory.
     */
    data class Native(val provider: ModelProvider) : ProviderEntry()

    /**
     * A predefined OpenAI-compatible cloud provider (OpenRouter, NVIDIA NIM, Groq, etc.).
     * Wired through [ModelProvider.OPENAI_COMPATIBLE] with pre-filled settings from [template].
     */
    data class Template(val template: OpenAiCompatibleTemplate) : ProviderEntry()

    /**
     * The "Other providers" catch-all for any custom OpenAI-compatible endpoint
     * that is not covered by a [Template]. Maps to [ModelProvider.OPENAI_COMPATIBLE]
     * with a blank base URL for the user to fill in manually.
     */
    object Custom : ProviderEntry()
}
