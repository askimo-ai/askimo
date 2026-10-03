/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.db

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * Companion to the `.sqm` files under `shared/src/main/sqldelight/migrations`.
 *
 * Most `.sqm` files are static SQL ported verbatim from the historical
 * `io.askimo.core.db.SchemaMigrations` list (unchanged there — see that file's doc comment for
 * the append-only rules). A subset of migrations are conditional (guarded `ADD COLUMN`, a
 * guarded `DROP COLUMN`, or a legacy-table rebuild) and cannot be expressed as static SQL —
 * those are ported here as [AfterVersion] callbacks instead, each tied to the exact version
 * boundary it originally occupied in the flat migration list.
 *
 * NEVER renumber an existing [AfterVersion] anchor or edit a `.sqm` file's historical content —
 * same append-only rule as `SchemaMigrations.all`. Add new callbacks only for new `.sqm` files
 * added after the highest existing version.
 *
 * Verified against `io.askimo.core.db.SchemaMigrations` by `SchemaMigrationEquivalenceTest`,
 * which must pass before this is trusted as a drop-in replacement for the JDBC-loop version.
 */
object SqlDelightSchemaMigrations {

    fun callbacks(): Array<AfterVersion> = arrayOf(
        AfterVersion(4) { d -> addColumnIfMissing(d, "projects", "synced_at", "TEXT") },
        AfterVersion(5) { d -> addColumnIfMissing(d, "projects", "is_starred", "INTEGER DEFAULT 0") },
        AfterVersion(6) { d -> addColumnIfMissing(d, "projects", "space_id", "TEXT") },
        AfterVersion(7) { d -> addColumnIfMissing(d, "projects", "space_name", "TEXT") },
        AfterVersion(8) { d -> addColumnIfMissing(d, "projects", "default_directive_id", "TEXT") },

        AfterVersion(10) { d -> addColumnIfMissing(d, "resource_collections", "index_status", "TEXT NOT NULL DEFAULT 'NOT_STARTED'") },
        AfterVersion(11) { d -> addColumnIfMissing(d, "resource_collections", "last_indexed_at", "TEXT") },
        AfterVersion(12) { d -> addColumnIfMissing(d, "resource_collections", "index_error", "TEXT") },

        AfterVersion(14) { d ->
            addColumnIfMissing(d, "chat_sessions", "project_id", "TEXT REFERENCES projects(id) ON DELETE CASCADE")
        },
        AfterVersion(15) { d -> addColumnIfMissing(d, "chat_sessions", "synced_at", "TEXT") },
        AfterVersion(16) { d -> addColumnIfMissing(d, "chat_sessions", "is_user_renamed", "INTEGER DEFAULT 0") },
        AfterVersion(17) { d ->
            addColumnIfMissing(d, "chat_sessions", "active_resource_collection_ids", "VARCHAR(2000) DEFAULT '[]'")
        },
        AfterVersion(18) { d -> dropColumnIfExists(d, "chat_sessions", "sort_order") },

        AfterVersion(20) { d -> addColumnIfMissing(d, "chat_messages", "is_edited", "INTEGER DEFAULT 0") },
        AfterVersion(21) { d -> addColumnIfMissing(d, "chat_messages", "is_failed", "INTEGER DEFAULT 0") },
        AfterVersion(22) { d -> addColumnIfMissing(d, "chat_messages", "synced_at", "TEXT") },
        AfterVersion(23) { d -> addColumnIfMissing(d, "chat_messages", "input_tokens", "INTEGER") },
        AfterVersion(24) { d -> addColumnIfMissing(d, "chat_messages", "output_tokens", "INTEGER") },
        AfterVersion(25) { d -> addColumnIfMissing(d, "chat_messages", "total_tokens", "INTEGER") },
        AfterVersion(26) { d -> addColumnIfMissing(d, "chat_messages", "duration_ms", "INTEGER") },
        AfterVersion(27) { d -> addColumnIfMissing(d, "chat_messages", "is_bookmarked", "INTEGER DEFAULT 0") },
        AfterVersion(28) { d -> addColumnIfMissing(d, "chat_messages", "content_json", "TEXT") },
        AfterVersion(29) { d ->
            addColumnIfMissing(d, "chat_messages", "used_resource_collection_ids", "VARCHAR(2000) DEFAULT '[]'")
        },

        AfterVersion(35) { d ->
            addColumnIfMissing(d, "chat_directives", "updated_at", "TEXT NOT NULL DEFAULT '1970-01-01T00:00:00'")
        },
        AfterVersion(36) { d -> addColumnIfMissing(d, "chat_directives", "deleted_at", "TEXT") },
        AfterVersion(37) { d -> addColumnIfMissing(d, "chat_directives", "synced_at", "TEXT") },
        AfterVersion(38) { d -> addColumnIfMissing(d, "chat_directives", "scope", "TEXT NOT NULL DEFAULT 'PERSONAL'") },
        AfterVersion(39) { d -> addColumnIfMissing(d, "chat_directives", "created_by", "TEXT") },

        AfterVersion(42) { d -> migrateFileSegmentsTable(d) },

        AfterVersion(44) { d -> migrateIndexFileStateTable(d) },

        AfterVersion(46) { d -> addColumnIfMissing(d, "plan_executions", "step_outputs", "TEXT") },
        AfterVersion(47) { d -> addColumnIfMissing(d, "plan_executions", "total_input_tokens", "INTEGER") },
        AfterVersion(48) { d -> addColumnIfMissing(d, "plan_executions", "total_output_tokens", "INTEGER") },
        AfterVersion(49) { d -> addColumnIfMissing(d, "plan_executions", "total_tokens", "INTEGER") },
        AfterVersion(50) { d -> addColumnIfMissing(d, "plan_executions", "total_duration_ms", "INTEGER") },

        AfterVersion(53) { d -> addColumnIfMissing(d, "agent_run_history", "content_json", "TEXT") },
        AfterVersion(54) { d -> addColumnIfMissing(d, "agent_run_history", "agent_id", "TEXT") },
        AfterVersion(55) { d -> addColumnIfMissing(d, "agent_run_history", "is_cancelled", "INTEGER NOT NULL DEFAULT 0") },

        AfterVersion(57) { d ->
            addColumnIfMissing(d, "resource_collections", "index_status", "TEXT NOT NULL DEFAULT 'NOT_STARTED'")
            addColumnIfMissing(d, "resource_collections", "last_indexed_at", "TEXT")
            addColumnIfMissing(d, "resource_collections", "index_error", "TEXT")
        },
        AfterVersion(58) { d -> addColumnIfMissing(d, "chat_message_attachments", "storage_path", "VARCHAR(1024)") },

        AfterVersion(61) { d ->
            dropColumnIfExists(d, "projects", "space_id")
            dropColumnIfExists(d, "projects", "space_name")
        },
    )

