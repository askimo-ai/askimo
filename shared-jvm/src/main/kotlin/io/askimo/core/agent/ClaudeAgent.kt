/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent

import io.askimo.core.agent.domain.SkillDefinition
import io.askimo.core.config.AppConfig
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

    override val supportsNativeSkillDiscovery = true

    /**
     * Materializes [skill] into `<workDir>/.claude/skills/<folder-name>/` so Claude Code's
     * native "Skills" mechanism can also discover and invoke it — on top of the ambient
     * `--append-system-prompt` injection already done in [run].
     */
    override fun materializeSkill(skill: SkillDefinition, workDir: File): AutoCloseable = materializeSkillFolder(skill, workDir.toPath().resolve(".claude").resolve("skills"))

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
        // Claude Code keeps its own conversation transcript per session id; `--resume`
        // continues it instead of Askimo replaying prior turns itself.
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
                    // Claude's own session id — capture it so a follow-up turn can pass it
                    // back via `--resume` and continue this conversation (Claude manages
                    // the transcript internally, not Askimo).
                    val sessionId = event.fields["session_id"] as? String
                    if (!sessionId.isNullOrBlank()) updateExecutionMetadata(sessionId = sessionId)

                    val model = event.fields["model"] as? String

                    if (AppConfig.developer.enabled && AppConfig.developer.active) {
                        // Dev mode: surface model, permission mode, tool/MCP counts, cwd —
                        // useful for debugging how the CLI is actually running.
                        val toolCount = (event.fields["tools"] as? String)
                            ?.let { ClaudeStreamJsonEventParser.parseArray(it).size }
                        val mcpCount = (event.fields["mcp_servers"] as? String)
                            ?.let { ClaudeStreamJsonEventParser.parseArray(it).size }
                        val permissionMode = event.fields["permissionMode"] as? String
                        val cwd = event.fields["cwd"] as? String

                        onStatus(
                            buildString {
                                append("init")
                                model?.let { append(" | model=$it") }
                                permissionMode?.let { append(" | permission=$it") }
                                toolCount?.let { append(" | tools=$it") }
                                mcpCount?.let { append(" | mcp=$it") }
                                cwd?.let { append(" | cwd=$it") }
                            },
                        )
                    } else if (!model.isNullOrBlank()) {
                        // Non-dev: just a brief, low-noise hint of which model answered.
                        onStatus("model: $model")
                    }
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
                            onToolCall(toolName, buildToolCallDetail(input))
                        }

                        "thinking" -> {
                            val thinking = fields["thinking"] as? String
                            if (thinking.isNullOrBlank()) continue
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

                    // Claude's "result" event carries a nested "usage" object (best-effort —
                    // exact key names verified against real CLI output per agent; the
                    // extractor also falls back to top-level "duration_ms" captured above).
                    @Suppress("UNCHECKED_CAST")
                    val usageMap = event.fields["usage"] as? Map<String, Any>
                    updateExecutionUsage(AgentUsageExtractor.extract(event.fields, usageMap))
                    // Pure lifecycle marker — nothing worth surfacing to the user as a status row.
                }
            }
        }
    }

    /**
     * Builds the display detail for a `tool_use` event's `input`. Prefers a `file_path`/
     * `command` "target" plus, when present, the tool's actual generated content (`content`
     * for `Write`, `new_string` for `Edit`) — without this, a `Write`/`Edit` call's real
     * content would never surface, since `file_path` always won the old fallback chain
     * before `content` was even looked at.
     *
     * Deliberately returns the **full, untruncated** detail: the live timeline shows the
     * entire content; only the persisted copy gets capped, right before being written to
     * `content_json` (see [io.askimo.core.chat.dto.truncatedForStorage]).
     */
    private fun buildToolCallDetail(input: Map<String, Any>?): String? {
        if (input == null) return null
        val target = input["file_path"] ?: input["command"]
        val content = ((input["content"] ?: input["new_string"]) as? String)?.takeIf { it.isNotBlank() }
        return when {
            target != null && content != null -> "$target: $content"
            target != null -> target.toString()
            else -> input.values.firstOrNull()?.toString()
        }
    }
}
