/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat

import io.askimo.core.chat.domain.SESSION_TITLE_MAX_LENGTH

/**
 * Deterministic, non-AI title generation shared by chat sessions and agent-run
 * conversations. Given the first user message, produces a short, non-empty title —
 * preferring to cut at the end of a sentence rather than mid-word — so a title is
 * always available instantly, before any (optional) AI-refined title arrives async.
 */
object TitleGenerator {
    /**
     * Truncates [firstMessage] into a title of at most [SESSION_TITLE_MAX_LENGTH] characters.
     * Prefers cutting at the first sentence boundary (`. `, `? `, `! `) if that keeps the
     * result within the limit; otherwise hard-truncates with an ellipsis.
     */
    fun fallbackTitle(firstMessage: String): String {
        val cleaned = firstMessage.trim().replace("\n", " ")
        return when {
            cleaned.length <= SESSION_TITLE_MAX_LENGTH -> cleaned

            cleaned.contains(". ") -> {
                val candidate = cleaned.substringBefore(". ") + "."
                if (candidate.length <= SESSION_TITLE_MAX_LENGTH) {
                    candidate
                } else {
                    cleaned.take(SESSION_TITLE_MAX_LENGTH - 3) + "..."
                }
            }

            cleaned.contains("? ") -> {
                val candidate = cleaned.substringBefore("? ") + "?"
                if (candidate.length <= SESSION_TITLE_MAX_LENGTH) {
                    candidate
                } else {
                    cleaned.take(SESSION_TITLE_MAX_LENGTH - 3) + "..."
                }
            }

            cleaned.contains("! ") -> {
                val candidate = cleaned.substringBefore("! ") + "!"
                if (candidate.length <= SESSION_TITLE_MAX_LENGTH) {
                    candidate
                } else {
                    cleaned.take(SESSION_TITLE_MAX_LENGTH - 3) + "..."
                }
            }

            else -> cleaned.take(SESSION_TITLE_MAX_LENGTH - 3) + "..."
        }
    }
}
