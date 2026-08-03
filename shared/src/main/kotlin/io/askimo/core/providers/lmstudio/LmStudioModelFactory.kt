/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.lmstudio

import dev.langchain4j.model.openai.OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder
import io.askimo.core.context.AppContext
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ModelProvider.LMSTUDIO
import io.askimo.core.providers.ensureLocalEmbeddingModelAvailable
import io.askimo.core.providers.openaicompatible.CompletionsApiDelegate
import io.askimo.core.providers.openaicompatible.OpenAiCompatibleChatModelFactory
import io.askimo.core.providers.openaicompatible.ResponsesApiDelegate
import io.askimo.core.util.toJdkVersion

/**
 * Model factory for LM Studio.
 *
 * Uses [CompletionsApiDelegate] (`/v1/chat/completions`) since LM Studio does not support
 * the OpenAI Responses API. HTTP/1.1 is enforced via [LmStudioSettings.httpVersion] —
 * LM Studio's server does not support HTTP/2.
 */
class LmStudioModelFactory :
    OpenAiCompatibleChatModelFactory<LmStudioSettings>(
        apiDelegate = ResponsesApiDelegate(),
    ) {

    override fun getProvider(): ModelProvider = LMSTUDIO

    override fun defaultSettings(): LmStudioSettings = LmStudioSettings()

    /**
     * When no explicit utility model is configured, fall back to whichever model is currently
     * active in the session rather than [LmStudioSettings.defaultModel].
     */
    override fun utilityModelFallback(settings: LmStudioSettings): String = AppContext.getInstance().params.model

    override fun checkEmbeddingAvailability(baseUrl: String, modelName: String) = ensureLocalEmbeddingModelAvailable(getProvider(), baseUrl, modelName)

    /** LM Studio requires an HTTP/1.1 client on the embedding builder as well. */
    override fun customizeEmbeddingBuilder(
        settings: LmStudioSettings,
        builder: OpenAiEmbeddingModelBuilder,
    ): OpenAiEmbeddingModelBuilder = builder.httpClientBuilder(
        createHttpClientBuilder(settings.baseUrl, httpVersion = settings.httpVersion.toJdkVersion()),
    )
}
