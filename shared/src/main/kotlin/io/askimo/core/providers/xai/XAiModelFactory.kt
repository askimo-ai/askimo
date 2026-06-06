/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.xai

import dev.langchain4j.model.embedding.EmbeddingModel
import io.askimo.core.providers.ModelDTO
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ModelProvider.XAI
import io.askimo.core.providers.OpenAiCompatibleChatModelFactory
import io.askimo.core.providers.ProviderModelUtils.fetchModels
import io.askimo.core.util.ApiKeyUtils.safeApiKey

class XAiModelFactory : OpenAiCompatibleChatModelFactory<XAiSettings>() {

    override fun getProvider(): ModelProvider = XAI

    override fun defaultSettings(): XAiSettings = XAiSettings()

    override fun resolveApiKey(settings: XAiSettings): String = safeApiKey(settings.apiKey)

    override fun availableModels(settings: XAiSettings): List<ModelDTO> {
        val apiKey = settings.apiKey.takeIf { it.isNotBlank() } ?: return emptyList()
        return fetchModels(apiKey = apiKey, url = "${settings.baseUrl.trimEnd('/')}/models", providerName = XAI)
            .map { ModelDTO.of(XAI, it) }
    }

    /** xAI does not offer an embedding endpoint. */
    override fun supportsEmbedding(): Boolean = false

    override fun createEmbeddingModel(settings: XAiSettings): EmbeddingModel = throw UnsupportedOperationException(
        "${getProvider().name} does not support embedding models. " +
            "Please switch to a provider that supports embeddings (OpenAI, Ollama, etc.) to use RAG features.",
    )
}
