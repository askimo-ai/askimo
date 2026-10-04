/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.mcp.connectors

import io.askimo.core.mcp.StdioMcpTransportConfig
import io.askimo.core.util.ProcessBuilderExt
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("StdioMcpConnector Windows Tests")
class StdioMcpConnectorWindowsTest {

    @Test
    @DisplayName("Should resolve npx command on Windows")
    fun testWindowsNpxCommandResolution() {
        val isWindows = ProcessBuilderExt.isWindows()
        Assumptions.assumeTrue(isWindows, "This test only runs on Windows")

        val resolvedCommand = ProcessBuilderExt.resolveCommand(
            listOf("npx", "-y", "@modelcontextprotocol/server-filesystem", "/path/to/dir"),
        )

        println("Original command: npx")
        println("Resolved command: ${resolvedCommand[0]}")

        assertTrue(
            resolvedCommand[0].contains("npx") || resolvedCommand[0].endsWith(".cmd"),
            "Command should be resolved to npx.cmd or full path",
        )

        assertEquals("-y", resolvedCommand[1])
        assertEquals("@modelcontextprotocol/server-filesystem", resolvedCommand[2])
        assertEquals("/path/to/dir", resolvedCommand[3])
    }

    @Test
    @DisplayName("Should validate connector with npx command on Windows")
    fun testWindowsNpxConnectorValidation() {
        val isWindows = ProcessBuilderExt.isWindows()
        Assumptions.assumeTrue(isWindows, "This test only runs on Windows")

        val config = StdioMcpTransportConfig(
            id = "test-npx-windows",
            name = "Test NPX Windows",
            command = listOf("npx", "-y", "@modelcontextprotocol/server-filesystem", "/path/to/dir"),
            env = emptyMap(),
        )

        val connector = StdioMcpConnector(config)

        val result = connector.validate()

        assertTrue(result.isValid, "Connector should be valid")
        println("Windows test: npx command validation passed")
    }

    @Test
    @DisplayName("Should handle npm command on Windows")
    fun testWindowsNpmCommandResolution() {
        val isWindows = ProcessBuilderExt.isWindows()
        Assumptions.assumeTrue(isWindows, "This test only runs on Windows")

        val resolvedCommand = ProcessBuilderExt.resolveCommand(listOf("npm", "run", "something"))

        println("Original command: npm")
        println("Resolved command: ${resolvedCommand[0]}")
        assertTrue(
            resolvedCommand[0].contains("npm"),
            "Command should contain npm",
        )
    }

    @Test
    @DisplayName("Should not modify commands on Unix systems")
    fun testUnixCommandsUnmodified() {
        val isWindows = ProcessBuilderExt.isWindows()
        Assumptions.assumeFalse(isWindows, "This test only runs on Unix systems")

        val originalCommand = listOf("npx", "-y", "@modelcontextprotocol/server-filesystem", "/path/to/dir")
        val resolvedCommand = ProcessBuilderExt.resolveCommand(originalCommand)

        assertEquals(originalCommand.size, resolvedCommand.size, "Command length should be preserved")
        assertEquals("-y", resolvedCommand[1])
        assertEquals("@modelcontextprotocol/server-filesystem", resolvedCommand[2])

        println("Unix test: command resolution passed")
        println("Original: ${originalCommand[0]}")
        println("Resolved: ${resolvedCommand[0]}")
    }

    @Test
    @DisplayName("Should validate OS detection")
    fun testOsDetection() {
        val osName = System.getProperty("os.name")
        val isWindows = ProcessBuilderExt.isWindows()

        println("Operating System: $osName")
        println("Detected as Windows: $isWindows")

        assertTrue(osName.isNotEmpty(), "OS name should not be empty")
    }

    @Test
    @DisplayName("Should preserve command arguments")
    fun testCommandArgumentsPreservation() {
        val originalCommand = listOf(
            "npx",
            "-y",
            "@modelcontextprotocol/server-filesystem",
            "/path/to/dir",
            "--option",
            "value",
        )

        val resolvedCommand = ProcessBuilderExt.resolveCommand(originalCommand)

        assertEquals(originalCommand.size, resolvedCommand.size, "Command size should be preserved")
        for (i in 1 until originalCommand.size) {
            assertEquals(
                originalCommand[i],
                resolvedCommand[i],
                "Argument at index $i should be preserved",
            )
        }

        println("Arguments preservation test passed")
    }
}
