/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Base class for transport configurations.
 * Sealed class ensures type-safety and exhaustive when expressions.
 */
@Serializable
sealed class McpTransportConfig {
    abstract val id: String
    abstract val name: String
    abstract val description: String?
}

/**
 * Configuration for stdio-based MCP transport
 */
@Serializable
@SerialName("stdio")
data class StdioMcpTransportConfig(
    override val id: String,
    override val name: String,
    override val description: String? = null,
    val command: List<String>,
    val env: Map<String, String> = emptyMap(),
    val workingDirectory: String? = null,
) : McpTransportConfig()

/**
 * Configuration for HTTP-based MCP transport.
 * Always uses the modern Streamable HTTP transport (StreamableHttpMcpTransport).
 */
@Serializable
@SerialName("http")
data class HttpMcpTransportConfig(
    override val id: String,
    override val name: String,
    override val description: String? = null,
    /** Resolved URL (all template variables already substituted) */
    val url: String,
    /** Resolved HTTP headers (all template variables already substituted) */
    val headers: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 60_000,
) : McpTransportConfig()
