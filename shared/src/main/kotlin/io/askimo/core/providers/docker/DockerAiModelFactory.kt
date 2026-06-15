/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.docker

import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiResponsesChatModel
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import io.askimo.core.config.AppConfig
import io.askimo.core.context.AppContext
import io.askimo.core.providers.ModelCapabilitiesCache
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ModelProvider.DOCKER
import io.askimo.core.providers.OpenAiCompatibleChatModelFactory
import io.askimo.core.providers.ensureLocalEmbeddingModelAvailable
import io.askimo.core.telemetry.TelemetryChatModelListener
import java.net.http.HttpClient

class DockerAiModelFactory : OpenAiCompatibleChatModelFactory<DockerAiSettings>() {

    override fun getProvider(): ModelProvider = DOCKER

    override fun defaultSettings(): DockerAiSettings = DockerAiSettings()

    /** Docker AI does not support HTTP/2. */
    override fun httpVersion(): HttpClient.Version = HttpClient.Version.HTTP_1_1

    override fun createStreamingModel(settings: DockerAiSettings): StreamingChatModel {
        val telemetry = AppContext.getInstance().telemetry
        val supportsThinking = ModelCapabilitiesCache.supportsThinking(getProvider(), settings.defaultModel)
        return OpenAiStreamingChatModel.builder()
            .httpClientBuilder(createHttpClientBuilder(settings.baseUrl))
            .baseUrl(settings.baseUrl)
            .apiKey(resolveApiKey(settings))
            .modelName(settings.defaultModel)
            .apply {
                val reasoningLevel = ModelCapabilitiesCache.getReasoningLevel(getProvider(), settings.defaultModel)
                if (supportsThinking && reasoningLevel.isEnabled) {
                    reasoningEffort(reasoningLevel.value)
                }
            }
            .logRequests(log.isDebugEnabled)
            .logResponses(log.isDebugEnabled)
            .listeners(listOf(TelemetryChatModelListener(telemetry, getProvider().providerKey())))
            .build()
    }

    override fun createSecondaryModel(settings: DockerAiSettings): ChatModel = OpenAiChatModel.builder()
        .httpClientBuilder(createHttpClientBuilder(settings.baseUrl))
        .baseUrl(settings.baseUrl)
        .apiKey(resolveApiKey(settings))
        .modelName(AppConfig.models[getProvider()].utilityModel.ifBlank { utilityModelFallback(settings) })
        .logRequests(log.isDebugEnabled)
        .build()

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
