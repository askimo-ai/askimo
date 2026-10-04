/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.LocalFoldersKnowledgeSourceConfig
import io.askimo.core.chat.domain.ResourceCollection
import io.askimo.core.db.DatabaseManager
import io.askimo.core.rag.state.IndexStatus
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Integration tests for the SQLDelight-backed [ResourceCollectionRepository] /
 * [DatabaseManager]. No Exposed-based IT test existed to mirror, so this covers every
 * public method directly.
 */
class ResourceCollectionRepositoryIT {

    @AfterEach
    fun tearDown() {
        repository.getAllCollections().forEach { repository.deleteCollection(it.id) }
    }

    companion object {
        private lateinit var testBaseScope: AskimoHome.TestBaseScope
        private lateinit var databaseManager: DatabaseManager
        private lateinit var repository: ResourceCollectionRepository

        @JvmStatic
        @BeforeAll
        fun setUpClass(@TempDir tempDir: Path) {
            testBaseScope = AskimoHome.withTestBase(tempDir)
            databaseManager = DatabaseManager.getInMemoryTestInstance(this)
            repository = databaseManager.getResourceCollectionRepository()
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            if (::databaseManager.isInitialized) databaseManager.close()
            if (::testBaseScope.isInitialized) testBaseScope.close()
        }
    }

    @Test
    fun `should create and retrieve a collection`() {
        val collection = repository.createCollection(
            ResourceCollection(id = "", name = "Tech Docs", description = "Technical documentation"),
        )

        assertTrue(collection.id.isNotBlank())

        val retrieved = repository.getCollection(collection.id)
        assertNotNull(retrieved)
        assertEquals("Tech Docs", retrieved.name)
        assertEquals("Technical documentation", retrieved.description)
        assertEquals(IndexStatus.NOT_STARTED, retrieved.indexStatus)
        assertFalse(retrieved.isSystemCollection)
    }

    @Test
    fun `should return null for non-existent collection`() {
        assertNull(repository.getCollection("non-existent"))
    }

    @Test
    fun `should get multiple collections by ids`() {
        val c1 = repository.createCollection(ResourceCollection(id = "", name = "A"))
        val c2 = repository.createCollection(ResourceCollection(id = "", name = "B"))
        repository.createCollection(ResourceCollection(id = "", name = "C"))

        val result = repository.getCollectionsByIds(listOf(c1.id, c2.id, "non-existent"))

        assertEquals(2, result.size)
        assertTrue(result.any { it.id == c1.id })
        assertTrue(result.any { it.id == c2.id })
    }

    @Test
    fun `should return empty list for empty ids`() {
        assertTrue(repository.getCollectionsByIds(emptyList()).isEmpty())
    }

    @Test
    fun `should get all collections and count them`() {
        repository.createCollection(ResourceCollection(id = "", name = "A"))
        repository.createCollection(ResourceCollection(id = "", name = "B"))

        assertEquals(2, repository.getAllCollections().size)
        assertEquals(2, repository.countAll())
    }

    @Test
    fun `should create collection with knowledge sources and preserve them`() {
        val sources = listOf(
            LocalFoldersKnowledgeSourceConfig(resourceIdentifier = "/path/1"),
            LocalFoldersKnowledgeSourceConfig(resourceIdentifier = "/path/2"),
        )
        val collection = repository.createCollection(
            ResourceCollection(id = "", name = "With Sources", knowledgeSources = sources),
        )

        val retrieved = repository.getCollection(collection.id)
        assertEquals(sources, retrieved!!.knowledgeSources)
    }

    @Test
    fun `should page collections by modified desc by default`() {
        val now = Clock.System.now()
        val c1 = repository.createCollection(ResourceCollection(id = "", name = "First", updatedAt = now.minus(2.hours)))
        val c2 = repository.createCollection(ResourceCollection(id = "", name = "Second", updatedAt = now.minus(1.hours)))
        val c3 = repository.createCollection(ResourceCollection(id = "", name = "Third", updatedAt = now))

        val page = repository.getCollectionsPaged(page = 1, pageSize = 10)

        assertEquals(3, page.totalItems)
        assertEquals(c3.id, page.items[0].id)
        assertEquals(c2.id, page.items[1].id)
        assertEquals(c1.id, page.items[2].id)
    }

