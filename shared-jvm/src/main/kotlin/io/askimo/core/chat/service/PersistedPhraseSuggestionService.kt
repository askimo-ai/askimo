/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.service

import io.askimo.core.chat.repository.PhraseVariant
import io.askimo.core.chat.repository.UserPhraseSuggestionRepository
import io.askimo.core.config.AppConfig
import io.askimo.core.logging.logger

/**
 * [SuggestionService] backed by AI-derived "typing habit" phrases persisted via
 * [UserPhraseSuggestionRepository] — see [PhraseHabitRefreshService] for how they're produced.
 *
 * Gated on [io.askimo.core.config.SuggestionsConfig.aiExtractionEnabled]: when the user hasn't
 * opted in to AI-based habit extraction, [suggest] returns null immediately — even if rows
 * remain from a previous opt-in, they won't be surfaced until re-enabled.
 *
 * Zero AI/network calls on the typing path: the persisted set (at most
 * [UserPhraseSuggestionRepository.MAX_CATEGORIES] x [UserPhraseSuggestionRepository.MAX_VARIANTS_PER_CATEGORY]
 * phrases) is kept as a small in-memory, write-through cache — [refreshCache] is called by
 * [PhraseHabitRefreshService] after every merge, so [suggest] never touches the database.
 *
 * Matching is exact, case-insensitive prefix only — the result is spliced in as
 * `match.substring(typed.length)`, which is only correct for a true prefix.
 *
 * Ties broken by highest `usageCount`, then most recently touched.
 */
class PersistedPhraseSuggestionService(
    private val phraseRepository: UserPhraseSuggestionRepository,
    private val profileIdProvider: () -> String?,
) : SuggestionService {

    companion object {
        /** Minimum typed length before we bother searching — avoids noisy 1-2 char matches. */
        private const val MIN_INPUT_LENGTH = 3
    }

    private val log = logger<PersistedPhraseSuggestionService>()

    @Volatile
    private var cachedProfileId: String? = null

    @Volatile
    private var cachedVariants: List<PhraseVariant> = emptyList()

    /**
     * Rebuilds the in-memory cache from the database for [profileId]. Called by
     * [PhraseHabitRefreshService] after every merge (write-through), and lazily on the first
     * [suggest] call if no refresh has run yet this session.
     */
    fun refreshCache(profileId: String) {
        val variants = runCatching { phraseRepository.getAllVariants(profileId) }
            .onFailure { e -> log.warn("Failed to load persisted phrase suggestions for profile {}: {}", profileId, e.message) }
            .getOrDefault(emptyList())
        cachedVariants = variants
        cachedProfileId = profileId
    }

    override fun suggest(input: String, sessionId: String?): String? {
        if (!AppConfig.suggestions.aiExtractionEnabled) return null

        val profileId = profileIdProvider() ?: return null
        if (cachedProfileId != profileId) {
            refreshCache(profileId)
        }

        val trimmed = input.trimStart()
        if (trimmed.length < MIN_INPUT_LENGTH) return null

        val match = cachedVariants
            .asSequence()
            .filter { variant ->
                variant.phrase.length > trimmed.length &&
                    variant.phrase.startsWith(trimmed, ignoreCase = true) &&
                    !variant.phrase.equals(trimmed, ignoreCase = true)
            }
            .maxWithOrNull(compareBy({ it.usageCount }, { it.updatedAt }))
            ?: return null

        return match.phrase.substring(trimmed.length)
    }
}
