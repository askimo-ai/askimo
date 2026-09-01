/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent.repository

import io.askimo.core.db.DatabaseManager
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class WorkspaceRepositoryIT {

    private val createdIds = mutableListOf<String>()

    @AfterEach
    fun tearDown() {
        createdIds.forEach { repository.delete(it) }
        createdIds.clear()
    }

    companion object {
        private lateinit var testBaseScope: AskimoHome.TestBaseScope
        private lateinit var databaseManager: DatabaseManager
        private lateinit var repository: WorkspaceRepository

        @JvmStatic
        @BeforeAll
        fun setUpClass(@TempDir tempDir: Path) {
            testBaseScope = AskimoHome.withTestBase(tempDir)

            databaseManager = DatabaseManager.getInMemoryTestInstance(this)

            repository = databaseManager.getWorkspaceRepository()
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            if (::databaseManager.isInitialized) {
                databaseManager.close()
            }
            if (::testBaseScope.isInitialized) {
                testBaseScope.close()
            }
        }
    }

    private fun track(workspace: io.askimo.core.agent.domain.Workspace): io.askimo.core.agent.domain.Workspace {
        createdIds.add(workspace.id)
        return workspace
    }

    @Test
    fun `should register a new workspace on first use`(@TempDir dir: Path) {
        val folder = dir.resolve("project-a").toFile().apply { mkdirs() }

        val workspace = track(repository.upsertByPath(folder))

        assertNotNull(workspace.id)
        assertEquals("project-a", workspace.name)
        assertEquals(folder.absoluteFile.normalize().path, workspace.path)
        assertFalse(workspace.pinned)
    }

    @Test
    fun `should use provided display name when registering a new workspace`(@TempDir dir: Path) {
        val folder = dir.resolve("project-b").toFile().apply { mkdirs() }

        val workspace = track(repository.upsertByPath(folder, displayName = "Custom Name"))

        assertEquals("Custom Name", workspace.name)
    }

    @Test
    fun `should not create duplicate workspace for the same path`(@TempDir dir: Path) {
        val folder = dir.resolve("project-c").toFile().apply { mkdirs() }

        val first = track(repository.upsertByPath(folder))
        val second = repository.upsertByPath(folder)

        assertEquals(first.id, second.id)
        assertEquals(1, repository.findAll().count { it.id == first.id })
    }

    @Test
    fun `should bump lastUsedAt when upserting an existing workspace`(@TempDir dir: Path) {
        val folder = dir.resolve("project-d").toFile().apply { mkdirs() }
        val first = track(repository.upsertByPath(folder))

        Thread.sleep(5)
        val second = repository.upsertByPath(folder)

        assertTrue(second.lastUsedAt.isAfter(first.createdAt) || second.lastUsedAt == first.createdAt)
        assertEquals(first.id, second.id)
    }

    @Test
    fun `should find workspace by id`(@TempDir dir: Path) {
        val folder = dir.resolve("project-e").toFile().apply { mkdirs() }
        val workspace = track(repository.upsertByPath(folder))

        val found = repository.findById(workspace.id)

        assertNotNull(found)
        assertEquals(workspace.id, found?.id)
    }

    @Test
    fun `should return null when finding workspace by unknown id`() {
        val found = repository.findById("unknown-id")

        assertNull(found)
    }

    @Test
    fun `should find workspace by path`(@TempDir dir: Path) {
        val folder = dir.resolve("project-f").toFile().apply { mkdirs() }
        val workspace = track(repository.upsertByPath(folder))

        val found = repository.findByPath(folder)

        assertNotNull(found)
        assertEquals(workspace.id, found?.id)
    }

    @Test
    fun `should return null when finding workspace by unknown path`(@TempDir dir: Path) {
        val folder = dir.resolve("never-registered").toFile()

        val found = repository.findByPath(folder)

        assertNull(found)
    }

    @Test
    fun `should return all workspaces pinned first then most recently used`(@TempDir dir: Path) {
        val a = track(repository.upsertByPath(dir.resolve("wa").toFile().apply { mkdirs() }))
        Thread.sleep(5)
        val b = track(repository.upsertByPath(dir.resolve("wb").toFile().apply { mkdirs() }))
        Thread.sleep(5)
        val c = track(repository.upsertByPath(dir.resolve("wc").toFile().apply { mkdirs() }))

        // Pin the oldest one so it should sort to the top despite being least recently used.
        repository.setPinned(a.id, true)

        val all = repository.findAll().filter { it.id in setOf(a.id, b.id, c.id) }

        assertEquals(a.id, all.first().id)
        // Remaining ones ordered by lastUsedAt desc: c then b
        assertEquals(listOf(c.id, b.id), all.drop(1).map { it.id })
    }

    @Test
    fun `should return most recently used workspace regardless of pinned status`(@TempDir dir: Path) {
        val a = track(repository.upsertByPath(dir.resolve("mru-a").toFile().apply { mkdirs() }))
        repository.setPinned(a.id, true)

        Thread.sleep(5)
        val b = track(repository.upsertByPath(dir.resolve("mru-b").toFile().apply { mkdirs() }))

        val mostRecent = repository.findMostRecentlyUsed()

        assertEquals(b.id, mostRecent?.id)
    }

    @Test
    fun `should bump lastUsedAt via touch`(@TempDir dir: Path) {
        val workspace = track(repository.upsertByPath(dir.resolve("touch-me").toFile().apply { mkdirs() }))
        val before = repository.findById(workspace.id)!!.lastUsedAt

        Thread.sleep(5)
        repository.touch(workspace.id)

        val after = repository.findById(workspace.id)!!.lastUsedAt
        assertTrue(after.isAfter(before))
    }

    @Test
    fun `should rename a workspace`(@TempDir dir: Path) {
        val workspace = track(repository.upsertByPath(dir.resolve("rename-me").toFile().apply { mkdirs() }))

        val result = repository.rename(workspace.id, "New Name")

        assertTrue(result)
        assertEquals("New Name", repository.findById(workspace.id)?.name)
    }

    @Test
    fun `should not rename workspace to a blank name`(@TempDir dir: Path) {
        val workspace = track(repository.upsertByPath(dir.resolve("blank-rename").toFile().apply { mkdirs() }))
        val originalName = workspace.name

        val result = repository.rename(workspace.id, "   ")

        assertFalse(result)
        assertEquals(originalName, repository.findById(workspace.id)?.name)
    }

    @Test
    fun `should return false when renaming unknown workspace`() {
        val result = repository.rename("unknown-id", "New Name")

        assertFalse(result)
    }

    @Test
    fun `should pin and unpin a workspace`(@TempDir dir: Path) {
        val workspace = track(repository.upsertByPath(dir.resolve("pin-me").toFile().apply { mkdirs() }))

        repository.setPinned(workspace.id, true)
        assertTrue(repository.findById(workspace.id)!!.pinned)

        repository.setPinned(workspace.id, false)
        assertFalse(repository.findById(workspace.id)!!.pinned)
    }

    @Test
    fun `should delete a workspace reference without deleting the folder on disk`(@TempDir dir: Path) {
        val folder = dir.resolve("delete-me").toFile().apply { mkdirs() }
        val workspace = repository.upsertByPath(folder)

        repository.delete(workspace.id)

        assertNull(repository.findById(workspace.id))
        assertTrue(folder.exists())
    }

    @Test
    fun `should treat different casings of same normalized path as same workspace`(@TempDir dir: Path) {
        val folder = dir.resolve("normalize").toFile().apply { mkdirs() }
        val first = track(repository.upsertByPath(folder))

        val second = repository.upsertByPath(File(folder.path + File.separator + "."))

        assertEquals(first.id, second.id)
    }
}
