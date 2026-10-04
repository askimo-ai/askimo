/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event.error

import io.askimo.core.event.Event
import io.askimo.core.event.EventSource
import io.askimo.core.event.EventType
import io.askimo.core.providers.ModelProvider
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Emitted when a model is not available and cannot be used.
 * UI should display an appropriate error message to the user.
 *
 * @param provider The model provider (e.g., OLLAMA, OPENAI, GEMINI)
 * @param modelName The name of the unavailable model
 * @param isEmbedding True if this is an embedding model, false for chat model
 * @param reason Detailed reason why the model is not available
 */
data class ModelNotAvailableEvent(
    val provider: ModelProvider,
    val modelName: String,
    val isEmbedding: Boolean,
    val reason: String,
    override val timestamp: Instant = Clock.System.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.ERROR

    override fun getDetails() = "Model '$modelName' not available: $reason"
}
