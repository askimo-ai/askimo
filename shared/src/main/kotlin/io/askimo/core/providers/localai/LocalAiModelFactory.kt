/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.localai

import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ModelProvider.LOCALAI
import io.askimo.core.providers.OpenAiCompatibleChatModelFactory
import io.askimo.core.providers.ensureLocalEmbeddingModelAvailable

class LocalAiModelFactory : OpenAiCompatibleChatModelFactory<LocalAiSettings>() {

    override fun getProvider(): ModelProvider = LOCALAI

    override fun defaultSettings(): LocalAiSettings = LocalAiSettings()

    override fun canFetchModels(settings: LocalAiSettings): Boolean = settings.baseUrl.isNotBlank()

    override fun checkEmbeddingAvailability(baseUrl: String, modelName: String) = ensureLocalEmbeddingModelAvailable(getProvider(), baseUrl, modelName)
}
