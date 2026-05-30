/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.skills.agent

import io.askimo.core.util.ProcessBuilderExt
import org.slf4j.Logger
import java.io.BufferedWriter
import java.io.File
import java.io.IOException

/**
 * Template base class for external CLI agents implementing the standard execution flow:
 * 1. Build command (agent-specific)
 * 2. Start process with environment setup
 * 3. Write stdin (agent-specific)
 * 4. Drain stderr in background
 * 5. Parse stdout (agent-specific)
 * 6. Handle completion and errors
 *
 * Subclasses override [buildCommand], [writeStdin], and [parseStdoutLine] to customize behavior.
 * This ensures all agents follow consistent patterns for:
 * - Error logging and handling
 * - Process IO management
 * - Stderr capturing
 * - Exit code validation
 *
 * Each subclass should define its own logger:
 * ```kotlin
 * private val log = logger<YourAgent>()
 * ```
 */
abstract class ExternalAgentTemplate : ExternalAgent {

    /**
     * Subclasses should define their own logger instance.
     * Example: `override val log = logger<CursorAgent>()`
     */
    protected abstract val log: Logger

    /**
     * Resolves the agent binary path from PATH.
     * Must be implemented by subclasses to provide the binary location.
     */
    protected abstract fun resolveAgentPath(): String?

    /**
     * Builds the command-line arguments for this agent.
     * Subclasses compose the full command with agent-specific flags and options.
     *
     * @param agentPath   The resolved binary path (from [resolveAgentPath]).
     * @param systemPrompt The skill's system prompt content.
     * @param userInput   The user's runtime input (may be blank).
     * @return List of command arguments to pass to ProcessBuilder.
     */
    protected abstract fun buildCommand(
        agentPath: String,
        systemPrompt: String,
        userInput: String,
        effectiveWorkDir: File,
    ): List<String>

    /**
     * Allows subclasses to customize process settings before start
     * (e.g., injecting API keys, custom env vars, trust setup).
     */
    protected open fun configureProcess(
        builder: ProcessBuilderExt,
        requestedWorkDir: File?,
        effectiveWorkDir: File,
        systemPrompt: String,
        userInput: String,
    ) {
        // Default: no-op
    }

    /**
     * Writes input to the process stdin if needed.
     * Called before stdout is read; the stream is closed automatically after this returns.
     *
     * Default implementation: no-op (agent receives all input via CLI flags).
     * Override to write systemPrompt/userInput to stdin for agents that use stdin.
     *
     * @param writer      BufferedWriter for process.outputStream (stdin).
     * @param systemPrompt The skill's system prompt content.
     * @param userInput   The user's runtime input (may be blank).
     */
    protected open fun writeStdin(
        writer: BufferedWriter,
        systemPrompt: String,
        userInput: String,
    ) {
        // Default: nothing written to stdin
    }

    /**
     * Parses a single line from process stdout.
     * Called for each non-blank line as it arrives.
     * Should call [onToken] to stream response text and [onStatus] for status updates.
     *
     * @param line      A non-blank line from stdout.
     * @param onToken   Callback to emit response text tokens.
     * @param onStatus  Callback to emit status messages (e.g., "Using tool: readFile").
     * @param output    StringBuilder accumulating all processed output (append final result).
     */
    protected abstract fun parseStdoutLine(
        line: String,
        onToken: (String) -> Unit,
        onStatus: (String) -> Unit,
        output: StringBuilder,
    )

    /**
     * Called for each stderr line.
     * Subclasses can surface selected lines to UI status via [onStatus].
     */
    protected open fun onStderrLine(line: String, onStatus: (String) -> Unit) {
        // Default: no-op
    }

    /**
     * Allows subclasses to strip noisy stderr lines before error reporting.
     */
    protected open fun filterErrorStderr(stderr: String): String = stderr.trim()

