/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.askimo.core.db.sqldelight.generated.AskimoDatabase
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that replaying every versioned migration from scratch (`.sqm` files +
 * [SqlDelightSchemaMigrations] `AfterVersion` callbacks, via [AskimoDatabase.Schema.migrate])
 * produces a byte-for-byte identical schema to [AskimoDatabase.Schema.create] — the schema
 * generated directly from the current `.sq` table definitions.
 *
 * This is the safety net for [DatabaseManager]: on a brand-new install it always migrates from
 * version 0 through every `.sqm` step in order (see [DatabaseManager]'s KDoc), so this path must
 * converge to exactly the schema SQLDelight considers canonical. It also covers a seeded "legacy
 * ad-hoc" database that predates the versioned migration system (the `file_segments` rename),
 * ensuring the guarded [AfterVersion] callbacks correctly normalize it too.
 */
class SchemaMigrationEquivalenceTest {

    private val expectedTables = setOf(
        "user_profiles",
        "user_interests",
        "user_preferences",
        "projects",
        "resource_collections",
        "chat_sessions",
        "chat_messages",
        "chat_message_attachments",
        "conversation_summaries",
        "chat_directives",
        "session_memory",
        "user_memory",
        "file_segments",
        "model_classifications",
        "index_file_state",
        "plan_executions",
        "workspaces",
        "agent_run_history",
        "llm_usage_records",
        "file_attachments",
        "attachment_references",
        "user_phrase_suggestions",
    )

    @Test
    fun `fresh database - migrated schema matches schema created directly from current sq files`() {
        createDriver().use { migratedDriver ->
            migrateFromScratch(migratedDriver)

            createDriver().use { createdDriver ->
                AskimoDatabase.Schema.create(createdDriver)
                assertSchemasMatch(migratedDriver, createdDriver)
            }
        }
    }

    @Test
    fun `legacy ad-hoc database - migration converges to the same canonical schema`() {
        // Seed a "legacy" DB: file_segments predates the container_id rename, with PRAGMA
        // user_version left at 0 — exactly the scenario SqlDelightSchemaMigrations.kt's doc
        // comment describes ("a pre-existing database... is simply replayed through the full
        // list from version 0").
        createDriver().use { migratedDriver ->
            seedLegacyFileSegments(migratedDriver)
            migrateFromScratch(migratedDriver)

            createDriver().use { createdDriver ->
                AskimoDatabase.Schema.create(createdDriver)
                assertSchemasMatch(migratedDriver, createdDriver)
            }
        }
    }

    private fun createDriver(): JdbcSqliteDriver = JdbcSqliteDriver("jdbc:sqlite:file:memdb_${System.nanoTime()}?mode=memory&cache=shared")

    private fun migrateFromScratch(driver: JdbcSqliteDriver) {
        val newVersion = AskimoDatabase.Schema.version
        AskimoDatabase.Schema.migrate(driver, 0, newVersion, *SqlDelightSchemaMigrations.callbacks())
        driver.execute(null, "PRAGMA user_version = $newVersion", 0)
    }

    private fun seedLegacyFileSegments(driver: SqlDriver) {
        driver.execute(null, "DROP TABLE IF EXISTS file_segments", 0)
        driver.execute(
            null,
            """
            CREATE TABLE file_segments (
                project_id TEXT NOT NULL,
                file_path TEXT NOT NULL,
                segment_id TEXT NOT NULL,
                chunk_index INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                PRIMARY KEY (project_id, file_path, segment_id)
            )
            """.trimIndent(),
            0,
        )
    }

    private fun assertSchemasMatch(migratedDriver: SqlDriver, createdDriver: SqlDriver) {
        for (table in expectedTables) {
            val migratedColumns = tableInfo(migratedDriver, table)
            val createdColumns = tableInfo(createdDriver, table)
            assertEquals(createdColumns, migratedColumns, "Column mismatch for table '$table'")

            val migratedIndexes = indexNames(migratedDriver, table)
            val createdIndexes = indexNames(createdDriver, table)
            assertEquals(createdIndexes, migratedIndexes, "Index mismatch for table '$table'")
        }
    }

    private data class ColumnInfo(val name: String, val affinity: String, val notNull: Boolean, val pk: Int)

    /**
     * Maps a SQLite declared type to its type affinity per SQLite's documented rules
     * (https://www.sqlite.org/datatype3.html#determination_of_column_affinity). `BIGINT` and
     * `INTEGER` both resolve to INTEGER affinity; `VARCHAR(n)` and `TEXT` both resolve to TEXT
     * affinity — these are behaviorally identical even though some migration paths spell the
     * declared type differently than the current `.sq` files.
     */
    private fun typeAffinity(declaredType: String): String {
        val type = declaredType.uppercase()
        return when {
            "INT" in type -> "INTEGER"
            "CHAR" in type || "CLOB" in type || "TEXT" in type -> "TEXT"
            "BLOB" in type || type.isEmpty() -> "BLOB"
            "REAL" in type || "FLOA" in type || "DOUB" in type -> "REAL"
            else -> "NUMERIC"
        }
    }

    private fun tableInfo(driver: SqlDriver, table: String): Set<ColumnInfo> {
        val columns = mutableSetOf<ColumnInfo>()
        driver.executeQuery(
            null,
            "PRAGMA table_info($table)",
            { cursor ->
                while (cursor.next().value) {
                    columns.add(
                        ColumnInfo(
                            name = cursor.getString(1)!!,
                            affinity = typeAffinity(cursor.getString(2)!!),
                            notNull = cursor.getLong(3) == 1L,
                            pk = cursor.getLong(5)?.toInt() ?: 0,
                        ),
                    )
                }
                QueryResult.Unit
            },
            0,
        )
        assertTrue(columns.isNotEmpty(), "Table '$table' missing from schema")
        return columns
    }

    private fun indexNames(driver: SqlDriver, table: String): Set<String> {
        val names = mutableSetOf<String>()
        driver.executeQuery(
            null,
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = '$table'",
            { cursor ->
                while (cursor.next().value) {
                    val name = cursor.getString(0)!!
                    // Skip SQLite's auto-generated indexes for inline UNIQUE/PK constraints —
                    // these are not declared explicitly and their internal naming can vary.
                    if (!name.startsWith("sqlite_autoindex_")) names.add(name)
                }
                QueryResult.Unit
            },
            0,
        )
        return names
    }
}
