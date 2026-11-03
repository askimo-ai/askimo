/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.mcp

import dev.langchain4j.mcp.client.transport.McpTransport

/**
 * Base class for all MCP connectors.
 * Handles the transport creation and lifecycle.
 */
abstract class McpConnector {
    /**
     * Creates and returns the MCP transport instance
     */
    abstract suspend fun createTransport(): McpTransport

    /**
     * Validates the connector configuration
     */
    abstract fun validate(): ValidationResult

    /**
     * Optional pre-flight check run before [createTransport]/handshake.
     *
     * langchain4j's MCP client only waits on the JSON-RPC handshake and surfaces a generic
     * [java.util.concurrent.TimeoutException] when a stdio server never responds — it does not
     * expose the child process's stderr or exit code. Connectors that can cheaply detect a
     * doomed launch (e.g. a process that crashes or exits before ever reaching the handshake)
     * should override this to return the real root cause instead.
     */
    open suspend fun probeStartup(): ProbeResult = ProbeResult.Ok
}

/**
 * Result of a [McpConnector.probeStartup] pre-flight check.
 */
sealed class ProbeResult {
    object Ok : ProbeResult()
    data class Failed(val message: String) : ProbeResult()
}

/**
 * Result of configuration validation
 */
data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList(),
) {
    companion object {
        fun valid() = ValidationResult(true)
        fun invalid(vararg errors: String) = ValidationResult(false, errors.toList())
    }
}

/**
 * Result of connection test
 */
sealed class TestResult {
    data class Invalid(val errors: List<String>) : TestResult()
    data class Failed(val message: String) : TestResult()
}
