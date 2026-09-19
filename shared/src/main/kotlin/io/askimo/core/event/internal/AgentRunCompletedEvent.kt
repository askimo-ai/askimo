/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event.internal

import io.askimo.core.event.Event
import io.askimo.core.event.EventSource
import io.askimo.core.event.EventType
import java.time.Instant

/**
 * Emitted when an agent run completes and is persisted to history.
 * Observers listening to this event (e.g., AgentsViewModel's history panel) will be notified
 * regardless of when they attached, preventing stale callback issues when ViewModels survive
 * navigation and observers change.
 *
 * @param workspaceId The workspace where the run completed
 */
data class AgentRunCompletedEvent(
    val workspaceId: String,
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails() = "Agent run completed in workspace $workspaceId"
}