    /**
     * Adds [column] to [table] only if it doesn't already exist. Ported 1:1 from
     * `io.askimo.core.db.SchemaMigrations.addColumnIfMissing`, operating on [SqlDriver]
     * instead of `java.sql.Connection`.
     */
    private fun addColumnIfMissing(driver: SqlDriver, table: String, column: String, columnDefinition: String) {
        if (column in tableColumns(driver, table)) return
        driver.execute(null, "ALTER TABLE $table ADD COLUMN $column $columnDefinition", 0)
    }

    private fun dropColumnIfExists(driver: SqlDriver, table: String, column: String) {
        if (column !in tableColumns(driver, table)) return
        runCatching { driver.execute(null, "ALTER TABLE $table DROP COLUMN $column", 0) }
    }

    private fun tableColumns(driver: SqlDriver, table: String): Set<String> {
        val columns = mutableSetOf<String>()
        driver.executeQuery(
            null,
            "PRAGMA table_info($table)",
            { cursor ->
                while (cursor.next().value) {
                    columns.add(cursor.getString(1)!!)
                }
                QueryResult.Unit
            },
            0,
        )
        return columns
    }

    /**
     * `file_segments` used to have `project_id` with a hard FK to `projects(id)`, but this
     * table is shared by Projects AND Resource Collections — the column actually holds
     * either a project id or a resource_collection id. Renames it to `container_id` and
     * drops the now-invalid FK by recreating the table. Ported 1:1 from
     * `io.askimo.core.db.SchemaMigrations.migrateFileSegmentsTable`, fully self-contained
     * (handles both the legacy-rename path and the final CREATE TABLE/index, regardless of
     * execution order relative to any static `.sqm` SQL for this version).
     */
    private fun migrateFileSegmentsTable(driver: SqlDriver) {
        val existingColumns = tableColumns(driver, "file_segments")
        val tableExists = existingColumns.isNotEmpty()
        val hasLegacyColumn = tableExists && "container_id" !in existingColumns

        if (hasLegacyColumn) {
            driver.execute(null, "PRAGMA foreign_keys = OFF", 0)
            driver.execute(null, "ALTER TABLE file_segments RENAME TO file_segments_old", 0)
            driver.execute(
                null,
                """
                CREATE TABLE file_segments (
                    container_id TEXT NOT NULL,
                    file_path TEXT NOT NULL,
                    segment_id TEXT NOT NULL,
                    chunk_index INTEGER NOT NULL,
                    created_at TEXT NOT NULL,
                    PRIMARY KEY (container_id, file_path, segment_id)
                )
                """.trimIndent(),
                0,
            )
            driver.execute(
                null,
                """
                INSERT INTO file_segments (container_id, file_path, segment_id, chunk_index, created_at)
                SELECT project_id, file_path, segment_id, chunk_index, created_at FROM file_segments_old
                """.trimIndent(),
                0,
            )
            driver.execute(null, "DROP TABLE file_segments_old", 0)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        }

        driver.execute(
            null,
            """
            CREATE TABLE IF NOT EXISTS file_segments (
                container_id TEXT NOT NULL,
                file_path TEXT NOT NULL,
                segment_id TEXT NOT NULL,
                chunk_index INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                PRIMARY KEY (container_id, file_path, segment_id)
            )
            """.trimIndent(),
            0,
        )

        driver.execute(null, "DROP INDEX IF EXISTS idx_file_segments_project_file", 0)
        driver.execute(
            null,
            "CREATE INDEX IF NOT EXISTS idx_file_segments_container_file ON file_segments (container_id, file_path)",
            0,
        )
    }

