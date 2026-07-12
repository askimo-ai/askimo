/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import io.askimo.core.event.EventBus
import io.askimo.core.event.error.ModelNotAvailableEvent
import io.askimo.core.logging.currentFileLogger
import io.askimo.core.logging.display

private val log = currentFileLogger()

/**
 * Ensures that a locally-hosted embedding model is available before use.
 * Logs diagnostics and emits a [ModelNotAvailableEvent] if the model or provider is unreachable.
 *
 * @param provider The local provider (Ollama, Docker, LocalAI, LMStudio)
 * @param baseUrl The base URL of the local provider
 * @param modelName The embedding model name to check
 * @throws IllegalStateException if the provider is unreachable or the model is not available
 */
fun ensureLocalEmbeddingModelAvailable(
    provider: ModelProvider,
    baseUrl: String,
    modelName: String,
) {
    when (val result = LocalModelValidator.checkModelExists(provider, baseUrl, modelName)) {
        is ModelAvailabilityResult.Available -> {
            log.display("✅ ${provider.name} embedding model '$modelName' is ready")
        }

        is ModelAvailabilityResult.ProviderUnreachable -> {
            EventBus.post(
                ModelNotAvailableEvent(
                    provider = provider,
                    modelName = modelName,
                    isEmbedding = true,
                    reason = "Can not get $modelName model list from ${provider.name} at $baseUrl: ${result.error}",
                ),
            )

            error("Can not get $modelName model list from ${provider.name} at $baseUrl: ${result.error}")
        }

        is ModelAvailabilityResult.NotAvailable -> {
            EventBus.post(
                ModelNotAvailableEvent(
                    provider = provider,
                    modelName = modelName,
                    isEmbedding = true,
                    reason = result.reason,
                ),
            )

            error("Embedding model '$modelName' is not available in ${provider.name}. Go to Settings > AI Provider and select an available embedding model under the provider configuration card.")
        }
    }
}
