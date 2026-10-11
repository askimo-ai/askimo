/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.service

/**
 * Combines multiple [SuggestionService]s, trying each in order and returning the first
 * non-null suggestion. Ordering matters: put the most specific/relevant source first.
 *
 * Current composition (see `DesktopModule`): [HistoryBackedSuggestionService] (own message
 * history, always on, free, local-only) before [PersistedPhraseSuggestionService] (AI-derived
 * typing habits, gated on `AppConfig.suggestions.aiExtractionEnabled`).
 *
 * This dispatcher stays config-agnostic — each delegate owns its own on/off semantics.
 */
class CompositeSuggestionService(
    private val delegates: List<SuggestionService>,
) : SuggestionService {

    constructor(vararg delegates: SuggestionService) : this(delegates.toList())

    override fun suggest(input: String, sessionId: String?): String? {
        for (delegate in delegates) {
            val suggestion = delegate.suggest(input, sessionId)
            if (suggestion != null) return suggestion
        }
        return null
    }
}
