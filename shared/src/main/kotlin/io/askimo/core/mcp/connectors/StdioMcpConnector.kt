/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.mcp.connectors

import dev.langchain4j.mcp.client.transport.McpTransport
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport
import io.askimo.core.logging.logger
import io.askimo.core.mcp.McpConnector
import io.askimo.core.mcp.StdioMcpTransportConfig
import io.askimo.core.mcp.ValidationResult
import io.askimo.core.util.ProcessBuilderExt
import java.io.File

/**
 * Connector for stdio-based MCP servers (local processes)
 */
class StdioMcpConnector(
    private val config: StdioMcpTransportConfig,
) : McpConnector() {

    private val log = logger<StdioMcpConnector>()

    override suspend fun createTransport(): McpTransport {
        var command = ProcessBuilderExt.resolveCommand(config.command)

        if (config.workingDirectory != null) {
            val workDir = config.workingDirectory
            val isWindows = ProcessBuilderExt.isWindows()
            command = if (isWindows) {
                listOf("cmd", "/c", "cd /d \"$workDir\" && ${command.joinToString(" ")}")
            } else {
                listOf("bash", "-c", "cd \"$workDir\" && ${command.joinToString(" ")}")
            }
        }

        val env = System.getenv().toMutableMap()
        config.env.forEach { (k, v) -> env[k] = v }
        env["PATH"] = ProcessBuilderExt.enrichedPath(env["PATH"] ?: "")

        log.debug("[MCP '${config.name}'] Launching stdio transport")
        log.debug("[MCP '${config.name}']   command : {}", command)
        log.debug("[MCP '${config.name}']   workDir : {}", config.workingDirectory ?: "<none>")
        log.debug("[MCP '${config.name}']   env keys: {}", env.keys)
        log.debug("[MCP '${config.name}']   PATH    : {}", env["PATH"])

        val builder = StdioMcpTransport.builder()
            .logger(log)
            .logEvents(log.isDebugEnabled) // lowered from isTraceEnabled so DEBUG is enough
            .command(command)
            .environment(env)

        return builder.build()
    }

    override fun validate(): ValidationResult {
        val errors = mutableListOf<String>()

        // Validate command
        if (config.command.isEmpty()) {
            errors.add("Command cannot be empty")
        }

        if (config.command.isNotEmpty()) {
            val executable = config.command[0]
            val execFile = File(executable)
            if (execFile.isAbsolute && !execFile.exists()) {
                errors.add("Executable not found: $executable")
            }
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
        )
    }
}
