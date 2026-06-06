/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.ollama

import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.OpenAiCompatibleChatModelFactory
import io.askimo.core.providers.ensureLocalEmbeddingModelAvailable

class OllamaModelFactory : OpenAiCompatibleChatModelFactory<OllamaSettings>() {

    override fun getProvider(): ModelProvider = ModelProvider.OLLAMA

    override fun defaultSettings(): OllamaSettings = OllamaSettings()

    override fun canFetchModels(settings: OllamaSettings): Boolean = settings.baseUrl.isNotBlank()

    override fun checkEmbeddingAvailability(baseUrl: String, modelName: String) = ensureLocalEmbeddingModelAvailable(getProvider(), baseUrl, modelName)

    override fun getNoModelsHelpText(): String = """
        You may not have any models installed yet.

        Visit https://ollama.com/library to browse available models.
        Then run: ollama pull <modelName> to install a model locally.

        Example: ollama pull llama3
    """.trimIndent()
}