    @Test
    fun `should page collections by created asc`() {
        val now = Clock.System.now()
        val c1 = repository.createCollection(ResourceCollection(id = "", name = "First", createdAt = now.minus(2.hours)))
        val c2 = repository.createCollection(ResourceCollection(id = "", name = "Second", createdAt = now.minus(1.hours)))

        val page = repository.getCollectionsPaged(
            sortColumn = CollectionSortColumn.CREATED,
            sortDirection = CollectionSortDirection.ASC,
        )

        assertEquals(c1.id, page.items[0].id)
        assertEquals(c2.id, page.items[1].id)
    }

    @Test
    fun `should search collections by name with pagination`() {
        repository.createCollection(ResourceCollection(id = "", name = "Alpha Docs"))
        repository.createCollection(ResourceCollection(id = "", name = "Beta Docs"))
        repository.createCollection(ResourceCollection(id = "", name = "Something Else"))

        val page = repository.searchCollectionsPaged("Docs")

        assertEquals(2, page.totalItems)
        assertTrue(page.items.all { it.name.contains("Docs") })
    }

    @Test
    fun `should update collection metadata without resetting index status when sources unchanged`() {
        val sources = listOf(LocalFoldersKnowledgeSourceConfig(resourceIdentifier = "/path/1"))
        val collection = repository.createCollection(
            ResourceCollection(id = "", name = "Original", knowledgeSources = sources),
        )
        repository.updateIndexStatus(collection.id, IndexStatus.READY)

        val updated = repository.updateCollection(
            collectionId = collection.id,
            name = "Renamed",
            description = "New description",
            knowledgeSources = sources,
        )

        assertTrue(updated)
        val retrieved = repository.getCollection(collection.id)!!
        assertEquals("Renamed", retrieved.name)
        assertEquals("New description", retrieved.description)
        assertEquals(IndexStatus.READY, retrieved.indexStatus)
    }

    @Test
    fun `should reset index status when knowledge sources change`() {
        val collection = repository.createCollection(
            ResourceCollection(id = "", name = "Original", knowledgeSources = listOf(LocalFoldersKnowledgeSourceConfig(resourceIdentifier = "/path/1"))),
        )
        repository.updateIndexStatus(collection.id, IndexStatus.READY)
        assertEquals(IndexStatus.READY, repository.getCollection(collection.id)!!.indexStatus)

        val updated = repository.updateCollection(
            collectionId = collection.id,
            name = "Original",
            knowledgeSources = listOf(LocalFoldersKnowledgeSourceConfig(resourceIdentifier = "/path/2")),
        )

        assertTrue(updated)
        val retrieved = repository.getCollection(collection.id)!!
        assertEquals(IndexStatus.NOT_STARTED, retrieved.indexStatus)
        assertNull(retrieved.lastIndexedAt)
        assertNull(retrieved.indexError)
    }

    @Test
    fun `should return false updating non-existent collection`() {
        assertFalse(
            repository.updateCollection(
                collectionId = "non-existent",
                name = "x",
                knowledgeSources = emptyList(),
            ),
        )
    }

    @Test
    fun `should update index status to READY and set lastIndexedAt`() {
        val collection = repository.createCollection(ResourceCollection(id = "", name = "Test"))

        val updated = repository.updateIndexStatus(collection.id, IndexStatus.READY)

        assertTrue(updated)
        val retrieved = repository.getCollection(collection.id)!!
        assertEquals(IndexStatus.READY, retrieved.indexStatus)
        assertNotNull(retrieved.lastIndexedAt)
        assertNull(retrieved.indexError)
    }

    @Test
    fun `should update index status to FAILED with error message`() {
        val collection = repository.createCollection(ResourceCollection(id = "", name = "Test"))

        repository.updateIndexStatus(collection.id, IndexStatus.FAILED, "boom")

        val retrieved = repository.getCollection(collection.id)!!
        assertEquals(IndexStatus.FAILED, retrieved.indexStatus)
        assertEquals("boom", retrieved.indexError)
    }

