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
