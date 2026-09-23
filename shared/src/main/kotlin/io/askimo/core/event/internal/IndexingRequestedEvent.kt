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
 * Event emitted when a container (project or resource collection) needs to be indexed.
 * This is an internal event that triggers [io.askimo.core.rag.RagIndexer] to index the
 * container's knowledge sources using the provided embedding store and model.
 *
 * @param knowledgeSources Optional list of specific sources to index.
 *   If null, all of the container's sources will be indexed.
 */
data class IndexingRequestedEvent(
    val containerId: String,
    val containerType: IndexingContainerType,
    val watchForChanges: Boolean = true,
    val knowledgeSources: List<KnowledgeSourceConfig>? = null,
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails(): String {
        val sourcesInfo = if (knowledgeSources != null) {
            ", sources: [${knowledgeSources.joinToString { it.resourceIdentifier }}]"
        } else {
            ""
        }
        return "Indexing requested for $containerType $containerId$sourcesInfo"
    }
}
