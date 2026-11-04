/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.mcp.connectors

import dev.langchain4j.mcp.client.transport.McpTransport
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport
import io.askimo.core.logging.logger
import io.askimo.core.mcp.McpConnector
import io.askimo.core.mcp.ProbeResult
import io.askimo.core.mcp.StdioMcpTransportConfig
import io.askimo.core.mcp.ValidationResult
import io.askimo.core.util.ProcessBuilderExt
import io.askimo.core.util.redactErrorText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Connector for stdio-based MCP servers (local processes)
 */
class StdioMcpConnector(
    private val config: StdioMcpTransportConfig,
) : McpConnector() {

    private val log = logger<StdioMcpConnector>()

    override suspend fun createTransport(): McpTransport {
        val command = resolveFullCommand()
        val env = buildEnv()

        log.debug("[MCP '${config.name}'] Launching stdio transport")
        log.debug("[MCP '${config.name}']   command : {}", command.map { redactErrorText(it) })
        log.debug("[MCP '${config.name}']   workDir : {}", config.workingDirectory ?: "<none>")
        log.debug("[MCP '${config.name}']   env keys: {}", env.keys)
        log.debug("[MCP '${config.name}']   PATH    : {}", env["PATH"])

        val builder = StdioMcpTransport.builder()
            .command(command)
            .environment(env)

        return builder.build()
    }

    /**
     * Launches the command to surface the real startup error (missing deps, resolver
     * errors, crashes) instead of an opaque handshake timeout. Called only after a normal
     * connection attempt fails.
     *
     * Still alive after [PROBE_TIMEOUT_MS] = healthy server waiting on the handshake;
     * it's killed and [ProbeResult.Ok] is returned.
     */
    override suspend fun probeStartup(): ProbeResult = withContext(Dispatchers.IO) {
        val command = resolveFullCommand()
        val process = try {
            ProcessBuilderExt(command).apply {
                environment().apply {
                    clear()
                    putAll(buildEnv())
                }
            }.start()
        } catch (e: Exception) {
            return@withContext ProbeResult.Failed(
                "Failed to launch '${command.joinToString(" ") { redactErrorText(it) }}': ${e.message}",
            )
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutThread = Thread {
            runCatching { process.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) } }
        }.apply {
            isDaemon = true
            start()
        }
        val stderrThread = Thread {
            runCatching { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }
        }.apply {
            isDaemon = true
            start()
        }

        val exitedInTime = process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!exitedInTime) {
            destroyProcessTree(process)
            stdoutThread.join(500)
            stderrThread.join(500)
            return@withContext ProbeResult.Ok
        }

        stdoutThread.join(500)
        stderrThread.join(500)

        val exitCode = process.exitValue()
        if (exitCode != 0) {
            val detail = stderr.toString().ifBlank { stdout.toString() }.trim().takeLast(2000)
            ProbeResult.Failed(
                "'${config.name}' exited with code $exitCode before completing the MCP handshake." +
                    if (detail.isNotBlank()) "\n\n$detail" else "",
            )
        } else {
            ProbeResult.Ok
        }
    }

    /**
     * Forcibly destroys [process] and all of its descendants.
     *
     * [resolveFullCommand] may wrap the command in `bash -c`/`cmd /c`, and launchers like `uvx`/`npx`
     * spawn the real MCP server as a child of their own process. Destroying only [process] can leave
     * that descendant running (and its reader threads blocked) after a timed-out probe, so the whole
     * process tree is torn down here.
     */
    private fun destroyProcessTree(process: Process) {
        process.toHandle().descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    /** Resolves the executable + wraps it for a working-directory `cd` if configured. */
    private fun resolveFullCommand(): List<String> {
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
        return command
    }

    /** Builds the child process environment, enriching `PATH` for GUI-launched apps. */
    private fun buildEnv(): Map<String, String> {
        val env = System.getenv().toMutableMap()
        config.env.forEach { (k, v) -> env[k] = v }
        env["PATH"] = ProcessBuilderExt.enrichedPath(env["PATH"] ?: "")
        return env
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
            if (execFile.isAbsolute) {
                if (!execFile.exists()) {
                    errors.add("Executable not found: $executable")
                }
            } else if (ProcessBuilderExt.which(executable) == null) {
                errors.add(missingExecutableMessage(executable))
            }
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
        )
    }

    companion object {
        /** Grace period given to [probeStartup] before assuming the process is healthy. */
        private const val PROBE_TIMEOUT_MS = 8000L

        /** Known install instructions for the runtimes most built-in MCP templates rely on. */
        private val KNOWN_INSTALL_HINTS: Map<String, String> = mapOf(
            "uvx" to "Install uv (provides 'uvx'): curl -LsSf https://astral.sh/uv/install.sh | sh " +
                "(macOS/Linux) or see https://docs.astral.sh/uv/getting-started/installation/",
            "uv" to "Install uv: curl -LsSf https://astral.sh/uv/install.sh | sh " +
                "(macOS/Linux) or see https://docs.astral.sh/uv/getting-started/installation/",
            "npx" to "Install Node.js (provides 'npx'): https://nodejs.org/en/download " +
                "or via a version manager, e.g. 'brew install node' / 'nvm install --lts'",
        )

        private fun missingExecutableMessage(executable: String): String {
            val hint = KNOWN_INSTALL_HINTS[executable]
            return if (hint != null) {
                "'$executable' was not found on your PATH. $hint"
            } else {
                "'$executable' was not found on your PATH. Please install it and ensure it's accessible " +
                    "from a terminal, then try again."
            }
        }
    }
}
