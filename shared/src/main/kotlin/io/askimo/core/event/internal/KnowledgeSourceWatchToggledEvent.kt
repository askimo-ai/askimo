/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event.internal

import io.askimo.core.chat.domain.LocalFoldersKnowledgeSourceConfig
import io.askimo.core.event.Event
import io.askimo.core.event.EventSource
import io.askimo.core.event.EventType
import java.time.Instant

/**
 * Event emitted when the user toggles the "watch for changes" setting for a local
 * folder knowledge source. This is an internal event that triggers ProjectIndexer to
 * start or stop the live file watcher for the already-running coordinator, without a
 * full re-index.
 */
data class KnowledgeSourceWatchToggledEvent(
    val projectId: String,
    val knowledgeSource: LocalFoldersKnowledgeSourceConfig,
    val watchForChanges: Boolean,
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails(): String = "Watch toggle requested for project $projectId, source: ${knowledgeSource.resourceIdentifier} -> $watchForChanges"
}
