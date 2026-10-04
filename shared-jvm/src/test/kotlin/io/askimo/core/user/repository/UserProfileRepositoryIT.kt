/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.user.repository

import io.askimo.core.db.DatabaseManager
import io.askimo.core.user.domain.UserProfile
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Integration tests for the SQLDelight-backed [UserProfileRepository] /
 * [DatabaseManager]. No Exposed-based IT test existed to mirror, so this covers every
 * public method directly.
 */
class UserProfileRepositoryIT {

    @AfterEach
    fun tearDown() {
        repository.clearProfile()
    }

    companion object {
        private lateinit var testBaseScope: AskimoHome.TestBaseScope
        private lateinit var databaseManager: DatabaseManager
        private lateinit var repository: UserProfileRepository

        @JvmStatic
        @BeforeAll
        fun setUpClass(@TempDir tempDir: Path) {
            testBaseScope = AskimoHome.withTestBase(tempDir)
            databaseManager = DatabaseManager.getInMemoryTestInstance(this)
            repository = databaseManager.getUserProfileRepository()
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            if (::databaseManager.isInitialized) databaseManager.close()
            if (::testBaseScope.isInitialized) testBaseScope.close()
        }
    }

    @Test
    fun `should create default profile on first get`() {
        val profile = repository.getProfile()

        assertEquals("default", profile.id)
        assertEquals(null, profile.name)
        assertTrue(profile.interests.isEmpty())
        assertTrue(profile.preferences.isEmpty())
    }

    @Test
    fun `should return same default profile on subsequent gets`() {
        val first = repository.getProfile()
        val second = repository.getProfile()

        assertEquals(first.id, second.id)
        assertEquals(first.createdAt, second.createdAt)
    }

    @Test
    fun `should save profile with interests and preferences`() {
        val saved = repository.saveProfile(
            UserProfile(
                id = "ignored-will-be-forced-to-default",
                name = "Ada",
                email = "ada@example.com",
                preferredTitle = "Dr.",
                occupation = "Engineer",
                location = "London",
                timezone = "Europe/London",
                bio = "Pioneer",
                interests = listOf("math", "computing"),
                preferences = mapOf("theme" to "dark"),
            ),
        )

        assertEquals("default", saved.id)
        assertEquals("Ada", saved.name)

        val retrieved = repository.getProfile()
        assertEquals("Ada", retrieved.name)
        assertEquals("ada@example.com", retrieved.email)
        assertEquals("Dr.", retrieved.preferredTitle)
        assertEquals("Engineer", retrieved.occupation)
        assertEquals("London", retrieved.location)
        assertEquals("Europe/London", retrieved.timezone)
        assertEquals("Pioneer", retrieved.bio)
        assertEquals(setOf("math", "computing"), retrieved.interests.toSet())
        assertEquals(mapOf("theme" to "dark"), retrieved.preferences)
    }

    @Test
    fun `should overwrite interests and preferences on re-save`() {
        repository.saveProfile(
            UserProfile(id = "x", name = "A", interests = listOf("old"), preferences = mapOf("k" to "old")),
        )

        repository.saveProfile(
            UserProfile(id = "x", name = "B", interests = listOf("new"), preferences = mapOf("k" to "new")),
        )

        val retrieved = repository.getProfile()
        assertEquals("B", retrieved.name)
        assertEquals(listOf("new"), retrieved.interests)
        assertEquals(mapOf("k" to "new"), retrieved.preferences)
    }

    @Test
    fun `should update specific fields via updateProfile without affecting others`() {
        repository.saveProfile(UserProfile(id = "x", name = "Original", occupation = "Engineer"))

        val updated = repository.updateProfile(mapOf("name" to "Updated"))

        assertEquals("Updated", updated.name)
        assertEquals("Engineer", updated.occupation)
    }

    @Test
    fun `should update interests and preferences via updateProfile`() {
        repository.saveProfile(UserProfile(id = "x", interests = listOf("a")))

        val updated = repository.updateProfile(
            mapOf(
                "interests" to listOf("b", "c"),
                "preferences" to mapOf("x" to "y"),
            ),
        )

        assertEquals(listOf("b", "c"), updated.interests)
        assertEquals(mapOf("x" to "y"), updated.preferences)
    }

    @Test
    fun `should clear profile and recreate default on next get`() {
        repository.saveProfile(UserProfile(id = "x", name = "ToClear"))
        repository.clearProfile()

        val afterClear = repository.getProfile()
        assertEquals(null, afterClear.name)
    }

    @Test
    fun `should return null personalization context when no data`() {
        repository.getProfile() // creates blank default
        assertNull(repository.getPersonalizationContext())
    }

    @Test
    fun `should build personalization context from profile fields`() {
        repository.saveProfile(
            UserProfile(
                id = "x",
                name = "Ada",
                occupation = "Engineer",
                interests = listOf("math"),
            ),
        )

        val context = repository.getPersonalizationContext()
        assertNotNull(context)
        assertTrue(context.contains("Ada"))
        assertTrue(context.contains("Engineer"))
        assertTrue(context.contains("math"))
    }

    @Test
    fun `should get and set a single preference`() {
        repository.getProfile() // ensure default profile row exists (FK requirement)
        assertNull(repository.getPreference("missing"))

        repository.setPreference("theme", "dark")
        assertEquals("dark", repository.getPreference("theme"))

        repository.setPreference("theme", "light")
        assertEquals("light", repository.getPreference("theme"))
    }

    @Test
    fun `should parse ISO-8601 timestamps written by this repository`() {
        val saved = repository.saveProfile(UserProfile(id = "default", name = "ISO User"))

        val profile = repository.getProfile()
        assertEquals("ISO User", profile.name)
        assertEquals(saved.createdAt, profile.createdAt)
        assertEquals(saved.updatedAt, profile.updatedAt)
    }

    @Test
    fun `should read legacy Exposed space-separated timestamp format`() {
        // Simulates a profile row created by the old Exposed repository, whose `datetime()`
        // column type writes SQLite's native space-separated format (no 'T', no 'Z') instead
        // of the ISO-8601 format this SQLDelight repository writes going forward. Tolerated by
        // `TimeUtil.parseLocalDateTime` — no data migration needed.
        databaseManager.db.userProfilesQueries.insertProfile(
            id = "default",
            name = "Legacy User",
            email = null,
            preferredTitle = null,
            occupation = null,
            location = null,
            timezone = null,
            bio = null,
            createdAt = "2026-09-09 12:46:12.696",
            updatedAt = "2026-09-09 12:46:12.696",
        )

        val profile = repository.getProfile()
        assertEquals("Legacy User", profile.name)
    }
}
