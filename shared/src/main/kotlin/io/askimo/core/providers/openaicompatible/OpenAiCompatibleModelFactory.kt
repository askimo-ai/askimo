/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.openaicompatible

import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.openai.OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder
import io.askimo.core.config.AppConfig
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.OpenAiCompatibleChatModelFactory
import io.askimo.core.util.ApiKeyUtils.safeApiKey

class OpenAiCompatibleModelFactory : OpenAiCompatibleChatModelFactory<OpenAiCompatibleSettings>() {

    override fun getProvider(): ModelProvider = ModelProvider.OPENAI_COMPATIBLE

    override fun defaultSettings(): OpenAiCompatibleSettings = OpenAiCompatibleSettings()

    /**
     * Real key from settings; falls back to "not-needed" for servers that don't require auth.
     * Wrapped in [safeApiKey] to guarantee a non-blank value for the HTTP client.
     */
    override fun resolveApiKey(settings: OpenAiCompatibleSettings): String = safeApiKey(settings.apiKey.ifBlank { "not-needed" })

    override fun getNoModelsHelpText(): String = """
        One possible reason is that your server URL or API key is not configured.

        1. Set the Base URL for your OpenAI-compatible server (e.g., http://localhost:8000/v1)
        2. Add an API key if your server requires it
    """.trimIndent()

    /**
     * OpenAI-compatible servers can be remote — skip the local model availability check.
     * Also uses the settings API key (via [resolveApiKey]) for the embedding request itself.
     */
    override fun createEmbeddingModel(settings: OpenAiCompatibleSettings): EmbeddingModel = OpenAiEmbeddingModelBuilder()
        .baseUrl(settings.baseUrl)
        .apiKey(resolveApiKey(settings))
        .modelName(AppConfig.models[ModelProvider.OPENAI_COMPATIBLE].embeddingModel)
        .build()

    override fun getEmbeddingTokenLimit(settings: OpenAiCompatibleSettings): Int {
        val modelName = AppConfig.models[ModelProvider.OPENAI_COMPATIBLE].embeddingModel.lowercase()
        return when {
            modelName.contains("text-embedding-3") -> 8191
            modelName.contains("ada-002") -> 8191
            else -> 8191
        }
    }
}
