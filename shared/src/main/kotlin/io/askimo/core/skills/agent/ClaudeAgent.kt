/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.skills.agent

import io.askimo.core.logging.logger
import io.askimo.core.util.ProcessBuilderExt
import java.io.BufferedWriter
import java.io.File

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

    override fun resolveAgentPath(): String? = runCatching {
        val proc = ProcessBuilderExt("which", "claude")
            .redirectErrorStream(true)
            .start()
        val path = proc.inputStream.bufferedReader().readText().trim()
        if (proc.waitFor() == 0 && path.isNotBlank()) path else null
    }.getOrNull()
    override fun buildCommand(
        agentPath: String,
        systemPrompt: String,
        userInput: String,
        effectiveWorkDir: File,
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
        onStatus: (String) -> Unit,
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
                for (block in blocks) {
                    when (block.type) {
                        "text" -> {
                            val text = block.fields["text"] as? String ?: continue
                            if (text.isNotBlank()) onToken(text)
                        }

                        "tool_use" -> {
                            val toolName = block.fields["name"] as? String ?: "tool"

                            @Suppress("UNCHECKED_CAST")
                            val input = block.fields["input"] as? Map<String, Any>
                            val summary = buildString {
                                append("tool: $toolName")
                                if (input != null) {
                                    val detail = (input["file_path"] ?: input["command"] ?: input.values.firstOrNull())
                                        ?.toString()?.let {
                                            if (it.length > 80) it.take(77) + "…" else it
                                        }
                                    if (detail != null) append(" → $detail")
                                }
                            }
                            onStatus(summary)
                        }

                        "thinking" -> {
                            val thinking = block.fields["thinking"] as? String ?: continue
                            log.debug("claude thinking: {}", thinking.take(200))
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
                if (subtype == "success") {
                    val result = event.fields["result"] as? String ?: return
                    if (result.isNotBlank()) {
                        output.append(result)
                        onToken(result)
                    }
                    val costUsd = event.fields["total_cost_usd"]
                    val durationMs = event.fields["duration_ms"]
                    val numTurns = event.fields["num_turns"]
                    val stopReason = event.fields["stop_reason"] as? String
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
