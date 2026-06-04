/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.ChatDirective
import io.askimo.core.chat.domain.DIRECTIVE_CONTENT_MAX_LENGTH
import io.askimo.core.chat.domain.DIRECTIVE_NAME_MAX_LENGTH
import io.askimo.core.db.DatabaseManager
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class ChatDirectiveRepositoryIT {

    @AfterEach
    fun tearDown() {
        repository.list().forEach { directive ->
            repository.delete(directive.id)
        }
    }

    companion object {
        private lateinit var testBaseScope: AskimoHome.TestBaseScope
        private lateinit var databaseManager: DatabaseManager
        private lateinit var repository: ChatDirectiveRepository

        @JvmStatic
        @BeforeAll
        fun setUpClass(@TempDir tempDir: Path) {
            testBaseScope = AskimoHome.withTestBase(tempDir)

            databaseManager = DatabaseManager.getInMemoryTestInstance(this)

            repository = databaseManager.getChatDirectiveRepository()
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

    @Test
    fun `should save and retrieve a chat directive`() {
        val directive = ChatDirective(
            name = "concise-code",
            content = "Provide concise code examples without verbose explanations.",
            createdAt = Instant.now(),
        )

        val saved = repository.save(directive)

        assertEquals(directive.id, saved.id)
        assertEquals("concise-code", saved.name)
        assertEquals("Provide concise code examples without verbose explanations.", saved.content)
        assertNotNull(saved.createdAt)

        val retrieved = repository.get(saved.id)
        assertNotNull(retrieved)
        assertEquals(directive.id, retrieved.id)
        assertEquals(directive.name, retrieved.name)
        assertEquals(directive.content, retrieved.content)
    }

    @Test
    fun `should return null for non-existent directive`() {
        val result = repository.get("non-existent")

        assertNull(result)
    }

    @Test
    fun `should update existing directive on save with same id`() {
        val original = ChatDirective(
            name = "test-directive",
            content = "Original content",
        )
        val saved = repository.save(original)

        val updated = saved.copy(
            name = "updated-name",
            content = "Updated content",
        )
        repository.save(updated)

        val retrieved = repository.get(saved.id)
        assertNotNull(retrieved)
        assertEquals("updated-name", retrieved.name)
        assertEquals("Updated content", retrieved.content)
    }

    @Test
    fun `should list all directives ordered by name`() {
        repository.save(ChatDirective(name = "zebra", content = "Last alphabetically"))
        repository.save(ChatDirective(name = "alpha", content = "First alphabetically"))
        repository.save(ChatDirective(name = "middle", content = "Middle alphabetically"))

        val directives = repository.list()

        assertEquals(3, directives.size)
        assertEquals("alpha", directives[0].name)
        assertEquals("middle", directives[1].name)
        assertEquals("zebra", directives[2].name)
    }

    @Test
    fun `should update existing directive`() {
        val directive = ChatDirective(name = "test", content = "Original content")
        val saved = repository.save(directive)

        val updated = saved.copy(name = "updated-test", content = "Updated content")
        val result = repository.update(updated)

        assertTrue(result)

        val retrieved = repository.get(saved.id)
        assertNotNull(retrieved)
        assertEquals("updated-test", retrieved.name)
        assertEquals("Updated content", retrieved.content)
    }

    @Test
    fun `should return false when updating non-existent directive`() {
        val directive = ChatDirective(name = "non-existent", content = "Some content")
        val result = repository.update(directive)

        assertFalse(result)
    }

    @Test
    fun `should delete existing directive`() {
        val saved = repository.save(ChatDirective(name = "to-delete", content = "Content"))

        assertTrue(repository.exists(saved.id))

        val deleted = repository.delete(saved.id)

        assertTrue(deleted)
        assertFalse(repository.exists(saved.id))
        assertNull(repository.get(saved.id))
    }

    @Test
    fun `should return false when deleting non-existent directive`() {
        val deleted = repository.delete("non-existent-id")

        assertFalse(deleted)
    }

    @Test
    fun `should check if directive exists`() {
        val directive = ChatDirective(name = "test-exists", content = "Content")

        assertFalse(repository.exists(directive.id))

        val saved = repository.save(directive)

        assertTrue(repository.exists(saved.id))
    }

    @Test
    fun `should get multiple directives by ids`() {
        val saved1 = repository.save(ChatDirective(name = "directive1", content = "Content 1"))
        val saved2 = repository.save(ChatDirective(name = "directive2", content = "Content 2"))
        val saved3 = repository.save(ChatDirective(name = "directive3", content = "Content 3"))

        val directives = repository.getByIds(listOf(saved1.id, saved3.id, "non-existent"))

        assertEquals(2, directives.size)
        assertEquals("directive1", directives[0].name)
        assertEquals("directive3", directives[1].name)
    }

    @Test
    fun `should return empty list when getting directives with empty ids list`() {
        repository.save(ChatDirective(name = "directive1", content = "Content 1"))

        val directives = repository.getByIds(emptyList())

        assertTrue(directives.isEmpty())
    }

    @Test
    fun `should get multiple directives by names`() {
        repository.save(ChatDirective(name = "directive1", content = "Content 1"))
        repository.save(ChatDirective(name = "directive2", content = "Content 2"))
        repository.save(ChatDirective(name = "directive3", content = "Content 3"))

        val directives = repository.getByNames(listOf("directive1", "directive3", "non-existent"))

        assertEquals(2, directives.size)
        assertEquals("directive1", directives[0].name)
        assertEquals("directive3", directives[1].name)
    }

    @Test
    fun `should return empty list when getting directives with empty names list`() {
        repository.save(ChatDirective(name = "directive1", content = "Content 1"))

        val directives = repository.getByNames(emptyList())

        assertTrue(directives.isEmpty())
    }

    @Test
    fun `should handle special characters in directive content`() {
        val specialContent = """
            Special characters: !@#$%^&*()
            Unicode: émoji 🎉
            Lists:
                - Item 1
                - Item 2
            Code: `inline` and ```block```
        """.trimIndent()

        val directive = ChatDirective(name = "special-chars", content = specialContent)
        val saved = repository.save(directive)

        val retrieved = repository.get(saved.id)
        assertNotNull(retrieved)
        assertEquals(specialContent, retrieved.content)
    }

    @Test
    fun `should enforce maximum name length`() {
        val longName = "a".repeat(DIRECTIVE_NAME_MAX_LENGTH + 1)
        val directive = ChatDirective(name = longName, content = "Content")

        val exception = assertThrows<IllegalArgumentException> {
            repository.save(directive)
        }

        assertTrue(exception.message!!.contains("cannot exceed ${DIRECTIVE_NAME_MAX_LENGTH} characters"))
    }

    @Test
    fun `should enforce maximum content length`() {
        val longContent = "a".repeat(DIRECTIVE_CONTENT_MAX_LENGTH + 1)
        val directive = ChatDirective(name = "test", content = longContent)

        val exception = assertThrows<IllegalArgumentException> {
            repository.save(directive)
        }

        assertTrue(exception.message!!.contains("cannot exceed ${DIRECTIVE_CONTENT_MAX_LENGTH} characters"))
    }

    @Test
    fun `should allow maximum length name`() {
        val maxName = "a".repeat(DIRECTIVE_NAME_MAX_LENGTH)
        val directive = ChatDirective(name = maxName, content = "Content")

        Assertions.assertDoesNotThrow {
            val saved = repository.save(directive)

            val retrieved = repository.get(saved.id)
            assertNotNull(retrieved)
            assertEquals(maxName, retrieved.name)
        }
    }

    @Test
    fun `should allow maximum length content`() {
        val maxContent = "a".repeat(DIRECTIVE_CONTENT_MAX_LENGTH)
        val directive = ChatDirective(name = "test", content = maxContent)

        Assertions.assertDoesNotThrow {
            val saved = repository.save(directive)

            val retrieved = repository.get(saved.id)
            assertNotNull(retrieved)
            assertEquals(maxContent, retrieved.content)
        }
    }

    @Test
    fun `should reject oversized content from server upsert`() {
        val oversized = ChatDirective(
            name = "from-server",
            content = "a".repeat(DIRECTIVE_CONTENT_MAX_LENGTH + 1),
        )

        val exception = assertThrows<IllegalArgumentException> {
            repository.upsertFromServer(listOf(oversized))
        }

        assertTrue(exception.message!!.contains("cannot exceed ${DIRECTIVE_CONTENT_MAX_LENGTH} characters"))
        assertNull(repository.get(oversized.id))
    }
}
