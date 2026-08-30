/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.skills.agent

import io.askimo.core.context.AppContext
import io.askimo.core.logging.logger
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.gemini.GeminiSettings
import io.askimo.core.security.SecureKeyManager
import io.askimo.core.util.ProcessBuilderExt
import java.io.BufferedWriter
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
    ): List<String> {
        val promptArg = userInput.ifBlank { " " }
        return listOf(
            agentPath,
            "--print",
            promptArg,
            "--output-format",
            "stream-json",
            "--dangerously-skip-permissions",
            "--add-dir",
            effectiveWorkDir.absolutePath,
        )
    }

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

    override fun writeStdin(
        writer: BufferedWriter,
        systemPrompt: String,
        userInput: String,
    ) {
        writer.write(buildStdin(systemPrompt))
    }

    override fun filterErrorStderr(stderr: String): String = stderr
        .lines()
        .filter { line -> STDERR_NOISE_PATTERNS.none { line.contains(it) } }
        .joinToString("\n")
        .trim()

    override fun parseStdoutLine(
        line: String,
        onToken: (String) -> Unit,
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
                val textDelta = event.fields["text_delta"] as? String
                when {
                    !textDelta.isNullOrEmpty() -> {
                        output.append(textDelta)
                        onToken(textDelta)
                    }

                    // "user_input DONE" is just an ack of our own prompt — nothing to surface.
                    stepType != null && stepType != "user_input" -> {
                        onStatus(if (state != null) "$stepType ($state)" else stepType)
                    }
                }
            }

            "result" -> {
                val status = event.fields["status"] as? String ?: "done"

                @Suppress("UNCHECKED_CAST")
                val usage = event.fields["usage"] as? Map<String, Any>
                val totalTokens = usage?.get("total_tokens")
                val durationSeconds = event.fields["duration_seconds"]
                val summary = buildString {
                    append("result: $status")
                    if (totalTokens != null) append(" | tokens: $totalTokens")
                    if (durationSeconds != null) {
                        val secs = durationSeconds.toString().toDoubleOrNull() ?: 0.0
                        append(" | duration: ${"%.1f".format(secs)}s")
                    }
                }
                onStatus(summary)
            }

            else -> onStatus(AntigravityStreamJsonEventParser.render(event))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildStdin(systemPrompt: String): String = buildString {
        if (systemPrompt.isNotBlank()) {
            append(systemPrompt.trim())
            append("\n\n---\n\n")
        }
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
