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
 * Fired by [io.askimo.core.mcp.McpInstanceService] whenever global MCP instances or their
 * tool configs change (create/update/delete instance, ephemeral instance sync, tool
 * enabled/selected/approval changes). UI components (e.g. ChatInputField's tools popup) can
 * listen for this to refresh their cached view of available MCP servers/tools without
 * refetching on every composition.
 */
data class McpInstancesChangedEvent(
    val reason: String? = null,
    override val timestamp: Instant = Clock.System.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails() = reason?.let { "MCP instances changed: $it" } ?: "MCP instances changed"
}
