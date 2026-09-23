/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event.internal

import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.event.Event
import io.askimo.core.event.EventSource
import io.askimo.core.event.EventType
import io.askimo.core.rag.container.IndexingContainerType
import java.time.Instant

/**
 * Emitted when the user requests a manual rescan of a single knowledge source.
 * Triggers [io.askimo.core.rag.RagIndexer] to re-index just this source, without touching
 * the container's other knowledge sources.
 */
data class KnowledgeSourceRescanRequestedEvent(
    val containerId: String,
    val containerType: IndexingContainerType,
    val knowledgeSource: KnowledgeSourceConfig,
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails(): String = "Rescan requested for $containerType $containerId, source: ${knowledgeSource.resourceIdentifier}"
}
