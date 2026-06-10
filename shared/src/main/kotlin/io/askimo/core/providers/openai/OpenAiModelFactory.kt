/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.openai

import dev.langchain4j.model.openai.OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ModelProvider.OPENAI
import io.askimo.core.providers.OpenAiCompatibleChatModelFactory
import io.askimo.core.util.ApiKeyUtils.safeApiKey

class OpenAiModelFactory : OpenAiCompatibleChatModelFactory<OpenAiSettings>() {

    override fun getProvider(): ModelProvider = OPENAI

    override fun defaultSettings(): OpenAiSettings = OpenAiSettings()

    override fun canFetchModels(settings: OpenAiSettings): Boolean = settings.apiKey.isNotBlank()

    override fun resolveApiKey(settings: OpenAiSettings): String = safeApiKey(settings.apiKey)

    override fun customizeEmbeddingBuilder(
        settings: OpenAiSettings,
        builder: OpenAiEmbeddingModelBuilder,
    ): OpenAiEmbeddingModelBuilder = builder.apiKey(resolveApiKey(settings))

    override fun getNoModelsHelpText(): String = """
        One possible reason is that you haven't provided your OpenAI API key yet.

        1. Get your API key from: https://platform.openai.com/account/api-keys
        2. Then set it in the Settings

        Get an API key here: https://platform.openai.com/api-keys
    """.trimIndent()
}
