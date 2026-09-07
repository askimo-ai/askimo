/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent

import io.askimo.core.agent.domain.SkillDefinition
import io.askimo.core.context.AppContext
import io.askimo.core.logging.logger
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.gemini.GeminiSettings
import io.askimo.core.security.SecureKeyManager
import io.askimo.core.util.JsonUtils
import io.askimo.core.util.ProcessBuilderExt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * External agent implementation for the
 * [Antigravity CLI](https://antigravity.google) (`agy`)
 */
class AntigravityAgent : ExternalAgentTemplate() {

    override val log = logger<AntigravityAgent>()

    override val id = "antigravity"
    override val name = "Antigravity CLI"
    override val installUrl = "https://antigravity.google"

    override val supportsNativeSkillDiscovery = true

    /**
     * Materializes [skill] into `<workDir>/.agents/skills/<folder-name>/` via a symbolic
     * link — Antigravity's workspace-scoped skill location per its docs ("Where skills
     * live"): `<workspace-root>/.agents/skills/<skill-folder>/` (Antigravity also supports
     * a global `~/.gemini/config/skills/` location and the legacy `.agent/skills` path, but
     * workspace scope mirrors Claude Code's per-workspace materialization and avoids
     * polluting the user's global skill folder with Askimo-managed, run-scoped skills).
     *
     * Uses a symlink rather than copying files (see [ExternalAgentTemplate.materializeSkillFolder])
     * since Antigravity is confirmed to follow symlinked skill folders — cheaper than copying
     * and trivial to clean up (a single link deletion).
     */
    override fun materializeSkill(skill: SkillDefinition, workDir: File): AutoCloseable = materializeSkillSymlink(skill, workDir.toPath().resolve(".agents").resolve("skills"))

    /**
     * Resolves the Google/Gemini API key from:
     * 1. AppContext GeminiSettings (if initialized) — handles keychain/encrypted refs
     * 2. SecureKeyManager direct lookup by provider key "gemini"
     * Returns null if no key is configured (user may rely on OAuth login instead).
     */
    private fun resolveApiKey(): String? {
        // Try AppContext first (handles keychain placeholder + encrypted prefix)
        runCatching {
            val ctx = AppContext.getInstance()
            val settings = ctx.getOrCreateProviderSettings(ModelProvider.GEMINI)
            if (settings is GeminiSettings) {
                val raw = settings.apiKey
                if (raw.isNotBlank() && raw != "***keychain***" && !raw.startsWith("encrypted:")) {
                    return raw
                }
            }
        }
        // Fall back to secure key manager using provider key name "gemini"
        return SecureKeyManager.retrieveSecretKey(ModelProvider.GEMINI.name.lowercase())
    }

    /**
     * Resolves the absolute path to the `agy` executable on `PATH`.
     * Returns null if not found.
     */
    override fun resolveAgentPath(): String? = ProcessBuilderExt.which("agy")

    override val requiresApiKey = true

    override fun isConfigured(): Boolean {
        if (!super.isBinaryAvailable()) return false
        val hasKey = resolveApiKey()?.isNotBlank() == true
        if (!hasKey) log.debug("antigravity CLI found but no GEMINI_API_KEY configured")
        return hasKey
    }

    /**
     * Non-blocking heads-up surfaced above the conversation title whenever a GEMINI_API_KEY is
     * configured but would be silently ignored by `agy` because `~/.gemini/antigravity-cli/
     * settings.json` is missing `"modelProvider": "gemini"` — per Antigravity's own docs. Not
     * shown when no key is configured at all: using agy's own starter quota with no key is a
     * legitimate, working configuration on its own, so there's nothing actionable to warn about.
     */
    override val nonBlockingWarning: AgentWarning?
        get() {
            val hasKey = resolveApiKey()?.isNotBlank() == true
            if (!hasKey || hasGeminiModelProvider()) return null
            return AgentWarning(
                messageKey = "agents.agentic.warning.antigravity.starter_quota",
                args = listOf(settingsFile().absolutePath),
                fixActionLabelKey = "agents.agentic.warning.fix_action",
                onFix = { fixModelProviderSetting() },
            )
        }

    /**
     * Resolves the path to Antigravity's own settings file, per its docs:
     * `~/.gemini/antigravity-cli/settings.json`.
     */
    private fun settingsFile(): File = File(System.getProperty("user.home"), ".gemini/antigravity-cli/settings.json")

    /**
     * Checks whether Antigravity's own settings file already selects the Gemini API key as its
     * model provider (`{"modelProvider": "gemini"}`). Used only by [nonBlockingWarning] — never
     * to block readiness, since running without this (on agy's own starter quota) is a
     * legitimate configuration too.
     */
    private fun hasGeminiModelProvider(): Boolean {
        val file = settingsFile()
        if (!file.exists() || !file.isFile) return false
        val fields = runCatching { JsonLineParser.parseObject(file.readText().trim()) }.getOrNull() ?: return false
        return (fields["modelProvider"] as? String)?.equals("gemini", ignoreCase = true) == true
    }

    /**
     * One-click remedy for [nonBlockingWarning]: merges `"modelProvider": "gemini"` into the
     * existing settings file without touching any other keys the user may already have there
     * (e.g. `model`, `agent`). Creates the file (and parent dirs) if it doesn't exist yet.
     */
    private fun fixModelProviderSetting() {
        runCatching {
            val file = settingsFile()
            val existing = if (file.exists() && file.isFile) {
                val parsed = runCatching { JsonUtils.json.parseToJsonElement(file.readText().trim()) }.getOrNull()
                (parsed as? JsonObject) ?: JsonObject(emptyMap())
            } else {
                JsonObject(emptyMap())
            }
            val merged = JsonObject(existing.toMutableMap().apply { put("modelProvider", JsonPrimitive("gemini")) })
            file.parentFile?.mkdirs()
            file.writeText(JsonUtils.prettyJson.encodeToString(JsonObject.serializer(), merged))
            log.debug("Updated {} with \"modelProvider\": \"gemini\"", file.absolutePath)
        }.onFailure { e ->
            log.warn("Failed to auto-fix {}: {}", settingsFile().absolutePath, e.message)
        }
    }

    /**
     * Stores [key] securely and syncs it to AppContext GeminiSettings so both the
     * Skills executor and the chat provider share the same key without re-entry.
     */
    override fun saveApiKey(key: String) {
        if (key.isBlank()) return
        SecureKeyManager.storeSecuredKey(ModelProvider.GEMINI.name.lowercase(), key)
        // Sync to AppContext so the chat Gemini provider picks it up in the same session
        runCatching {
            val ctx = AppContext.getInstance()
            val settings = ctx.getOrCreateProviderSettings(ModelProvider.GEMINI)
            if (settings is GeminiSettings) {
                settings.apiKey = key
            }
        }
        log.debug("Gemini API key saved and synced to provider settings")
    }

    override fun buildCommand(
        agentPath: String,
        systemPrompt: String,
        userInput: String,
        effectiveWorkDir: File,
        resumeSessionId: String?,
    ): List<String> {
        val prompt = buildString {
            if (systemPrompt.isNotBlank()) {
                append(systemPrompt.trim())
                append("\n\n---\n\n")
            }
            append(userInput.trim())
        }.let(::sanitizePromptArg)

        return buildList {
            add(agentPath)
            add("--output-format")
            add("stream-json")
            add("--dangerously-skip-permissions")
            add("--add-dir")
            add(effectiveWorkDir.absolutePath)
            if (!resumeSessionId.isNullOrBlank()) {
                add("--conversation")
                add(resumeSessionId)
            }
            add("--print")
            add(prompt)
        }
    }

    /**
     * Works around a Windows-only argument-corruption bug where a single CLI argument
     * containing an embedded, unescaped double quote (e.g. `with content "Hello world"`) gets
     * split into multiple unrelated positional arguments at the OS command-line boundary
     * (Java's argument quoting for `ProcessBuilder`/`CreateProcessW`, and/or `agy`'s own
     * Rust argv parsing, mishandles the internal quote) — observed as `agy` reporting
     * `unexpected argument "world"` for a prompt ending in `"Hello world"`. Neither the JDK
     * nor the CLI expose a way to escape this correctly from our side, so straight double
     * quotes are replaced with single quotes before the prompt ever reaches argv. Only applied
     * on Windows; Unix `ProcessBuilder` passes argv directly with no such corruption.
     */
    private fun sanitizePromptArg(text: String): String = if (ProcessBuilderExt.isWindows()) text.replace('"', '\'') else text

    override fun configureProcess(
        builder: ProcessBuilderExt,
        requestedWorkDir: File?,
        effectiveWorkDir: File,
        systemPrompt: String,
        userInput: String,
    ) {
        loadDotEnv(requestedWorkDir)?.forEach { (k, v) -> builder.environment()[k] = v }
        resolveApiKey()?.takeIf { it.isNotBlank() }?.let { key ->
            log.debug("Injecting GEMINI_API_KEY from Askimo provider settings")
            builder.environment()["GEMINI_API_KEY"] = key
        }
    }

    override fun filterErrorStderr(stderr: String): String = stderr
        .lines()
        .filter { line -> STDERR_NOISE_PATTERNS.none { line.contains(it) } }
        .joinToString("\n")
        .trim()

    override fun parseStdoutLine(
        line: String,
        onToken: (String) -> Unit,
        onToolCall: (toolName: String, detail: String?) -> Unit,
        onStatus: (String) -> Unit,
        onThinking: (String) -> Unit,
        output: StringBuilder,
    ) {
        val event = AntigravityStreamJsonEventParser.parse(line)
        if (event == null) {
            log.debug("antigravity unparseable line: {}", line)
            return
        }
        log.debug("antigravity event: type={}, line {}", event.type, line)
        when (event.type) {
            "init" -> {
                // Session metadata (cwd, available tools, permission mode) — capture the
                // conversation id for history/session tracking; nothing to show the user.
                val conversationId = event.fields["conversation_id"] as? String
                if (conversationId != null) updateExecutionMetadata(sessionId = conversationId)
            }

            "step_update" -> {
                val stepType = event.fields["step_type"] as? String
                val state = event.fields["state"] as? String
                // Some `agy` versions stream incremental text under `text_delta`; others emit
                // the full text for the step in one shot under `text` or `response` once
                // `state == DONE`. Check all known variants so we don't drop content.
                val textDelta = event.fields["text_delta"] as? String
                    ?: event.fields["text"] as? String
                    ?: event.fields["response"] as? String

                when {
                    !textDelta.isNullOrEmpty() -> {
                        output.append(textDelta)
                        onToken(textDelta)
                    }

                    // "user_input DONE" is just an ack of our own prompt — nothing to surface.
                    stepType == "user_input" -> Unit

                    // Lifecycle updates for the final answer step with no text yet — the "DONE"
                    // variant is just an ack (the reply was already streamed via text_delta, or
                    // will be surfaced by the final "result" event) so it's pure noise in the UI.
                    stepType == "agent_response" -> Unit

                    // Actual tool invocations. `agy` reports the generic `step_type == "tool"` for
                    // every tool call, with the real tool identity in the `tool_name` field and its
                    // arguments nested under `tool_info.parameters`, e.g.:
                    // {"step_type":"tool","tool_name":"view_file","tool_info":{"name":"view_file","parameters":{"AbsolutePath":"..."}}}
                    stepType == "tool" -> {
                        val toolInfo = event.fields["tool_info"] as? Map<*, *>

                        @Suppress("UNCHECKED_CAST")
                        val toolParams = toolInfo?.get("parameters") as? Map<String, Any>
                        val toolName = event.fields["tool_name"] as? String ?: "tool"
                        val detail = toolParams?.let { formatToolArgs(it).take(ExternalAgent.TOOL_DETAIL_MAX_LENGTH) }
                        onToolCall(toolName, detail?.ifBlank { null })
                    }

                    // Any other step type's "DONE" state is just a lifecycle ack — like
                    // "user_input"/"agent_response" above, it carries no new information (the
                    // actual content already streamed via text_delta or will arrive in the
                    // final "result" event), so showing it as a status line is pure noise that
                    // flashes in the UI and then vanishes once the real message renders.
                    state.equals("DONE", ignoreCase = true) -> Unit

                    state != null -> onStatus(state)
                }
            }

            "result" -> {
                val status = event.fields["status"] as? String ?: "done"
                val response = event.fields["response"] as? String
                val errorMessage = event.fields["error"] as? String

                @Suppress("UNCHECKED_CAST")
                val usage = event.fields["usage"] as? Map<String, Any>
                updateExecutionUsage(AgentUsageExtractor.extract(event.fields, usage))

                // An ERROR result (e.g. quota exceeded, auth failure) carries the actual
                // failure reason in `error` — report it via reportResultError so run() prefers
                // it over the generic "exited with code N" message once the process exits
                // (agy also exits non-zero on these application-level failures, and without
                // this the specific reason — e.g. "Individual quota reached..." — was silently
                // discarded and only the generic exit-code message reached the UI).
                if (status.equals("ERROR", ignoreCase = true) && !errorMessage.isNullOrBlank()) {
                    reportResultError(errorMessage)
                }

                // `agy` doesn't always stream the answer via `step_update.text_delta` — when
                // no step_update carried any text, the full answer is only delivered here in
                // `result.response`. Surface it as the final token so the reply isn't dropped.
                if (!response.isNullOrEmpty() && output.isEmpty()) {
                    output.append(response)
                    onToken(response)
                }
            }

            else -> onStatus(AntigravityStreamJsonEventParser.render(event))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Renders a tool call's `args`/`arguments` payload (per the Antigravity hooks doc,
     * `toolCall.args` is a JSON object keyed by tool-specific parameter names such as
     * `CommandLine`, `TargetFile`, `Query`, etc.) into a compact single-line summary for
     * display next to the tool name.
     */
    private fun formatToolArgs(args: Any): String = when (args) {
        is Map<*, *> -> args.entries.joinToString(", ") { (k, v) ->
            val display = v?.toString()?.replace('\n', ' ')?.let {
                if (it.length > 80) it.take(77) + "…" else it
            } ?: "null"
            "$k=$display"
        }

        else -> args.toString()
    }

    /**
     * Reads key=value pairs from the first `.env` file found in:
     *   1. [workDir]
     *   2. User home (`~`)
     *   3. `~/.askimo/personal`
     *
     * Lines starting with `#` and blank lines are ignored.
     * Returns `null` if no `.env` file is found.
     */
    private fun loadDotEnv(workDir: File?): Map<String, String>? {
        val candidates = listOfNotNull(
            workDir?.resolve(".env"),
            File(System.getProperty("user.home"), ".env"),
            File(System.getProperty("user.home"), ".askimo/personal/.env"),
        )
        val envFile = candidates.firstOrNull { it.exists() && it.isFile } ?: return null
        log.debug("Loading .env from {}", envFile.absolutePath)
        return envFile.readLines()
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") && it.contains("=") }
            .associate { line ->
                val idx = line.indexOf('=')
                line.substring(0, idx).trim() to line.substring(idx + 1).trim().removeSurrounding("\"").removeSurrounding("'")
            }
    }

    companion object {
        /**
         * Stderr lines containing these substrings are noise emitted by the Antigravity CLI
         * regardless of the actual response — filtered out when reporting errors.
         */
        private val STDERR_NOISE_PATTERNS = listOf(
            "256-color support not detected",
            "Ripgrep is not available",
            "Falling back to GrepTool",
        )
    }
}
