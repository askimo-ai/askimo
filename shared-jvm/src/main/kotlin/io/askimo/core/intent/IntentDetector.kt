/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.intent

/**
 * Intent detector interface for the Chain of Responsibility pattern.
 * Each detector is responsible for detecting a specific tool category.
 */
interface IntentDetector {
    /**
     * The tool category this detector is responsible for.
     */
    val category: ToolCategory

    /**
     * Detect if the message matches this category's intent.
     *
     * @param message The user message to analyze (already lowercased)
     * @return true if the message matches this category's intent
     */
    fun detect(message: String): Boolean
}

/**
 * Base implementation of IntentDetector providing common detection logic.
 */
abstract class BaseIntentDetector(
    override val category: ToolCategory,
    protected val directKeywords: List<String>,
    protected val contextualPatterns: List<String>,
) : IntentDetector {

    override fun detect(message: String): Boolean {
        // Check direct keywords first (fast path)
        if (detectDirectKeywords(message)) return true

        // Check contextual patterns
        return detectContextualPatterns(message)
    }

    /**
     * Detect direct keywords in the message.
     * Can be overridden for custom logic (e.g., word boundaries for short words).
     */
    protected open fun detectDirectKeywords(message: String): Boolean = directKeywords.any { message.contains(it) }

    /**
     * Detect contextual patterns in the message using regex.
     */
    protected open fun detectContextualPatterns(message: String): Boolean = contextualPatterns.any { pattern ->
        message.contains(Regex(pattern))
    }
}
