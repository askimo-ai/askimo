/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.lmstudio

import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ModelProvider.LMSTUDIO
import io.askimo.core.providers.ensureLocalEmbeddingModelAvailable
import io.askimo.core.providers.openaicompatible.CompletionsApiDelegate
import io.askimo.core.providers.openaicompatible.OpenAiCompatibleChatModelFactory
import io.askimo.core.providers.openaicompatible.ResponsesApiDelegate

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

    override fun checkEmbeddingAvailability(baseUrl: String, modelName: String) = ensureLocalEmbeddingModelAvailable(getProvider(), baseUrl, modelName)
}
