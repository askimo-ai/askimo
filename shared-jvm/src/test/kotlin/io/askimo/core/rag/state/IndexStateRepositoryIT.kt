/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.state

import io.askimo.core.db.DatabaseManager
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndexStateRepositoryIT {

    companion object {
        private lateinit var testBaseScope: AskimoHome.TestBaseScope
        private lateinit var databaseManager: DatabaseManager
        private lateinit var repository: IndexStateRepository

        @JvmStatic
        @BeforeAll
        fun setUpClass(@TempDir tempDir: Path) {
            testBaseScope = AskimoHome.withTestBase(tempDir)
            databaseManager = DatabaseManager.getInMemoryTestInstance(this)
            repository = databaseManager.getIndexStateRepository()
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            if (::databaseManager.isInitialized) databaseManager.close()
            if (::testBaseScope.isInitialized) testBaseScope.close()
        }
    }

    private val createdContainers = mutableListOf<String>()

    @AfterEach
    fun tearDown() {
        createdContainers.forEach { repository.clearAllStatesForContainer(it) }
        createdContainers.clear()
    }

    private fun newContainerId(): String = "container-${System.nanoTime()}".also { createdContainers.add(it) }

    @Nested
    inner class GetHashesForSourceType {

        @Test
        fun `returns hashes scoped to container, source type and resource`() {
            val containerId = newContainerId()
            repository.batchSaveFileStates(
                containerId = containerId,
                fileHashes = mapOf("a.txt" to "hash-a", "b.txt" to "hash-b"),
                sourceType = "folders",
                resourceId = "res-1",
            )

            val hashes = repository.getHashesForSourceType(containerId, "folders", "res-1")

            assertEquals(mapOf("a.txt" to "hash-a", "b.txt" to "hash-b"), hashes)
        }

        @Test
        fun `returns empty map when nothing matches`() {
            val hashes = repository.getHashesForSourceType(newContainerId(), "folders", "res-none")
            assertTrue(hashes.isEmpty())
        }
    }

    @Nested
    inner class GetHashesForFiles {

        @Test
        fun `returns hashes only for the requested subset of files`() {
            val containerId = newContainerId()
            repository.batchSaveFileStates(
                containerId = containerId,
                fileHashes = mapOf("a.txt" to "hash-a", "b.txt" to "hash-b", "c.txt" to "hash-c"),
                sourceType = "folders",
                resourceId = "res-1",
            )

            val hashes = repository.getHashesForFiles(containerId, "res-1", listOf("a.txt", "c.txt"))

            assertEquals(mapOf("a.txt" to "hash-a", "c.txt" to "hash-c"), hashes)
        }

        @Test
        fun `returns empty map for empty input list`() {
            assertTrue(repository.getHashesForFiles(newContainerId(), "res-1", emptyList()).isEmpty())
        }

        @Test
        fun `chunks requests larger than the IN-list chunk size`() {
            val containerId = newContainerId()
            val fileHashes = (1..250).associate { "file-$it.txt" to "hash-$it" }
            repository.batchSaveFileStates(
                containerId = containerId,
                fileHashes = fileHashes,
                sourceType = "folders",
                resourceId = "res-1",
            )

            val hashes = repository.getHashesForFiles(containerId, "res-1", fileHashes.keys.toList())

            assertEquals(fileHashes, hashes)
        }
    }

    @Nested
    inner class GetPathsForResource {

        @Test
        fun `returns all stored paths for a resource`() {
            val containerId = newContainerId()
            repository.batchSaveFileStates(
                containerId = containerId,
                fileHashes = mapOf("a.txt" to "h1", "b.txt" to "h2"),
                sourceType = "folders",
                resourceId = "res-1",
            )

            val paths = repository.getPathsForResource(containerId, "res-1")

            assertEquals(setOf("a.txt", "b.txt"), paths)
        }

        @Test
        fun `returns empty set when resource has no state`() {
            assertTrue(repository.getPathsForResource(newContainerId(), "res-none").isEmpty())
        }
    }

    @Nested
    inner class GetPathsForProject {

        @Test
        fun `returns all paths for container when no source types given`() {
            val containerId = newContainerId()
            repository.batchSaveFileStates(containerId, mapOf("a.txt" to "h1"), "folders", "res-1")
            repository.batchSaveFileStates(containerId, mapOf("b.txt" to "h2"), "urls", "res-2")

            val paths = repository.getPathsForProject(containerId)

            assertEquals(setOf("a.txt", "b.txt"), paths)
        }

        @Test
        fun `filters by source types when provided`() {
            val containerId = newContainerId()
            repository.batchSaveFileStates(containerId, mapOf("a.txt" to "h1"), "folders", "res-1")
            repository.batchSaveFileStates(containerId, mapOf("b.txt" to "h2"), "urls", "res-2")

            val paths = repository.getPathsForProject(containerId, setOf("folders"))

            assertEquals(setOf("a.txt"), paths)
        }
    }

    @Nested
    inner class UpsertFileHashesBatch {

        @Test
        fun `inserts new entries without touching unrelated files`() {
            val containerId = newContainerId()
            repository.batchSaveFileStates(containerId, mapOf("a.txt" to "h1"), "folders", "res-1")

            repository.upsertFileHashesBatch(containerId, "res-1", "folders", mapOf("b.txt" to "h2"))

            val hashes = repository.getHashesForSourceType(containerId, "folders", "res-1")
            assertEquals(mapOf("a.txt" to "h1", "b.txt" to "h2"), hashes)
        }

        @Test
        fun `updates hash for an existing file path`() {
            val containerId = newContainerId()
            repository.batchSaveFileStates(containerId, mapOf("a.txt" to "h1"), "folders", "res-1")

            repository.upsertFileHashesBatch(containerId, "res-1", "folders", mapOf("a.txt" to "h1-new"))

            assertEquals("h1-new", repository.getHashesForSourceType(containerId, "folders", "res-1")["a.txt"])
        }

        @Test
        fun `no-ops for an empty map`() {
            val containerId = newContainerId()
            repository.upsertFileHashesBatch(containerId, "res-1", "folders", emptyMap())

            assertTrue(repository.getPathsForResource(containerId, "res-1").isEmpty())
        }
    }

    @Nested
    inner class BatchSaveFileStates {

        @Test
        fun `replaces all prior state for the source type and resource`() {
            val containerId = newContainerId()
            repository.batchSaveFileStates(containerId, mapOf("a.txt" to "h1", "b.txt" to "h2"), "folders", "res-1")

            repository.batchSaveFileStates(containerId, mapOf("c.txt" to "h3"), "folders", "res-1")

            val hashes = repository.getHashesForSourceType(containerId, "folders", "res-1")
            assertEquals(mapOf("c.txt" to "h3"), hashes)
        }
    }

    @Nested
    inner class RemoveFilePaths {

        @Test
        fun `removes the specified paths only`() {
            val containerId = newContainerId()
            repository.batchSaveFileStates(containerId, mapOf("a.txt" to "h1", "b.txt" to "h2"), "folders", "res-1")

            repository.removeFilePaths(containerId, "res-1", setOf("a.txt"))

            assertEquals(setOf("b.txt"), repository.getPathsForResource(containerId, "res-1"))
        }

        @Test
        fun `no-ops for an empty set`() {
            val containerId = newContainerId()
            repository.batchSaveFileStates(containerId, mapOf("a.txt" to "h1"), "folders", "res-1")

            repository.removeFilePaths(containerId, "res-1", emptySet())

            assertEquals(setOf("a.txt"), repository.getPathsForResource(containerId, "res-1"))
        }
    }

    @Nested
    inner class ClearResourceState {

        @Test
        fun `clears only the targeted resource`() {
            val containerId = newContainerId()
            repository.batchSaveFileStates(containerId, mapOf("a.txt" to "h1"), "folders", "res-1")
            repository.batchSaveFileStates(containerId, mapOf("b.txt" to "h2"), "folders", "res-2")

            repository.clearResourceState(containerId, "res-1")

            assertTrue(repository.getPathsForResource(containerId, "res-1").isEmpty())
            assertEquals(setOf("b.txt"), repository.getPathsForResource(containerId, "res-2"))
        }
    }

    @Nested
    inner class ClearAllStatesForContainer {

        @Test
        fun `clears every resource and source type under the container`() {
            val containerId = newContainerId()
            repository.batchSaveFileStates(containerId, mapOf("a.txt" to "h1"), "folders", "res-1")
            repository.batchSaveFileStates(containerId, mapOf("b.txt" to "h2"), "urls", "res-2")

            repository.clearAllStatesForContainer(containerId)

            assertTrue(repository.getPathsForProject(containerId).isEmpty())
        }
    }
}
