/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.service

/**
 * Detects whether URL content should be extracted based on explicit user intent.
 *
 * Following the pattern used by ChatGPT, Claude, and other AI services,
 * we only extract URL content when the user explicitly requests it through:
 * - Action verbs: "summarize", "analyze", "explain", "read"
 * - Questions about URL: "what does", "what's on", "what is"
 * - URL followed by a question or request
 *
 * This prevents automatic URL fetching for simple references or sharing,
 * which could be slow, privacy-invasive, or unexpected.
 */
object UrlIntentDetector {

    /**
     * Determines if URL content should be extracted based on explicit user intent.
     *
     * @param userMessage The user's message
     * @param url The URL to check
     * @return true if URL content should be extracted, false otherwise
     */
    fun shouldExtractUrlContent(userMessage: String, url: String): Boolean {
        val message = userMessage.lowercase()
        val urlLower = url.lowercase()

        // Find the position of the URL in the message
        val urlIndex = message.indexOf(urlLower)
        if (urlIndex == -1) return false

        // Get text before and after the URL (within reasonable distance)
        val beforeUrl = message.substring(0, urlIndex).takeLast(100) // Look at last 100 chars before URL
        val afterUrl = message.substring(urlIndex + urlLower.length).take(100) // Look at first 100 chars after URL

        // Patterns to check in text BEFORE the URL (action verb before URL)
        val beforePatterns = listOf(
            "\\b(summarize|summarise)\\s+[^.!?]*$", // Action verb not separated by sentence boundary
            "\\banalyze\\s+[^.!?]*$",
            "\\banalyse\\s+[^.!?]*$",
            "\\bexplain\\s+[^.!?]*$",
            "\\bread\\s+[^.!?]*$",
            "\\breview\\s+[^.!?]*$",
            "\\bexamine\\s+[^.!?]*$",
            "\\bcheck\\s+(what|what's|whats|the\\s+content|this)[^.!?]*$",
            // Questions about URL
            "what\\s+does[^.!?]*$",
            "what\\s+is[^.!?]*$",
            "what'?s?\\s+(on|in|at)[^.!?]*$",
            "what[^.!?]*$", // Generic "what" question before URL
        )

        // Patterns to check in text AFTER the URL (URL followed by request)
        val afterPatterns = listOf(
            "^[^.!?]*\\?", // URL followed by question mark (same sentence)
            "^[^.!?]*(tell|show)\\s+me", // URL followed by "tell me" or "show me"
            "^[^.!?]*(summarize|summarise|analyze|analyse|explain|review)", // URL followed by action
        )

        // Check if any before pattern matches
        val hasBeforeMatch = beforePatterns.any { pattern ->
            Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(beforeUrl)
        }

        // Check if any after pattern matches
        val hasAfterMatch = afterPatterns.any { pattern ->
            Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(afterUrl)
        }

        return hasBeforeMatch || hasAfterMatch
    }
}