    /**
     * Called when process exits with a non-zero exit code.
     * Subclasses can override to provide richer error context or custom logging.
     *
     * Default: logs a standard warning message.
     *
     * @param exitCode The non-zero exit code.
     * @param stderr   All captured stderr output.
     */
    protected open fun onProcessError(exitCode: Int, stderr: String) {
        val errMsg = stderr.trim()
        log.warn("{} exited with code {} — stderr: {}", id, exitCode, errMsg)
    }

    override fun isBinaryAvailable(): Boolean {
        val found = resolveAgentPath() != null
        if (!found) log.debug("{} binary not found on PATH", name)
        return found
    }

    override fun run(
        systemPrompt: String,
        userInput: String,
        workDir: File?,
        onToken: (String) -> Unit,
        onStatus: (String) -> Unit,
    ): Result<String> = runCatching {
        val agentPath = resolveAgentPath() ?: error("$name binary not found on PATH")
        val effectiveWorkDir = workDir ?: File(System.getProperty("user.home"))

        log.debug(
            "Starting {} for skill execution ({} chars systemPrompt, workDir={})",
            name,
            systemPrompt.length,
            effectiveWorkDir,
        )

        val cmd = buildCommand(agentPath, systemPrompt, userInput, effectiveWorkDir)
        val processBuilder = ProcessBuilderExt(*cmd.toTypedArray()).apply {
            effectiveWorkDir.mkdirs()
            directory(effectiveWorkDir)
            environment()["HOME"] = System.getProperty("user.home")
        }
        configureProcess(processBuilder, workDir, effectiveWorkDir, systemPrompt, userInput)
        val process = processBuilder.start()

        // Drain stderr in background to prevent blocking
        val stderrOutput = StringBuilder()
        val stderrThread = Thread {
            process.errorStream.bufferedReader().forEachLine { line ->
                log.debug("{} stderr: {}", id, line)
                stderrOutput.appendLine(line)
                onStderrLine(line, onStatus)
            }
        }.also {
            it.isDaemon = true
            it.start()
        }

        var stdinWriteError: IOException? = null

        // Write stdin (if needed by this agent)
        try {
            process.outputStream.bufferedWriter().use { writer ->
                writeStdin(writer, systemPrompt, userInput)
            }
        } catch (e: IOException) {
            stdinWriteError = e
            val isStillRunning = process.isAlive
            val exitCode = if (!isStillRunning) process.exitValue() else -1
            val errMsg = stderrOutput.toString().trim()
            log.debug(
                "Deferred stdin write error for {} (exit code: {}, running: {}): {} — stderr: {}",
                id,
                exitCode,
                isStillRunning,
                e.message,
                errMsg,
            )
            // Some CLIs close stdin early after consuming enough input.
            // Continue reading stdout/stderr and decide success from exit code.
        }

        // Parse stdout
        val output = StringBuilder()
        process.inputStream.bufferedReader().forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            parseStdoutLine(line, onToken, onStatus, output)
        }

        val exitCode = process.waitFor()
        stderrThread.join(2_000)

        if (exitCode != 0) {
            val errMsg = buildString {
                val stderr = filterErrorStderr(stderrOutput.toString())
                if (stderr.isNotBlank()) append(stderr)
                stdinWriteError?.message?.takeIf { it.isNotBlank() }?.let { writeErr ->
                    if (isNotEmpty()) append("\n")
                    append("stdin write error: ")
                    append(writeErr)
                }
            }.trim()
            onProcessError(exitCode, errMsg)
            error("$name exited with code $exitCode${if (errMsg.isNotBlank()) ": $errMsg" else ""}")
        }

        if (stdinWriteError != null) {
            log.debug(
                "Ignoring stdin write error for {} because process exited successfully: {}",
                id,
                stdinWriteError.message,
            )
        }

        output.toString().trimEnd()
    }.onFailure { e ->
        log.error("{} run failed: {}", name, e.message, e)
    }
}
