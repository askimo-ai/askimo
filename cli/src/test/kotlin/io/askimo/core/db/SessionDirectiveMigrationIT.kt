/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.db

import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class SessionDirectiveMigrationIT {

    @TempDir
    lateinit var tempDir: Path

    private var testBaseScope: AskimoHome.TestBaseScope? = null

    @AfterEach
    fun tearDown() {
        DatabaseManager.reset()
        testBaseScope?.close()
    }

    @Test
    fun `migrates a legacy directive once and does not reactivate it after disabling`() {
        testBaseScope = AskimoHome.withTestBase(tempDir)
        Files.createDirectories(AskimoHome.base())
        val databasePath = AskimoHome.base().resolve("askimo.db")

        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE chat_directives (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        content TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE chat_sessions (
                        id TEXT PRIMARY KEY,
                        title TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        directive_id TEXT,
                        folder_id TEXT,
                        is_starred INTEGER DEFAULT 0,
                        synced_at TEXT
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    "INSERT INTO chat_directives (id, name, content, created_at) " +
                        "VALUES ('directive-1', 'Legacy', 'Legacy content', '2026-01-01T00:00:00Z')",
                )
                statement.executeUpdate(
                    "INSERT INTO chat_sessions (id, title, created_at, updated_at, directive_id) " +
                        "VALUES ('session-1', 'Legacy session', '2026-01-01T00:00:00Z', " +
                        "'2026-01-01T00:00:00Z', 'directive-1')",
                )
            }
        }

        val databaseManager = DatabaseManager.getInstance()
        val repository = databaseManager.getChatSessionRepository()
        assertEquals(setOf("directive-1"), repository.getActiveDirectiveIds("session-1"))

        assertTrue(repository.setSessionDirectiveActive("session-1", "directive-1", false))
        assertTrue(repository.getActiveDirectiveIds("session-1").isEmpty())

        DatabaseManager.reset()
        val reopenedRepository = DatabaseManager.getInstance().getChatSessionRepository()
        assertTrue(reopenedRepository.getActiveDirectiveIds("session-1").isEmpty())
    }
}
