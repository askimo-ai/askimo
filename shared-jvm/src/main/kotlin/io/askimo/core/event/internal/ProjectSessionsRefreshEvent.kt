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
 * Event to request refresh of sessions for a specific project.
 * Emitted when sessions within a project are modified and need to be reloaded.
 *
 * Use cases:
 * - Session deleted from project
 * - Session moved to/from project
 * - Session created in project
 * - Session properties changed within project
 *
 * @param projectId The ID of the project whose sessions should be refreshed
 * @param reason Optional description of why the refresh is needed (for debugging)
 */
data class ProjectSessionsRefreshEvent(
    val projectId: String,
    val reason: String? = null,
    override val timestamp: Instant = Clock.System.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails() = reason?.let { "Project sessions refresh for $projectId: $it" }
        ?: "Project sessions refresh for $projectId"
}
