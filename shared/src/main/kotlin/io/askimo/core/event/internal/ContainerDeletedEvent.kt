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
 * Event emitted when a container (project or resource collection) is deleted.
 * Allows components to cleanup container-related resources without tight coupling.
 */
data class ContainerDeletedEvent(
    val containerId: String,
    val containerType: IndexingContainerType,
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails() = "$containerType deleted: $containerId"
}
