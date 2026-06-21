/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.indexing

import dev.langchain4j.data.document.Metadata
import dev.langchain4j.data.embedding.Embedding
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.output.Response
import dev.langchain4j.store.embedding.EmbeddingStore
import io.askimo.core.chat.domain.Project
import io.askimo.core.chat.repository.ResourceSegmentRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.rag.LuceneIndexer
import io.askimo.test.extensions.AskimoTestHome
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@AskimoTestHome
class HybridIndexerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var db: DatabaseManager
    private lateinit var segmentRepository: ResourceSegmentRepository
    private lateinit var embeddingStore: EmbeddingStore<TextSegment>
    private lateinit var embeddingModel: EmbeddingModel
    private lateinit var indexer: HybridIndexer

    private val projectId = "test-project"

    @BeforeEach
    fun setup() {
        db = DatabaseManager.getInMemoryTestInstance(this)
        segmentRepository = db.getResourceSegmentRepository()

        embeddingStore = mock()
        embeddingModel = mock()

        // Stub: return a fixed 3-dim embedding per segment — no real model needed
        whenever(embeddingModel.embedAll(any())).thenAnswer { invocation ->
            val segments = invocation.getArgument<List<TextSegment>>(0)
            val embeddings = segments.map { Embedding.from(floatArrayOf(0.1f, 0.2f, 0.3f)) }
            Response.from(embeddings)
        }

        LuceneIndexer.removeInstance(projectId)

        db.getProjectRepository().createProject(
            Project(
                id = projectId,
                name = "Test Project",
                description = null,
                knowledgeSources = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ),
        )

        indexer = HybridIndexer(
            embeddingStore = embeddingStore,
            embeddingModel = embeddingModel,
            projectId = projectId,
            segmentRepository = segmentRepository,
        )
    }

    @AfterEach
    fun tearDown() {
        LuceneIndexer.removeInstance(projectId)
        db.close()
        DatabaseManager.reset()
    }

    private fun segment(filePath: Path, chunkIndex: Int = 0, text: String = "content $chunkIndex"): TextSegment {
        val meta = Metadata()
        meta.put("file_path", filePath.toAbsolutePath().toString())
        meta.put("file_name", filePath.fileName.toString())
        meta.put("chunk_index", chunkIndex)
        return TextSegment.from(text, meta)
    }

    private fun file(name: String, content: String = "hello"): Path = tempDir.resolve(name).also { it.writeText(content) }

    private fun dir(name: String): Path = tempDir.resolve(name).also { it.createDirectories() }

    @Nested
    inner class AddAndFlush {

        @Test
        fun `single segment is stored in DB after flush`() = runBlocking<Unit> {
            val f = file("a.txt")
            indexer.addSegmentToBatch(segment(f), f)
            val ok = indexer.flushRemainingSegments()

            assertTrue(ok)
            assertEquals(1, segmentRepository.getSegmentIdsForFile(projectId, f).size)
        }

        @Test
        fun `multiple segments for same file are all stored`() = runBlocking<Unit> {
            val f = file("multi.kt", "line1\nline2\nline3")
            (0..2).forEach { i -> indexer.addSegmentToBatch(segment(f, i, "chunk $i"), f) }
            indexer.flushRemainingSegments()

            assertEquals(3, segmentRepository.getSegmentIdsForFile(projectId, f).size)
        }

        @Test
        fun `segments from different files are stored separately`() = runBlocking<Unit> {
            val f1 = file("f1.kt")
            val f2 = file("f2.kt")
            indexer.addSegmentToBatch(segment(f1), f1)
            indexer.addSegmentToBatch(segment(f2), f2)
            indexer.flushRemainingSegments()

            assertEquals(1, segmentRepository.getSegmentIdsForFile(projectId, f1).size)
            assertEquals(1, segmentRepository.getSegmentIdsForFile(projectId, f2).size)
        }

        @Test
        fun `embedding store receives all embeddings on flush`() = runBlocking<Unit> {
            val f = file("b.txt")
            indexer.addSegmentToBatch(segment(f), f)
            indexer.flushRemainingSegments()

            verify(embeddingStore).addAll(any(), any())
        }

        @Test
        fun `flush on empty batch returns true without calling embedding model`() = runBlocking<Unit> {
            val result = indexer.flushRemainingSegments()

            assertTrue(result)
            verify(embeddingModel, never()).embedAll(any())
        }
    }

    @Nested
    inner class RemoveFile {

        @Test
        fun `removes DB segment mappings after indexing`() = runBlocking<Unit> {
            val f = file("remove-me.txt")
            indexer.addSegmentToBatch(segment(f), f)
            indexer.flushRemainingSegments()

            assertTrue(segmentRepository.getSegmentIdsForFile(projectId, f).isNotEmpty())

            indexer.removeFileFromIndex(f)

            assertTrue(segmentRepository.getSegmentIdsForFile(projectId, f).isEmpty())
        }

        @Test
        fun `calls embeddingStore removeAll with the indexed segment IDs`() = runBlocking<Unit> {
            val f = file("vec-remove.txt")
            indexer.addSegmentToBatch(segment(f), f)
            indexer.flushRemainingSegments()

            indexer.removeFileFromIndex(f)

            verify(embeddingStore).removeAll(any<Collection<String>>())
        }

        @Test
        fun `is a no-op when file has no indexed segments`() = runBlocking<Unit> {
            val f = file("ghost.txt")

            indexer.removeFileFromIndex(f)

            // No embeddings stored → removeAll should never be called
            verify(embeddingStore, never()).removeAll(any<Collection<String>>())
        }

        @Test
        fun `does not affect other files in same project`() = runBlocking<Unit> {
            val f1 = file("keep.kt")
            val f2 = file("delete.kt")
            indexer.addSegmentToBatch(segment(f1), f1)
            indexer.addSegmentToBatch(segment(f2), f2)
            indexer.flushRemainingSegments()

            indexer.removeFileFromIndex(f2)

            assertTrue(segmentRepository.getSegmentIdsForFile(projectId, f1).isNotEmpty(), "f1 must be untouched")
            assertTrue(segmentRepository.getSegmentIdsForFile(projectId, f2).isEmpty(), "f2 must be gone")
        }
    }

    @Nested
    inner class RemoveDirectory {

        @Test
        fun `removes all files under directory from DB`() = runBlocking<Unit> {
            val sub = dir("src")
            val f1 = sub.resolve("A.kt").also { it.writeText("class A") }
            val f2 = sub.resolve("B.kt").also { it.writeText("class B") }
            val outside = file("Root.kt", "root")

            indexer.addSegmentToBatch(segment(f1), f1)
            indexer.addSegmentToBatch(segment(f2), f2)
            indexer.addSegmentToBatch(segment(outside), outside)
            indexer.flushRemainingSegments()

            indexer.removeDirectoryFromIndex(sub)

            assertTrue(segmentRepository.getSegmentIdsForFile(projectId, f1).isEmpty(), "f1 inside dir should be removed")
            assertTrue(segmentRepository.getSegmentIdsForFile(projectId, f2).isEmpty(), "f2 inside dir should be removed")
            assertTrue(segmentRepository.getSegmentIdsForFile(projectId, outside).isNotEmpty(), "outside file must be untouched")
        }

        @Test
        fun `calls embeddingStore removeAll for files inside directory`() = runBlocking<Unit> {
            val sub = dir("pkg")
            val f = sub.resolve("X.kt").also { it.writeText("class X") }
            indexer.addSegmentToBatch(segment(f), f)
            indexer.flushRemainingSegments()

            indexer.removeDirectoryFromIndex(sub)

            verify(embeddingStore).removeAll(any<Collection<String>>())
        }

        @Test
        fun `is a no-op when directory has no indexed files`() = runBlocking {
            val empty = dir("empty")

            indexer.removeDirectoryFromIndex(empty)

            verify(embeddingStore, never()).removeAll(any<Collection<String>>())
        }

        @Test
        fun `handles deeply nested directories`() = runBlocking {
            val deep = tempDir.resolve("a/b/c").also { it.createDirectories() }
            val deepFile = deep.resolve("deep.kt").also { it.writeText("deep") }
            val shallowFile = tempDir.resolve("a").resolve("top.kt").also { it.writeText("top") }

            indexer.addSegmentToBatch(segment(deepFile), deepFile)
            indexer.addSegmentToBatch(segment(shallowFile), shallowFile)
            indexer.flushRemainingSegments()

            // Remove from "a" — should wipe both deep and shallow inside "a"
            indexer.removeDirectoryFromIndex(tempDir.resolve("a"))

            assertTrue(segmentRepository.getSegmentIdsForFile(projectId, deepFile).isEmpty(), "deep file should be removed")
            assertTrue(segmentRepository.getSegmentIdsForFile(projectId, shallowFile).isEmpty(), "shallow file in 'a' should be removed")
        }

        @Test
        fun `does not remove sibling directory with similar name prefix`() = runBlocking<Unit> {
            val moduleA = dir("moduleA")
            val moduleAExtra = dir("moduleAExtra")
            val fA = moduleA.resolve("Foo.kt").also { it.writeText("foo") }
            val fExtra = moduleAExtra.resolve("Bar.kt").also { it.writeText("bar") }

            indexer.addSegmentToBatch(segment(fA), fA)
            indexer.addSegmentToBatch(segment(fExtra), fExtra)
            indexer.flushRemainingSegments()

            indexer.removeDirectoryFromIndex(moduleA)

            assertTrue(segmentRepository.getSegmentIdsForFile(projectId, fA).isEmpty(), "moduleA/Foo.kt should be removed")
            assertTrue(segmentRepository.getSegmentIdsForFile(projectId, fExtra).isNotEmpty(), "moduleAExtra/Bar.kt must be untouched")
        }
    }

    @Nested
    inner class ProjectDeletedDuringIndexing {

        @Test
        fun `flushRemainingSegments returns false when project row deleted mid-indexing`() = runBlocking<Unit> {
            val f = file("doc.txt")
            indexer.addSegmentToBatch(segment(f), f)

            // Simulate the production scenario: project row deleted while indexing is running
            db.getProjectRepository().deleteProject(projectId)

            val result = indexer.flushRemainingSegments()

            assertFalse(result, "flush must return false when the project row has been deleted")
        }

        @Test
        fun `addSegmentToBatch returns false when a full batch flush triggers FK violation`() = runBlocking<Unit> {
            val f = file("batch.txt")

            // Fill batch to default batch size - 1 (49) — no auto-flush yet
            repeat(49) { i -> indexer.addSegmentToBatch(segment(f, i, "chunk $i"), f) }

            // Delete the project row so the FK constraint fires on the 50th add
            db.getProjectRepository().deleteProject(projectId)

            val result = indexer.addSegmentToBatch(segment(f, 49, "chunk 49"), f)

            assertFalse(result, "addSegmentToBatch must return false when the batch flush fails due to FK violation")
        }

        @Test
        fun `pending mappings are cleared after FK failure so a second flush is a no-op`() = runBlocking<Unit> {
            val f = file("retry.txt")
            indexer.addSegmentToBatch(segment(f), f)

            // Trigger FK failure
            db.getProjectRepository().deleteProject(projectId)
            indexer.flushRemainingSegments()

            // Re-create the project row so subsequent DB writes would succeed if retried
            db.getProjectRepository().createProject(
                Project(
                    id = projectId,
                    name = "Recreated",
                    description = null,
                    knowledgeSources = emptyList(),
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                ),
            )

            indexer.flushRemainingSegments()

            assertTrue(
                segmentRepository.getSegmentIdsForFile(projectId, f).isEmpty(),
                "No segments should be in DB — pendingMappings must be cleared on FK failure, not retried",
            )
        }
    }
}
