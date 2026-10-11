/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.service

import io.askimo.core.config.AppConfig

/**
 * Combines multiple [SuggestionService]s, trying each in order and returning the first
 * non-null suggestion. Ordering matters: put the most specific/relevant source first.
 *
 * Current composition (see `DesktopModule`): [HistoryBackedSuggestionService] (this exact
 * session's own history — most likely to be an exact continuation the user wants) before
 * [PersistedPhraseSuggestionService] (cross-session typing habits — broader, less specific).
 */
class CompositeSuggestionService(
    private val delegates: List<SuggestionService>,
) : SuggestionService {

    constructor(vararg delegates: SuggestionService) : this(delegates.toList())

    override fun suggest(input: String, sessionId: String?): String? {
        if (!AppConfig.suggestions.enabled) return null
        for (delegate in delegates) {
            val suggestion = delegate.suggest(input, sessionId)
            if (suggestion != null) return suggestion
        }
        return null
    }
}
