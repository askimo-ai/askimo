/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.Project
import io.askimo.core.db.DatabaseManager
import io.askimo.test.extensions.AskimoTestHome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@AskimoTestHome
class ResourceSegmentRepositoryTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var db: DatabaseManager
    private lateinit var repo: ResourceSegmentRepository

    private val projectId = "proj-test"

    @BeforeEach
    fun setup() {
        db = DatabaseManager.getInMemoryTestInstance(this)
        repo = db.getResourceSegmentRepository()

        val projectRepo = db.getProjectRepository()
        listOf(projectId, "other-project").forEach { id ->
            projectRepo.createProject(
                Project(
                    id = id,
                    name = id,
                    description = null,
                    knowledgeSources = emptyList(),
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                ),
            )
        }
    }

    @AfterEach
    fun tearDown() {
        db.close()
        DatabaseManager.reset()
    }

    private fun save(filePath: String, vararg segmentIds: String) {
        repo.saveSegmentMappings(projectId, filePath, segmentIds.mapIndexed { i, id -> id to i })
    }

    private fun path(vararg parts: String): String = parts.fold(tempDir.toAbsolutePath().toString()) { acc, p -> "$acc/$p" }

    @Nested
    inner class GetSegmentIdsForFile {

        @Test
        fun `returns stored segment IDs for a known file`() {
            save(path("src", "Foo.kt"), "seg-1", "seg-2")

            val ids = repo.getSegmentIdsForFile(projectId, tempDir.resolve("src/Foo.kt"))
            assertEquals(listOf("seg-1", "seg-2"), ids)
        }

        @Test
        fun `returns empty list for unknown file`() {
            val ids = repo.getSegmentIdsForFile(projectId, tempDir.resolve("Unknown.kt"))
            assertTrue(ids.isEmpty())
        }

        @Test
        fun `does not return segments from a different project`() {
            save(path("Foo.kt"), "seg-x")

            val ids = repo.getSegmentIdsForFile("other-project", tempDir.resolve("Foo.kt"))
            assertTrue(ids.isEmpty())
        }
    }

    @Nested
    inner class RemoveSegmentMappingsForFile {

        @Test
        fun `removes only the target file mappings`() {
            save(path("A.kt"), "seg-a")
            save(path("B.kt"), "seg-b")

            val removed = repo.removeSegmentMappingsForFile(projectId, tempDir.resolve("A.kt"))
            assertEquals(1, removed)

            assertTrue(repo.getSegmentIdsForFile(projectId, tempDir.resolve("A.kt")).isEmpty())
            assertTrue(repo.getSegmentIdsForFile(projectId, tempDir.resolve("B.kt")).isNotEmpty())
        }

        @Test
        fun `returns 0 when file is not indexed`() {
            val removed = repo.removeSegmentMappingsForFile(projectId, tempDir.resolve("Ghost.kt"))
            assertEquals(0, removed)
        }
    }

    @Nested
    inner class GetSegmentIdsForDirectory {

        @Test
        fun `returns all segment IDs under a directory`() {
            save(path("src", "A.kt"), "seg-a1", "seg-a2")
            save(path("src", "B.kt"), "seg-b1")
            save(path("other", "C.kt"), "seg-c1")

            val ids = repo.getSegmentIdsForDirectory(projectId, path("src"))
            assertEquals(setOf("seg-a1", "seg-a2", "seg-b1"), ids.toSet())
        }

        @Test
        fun `returns empty list when directory has no indexed files`() {
            val ids = repo.getSegmentIdsForDirectory(projectId, path("empty"))
            assertTrue(ids.isEmpty())
        }

        @Test
        fun `does not bleed into sibling directory sharing a name prefix`() {
            // "moduleA" and "moduleAExtra" share the prefix "moduleA" — must not bleed
            save(path("moduleA", "Foo.kt"), "seg-a")
            save(path("moduleAExtra", "Bar.kt"), "seg-extra")

            val ids = repo.getSegmentIdsForDirectory(projectId, path("moduleA"))
            assertEquals(listOf("seg-a"), ids)
        }

        @Test
        fun `returns segments from deeply nested subdirectories`() {
            save(path("a", "b", "c", "Deep.kt"), "seg-deep")
            save(path("a", "Top.kt"), "seg-top")
            save(path("z", "Other.kt"), "seg-other")

            val ids = repo.getSegmentIdsForDirectory(projectId, path("a"))
            assertEquals(setOf("seg-deep", "seg-top"), ids.toSet())
        }

        @Test
        fun `does not return segments from a different project`() {
            save(path("src", "Foo.kt"), "seg-1")
            repo.saveSegmentMappings("other-project", path("src", "Foo.kt"), listOf("seg-other" to 0))

            val ids = repo.getSegmentIdsForDirectory(projectId, path("src"))
            assertEquals(listOf("seg-1"), ids)
        }
    }

    @Nested
    inner class RemoveSegmentMappingsForDirectory {

        @Test
        fun `removes all mappings under the directory`() {
            save(path("src", "A.kt"), "seg-a")
            save(path("src", "B.kt"), "seg-b")
            save(path("other", "C.kt"), "seg-c")

            val removed = repo.removeSegmentMappingsForDirectory(projectId, path("src"))
            assertEquals(2, removed)

            assertTrue(repo.getSegmentIdsForDirectory(projectId, path("src")).isEmpty())
            assertTrue(repo.getSegmentIdsForDirectory(projectId, path("other")).isNotEmpty())
        }

        @Test
        fun `returns 0 when directory has no indexed files`() {
            val removed = repo.removeSegmentMappingsForDirectory(projectId, path("empty"))
            assertEquals(0, removed)
        }

        @Test
        fun `does not remove sibling directory with similar name prefix`() {
            save(path("moduleA", "Foo.kt"), "seg-a")
            save(path("moduleAExtra", "Bar.kt"), "seg-extra")

            repo.removeSegmentMappingsForDirectory(projectId, path("moduleA"))

            assertTrue(repo.getSegmentIdsForDirectory(projectId, path("moduleA")).isEmpty())
            assertTrue(repo.getSegmentIdsForDirectory(projectId, path("moduleAExtra")).isNotEmpty())
        }

        @Test
        fun `does not affect other projects`() {
            save(path("src", "Foo.kt"), "seg-1")
            repo.saveSegmentMappings("other-project", path("src", "Foo.kt"), listOf("seg-other" to 0))

            repo.removeSegmentMappingsForDirectory(projectId, path("src"))

            val remaining = repo.getSegmentIdsForDirectory("other-project", path("src"))
            assertEquals(listOf("seg-other"), remaining)
        }
    }
}
