/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.mcp.connectors

import io.askimo.core.mcp.StdioMcpTransportConfig
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("StdioMcpConnector validation")
class StdioMcpConnectorValidationTest {

    @Test
    @DisplayName("Should report an empty command as invalid")
    fun testEmptyCommandIsInvalid() {
        val config = StdioMcpTransportConfig(
            id = "test-empty",
            name = "Test Empty",
            command = emptyList(),
            env = emptyMap(),
        )

        val result = StdioMcpConnector(config).validate()

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("Command cannot be empty") })
    }

    @Test
    @DisplayName("Should report a nonexistent absolute-path executable as invalid")
    fun testNonexistentAbsolutePathIsInvalid() {
        val config = StdioMcpTransportConfig(
            id = "test-abs",
            name = "Test Absolute",
            command = listOf("/definitely/not/a/real/path/to/some-binary"),
            env = emptyMap(),
        )

        val result = StdioMcpConnector(config).validate()

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("Executable not found") })
    }

    @Test
    @DisplayName("Should report a missing bare command with a helpful install hint")
    fun testMissingBareCommandHasInstallHint() {
        val config = StdioMcpTransportConfig(
            id = "test-uvx",
            name = "Test uvx",
            command = listOf("uvx", "mcp-server-time"),
            env = emptyMap(),
        )

        val result = StdioMcpConnector(config).validate()

        // Only assert the "not found" branch when uvx genuinely isn't installed on this
        // machine — otherwise this test would be flaky on environments that do have it.
        if (!result.isValid) {
            assertTrue(result.errors.any { it.contains("uvx") })
            assertTrue(result.errors.any { it.contains("astral.sh") })
        }
    }

    @Test
    @DisplayName("Should report a missing unknown bare command with a generic message")
    fun testMissingUnknownBareCommandHasGenericMessage() {
        val config = StdioMcpTransportConfig(
            id = "test-unknown",
            name = "Test Unknown Tool",
            command = listOf("definitely-not-a-real-cli-tool-xyz"),
            env = emptyMap(),
        )

        val result = StdioMcpConnector(config).validate()

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("was not found on your PATH") })
    }
}
