/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.mcp.connectors

import io.askimo.core.mcp.ProbeResult
import io.askimo.core.mcp.StdioMcpTransportConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs

@DisplayName("StdioMcpConnector command redaction")
class StdioMcpConnectorRedactionTest {

    @Test
    @DisplayName("probeStartup failure message does not leak a secret-shaped command argument")
    fun testProbeStartupFailureMessageRedactsSecretShapedArgument() {
        val secretValue = "sk-super-secret-value-should-not-leak-1234567890"
        val config = StdioMcpTransportConfig(
            id = "test-redacted-probe",
            name = "Test Redacted Probe",
            command = listOf("definitely-not-a-real-cli-tool-xyz", secretValue),
            env = emptyMap(),
        )

        val result = runBlocking { StdioMcpConnector(config).probeStartup() }

        assertIs<ProbeResult.Failed>(result)
        val failed = result as ProbeResult.Failed
        assertFalse(failed.message.contains(secretValue), "Failure message must not contain the raw secret")
    }
}
