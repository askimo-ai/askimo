/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event.internal

import io.askimo.core.event.Event
import io.askimo.core.event.EventSource
import io.askimo.core.event.EventType
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Emitted when a Mermaid diagram in a message was auto-fixed by AI.
 * ChatViewModel subscribes and persists the corrected content to the database.
 */
data class DiagramFixedEvent(
    val entityId: String,
    val originalDiagram: String,
    val fixedDiagram: String,
    override val timestamp: Instant = Clock.System.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails() = "Diagram auto-fixed in message $entityId"
}
