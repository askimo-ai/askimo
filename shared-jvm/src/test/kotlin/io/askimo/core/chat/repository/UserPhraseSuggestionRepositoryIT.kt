/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.db.DatabaseManager
import io.askimo.core.user.repository.UserProfileRepository
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Integration tests for the SQLDelight-backed [UserPhraseSuggestionRepository] /
 * [DatabaseManager]. Covers category/variant CRUD + LRU eviction (capacity rules described in
 * the class doc) as well as the `phrase_refresh_checkpoint` gate tracked independently of
 * suggestion rows (see `PhraseHabitRefreshService.attemptRefresh`).
 */
class UserPhraseSuggestionRepositoryIT {

    @AfterEach
    fun tearDown() {
        repository.clear(PROFILE_ID)
        repository.clear(OTHER_PROFILE_ID)
    }

    companion object {
        private const val PROFILE_ID = "default"
        private const val OTHER_PROFILE_ID = "other-profile"

        private lateinit var testBaseScope: AskimoHome.TestBaseScope
        private lateinit var databaseManager: DatabaseManager
        private lateinit var repository: UserPhraseSuggestionRepository
        private lateinit var userProfileRepository: UserProfileRepository

        @JvmStatic
        @BeforeAll
        fun setUpClass(@TempDir tempDir: Path) {
            testBaseScope = AskimoHome.withTestBase(tempDir)
            databaseManager = DatabaseManager.getInMemoryTestInstance(this)
            repository = databaseManager.getUserPhraseSuggestionRepository()
            userProfileRepository = databaseManager.getUserProfileRepository()
            // Phrase suggestion rows FK-reference user_profiles(id) — ensure the default
            // profile row exists before any insert.
            userProfileRepository.getProfile()
            // UserProfileRepository forces every save to the single "default" id (single-user
            // app), but UserPhraseSuggestionRepository's own API is generically keyed by
            // profileId — insert a second profile row directly so isolation can still be
            // exercised at this layer, mirroring UserProfileRepositoryIT's legacy-row test.
            insertRawProfile(OTHER_PROFILE_ID)
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            if (::databaseManager.isInitialized) databaseManager.close()
            if (::testBaseScope.isInitialized) testBaseScope.close()
        }

        private fun insertRawProfile(id: String) {
            databaseManager.driver.execute(
                identifier = null,
                sql = """
                    |INSERT INTO user_profiles (id, name, email, preferred_title, occupation, location, timezone, bio, created_at, updated_at)
                    |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimMargin(),
                parameters = 10,
            ) {
                bindString(0, id)
                bindString(1, null)
                bindString(2, null)
                bindString(3, null)
                bindString(4, null)
                bindString(5, null)
                bindString(6, null)
                bindString(7, null)
                bindString(8, "2026-01-01T00:00:00Z")
                bindString(9, "2026-01-01T00:00:00Z")
            }
        }
    }

    // ── getCategories / getAllVariants / getRepresentativePhrasesByCategory ────────────────

    @Test
    fun `should return empty categories when nothing stored`() {
        assertTrue(repository.getCategories(PROFILE_ID).isEmpty())
        assertTrue(repository.getAllVariants(PROFILE_ID).isEmpty())
        assertTrue(repository.getRepresentativePhrasesByCategory(PROFILE_ID).isEmpty())
    }

    @Test
    fun `should merge new habit into its own category`() {
        repository.mergeHabit(PROFILE_ID, "closing_courtesy", "Thanks so much!")

        val categories = repository.getCategories(PROFILE_ID)
        assertEquals(setOf("closing_courtesy"), categories.keys)
        assertEquals(1, categories.getValue("closing_courtesy").size)
        assertEquals("Thanks so much!", categories.getValue("closing_courtesy").single().phrase)
        assertEquals(1L, categories.getValue("closing_courtesy").single().usageCount)
    }

    @Test
    fun `should normalize category key and phrase whitespace`() {
        repository.mergeHabit(PROFILE_ID, "  Closing Courtesy!! ", "  Thanks so much!  ")

        val categories = repository.getCategories(PROFILE_ID)
        assertEquals(setOf("closing_courtesy"), categories.keys)
        assertEquals("Thanks so much!", categories.getValue("closing_courtesy").single().phrase)
    }

    @Test
    fun `should ignore blank category key or phrase`() {
        repository.mergeHabit(PROFILE_ID, "   ", "Thanks!")
        repository.mergeHabit(PROFILE_ID, "closing_courtesy", "   ")

        assertTrue(repository.getCategories(PROFILE_ID).isEmpty())
    }

    @Test
    fun `should touch existing variant instead of duplicating on exact case-insensitive match`() {
        repository.mergeHabit(PROFILE_ID, "closing_courtesy", "Thanks so much!")
        repository.mergeHabit(PROFILE_ID, "closing_courtesy", "thanks so much!")

        val variants = repository.getCategories(PROFILE_ID).getValue("closing_courtesy")
        assertEquals(1, variants.size)
        assertEquals(2L, variants.single().usageCount)
    }

    @Test
    fun `should add a second variant under the same category for a different phrase`() {
        repository.mergeHabit(PROFILE_ID, "closing_courtesy", "Thanks so much!")
        repository.mergeHabit(PROFILE_ID, "closing_courtesy", "Appreciate it!")

        val variants = repository.getCategories(PROFILE_ID).getValue("closing_courtesy")
        assertEquals(2, variants.size)
        assertEquals(setOf("Thanks so much!", "Appreciate it!"), variants.map { it.phrase }.toSet())
    }

    @Test
    fun `should evict least-recently-touched variant when category exceeds capacity`() {
        // Fill a category to its cap, each as a distinct phrase/variant.
        repeat(UserPhraseSuggestionRepository.MAX_VARIANTS_PER_CATEGORY) { i ->
            repository.mergeHabit(PROFILE_ID, "opener", "Hi there, variant $i")
        }
        assertEquals(
            UserPhraseSuggestionRepository.MAX_VARIANTS_PER_CATEGORY,
            repository.getCategories(PROFILE_ID).getValue("opener").size,
        )

        // One more distinct phrase should evict the oldest (variant 0) and keep the cap.
        repository.mergeHabit(PROFILE_ID, "opener", "Hi there, variant overflow")

        val variants = repository.getCategories(PROFILE_ID).getValue("opener")
        assertEquals(UserPhraseSuggestionRepository.MAX_VARIANTS_PER_CATEGORY, variants.size)
        assertTrue(variants.none { it.phrase == "Hi there, variant 0" })
        assertTrue(variants.any { it.phrase == "Hi there, variant overflow" })
    }

    @Test
    fun `should evict entire least-recently-touched category when profile exceeds category cap`() {
        // Fill the profile to its category cap, one variant each, oldest first.
        repeat(UserPhraseSuggestionRepository.MAX_CATEGORIES) { i ->
            repository.mergeHabit(PROFILE_ID, "category_$i", "Phrase for category $i")
        }
        assertEquals(UserPhraseSuggestionRepository.MAX_CATEGORIES, repository.getCategories(PROFILE_ID).keys.size)

        // A brand-new category should evict the whole least-recently-touched category
        // (category_0, the first one inserted) rather than just one variant from it.
        repository.mergeHabit(PROFILE_ID, "new_category", "Brand new phrase")

        val categories = repository.getCategories(PROFILE_ID)
        assertEquals(UserPhraseSuggestionRepository.MAX_CATEGORIES, categories.keys.size)
        assertTrue("category_0" !in categories.keys)
        assertTrue("new_category" in categories.keys)
    }

    @Test
    fun `should return representative phrase per category by highest usage then recency`() {
        repository.mergeHabit(PROFILE_ID, "opener", "Hey!")
        repository.mergeHabit(PROFILE_ID, "opener", "Hello!")
        repository.mergeHabit(PROFILE_ID, "opener", "Hello!") // touched again -> highest usage

        val representative = repository.getRepresentativePhrasesByCategory(PROFILE_ID)
        assertEquals("Hello!", representative.getValue("opener"))
    }

    @Test
    fun `should list all variants across categories flattened`() {
        repository.mergeHabit(PROFILE_ID, "opener", "Hi!")
        repository.mergeHabit(PROFILE_ID, "closer", "Bye!")

        val all = repository.getAllVariants(PROFILE_ID)
        assertEquals(2, all.size)
        assertEquals(setOf("Hi!", "Bye!"), all.map { it.phrase }.toSet())
    }

    // ── lastRefreshedAt / recordRefreshCheckpoint — independent of suggestion rows ──────────

    @Test
    fun `should return null checkpoint when never recorded`() {
        assertNull(repository.lastRefreshedAt(PROFILE_ID))
    }

    @Test
    fun `should record and read back refresh checkpoint`() {
        val now = Clock.System.now()
        repository.recordRefreshCheckpoint(PROFILE_ID, now)

        assertEquals(now, repository.lastRefreshedAt(PROFILE_ID))
    }

    @Test
    fun `should advance checkpoint even when no habits were merged`() {
        // Simulates a successful AI extraction that legitimately found nothing recurring —
        // the gate must still advance so the cooldown/message-count gate isn't bypassed on
        // every subsequent message.
        val attemptTime = Clock.System.now()
        repository.recordRefreshCheckpoint(PROFILE_ID, attemptTime)

        assertEquals(attemptTime, repository.lastRefreshedAt(PROFILE_ID))
        assertTrue(repository.getAllVariants(PROFILE_ID).isEmpty())
    }

    @Test
    fun `should overwrite checkpoint on repeated recordRefreshCheckpoint calls`() {
        val first = Clock.System.now()
        repository.recordRefreshCheckpoint(PROFILE_ID, first)

        val second = first + 30.minutes
        repository.recordRefreshCheckpoint(PROFILE_ID, second)

        assertEquals(second, repository.lastRefreshedAt(PROFILE_ID))
    }

    @Test
    fun `should track checkpoint independently of merged habit rows`() {
        val checkpointTime = Clock.System.now()
        repository.recordRefreshCheckpoint(PROFILE_ID, checkpointTime)
        repository.mergeHabit(PROFILE_ID, "opener", "Hi!")

        // Merging a habit doesn't touch the checkpoint table — only recordRefreshCheckpoint does.
        assertEquals(checkpointTime, repository.lastRefreshedAt(PROFILE_ID))
    }

    // ── clear ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `should clear both suggestion rows and refresh checkpoint`() {
        repository.mergeHabit(PROFILE_ID, "opener", "Hi!")
        repository.recordRefreshCheckpoint(PROFILE_ID, Clock.System.now())

        repository.clear(PROFILE_ID)

        assertTrue(repository.getAllVariants(PROFILE_ID).isEmpty())
        assertNull(repository.lastRefreshedAt(PROFILE_ID))
    }

    // ── profile isolation ───────────────────────────────────────────────────────────────

    @Test
    fun `should keep merged habits isolated per profile`() {
        repository.mergeHabit(PROFILE_ID, "opener", "Hi from default!")
        repository.mergeHabit(OTHER_PROFILE_ID, "opener", "Hi from other!")

        assertEquals(listOf("Hi from default!"), repository.getAllVariants(PROFILE_ID).map { it.phrase })
        assertEquals(listOf("Hi from other!"), repository.getAllVariants(OTHER_PROFILE_ID).map { it.phrase })
    }

    @Test
    fun `should not let one profile's category count trigger eviction for another profile`() {
        // Fill PROFILE_ID to its category cap.
        repeat(UserPhraseSuggestionRepository.MAX_CATEGORIES) { i ->
            repository.mergeHabit(PROFILE_ID, "category_$i", "Phrase $i")
        }
        // OTHER_PROFILE_ID starts completely fresh — adding a category here must not evict
        // anything from PROFILE_ID, and must not itself be capped by PROFILE_ID's count.
        repository.mergeHabit(OTHER_PROFILE_ID, "greeting", "Hello from other!")

        assertEquals(UserPhraseSuggestionRepository.MAX_CATEGORIES, repository.getCategories(PROFILE_ID).keys.size)
        assertEquals(setOf("greeting"), repository.getCategories(OTHER_PROFILE_ID).keys)
    }

    @Test
    fun `should keep refresh checkpoints isolated per profile`() {
        val defaultCheckpoint = Clock.System.now()
        val otherCheckpoint = defaultCheckpoint + 30.minutes
        repository.recordRefreshCheckpoint(PROFILE_ID, defaultCheckpoint)
        repository.recordRefreshCheckpoint(OTHER_PROFILE_ID, otherCheckpoint)

        assertEquals(defaultCheckpoint, repository.lastRefreshedAt(PROFILE_ID))
        assertEquals(otherCheckpoint, repository.lastRefreshedAt(OTHER_PROFILE_ID))
    }

    @Test
    fun `should clear only the targeted profile's rows and checkpoint`() {
        repository.mergeHabit(PROFILE_ID, "opener", "Hi!")
        repository.mergeHabit(OTHER_PROFILE_ID, "opener", "Hey!")
        repository.recordRefreshCheckpoint(PROFILE_ID, Clock.System.now())
        repository.recordRefreshCheckpoint(OTHER_PROFILE_ID, Clock.System.now())

        repository.clear(PROFILE_ID)

        assertTrue(repository.getAllVariants(PROFILE_ID).isEmpty())
        assertNull(repository.lastRefreshedAt(PROFILE_ID))
        assertEquals(1, repository.getAllVariants(OTHER_PROFILE_ID).size)
        assertTrue(repository.lastRefreshedAt(OTHER_PROFILE_ID) != null)
    }
}
