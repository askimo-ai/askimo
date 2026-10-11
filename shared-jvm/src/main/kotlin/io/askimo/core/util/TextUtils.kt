/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

/**
 * General-purpose text-processing helpers used across modules. Add new
 * string-processing helpers here as needed.
 */
object TextUtils {
    /**
     * Strips common Markdown syntax from [text] and collapses whitespace, producing a short
     * plain-text snippet suitable for surfaces that can't render Markdown — e.g. OS-level
     * notification banners
     *
     * The result is truncated to at most [maxLen] characters (appending an ellipsis if truncated).
     * Returns an empty string for blank input.
     */
    fun stripMarkdownForPreview(text: String, maxLen: Int = 100): String {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return ""

        var plain = trimmed
            // Fenced code blocks: drop the whole block content along with the fences.
            .replace(Regex("```[\\s\\S]*?```"), " ")
            // Inline code: keep the content, drop the backticks.
            .replace(Regex("`([^`]*)`"), "$1")
            // Images: ![alt](url) -> alt
            .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
            // Links: [text](url) -> text
            .replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
            // Headings: leading #'s
            .replace(Regex("(?m)^#{1,6}\\s*"), "")
            // Blockquotes
            .replace(Regex("(?m)^>\\s?"), "")
            // Bold/italic/strikethrough markers
            .replace(Regex("[*_~]{1,3}"), "")
            // List bullets
            .replace(Regex("(?m)^\\s*[-+*]\\s+"), "")
            // Collapse all whitespace (including newlines) to single spaces
            .replace(Regex("\\s+"), " ")
            .trim()

        if (plain.isBlank()) return ""

        if (plain.length > maxLen) {
            plain = plain.take(maxLen).trimEnd() + "…"
        }
        return plain
    }

    /** Escapes a string for safe interpolation inside an AppleScript double-quoted literal. */
    fun quoteForAppleScript(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/**
 * Scrubs known-identifying patterns from free-text error messages before they're sent to
 * analytics, then truncates to a short bound. This is the ONLY sanctioned way to include
 * exception/provider text in an analytics payload — never pass raw `Throwable.message` or
 * rendered user-facing text directly (see the privacy contract above).
 *
 * Pattern-based and best-effort, not a formal guarantee — callers that need a zero-risk
 * alternative should prefer a fixed, bounded identifier instead (e.g. an i18n message key).
 *
 * Order matters: broader patterns (URLs) are redacted before narrower ones (bare tokens)
 * that could otherwise match substrings of an already-redacted URL.
 */
fun redactErrorText(message: String): String {
    val result = message
        .replace(Regex("[\\r\\n]+"), " ")
        .replace(Regex("""[\w.+-]+@[\w-]+\.[\w.-]+"""), "[email]")
        .replace(Regex("""https?://\S+"""), "[url]")
        .replace(Regex("""(?i)bearer\s+\S+"""), "[token]")
        .replace(Regex("""\b(sk|pk|key)-[A-Za-z0-9_-]{10,}\b"""), "[api-key]")
        .replace(Regex("""(?:[A-Za-z]:\\|/)[\w./\\-]{3,}"""), "[path]")
        .replace(Regex("""\b[A-Za-z0-9_-]{16,}\b"""), "[id]")

    return result.take(500)
}
