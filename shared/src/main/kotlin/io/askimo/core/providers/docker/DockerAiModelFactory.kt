/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.docker

import io.askimo.core.context.AppContext
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ModelProvider.DOCKER
import io.askimo.core.providers.ensureLocalEmbeddingModelAvailable
import io.askimo.core.providers.openaicompatible.CompletionsApiDelegate
import io.askimo.core.providers.openaicompatible.OpenAiCompatibleChatModelFactory
import io.askimo.core.providers.openaicompatible.ResponsesApiDelegate

/**
 * Model factory for Docker AI.
 *
 * Uses [CompletionsApiDelegate] (`/v1/chat/completions`) since Docker AI does not support
 * the OpenAI Responses API. HTTP/1.1 is enforced via [DockerAiSettings.httpVersion] —
 * Docker AI's server stack does not support HTTP/2.
 */
class DockerAiModelFactory :
    OpenAiCompatibleChatModelFactory<DockerAiSettings>(
        apiDelegate = ResponsesApiDelegate(),
    ) {

    override fun getProvider(): ModelProvider = DOCKER

    override fun defaultSettings(): DockerAiSettings = DockerAiSettings()

    /**
     * When no explicit utility model is configured, fall back to whichever model is currently
     * active in the session rather than [DockerAiSettings.defaultModel].
     */
    override fun utilityModelFallback(settings: DockerAiSettings): String = AppContext.getInstance().params.model

    override fun checkEmbeddingAvailability(baseUrl: String, modelName: String) = ensureLocalEmbeddingModelAvailable(getProvider(), baseUrl, modelName)

    override fun getNoModelsHelpText(): String = """
        You may not have any models installed yet.

        Make sure Docker AI is running and has models available.
        Visit Docker AI documentation for model installation instructions.
    """.trimIndent()
}
