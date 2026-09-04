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
 * Emitted when an agent-run conversation's title is updated — either the initial
 * deterministic fallback title or, later, a short AI-generated replacement.
 * UI components (agentic run area, run-history panel) use this to refresh in place
 * without waiting for a full history reload.
 */
data class AgentRunTitleUpdatedEvent(
    val conversationId: String,
    val newTitle: String,
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails() = "Agent run conversation $conversationId title updated to: $newTitle"
}
