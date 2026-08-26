/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event.internal

import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.event.Event
import io.askimo.core.event.EventSource
import io.askimo.core.event.EventType
import java.time.Instant

/**
 * Event emitted when the user requests a manual rescan of a single knowledge source
 * (issue #619). This is an internal event that triggers ProjectIndexer to re-index just
 * this source, without touching the rest of the project's knowledge sources.
 */
data class KnowledgeSourceRescanRequestedEvent(
    val projectId: String,
    val knowledgeSource: KnowledgeSourceConfig,
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails(): String = "Rescan requested for project $projectId, source: ${knowledgeSource.resourceIdentifier}"
}
