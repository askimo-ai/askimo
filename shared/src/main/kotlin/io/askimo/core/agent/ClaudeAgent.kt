/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent

import io.askimo.core.agent.domain.SkillDefinition
import io.askimo.core.logging.logger
import io.askimo.core.util.ProcessBuilderExt
import java.io.BufferedWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * External agent implementation for [Claude Code](https://docs.anthropic.com/en/docs/claude-code).
 *
 * Invocation:
 * ```
 * claude --print --dangerously-skip-permissions --append-system-prompt "<systemPrompt>"
 * ```
 * - `--print`                        Non-interactive mode: print the response to stdout and exit.
 * - `--dangerously-skip-permissions` Auto-approve all tool actions (no interactive confirmation).
 * - `--append-system-prompt`         Appends text to Claude's built-in system prompt at the API level,
 *                                    without touching any files on disk.
 *
 * Only `userInput` is written to stdin.
 */
class ClaudeAgent : ExternalAgentTemplate() {

    override val log = logger<ClaudeAgent>()

    override val id = "claude"
    override val name = "Claude Code"
    override val installUrl = "https://docs.anthropic.com/en/docs/claude-code"

    override val commands: List<AgentCommand> = listOf(
        AgentCommand(
            name = "/help",
            description = "Show available Claude Code commands",
            usage = "/help",
        ),
        AgentCommand(
            name = "/review",
            description = "Review code in the working directory",
            usage = "/review",
        ),
        AgentCommand(
            name = "/cost",
            description = "Show token usage and cost for this session",
            usage = "/cost",
        ),
        AgentCommand(
            name = "/doctor",
            description = "Check Claude Code installation and configuration",
            usage = "/doctor",
        ),
        AgentCommand(
            name = "/compact",
            description = "Compact conversation history to save tokens",
            usage = "/compact [instructions]",
        ),
    )

    override val configurationHint = "Run 'claude login' in a terminal to authenticate with your Anthropic account, then return here."

    /**
     * Materializes [skill] into `<workDir>/.claude/skills/<folder-name>/` so Claude Code's
     * own native "Skills" mechanism (its `Skill` tool) can also discover and invoke it —
     * on top of the ambient `--append-system-prompt` injection already done in [run].
     *
     * The skill's entire source folder (SKILL.md + supplemental files, excluding `.git`)
     * is copied as-is. If a skill folder with the same name already exists under
     * `<workDir>/.claude/skills/` (e.g. the user's own project skill), it is left untouched
     * and nothing is deleted on cleanup — we never want to clobber user-owned files.
     */
    override fun materializeSkill(skill: SkillDefinition, workDir: File): AutoCloseable = runCatching {
        val sourceDir = skill.absolutePath.parent
        if (sourceDir == null || !Files.isDirectory(sourceDir)) return@runCatching AutoCloseable {}

        val folderName = sourceDir.fileName.toString()
        val targetDir = workDir.toPath().resolve(".claude").resolve("skills").resolve(folderName)

        if (Files.exists(targetDir)) {
            log.debug("Claude skill '{}' already present at {} — leaving as-is", folderName, targetDir)
            return@runCatching AutoCloseable {}
        }

        Files.createDirectories(targetDir)
        Files.walk(sourceDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { path -> path.none { seg -> seg.toString() == ".git" } }
                .forEach { src ->
                    val dest = targetDir.resolve(sourceDir.relativize(src))
                    Files.createDirectories(dest.parent)
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
                }
        }
        log.debug("Materialized Claude skill '{}' into {}", folderName, targetDir)

        AutoCloseable {
            runCatching {
                Files.walk(targetDir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
                log.debug("Cleaned up materialized Claude skill at {}", targetDir)
            }.onFailure { e -> log.warn("Failed to clean up materialized skill at {}: {}", targetDir, e.message) }
        }
    }.onFailure { e ->
        log.warn("Failed to materialize Claude skill '{}': {}", skill.name, e.message)
    }.getOrElse { AutoCloseable {} }

    override fun resolveAgentPath(): String? = ProcessBuilderExt.which("claude")
    override fun buildCommand(
        agentPath: String,
        systemPrompt: String,
        userInput: String,
        effectiveWorkDir: File,
        resumeSessionId: String?,
    ): List<String> = buildList {
        add(agentPath)
        add("--print")
        add("--dangerously-skip-permissions")
        add("--verbose")
        add("--output-format")
        add("stream-json")
        if (systemPrompt.isNotBlank()) {
            add("--append-system-prompt")
            add(systemPrompt.trim())
        }
        // Claude Code keeps its own conversation transcript/context per session id;
        // `--resume` continues it instead of Askimo replaying prior turns itself.
        if (!resumeSessionId.isNullOrBlank()) {
            add("--resume")
            add(resumeSessionId)
        }
    }

    override fun writeStdin(
        writer: BufferedWriter,
        systemPrompt: String,
        userInput: String,
    ) {
        if (userInput.isNotBlank()) writer.write(userInput.trim() + "\n")
    }

    override fun parseStdoutLine(
        line: String,
        onToken: (String) -> Unit,
        onToolCall: (toolName: String, detail: String?) -> Unit,
        onStatus: (String) -> Unit,
        onThinking: (String) -> Unit,
        output: StringBuilder,
    ) {
        val event = ClaudeStreamJsonEventParser.parse(line)
        if (event == null) {
            log.debug("claude unparseable line: {}", line)
            return
        }
        log.debug("claude event: type={} line {}", event.type, line)
        when (event.type) {
            "system" -> {
                val subtype = event.fields["subtype"] as? String
                if (subtype == "init") {
                    val model = event.fields["model"] as? String
                    val permissionMode = event.fields["permissionMode"] as? String
                    val version = event.fields["claude_code_version"] as? String
                    // Claude Code's own session id — capture it so a follow-up turn can
                    // pass it back via `--resume` and continue this same conversation
                    // (Claude manages the transcript/context internally, not Askimo).
                    val sessionId = event.fields["session_id"] as? String
                    if (!sessionId.isNullOrBlank()) updateExecutionMetadata(sessionId = sessionId)
                    val summary = buildString {
                        append("claude init")
                        if (model != null) append(" | model: $model")
                        if (version != null) append(" | v$version")
                        if (permissionMode != null) append(" | permissions: $permissionMode")
                    }
                    onStatus(summary)
                }
            }

            "assistant" -> {
                val blocks = ClaudeStreamJsonEventParser.extractContentBlocks(event.fields)
                for ((type, fields) in blocks) {
                    when (type) {
                        "text" -> {
                            val text = fields["text"] as? String ?: continue
                            if (text.isNotBlank()) onToken(text)
                        }

                        "tool_use" -> {
                            val toolName = fields["name"] as? String ?: "tool"

                            @Suppress("UNCHECKED_CAST")
                            val input = fields["input"] as? Map<String, Any>
                            val detail = input
                                ?.let { it["file_path"] ?: it["command"] ?: it.values.firstOrNull() }
                                ?.toString()
                                ?.let {
                                    if (it.length > ExternalAgent.TOOL_DETAIL_MAX_LENGTH) {
                                        it.take(ExternalAgent.TOOL_DETAIL_MAX_LENGTH) + "…"
                                    } else {
                                        it
                                    }
                                }
                            onToolCall(toolName, detail)
                        }

                        "thinking" -> {
                            val thinking = fields["thinking"] as? String ?: continue
                            if (thinking.isBlank()) {
                                continue
                            }
                            log.debug("claude thinking: {}", thinking.take(200))
                            onThinking(thinking)
                        }
                    }
                }
            }

            "user" -> {
                when (val toolResult = ClaudeStreamJsonEventParser.extractToolUseResult(event.fields)) {
                    is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        val resultMap = toolResult as Map<String, Any>
                        val opType = resultMap["type"] as? String
                        val filePath = resultMap["filePath"] as? String
                        if (opType != null && filePath != null) {
                            val shortPath = filePath.substringAfterLast("/")
                            onStatus("✓ $opType: $shortPath")
                        }
                    }

                    is String -> {
                        log.debug("claude tool_use_result: {}", toolResult.take(200))
                    }
                }
            }

            "result" -> {
                val subtype = event.fields["subtype"] as? String
                val isError = event.fields["is_error"] as? Boolean ?: false
                val result = event.fields["result"] as? String

                if (isError) {
                    val errMsg = result?.takeIf { it.isNotBlank() } ?: "Claude Code reported an error"
                    onStatus("result: error | $errMsg")
                    reportResultError(errMsg)
                    return
                }

                if (subtype == "success") {
                    if (!result.isNullOrBlank()) {
                        output.append(result)
                        onToken(result)
                    }
                    val costUsd = event.fields["total_cost_usd"]
                    val durationMs = event.fields["duration_ms"]
                    val numTurns = event.fields["num_turns"]
                    val stopReason = event.fields["stop_reason"] as? String

                    // Claude's "result" event carries a nested "usage" object (best-effort —
                    // exact key names verified against real CLI output per agent; the
                    // extractor also falls back to top-level "duration_ms" captured above).
                    @Suppress("UNCHECKED_CAST")
                    val usageMap = event.fields["usage"] as? Map<String, Any>
                    updateExecutionUsage(AgentUsageExtractor.extract(event.fields, usageMap))
                    val summary = buildString {
                        append("result: success")
                        if (stopReason != null) append(" | stop: $stopReason")
                        if (numTurns != null) append(" | turns: $numTurns")
                        if (durationMs != null) {
                            val secs = (durationMs.toString().toDoubleOrNull() ?: 0.0) / 1000.0
                            append(" | duration: ${"%.1f".format(secs)}s")
                        }
                        if (costUsd != null) append(" | cost: \$$costUsd")
                    }
                    onStatus(summary)
                }
            }
        }
    }
}
