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
 * Internal events for component-to-component communication.
 * These events are not shown in the UI but enable decoupled communication between services.
 * Emitted when the user changes the AI model in settings.
 * ChatSessionService should clear cached clients when receiving this event.
 */
data class ModelChangedEvent(
    val provider: ModelProvider,
    val newModel: String,
    /**
     * Stable ID of the [io.askimo.core.providers.ProviderInstance] that was changed.
     * Empty string for legacy callers that have not yet been migrated to instance-aware code.
     */
    val instanceId: String = "",
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails() = "Model changed to $newModel for provider $provider (instance=$instanceId)"
}
