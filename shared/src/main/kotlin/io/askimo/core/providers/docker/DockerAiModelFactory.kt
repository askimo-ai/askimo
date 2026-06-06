/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.docker

import io.askimo.core.context.AppContext
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ModelProvider.DOCKER
import io.askimo.core.providers.OpenAiCompatibleChatModelFactory
import io.askimo.core.providers.ensureLocalEmbeddingModelAvailable
import java.net.http.HttpClient

class DockerAiModelFactory : OpenAiCompatibleChatModelFactory<DockerAiSettings>() {

    override fun getProvider(): ModelProvider = DOCKER

    override fun defaultSettings(): DockerAiSettings = DockerAiSettings()

    /** Docker AI does not support HTTP/2. */
    override fun httpVersion(): HttpClient.Version = HttpClient.Version.HTTP_1_1

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
