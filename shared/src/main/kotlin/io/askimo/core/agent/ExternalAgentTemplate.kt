/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent

import io.askimo.core.agent.domain.SkillDefinition
import io.askimo.core.util.ProcessBuilderExt
import org.slf4j.Logger
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    @Volatile
    private var executionSessionId: String? = null

    @Volatile
    private var executionUsage: AgentUsage? = null

    @Volatile
    private var resultErrorMessage: String? = null

    /** The OS process for the run currently in flight, if any — see [cancel]. */
    @Volatile
    private var currentProcess: Process? = null

    /**
     * Set by [cancel] before killing [currentProcess], so [run] can distinguish this from a
     * genuine process failure. Uses [AtomicBoolean] so [run]'s capture-then-clear on start is
     * an atomic read-and-clear, not two racy steps that could drop a concurrent cancel().
     */
    private val cancelRequested = AtomicBoolean(false)

    override val lastExecutionSessionId: String?
        get() = executionSessionId

    override val lastExecutionUsage: AgentUsage?
        get() = executionUsage

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
     * @param agentPath       The resolved binary path (from [resolveAgentPath]).
     * @param systemPrompt    The skill's system prompt content.
     * @param userInput       The user's runtime input (may be blank).
     * @param resumeSessionId Native session id to resume (from a prior [lastExecutionSessionId]),
     *                        or `null` to start a fresh conversation. Agents that expose a
     *                        native resume flag (e.g. `--resume <id>`) should append it here;
     *                        agents with no such mechanism can ignore this parameter — the
     *                        default no-op override does exactly that.
     * @return List of command arguments to pass to ProcessBuilder.
     */
    protected abstract fun buildCommand(
        agentPath: String,
        systemPrompt: String,
        userInput: String,
        effectiveWorkDir: File,
        resumeSessionId: String?,
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
     * Should call [onToken] to stream response text, [onToolCall] when the agent actually
     * invokes a tool, and [onStatus] for non-tool lifecycle/status updates.
     *
     * @param line      A non-blank line from stdout.
     * @param onToken   Callback to emit response text tokens.
     * @param onToolCall Callback to emit a discrete tool invocation (name + optional detail).
     * @param onStatus  Callback to emit non-tool status messages (e.g. session init, run summary).
     * @param output    StringBuilder accumulating all processed output (append final result).
     */
    protected abstract fun parseStdoutLine(
        line: String,
        onToken: (String) -> Unit,
        onToolCall: (toolName: String, detail: String?) -> Unit,
        onStatus: (String) -> Unit,
        onThinking: (String) -> Unit,
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
     * Materializes [skill] into an agent's own native, workspace-scoped skill folder under
     * [skillsRootDir] (e.g. `<workDir>/.claude/skills/` for Claude Code) by **copying** the
     * skill's entire source folder (SKILL.md + supplemental files, excluding `.git`) as-is.
     * The destination folder name is [SkillDefinition.slug] — a sanitized, category-qualified
     * identifier, not just the skill's leaf folder name — so two skills that happen to share
     * a leaf folder name (e.g. imported from different packs) never collide once materialized.
     *
     * Use this for agents whose skill-discovery mechanism doesn't reliably support (or hasn't
     * been verified to support) symbolic links — see [materializeSkillSymlink] for a cheaper
     * alternative when the target agent is confirmed to follow symlinks.
     *
     * If a folder with the same name already exists under [skillsRootDir] (e.g. the user's own
     * project skill), it is left untouched and nothing is deleted on cleanup — we never want to
     * clobber user-owned files.
     */
    protected fun materializeSkillFolder(skill: SkillDefinition, skillsRootDir: Path): AutoCloseable = runCatching {
        val sourceDir = skill.absolutePath.parent
        if (sourceDir == null || !Files.isDirectory(sourceDir)) {
            log.warn(
                "Skipping materialization of skill '{}': source folder missing/invalid (absolutePath={}, resolvedParent={})",
                skill.name,
                skill.absolutePath,
                sourceDir,
            )
            return@runCatching AutoCloseable {}
        }

        val folderName = skill.slug
        val targetDir = skillsRootDir.resolve(folderName)
        log.debug(
            "Materializing skill '{}' (slug={}) from {} into {}",
            skill.name,
            folderName,
            sourceDir,
            targetDir,
        )

        if (Files.exists(targetDir)) {
            log.debug("Skill '{}' already present at {} — leaving as-is", folderName, targetDir)
            return@runCatching AutoCloseable {}
        }

        Files.createDirectories(targetDir)
        var copiedCount = 0
        Files.walk(sourceDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { path -> path.none { seg -> seg.toString() == ".git" } }
                .forEach { src ->
                    val dest = targetDir.resolve(sourceDir.relativize(src))
                    Files.createDirectories(dest.parent)
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
                    copiedCount++
                }
        }
        // The agent's native discovery mechanism (Claude/Codex/Antigravity) requires a file
        // named `SKILL.md` (case-insensitive) directly inside the materialized folder — log a
        // warning if it's missing so a silent no-op discovery failure is easy to diagnose from
        // logs alone, without needing to inspect the filesystem by hand.
        val hasEntryPoint = Files.list(targetDir).use { s ->
            s.anyMatch { it.fileName.toString().equals("SKILL.md", ignoreCase = true) }
        }
        if (!hasEntryPoint) {
            log.warn(
                "Materialized skill '{}' at {} has no SKILL.md entry point ({} files copied) — " +
                    "the target agent's native skill discovery will not recognize it",
                folderName,
                targetDir,
                copiedCount,
            )
        }
        log.debug("Materialized skill '{}' into {} ({} files copied)", folderName, targetDir, copiedCount)

        AutoCloseable {
            runCatching {
                Files.walk(targetDir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
                log.debug("Cleaned up materialized skill at {}", targetDir)
            }.onFailure { e -> log.warn("Failed to clean up materialized skill at {}: {}", targetDir, e.message) }
        }
    }.onFailure { e ->
        log.warn("Failed to materialize skill '{}': {}", skill.name, e.message, e)
    }.getOrElse { AutoCloseable {} }

    /**
     * Materializes [skill] into an agent's own native, workspace-scoped skill folder under
     * [skillsRootDir] (e.g. `<workDir>/.agents/skills/` for Antigravity) by creating a single
     * **symbolic link** pointing at the skill's own source folder, instead of copying files.
     * The destination link name is [SkillDefinition.slug] — a sanitized, category-qualified
     * identifier, not just the skill's leaf folder name — so two skills that happen to share
     * a leaf folder name (e.g. imported from different packs) never collide once materialized.
     * Cheaper than [materializeSkillFolder] (no file copying, cleanup is a single link
     * deletion) — only use this once the target agent is confirmed to follow symlinks when
     * discovering skills.
     *
     * If a folder or link with the same name already exists under [skillsRootDir] (e.g. the
     * user's own project skill), it is left untouched and nothing is deleted on cleanup — we
     * never want to clobber user-owned files.
     */
    protected fun materializeSkillSymlink(skill: SkillDefinition, skillsRootDir: Path): AutoCloseable = runCatching {
        val sourceDir = skill.absolutePath.parent
        if (sourceDir == null || !Files.isDirectory(sourceDir)) return@runCatching AutoCloseable {}

        val folderName = skill.slug
        val linkPath = skillsRootDir.resolve(folderName)

        if (Files.exists(linkPath, LinkOption.NOFOLLOW_LINKS)) {
            log.debug("Skill '{}' already present at {} — leaving as-is", folderName, linkPath)
            return@runCatching AutoCloseable {}
        }

        Files.createDirectories(skillsRootDir)
        Files.createSymbolicLink(linkPath, sourceDir)
        log.debug("Symlinked skill '{}' -> {} at {}", folderName, sourceDir, linkPath)

        AutoCloseable {
            runCatching {
                Files.deleteIfExists(linkPath)
                log.debug("Removed symlinked skill at {}", linkPath)
            }.onFailure { e -> log.warn("Failed to remove symlinked skill at {}: {}", linkPath, e.message) }
        }
    }.onFailure { e ->
        log.warn("Failed to symlink skill '{}': {}", skill.name, e.message)
    }.getOrElse { AutoCloseable {} }

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

    /**
     * Updates metadata captured for the current execution.
     * Subclasses call this while parsing stdout events.
     */
    protected fun updateExecutionMetadata(
        sessionId: String? = executionSessionId,
    ) {
        executionSessionId = sessionId
    }

    /**
     * Subclasses call this from within [parseStdoutLine] once the agent's stream reports
     * token usage / duration for the run (typically on its final `"result"` event).
     * See [AgentUsageExtractor] for a shared, defensive field-extraction helper.
     */
    protected fun updateExecutionUsage(usage: AgentUsage?) {
        executionUsage = usage
    }

    /**
     * Subclasses call this from within [parseStdoutLine] when the agent's own stream
     * reports an application-level failure (e.g. "Not logged in · Please run /login")
     * even though the OS process exits with code 0. Without this, such messages would
     * silently be treated as a normal successful response and shown to the user as if
     * the run succeeded.
     *
     * Once set, [run] converts the outcome to a [Result.failure] carrying [message].
     */
    protected fun reportResultError(message: String) {
        resultErrorMessage = message
    }

    override fun isBinaryAvailable(): Boolean {
        val path = resolveAgentPath()
        val found = path != null
        if (found) {
            log.debug("{} binary found on PATH: {}", name, path)
        } else {
            log.debug("{} binary not found on PATH", name)
        }
        return found
    }

    /**
     * Shared termination sequence used by both [cancel] and the early-cancellation check in
     * [run]: gracefully first (`Process.destroy()` — `SIGTERM` on Unix), giving the process up
     * to 3 seconds to exit on its own so any child processes it spawned (shell commands, etc.)
     * can clean up, then forcibly (`Process.destroyForcibly()` — `SIGKILL`) if it's still alive.
     */
    private fun terminateProcess(process: Process) {
        process.destroy()
        val exitedInTime = try {
            process.waitFor(3, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!exitedInTime) {
            log.debug("{} did not exit after destroy(); escalating to destroyForcibly()", id)
            process.destroyForcibly()
        }
    }

    /**
     * Requests termination of the run currently in flight, if any. Sets [cancelRequested]
     * unconditionally *before* looking at [currentProcess] — not only so [run] reports
     * [AgentCancelledException] instead of a generic "exited with code N" error, but so a Stop
     * click landing in the brief window between [run] starting the OS process and assigning it
     * to [currentProcess] is still honored: [run] checks [cancelRequested] again immediately
     * after that assignment and terminates the process itself if this method ran too early to
     * see it. No-op (beyond recording intent) if nothing is currently tracked.
     */
    override fun cancel() {
        cancelRequested.set(true)
        val process = currentProcess ?: return
        if (!process.isAlive) return
        log.debug("Cancel requested for {} (pid={})", id, runCatching { process.pid() }.getOrNull())
        terminateProcess(process)
    }

    override fun run(
        systemPrompt: String,
        userInput: String,
        workDir: File?,
        resumeSessionId: String?,
        onToken: (String) -> Unit,
        onToolCall: (toolName: String, detail: String?) -> Unit,
        onStatus: (String) -> Unit,
        onThinking: (String) -> Unit,
    ): Result<String> = runCatching {
        val agentPath = resolveAgentPath() ?: error("$name binary not found on PATH")
        val effectiveWorkDir = workDir ?: File(System.getProperty("user.home"))
        // Seed with resumeSessionId (if any) so lastExecutionSessionId still reflects the
        // conversation we're continuing even if this agent's CLI doesn't re-emit an id on
        // resume. A captured id emitted during this run (via updateExecutionMetadata) overrides it.
        updateExecutionMetadata(sessionId = resumeSessionId)
        resultErrorMessage = null
        executionUsage = null
        // Capture-then-clear instead of a plain reset: a Stop click can land before this run()
        // call is reached (e.g. while skills are still being materialized). getAndSet(false)
        // does this atomically so a concurrent cancel() can't be clobbered by our own clear.
        val cancelledBeforeStart = cancelRequested.getAndSet(false)
        if (cancelledBeforeStart) {
            log.debug("{} cancel() was requested before this run started; aborting immediately", id)
            throw AgentCancelledException()
        }

        log.debug(
            "Starting {} for skill execution ({} chars systemPrompt, workDir={}, resume={})",
            name,
            systemPrompt.length,
            effectiveWorkDir,
            resumeSessionId != null,
        )

        val cmd = buildCommand(agentPath, systemPrompt, userInput, effectiveWorkDir, resumeSessionId)
        log.debug("{} command: {} ({} args)", name, agentPath, cmd.size)
        val processBuilder = ProcessBuilderExt(*cmd.toTypedArray()).apply {
            effectiveWorkDir.mkdirs()
            directory(effectiveWorkDir)
            environment()["HOME"] = System.getProperty("user.home")
        }
        configureProcess(processBuilder, workDir, effectiveWorkDir, systemPrompt, userInput)
        val process = processBuilder.start()
        currentProcess = process
        // Honor a Stop click that landed in the tiny window between start() above and this
        // assignment — cancel() would have set cancelRequested but found currentProcess still
        // null/stale, so it couldn't terminate this process itself.
        if (cancelRequested.get()) {
            log.debug("{} cancel() was requested before process tracking began; terminating immediately", id)
            terminateProcess(process)
        }

        try {
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

            // Write stdin in a background thread so stdout draining starts immediately.
            // For long agent runs the stdout pipe buffer (~64 KB) fills up fast; if we
            // block on stdin first the agent can't write more output → deadlock → broken pipe.
            val stdinThread = Thread {
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
                    // Some CLIs close stdin early after consuming enough input — safe to ignore.
                }
            }.also {
                it.isDaemon = true
                it.name = "$id-stdin"
                it.start()
            }

            // Parse stdout — runs on calling thread while stdin is written concurrently.
            // If cancel() destroys the process mid-read, this loop simply ends (stream closes)
            // rather than throwing — the cancelRequested check below is what reports it.
            val output = StringBuilder()
            process.inputStream.bufferedReader().forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                parseStdoutLine(line, onToken, onToolCall, onStatus, onThinking, output)
            }

            // Wait for stdin to finish (it is almost always done by the time stdout is drained).
            stdinThread.join(5_000)

            val exitCode = process.waitFor()
            stderrThread.join(2_000)

            // cancel() killed this process on purpose — report a distinct cancellation outcome
            // instead of a generic "exited with code N" error, regardless of the actual exit
            // code or any resultErrorMessage the partial output may have triggered.
            if (cancelRequested.get()) {
                log.debug("{} run cancelled by user (exit code {})", id, exitCode)
                throw AgentCancelledException()
            }

            // An application-level failure reported by the agent's own stream (e.g. "Not logged
            // in · Please run /login") is the most accurate error message we have — prefer it over
            // a generic "exited with code N" message, regardless of the OS exit code.
            resultErrorMessage?.let { errMsg ->
                error(errMsg)
            }

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
        } finally {
            currentProcess = null
            cancelRequested.set(false)
        }
    }.onFailure { e ->
        if (e is AgentCancelledException) {
            log.debug("{} run cancelled: {}", name, e.message)
        } else {
            log.error("{} run failed: {}", name, e.message, e)
        }
    }
}
