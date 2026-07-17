/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import io.askimo.core.providers.ModelProvider.ANTHROPIC
import io.askimo.core.providers.ModelProvider.DOCKER
import io.askimo.core.providers.ModelProvider.GEMINI
import io.askimo.core.providers.ModelProvider.LMSTUDIO
import io.askimo.core.providers.ModelProvider.LOCALAI
import io.askimo.core.providers.ModelProvider.OLLAMA
import io.askimo.core.providers.ModelProvider.OPENAI
import io.askimo.core.providers.ModelProvider.OPENAI_COMPATIBLE
import io.askimo.core.providers.ModelProvider.XAI
import io.askimo.core.providers.anthropic.AnthropicModelFactory
import io.askimo.core.providers.docker.DockerAiModelFactory
import io.askimo.core.providers.gemini.GeminiModelFactory
import io.askimo.core.providers.lmstudio.LmStudioModelFactory
import io.askimo.core.providers.localai.LocalAiModelFactory
import io.askimo.core.providers.ollama.OllamaModelFactory
import io.askimo.core.providers.openai.OpenAiModelFactory
import io.askimo.core.providers.openaicompatible.OpenAiCompatibleModelFactory
import io.askimo.core.providers.xai.XAiModelFactory

/**
 * Central registry for managing chat model factories across different AI providers.
 * Mutable to allow downstream apps (e.g. Askimo Pro) to register additional factories
 * via [register] without modifying this shared module.
 */
object ProviderRegistry {

    private val factories: MutableMap<ModelProvider, ChatModelFactory<*>> = mutableMapOf(
        OPENAI to OpenAiModelFactory(),
        XAI to XAiModelFactory(),
        GEMINI to GeminiModelFactory(),
        OLLAMA to OllamaModelFactory(),
        DOCKER to DockerAiModelFactory(),
        ANTHROPIC to AnthropicModelFactory(),
        LOCALAI to LocalAiModelFactory(),
        LMSTUDIO to LmStudioModelFactory(),
        OPENAI_COMPATIBLE to OpenAiCompatibleModelFactory(),
    )

    /**
     * Registers a custom factory for the given provider.
     * Intended for downstream apps that extend the shared provider set.
     */
    fun register(provider: ModelProvider, factory: ChatModelFactory<*>) {
        factories[provider] = factory
    }

    /** Returns the factory for [provider], or null if none is registered. */
    fun getFactory(provider: ModelProvider): ChatModelFactory<*>? = factories[provider]

    /** Returns the set of providers that currently have a registered factory. */
    fun getSupportedProviders(): Set<ModelProvider> = factories.keys

    // ── Instance factory helpers ─────────────────────────────────────────────────────────────

    /**
     * Creates a new [ProviderInstance] for [providerType] with a randomly generated UUID.
     *
     * @param providerType  The provider type — must have a registered factory.
     * @param displayName   User-visible name (e.g. `"Work OpenAI"`, `"ollama-macbook"`).
     *                      Defaults to the canonical provider key (e.g. `"openai"`).
     * @param settings      Optional override settings. When null, the factory's
     *                      [ChatModelFactory.defaultSettings] are used.
     * @return A fully initialised [ProviderInstance] ready to add to [AppContextParams].
     */
    fun createInstance(
        providerType: ModelProvider,
        displayName: String = providerType.providerKey(),
        settings: ProviderSettings? = null,
    ): ProviderInstance = ProviderInstance.create(
        displayName = displayName,
        providerType = providerType,
        settings = settings,
    )

    /**
     * Returns a human-readable display name for a provider type, suitable for use as a
     * type badge in the UI (e.g. next to an instance name in the provider switcher).
     *
     * Examples: `OPENAI` → `"OpenAI"`, `OPENAI_COMPATIBLE` → `"OpenAI Compatible"`,
     * `LMSTUDIO` → `"LM Studio"`.
     */
    fun getProviderDisplayName(provider: ModelProvider): String = when (provider) {
        ModelProvider.OPENAI -> "OpenAI"
        ModelProvider.XAI -> "xAI"
        ModelProvider.GEMINI -> "Gemini"
        ModelProvider.OLLAMA -> "Ollama"
        ModelProvider.DOCKER -> "Docker AI"
        ModelProvider.ANTHROPIC -> "Anthropic"
        ModelProvider.LOCALAI -> "LocalAI"
        ModelProvider.LMSTUDIO -> "LM Studio"
        ModelProvider.OPENAI_COMPATIBLE -> "OpenAI Compatible"
        ModelProvider.ASKIMO_PRO -> "Askimo Pro"
        ModelProvider.UNKNOWN -> "Unknown"
    }
}
