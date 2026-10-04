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
 * Emitted after a chat directive is soft-deleted from the local database.
 * Allows the sync layer to propagate the deletion to the server without
 * tight coupling between the repository and the sync service.
 *
 * @param directiveId The ID of the deleted directive.
 */
data class DirectiveDeletedEvent(
    val directiveId: String,
    override val timestamp: Instant = Clock.System.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL
    override fun getDetails() = "Directive $directiveId deleted"
}
