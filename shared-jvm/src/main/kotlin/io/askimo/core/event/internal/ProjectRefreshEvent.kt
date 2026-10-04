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
 * Event to request a refresh of a specific project's data.
 * This event is emitted when a project's knowledge sources or other properties
 * have been modified and the UI needs to reload the project data.
 *
 * Use cases:
 * - Knowledge source added to project
 * - Knowledge source removed from project
 * - Project properties updated (name, description)
 * - Any other scenario requiring a specific project's data to be reloaded
 *
 * @param projectId The ID of the project to refresh
 * @param reason Optional description of why the refresh is needed (for debugging)
 */
data class ProjectRefreshEvent(
    val projectId: String,
    val reason: String? = null,
    override val timestamp: Instant = Clock.System.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails() = reason?.let { "Project $projectId refresh requested: $it" }
        ?: "Project $projectId refresh requested"
}