    @Test
    fun `should update index status to other states clearing error`() {
        val collection = repository.createCollection(ResourceCollection(id = "", name = "Test"))
        repository.updateIndexStatus(collection.id, IndexStatus.FAILED, "boom")

        repository.updateIndexStatus(collection.id, IndexStatus.QUEUED)

        val retrieved = repository.getCollection(collection.id)!!
        assertEquals(IndexStatus.QUEUED, retrieved.indexStatus)
        assertNull(retrieved.indexError)
    }

    @Test
    fun `should mark interrupted indexing as failed`() {
        val queued = repository.createCollection(ResourceCollection(id = "", name = "Queued"))
        val indexing = repository.createCollection(ResourceCollection(id = "", name = "Indexing"))
        val ready = repository.createCollection(ResourceCollection(id = "", name = "Ready"))

        repository.updateIndexStatus(queued.id, IndexStatus.QUEUED)
        repository.updateIndexStatus(indexing.id, IndexStatus.INDEXING)
        repository.updateIndexStatus(ready.id, IndexStatus.READY)

        val interruptedIds = repository.markInterruptedIndexingAsFailed("interrupted by restart")

        assertEquals(2, interruptedIds.size)
        assertTrue(interruptedIds.containsAll(listOf(queued.id, indexing.id)))
        assertEquals(IndexStatus.FAILED, repository.getCollection(queued.id)!!.indexStatus)
        assertEquals(IndexStatus.FAILED, repository.getCollection(indexing.id)!!.indexStatus)
        assertEquals(IndexStatus.READY, repository.getCollection(ready.id)!!.indexStatus)
    }

    @Test
    fun `should return empty list when nothing interrupted`() {
        repository.createCollection(ResourceCollection(id = "", name = "Ready"))
        val interruptedIds = repository.markInterruptedIndexingAsFailed("n/a")
        assertTrue(interruptedIds.isEmpty())
    }

    @Test
    fun `should delete collection and not affect others`() {
        val c1 = repository.createCollection(ResourceCollection(id = "", name = "A"))
        val c2 = repository.createCollection(ResourceCollection(id = "", name = "B"))

        val deleted = repository.deleteCollection(c1.id)

        assertTrue(deleted)
        assertNull(repository.getCollection(c1.id))
        assertNotNull(repository.getCollection(c2.id))
    }

    @Test
    fun `should return false deleting non-existent collection`() {
        assertFalse(repository.deleteCollection("non-existent"))
    }

    @Test
    fun `should get unsynced collections and mark synced`() {
        val collection = repository.createCollection(ResourceCollection(id = "", name = "Unsynced"))

        val unsynced = repository.getUnsyncedCollections()
        assertTrue(unsynced.any { it.id == collection.id })

        val marked = repository.markSynced(collection.id)
        assertTrue(marked)

        val stillUnsynced = repository.getUnsyncedCollections()
        assertFalse(stillUnsynced.any { it.id == collection.id })
    }

    @Test
    fun `should upsert new collection from server`() {
        val fromServer = ResourceCollection(id = "", name = "Server Collection")

        repository.upsertFromServer(listOf(fromServer))

        // id was blank so it was not generated by upsertFromServer; verify via name search instead
        val found = repository.getAllCollections().find { it.name == "Server Collection" }
        assertNotNull(found)
    }

    @Test
    fun `should overwrite local collection when server version is newer`() {
        val saved = repository.createCollection(ResourceCollection(id = "", name = "Local"))
        Thread.sleep(5)
        val newer = saved.copy(name = "Server Wins", updatedAt = Clock.System.now())

        repository.upsertFromServer(listOf(newer))

        val retrieved = repository.getCollection(saved.id)
        assertNotNull(retrieved)
        assertEquals("Server Wins", retrieved.name)
    }

    @Test
    fun `should skip upsert when local version is same age or newer`() {
        val saved = repository.createCollection(ResourceCollection(id = "", name = "Local"))
        val older = saved.copy(name = "Should Not Apply", updatedAt = saved.updatedAt.minus(5.minutes))

        repository.upsertFromServer(listOf(older))

        val retrieved = repository.getCollection(saved.id)
        assertEquals("Local", retrieved!!.name)
    }

    @Test
    fun `should no-op upsert with empty list`() {
        repository.upsertFromServer(emptyList())
        assertTrue(repository.getAllCollections().isEmpty())
    }
}
