/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event.internal

import io.askimo.core.event.Event
import io.askimo.core.event.EventSource
import io.askimo.core.event.EventType
import io.askimo.core.rag.container.IndexingContainerType
import java.time.Instant

/**
 * Emitted when a container (project or resource collection) needs re-indexing.
 * Triggers [io.askimo.core.rag.RagIndexer] to clear and rebuild the index.
 */
data class ReIndexEvent(
    val containerId: String,
    val containerType: IndexingContainerType,
    val reason: String = "Manual re-index requested",
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails(): String = "Re-indexing $containerType $containerId: $reason"
}
