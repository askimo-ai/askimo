/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.db.AbstractRepository
import io.askimo.core.db.DatabaseManager
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant

/** A single stored phrase variant under a [categoryKey] — see [UserPhraseSuggestionRepository]. */
data class PhraseVariant(
    val id: String,
    val categoryKey: String,
    val phrase: String,
    val usageCount: Long,
    val updatedAt: Instant,
)

/**
 * Stores AI-derived "typing habit" phrases, grouped under stable [PhraseVariant.categoryKey]s
 * assigned by the extraction model (see `PhraseHabitRefreshService`) so repeated habits
 * reinforce the same entry instead of accumulating near-duplicates.
 *
 * ## Capacity
 * Bounded to [MAX_CATEGORIES] categories x [MAX_VARIANTS_PER_CATEGORY] variants per profile
 * (≤100 rows) — small enough that all grouping/eviction logic below operates on an
 * in-memory snapshot per profile rather than bespoke per-category SQL.
 *
 * ## Eviction (LRU-by-recency, mirrors [io.askimo.core.memory.UserMemorySummary.merge])
 * - Touching a variant (exact case-insensitive match confirmed again) bumps its `usage_count`
 *   and `updated_at` — keeping it alive.
 * - A category exceeding [MAX_VARIANTS_PER_CATEGORY] evicts its least-recently-touched variant.
 * - A profile exceeding [MAX_CATEGORIES] distinct categories evicts the **entire**
 *   least-recently-touched category (its most-recent variant's `updated_at` is used as the
 *   category's recency).
 *
 * No raw chat message content is ever stored here — only AI-generalized phrases.
 */
class UserPhraseSuggestionRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractRepository(databaseManager) {

    companion object {
        const val MAX_CATEGORIES = 20
        const val MAX_VARIANTS_PER_CATEGORY = 5
    }

    private val queries get() = db.userPhraseSuggestionsQueries

    /** All stored variants for [profileId], grouped by category key. */
    fun getCategories(profileId: String): Map<String, List<PhraseVariant>> = queries
        .selectByProfileId(profileId)
        .executeAsList()
        .map {
            PhraseVariant(
                id = it.id,
                categoryKey = it.category_key,
                phrase = it.phrase,
                usageCount = it.usage_count,
                updatedAt = it.updated_at,
            )
        }
        .groupBy { it.categoryKey }

    /** Flat list of all stored variants for [profileId] — used to build the Lucene match index. */
    fun getAllVariants(profileId: String): List<PhraseVariant> = getCategories(profileId).values.flatten()

    /**
     * One representative phrase per category (highest usage, most recent as tiebreaker) —
     * passed back to the AI extraction prompt so it can reuse existing keys instead of
     * minting near-duplicate ones for the same recurring habit.
     */
    fun getRepresentativePhrasesByCategory(profileId: String): Map<String, String> = getCategories(profileId)
        .mapValues { (_, variants) -> variants.maxWith(compareBy({ it.usageCount }, { it.updatedAt })).phrase }

    fun lastRefreshedAt(profileId: String): Instant? = queries
        .selectRefreshCheckpoint(profileId)
        .executeAsOneOrNull()

    /**
     * Records that a refresh attempt completed successfully for [profileId] — called even when
     * the extraction legitimately returned no habits, so the next attempt's cooldown/
     * message-count gate (see `PhraseHabitRefreshService.attemptRefresh`) is honored instead of
     * re-firing on every subsequent message.
     */
    fun recordRefreshCheckpoint(profileId: String, at: Instant) {
        queries.upsertRefreshCheckpoint(profileId = profileId, lastRefreshedAt = at)
    }

    /**
     * Merges a single `{categoryKey, phrase}` extraction result into the persisted set,
     * applying the LRU-by-recency capacity rules described in the class doc. Call once per
     * habit returned by the AI for a given refresh cycle — each call is its own transaction.
     */
    fun mergeHabit(profileId: String, categoryKey: String, phrase: String) {
        val normalizedKey = categoryKey.trim().lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
        val normalizedPhrase = phrase.trim()
        if (normalizedKey.isBlank() || normalizedPhrase.isBlank()) return

        db.transaction {
            val now = Clock.System.now()
            val categories = getCategories(profileId).toMutableMap()
            val existingVariants = categories[normalizedKey].orEmpty()

            val exactMatch = existingVariants.firstOrNull { it.phrase.equals(normalizedPhrase, ignoreCase = true) }
            if (exactMatch != null) {
                queries.touchVariant(updatedAt = now, id = exactMatch.id)
                return@transaction
            }

            // New variant for this category.
            val newId = UUID.randomUUID().toString()
            queries.insertVariant(
                id = newId,
                profileId = profileId,
                categoryKey = normalizedKey,
                phrase = normalizedPhrase,
                createdAt = now,
                updatedAt = now,
            )

            // Evict the least-recently-touched variant if this category is now over capacity.
            val updatedVariants = existingVariants + PhraseVariant(newId, normalizedKey, normalizedPhrase, 1, now)
            if (updatedVariants.size > MAX_VARIANTS_PER_CATEGORY) {
                val toEvict = updatedVariants.minBy { it.updatedAt }
                queries.deleteById(toEvict.id)
            }

            // Evict the entire least-recently-touched category if the profile is now over its
            // cap. The new category is excluded from the stale-candidate pool before taking
            // the minimum — otherwise a timestamp tie or wall-clock rollback could make it
            // "win" as stalest, and a post-filter would then skip eviction entirely, leaving
            // the profile over MAX_CATEGORIES.
            val categoryCount = getCategories(profileId).keys.size
            if (categoryCount > MAX_CATEGORIES) {
                val recencyByCategory = getCategories(profileId)
                    .filterKeys { it != normalizedKey }
                    .mapValues { (_, variants) -> variants.maxOf { it.updatedAt } }
                val staleCategory = recencyByCategory.minByOrNull { it.value }?.key
                if (staleCategory != null) {
                    getCategories(profileId)[staleCategory]?.forEach { queries.deleteById(it.id) }
                }
            }
        }
    }

    fun clear(profileId: String) {
        queries.deleteByProfileId(profileId)
        queries.deleteRefreshCheckpointByProfileId(profileId)
    }
}
