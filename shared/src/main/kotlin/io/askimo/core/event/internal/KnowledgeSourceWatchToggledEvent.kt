/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event.internal

import io.askimo.core.chat.domain.LocalFoldersKnowledgeSourceConfig
import io.askimo.core.event.Event
import io.askimo.core.event.EventSource
import io.askimo.core.event.EventType
import io.askimo.core.rag.container.IndexingContainerType
import java.time.Instant

/**
 * Emitted when the user toggles "watch for changes" for a local folder knowledge
 * source. Triggers [io.askimo.core.rag.RagIndexer] to start/stop the live file
 * watcher for the already-running coordinator, without a full re-index.
 */
data class KnowledgeSourceWatchToggledEvent(
    val containerId: String,
    val containerType: IndexingContainerType,
    val knowledgeSource: LocalFoldersKnowledgeSourceConfig,
    val watchForChanges: Boolean,
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails(): String = "Watch toggle requested for $containerType $containerId, source: ${knowledgeSource.resourceIdentifier} -> $watchForChanges"
}
