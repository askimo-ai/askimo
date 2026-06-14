/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event.internal

import io.askimo.core.event.Event
import io.askimo.core.event.EventSource
import io.askimo.core.event.EventType
import io.askimo.core.providers.ModelProvider
import java.time.Instant

/**
 * Fired by [io.askimo.core.providers.ModelCapabilitiesCache] after the thinking-support probe
 * completes for a model. UI components (e.g. ChatInputField) can listen
 * for this to reactively show/hide the reasoning effort control.
 */
data class ThinkingSupportDetectedEvent(
    val provider: ModelProvider,
    val model: String,
    val supportsThinking: Boolean,
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails() = "Thinking support for $model ($provider): $supportsThinking"
}
