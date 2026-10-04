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
