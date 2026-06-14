/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event.internal

import io.askimo.core.event.Event
import io.askimo.core.event.EventSource
import io.askimo.core.event.EventType
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ReasoningEffort
import java.time.Instant

/**
 * Internal event emitted when the user changes the reasoning effort level for a model.
 */
data class ReasoningEffortChangedEvent(
    val provider: ModelProvider,
    val model: String,
    val newEffort: ReasoningEffort,
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails() = "Reasoning effort changed to $newEffort for $model ($provider)"
}
