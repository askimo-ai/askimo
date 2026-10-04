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
 * Generic event to request a refresh of the projects list.
 * This event can be emitted by any component that modifies project data and needs
 * the UI to reflect those changes.
 *
 * Use cases:
 * - Project created
 * - Project deleted
 * - Project updated (name, description, paths)
 * - Session moved to/from a project
 * - Project re-indexed
 * - Any other scenario requiring project list refresh
 *
 * @param reason Optional description of why the refresh is needed (for debugging)
 */
data class ProjectsRefreshEvent(
    val reason: String? = null,
    override val timestamp: Instant = Clock.System.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails() = reason?.let { "Projects refresh requested: $it" }
        ?: "Projects refresh requested"
}
