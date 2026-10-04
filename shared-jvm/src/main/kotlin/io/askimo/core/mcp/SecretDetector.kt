/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.mcp

/**
 * Determines whether a parameter value should be stored securely via [SecureKeyManager].
 *
 * Two-layer detection in priority order:
 *  1. **Definition-based** — if the [McpServerDefinition] declares the parameter as
 *     [ParameterType.SECRET], that is authoritative.
 *  2. **Convention-based** — if no definition is available (e.g. user-defined server or
 *     unknown parameter), fall back to keyword matching on the parameter name.
 */
object SecretDetector {

    private val SECRET_NAME_PATTERNS = setOf(
        "key", "token", "secret", "password", "passwd", "pwd",
        "credential", "auth", "apikey", "api_key", "private",
        "cert", "bearer", "access_token", "refresh_token",
    )

    /**
     * Patterns that must match as a whole word/segment rather than as a substring,
     * to avoid false positives (e.g. "pat" inside "rootPath").
     * Matching is done against the original (non-normalised) key split by camelCase / underscores / hyphens.
     */
    private val SECRET_SEGMENT_PATTERNS = setOf("pat")

    /**
     * Returns true if [paramKey] should be stored securely.
     *
     * @param paramKey  The parameter name (e.g. "apiKey", "GITHUB_TOKEN")
     * @param definition Optional server definition — checked first for [ParameterType.SECRET]
     */
    fun isSecret(paramKey: String, definition: McpServerDefinition? = null): Boolean {
        // 1. Definition-based — authoritative
        if (definition != null) {
            val param = definition.parameters.find { it.key == paramKey }
            if (param != null) return param.type == ParameterType.SECRET
        }

        // 2. Convention-based fallback
        val normalised = paramKey.lowercase()
            .replace("-", "")
            .replace("_", "")

        // Substring match for multi-character patterns (low false-positive risk)
        if (SECRET_NAME_PATTERNS.any { pattern ->
                normalised.contains(pattern.replace("_", ""))
            }
        ) {
            return true
        }

        // Segment match for short patterns like "pat" that could appear inside innocent words
        // e.g. GITHUB_PAT → segments=[github, pat] ✓  rootPath → segments=[root, path] ✗
        val segments = paramKey.lowercase()
            .split("_", "-")
            .flatMap { it.splitCamelCase() }
        return SECRET_SEGMENT_PATTERNS.any { it in segments }
    }

    /** Splits a camelCase string into lowercase words: "rootPath" → ["root", "path"] */
    private fun String.splitCamelCase(): List<String> = replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .lowercase()
        .split(" ")
        .filter { it.isNotEmpty() }
}
