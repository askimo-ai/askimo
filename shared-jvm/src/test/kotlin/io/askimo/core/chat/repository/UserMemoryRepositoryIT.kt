/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.db.DatabaseManager
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
 * Integration tests for the SQLDelight-backed [UserMemoryRepository] /
 * [DatabaseManager]. No Exposed-based IT test existed to mirror, so this covers every
 * public method directly.
 */
class UserMemoryRepositoryIT {

    @AfterEach
    fun tearDown() {
        repository.clear()
    }

    companion object {
        private lateinit var testBaseScope: AskimoHome.TestBaseScope
        private lateinit var databaseManager: DatabaseManager
        private lateinit var repository: UserMemoryRepository

        @JvmStatic
        @BeforeAll
        fun setUpClass(@TempDir tempDir: Path) {
            testBaseScope = AskimoHome.withTestBase(tempDir)
            databaseManager = DatabaseManager.getInMemoryTestInstance(this)
            repository = databaseManager.getUserMemoryRepository()
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            if (::databaseManager.isInitialized) databaseManager.close()
            if (::testBaseScope.isInitialized) testBaseScope.close()
        }
    }

    @Test
    fun `should return null when never written`() {
        assertNull(repository.get())
    }

    @Test
    fun `should create row on first save`() {
        val saved = repository.save("""{"facts":["likes Kotlin"]}""")

        assertEquals("""{"facts":["likes Kotlin"]}""", saved.memoryJson)
        assertNotNull(saved.createdAt)
        assertNotNull(saved.lastUpdated)

        val retrieved = repository.get()
        assertNotNull(retrieved)
        assertEquals(saved.memoryJson, retrieved.memoryJson)
    }

    @Test
    fun `should update row on subsequent save and preserve createdAt`() {
        val first = repository.save("""{"v":1}""")
        Thread.sleep(10)
        val second = repository.save("""{"v":2}""")

        assertEquals(first.createdAt, second.createdAt)
        assertTrue(second.lastUpdated >= first.lastUpdated)

        val retrieved = repository.get()
        assertNotNull(retrieved)
        assertEquals("""{"v":2}""", retrieved.memoryJson)
        assertEquals(first.createdAt, retrieved.createdAt)
    }

    @Test
    fun `should clear memory and return number of rows deleted`() {
        repository.save("""{"v":1}""")
        assertNotNull(repository.get())

        val deleted = repository.clear()

        assertEquals(1, deleted)
        assertNull(repository.get())
    }

    @Test
    fun `should return zero when clearing with no existing memory`() {
        assertEquals(0, repository.clear())
    }
}