    /**
     * `index_file_state` gained a `resource_id` column and was renamed from `project_id`
     * to `container_id` (shared by Projects and Resource Collections). Since this table
     * is a derived cache rebuilt on the next indexing run, the legacy schema is simply
     * dropped and recreated instead of migrated in place. Ported 1:1 from
     * `io.askimo.core.db.SchemaMigrations.migrateIndexFileStateTable`, fully self-contained.
     */
    private fun migrateIndexFileStateTable(driver: SqlDriver) {
        val existingColumns = tableColumns(driver, "index_file_state")
        val tableExists = existingColumns.isNotEmpty()
        val hasCurrentSchema = "resource_id" in existingColumns && "container_id" in existingColumns

        if (tableExists && !hasCurrentSchema) {
            driver.execute(null, "DROP TABLE IF EXISTS index_file_state", 0)
        }

        driver.execute(
            null,
            """
            CREATE TABLE IF NOT EXISTS index_file_state (
                container_id TEXT NOT NULL,
                resource_id TEXT NOT NULL,
                file_path   TEXT NOT NULL,
                file_hash   TEXT NOT NULL,
                source_type TEXT NOT NULL,
                indexed_at  TEXT NOT NULL,
                PRIMARY KEY (container_id, resource_id, file_path)
            )
            """.trimIndent(),
            0,
        )

        driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_index_file_state_hash ON index_file_state (file_hash)", 0)
        driver.execute(
            null,
            """
            CREATE INDEX IF NOT EXISTS idx_index_file_state_container_resource_source
            ON index_file_state (container_id, resource_id, source_type)
            """.trimIndent(),
            0,
        )
    }
}
