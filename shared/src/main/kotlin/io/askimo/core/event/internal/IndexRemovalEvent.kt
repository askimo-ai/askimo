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
 * Event emitted when a specific knowledge source should be removed from the index.
 * This is an internal event that triggers [io.askimo.core.rag.RagIndexer] to delete the
 * embeddings and index state associated with the given knowledge source.
 */
data class IndexRemovalEvent(
    val containerId: String,
    val containerType: IndexingContainerType,
    val knowledgeSource: KnowledgeSourceConfig,
    val reason: String = "Knowledge source removed",
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails(): String = "Removal requested for $containerType $containerId, source: ${knowledgeSource.resourceIdentifier} — $reason"
}
