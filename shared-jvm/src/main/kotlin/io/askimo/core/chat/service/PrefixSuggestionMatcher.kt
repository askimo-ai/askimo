/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.service

/**
 * Shared "ghost text" matching logic used by every [SuggestionService] that completes [trimmedInput]
 * against a pool of candidate strings via case-insensitive whole-input prefix matching — e.g.
 * [HistoryBackedSuggestionService] (candidates = past user messages) and
 * [PersistedPhraseSuggestionService] (candidates = AI-derived habit phrases).
 *
 * A candidate qualifies when it's strictly longer than [trimmedInput], starts with it
 * (case-insensitive), and isn't equal to it (ignoring case) — i.e. there's an actual non-empty
 * remainder to suggest. Among qualifying candidates, [comparator]'s *maximum* wins (callers
 * typically rank by usage frequency then recency, via `compareBy`).
 *
 * @return the suggested remainder (`candidateText.substring(trimmedInput.length)`), or `null`
 *   if no candidate qualifies.
 */
internal fun <T> bestPrefixMatch(
    candidates: Sequence<T>,
    trimmedInput: String,
    textOf: (T) -> String,
    comparator: Comparator<T>,
): String? {
    val match = candidates
        .filter { candidate ->
            val text = textOf(candidate)
            text.length > trimmedInput.length &&
                text.startsWith(trimmedInput, ignoreCase = true) &&
                !text.equals(trimmedInput, ignoreCase = true)
        }
        .maxWithOrNull(comparator)
        ?: return null

    return textOf(match).substring(trimmedInput.length)
}
