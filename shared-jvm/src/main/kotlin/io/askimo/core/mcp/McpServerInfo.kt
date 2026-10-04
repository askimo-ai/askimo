/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.mcp

import io.askimo.core.intent.ToolConfig

/**
 * Describes an MCP server (or the built-in Askimo tools pseudo-server) along with the
 * tools it currently exposes, for display in the chat input "tools" popup.
 *
 * Built-in tools entries (`isBuiltIn = true`) are constructed by the UI layer directly from
 * [io.askimo.core.intent.ToolRegistry] — they are not MCP instances and are never returned by
 * [McpInstanceService.listActiveMcpServers].
 */
data class McpServerInfo(
    val name: String,
    val id: String,
    val isGlobal: Boolean,
    val isBuiltIn: Boolean = false,
    val tools: List<ToolConfig> = emptyList(),
)
