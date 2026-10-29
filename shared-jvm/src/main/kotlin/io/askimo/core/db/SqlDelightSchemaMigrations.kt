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
 * those are ported here as [AfterVersion] callbacks instead.
 *
 * IMPORTANT: an `AfterVersion(N)` callback must be anchored at the SAME number `N` as the
 * `.sqm` file it's meant to accompany (e.g. `65.sqm` pairs with `AfterVersion(65)`), NOT
 * `N-1`. SQLDelight's generated `migrate()` only fires a callback when
 * `callback.afterVersion >= oldVersion`, and bundles it with the static SQL block for that
 * exact version number. Anchoring one version too low silently drops the callback for any
 * user whose on-disk `user_version` already sits exactly at that boundary. Several
 * pre-existing anchors below predate this fix and use the old (technically off-by-one)
 * `N+1.sqm ↔ AfterVersion(N)` convention — left as-is per the append-only rule, since
 * they're all harmless redundant re-adds of columns created by earlier, correctly-anchored
 * migrations. Do not use them as a template for new entries.
 *
 * NEVER renumber an existing [AfterVersion] anchor that has already shipped to users, or
 * edit a `.sqm` file's historical content — same append-only rule as `SchemaMigrations.all`.
 * Add new callbacks only for new `.sqm` files added after the highest existing version.
 *
 * Verified against `io.askimo.core.db.SchemaMigrations` by `SchemaMigrationEquivalenceTest`,
 * which must pass before this is trusted as a drop-in replacement for the JDBC-loop version.
 *
 * SELF-HEALING: every action below is idempotent (guarded by `tableColumns`/existence checks),
 * so in addition to being wired up as version-gated [AfterVersion] callbacks for the normal
 * `Schema.migrate()` path, they're also exposed via [repairAll] — which re-applies ALL of them
 * unconditionally, regardless of the recorded `PRAGMA user_version`. [DatabaseManager] calls
 * [repairAll] on every startup (not just when a migration actually runs) so that a database
 * whose `user_version` has drifted ahead of its real column set (e.g. a `.db` file copied/synced
 * from another machine without its matching `-wal` file, an interrupted prior run, or any other
 * way the counter and the actual schema fell out of sync) still gets missing columns/tables
 * repaired instead of throwing "no such column" at query time.
 */
object SqlDelightSchemaMigrations {

    /**
     * Single source of truth for every conditional migration action, each paired with the
     * schema version it's anchored to. [callbacks] wraps these as version-gated [AfterVersion]
     * callbacks for `Schema.migrate()`; [repairAll] runs all of them unconditionally as a
     * self-healing pass. Add new entries only for new `.sqm` files added after the highest
     * existing version — same append-only rule as before.
     */
    private val migrationActions: List<Pair<Long, (SqlDriver) -> Unit>> = listOf(
        4L to { d -> addColumnIfMissing(d, "projects", "synced_at", "TEXT") },
        5L to { d -> addColumnIfMissing(d, "projects", "is_starred", "INTEGER DEFAULT 0") },
        6L to { d -> addColumnIfMissing(d, "projects", "space_id", "TEXT") },
        7L to { d -> addColumnIfMissing(d, "projects", "space_name", "TEXT") },
        8L to { d -> addColumnIfMissing(d, "projects", "default_directive_id", "TEXT") },

        10L to { d -> addColumnIfMissing(d, "resource_collections", "index_status", "TEXT NOT NULL DEFAULT 'NOT_STARTED'") },
        11L to { d -> addColumnIfMissing(d, "resource_collections", "last_indexed_at", "TEXT") },
        12L to { d -> addColumnIfMissing(d, "resource_collections", "index_error", "TEXT") },

        14L to { d ->
            addColumnIfMissing(d, "chat_sessions", "project_id", "TEXT REFERENCES projects(id) ON DELETE CASCADE")
        },
        15L to { d -> addColumnIfMissing(d, "chat_sessions", "synced_at", "TEXT") },
        16L to { d -> addColumnIfMissing(d, "chat_sessions", "is_user_renamed", "INTEGER DEFAULT 0") },
        17L to { d ->
            addColumnIfMissing(d, "chat_sessions", "active_resource_collection_ids", "VARCHAR(2000) DEFAULT '[]'")
        },
        18L to { d -> dropColumnIfExists(d, "chat_sessions", "sort_order") },

        20L to { d -> addColumnIfMissing(d, "chat_messages", "is_edited", "INTEGER DEFAULT 0") },
        21L to { d -> addColumnIfMissing(d, "chat_messages", "is_failed", "INTEGER DEFAULT 0") },
        22L to { d -> addColumnIfMissing(d, "chat_messages", "synced_at", "TEXT") },
        23L to { d -> addColumnIfMissing(d, "chat_messages", "input_tokens", "INTEGER") },
        24L to { d -> addColumnIfMissing(d, "chat_messages", "output_tokens", "INTEGER") },
        25L to { d -> addColumnIfMissing(d, "chat_messages", "total_tokens", "INTEGER") },
        26L to { d -> addColumnIfMissing(d, "chat_messages", "duration_ms", "INTEGER") },
        27L to { d -> addColumnIfMissing(d, "chat_messages", "is_bookmarked", "INTEGER DEFAULT 0") },
        28L to { d -> addColumnIfMissing(d, "chat_messages", "content_json", "TEXT") },
        29L to { d ->
            addColumnIfMissing(d, "chat_messages", "used_resource_collection_ids", "VARCHAR(2000) DEFAULT '[]'")
        },

        35L to { d ->
            addColumnIfMissing(d, "chat_directives", "updated_at", "TEXT NOT NULL DEFAULT '1970-01-01T00:00:00'")
        },
        36L to { d -> addColumnIfMissing(d, "chat_directives", "deleted_at", "TEXT") },
        37L to { d -> addColumnIfMissing(d, "chat_directives", "synced_at", "TEXT") },
        38L to { d -> addColumnIfMissing(d, "chat_directives", "scope", "TEXT NOT NULL DEFAULT 'PERSONAL'") },
        39L to { d -> addColumnIfMissing(d, "chat_directives", "created_by", "TEXT") },

        42L to { d -> migrateFileSegmentsTable(d) },

        44L to { d -> migrateIndexFileStateTable(d) },

        46L to { d -> addColumnIfMissing(d, "plan_executions", "step_outputs", "TEXT") },
        47L to { d -> addColumnIfMissing(d, "plan_executions", "total_input_tokens", "INTEGER") },
        48L to { d -> addColumnIfMissing(d, "plan_executions", "total_output_tokens", "INTEGER") },
        49L to { d -> addColumnIfMissing(d, "plan_executions", "total_tokens", "INTEGER") },
        50L to { d -> addColumnIfMissing(d, "plan_executions", "total_duration_ms", "INTEGER") },

        53L to { d -> addColumnIfMissing(d, "agent_run_history", "content_json", "TEXT") },
        54L to { d -> addColumnIfMissing(d, "agent_run_history", "agent_id", "TEXT") },
        55L to { d -> addColumnIfMissing(d, "agent_run_history", "is_cancelled", "INTEGER NOT NULL DEFAULT 0") },

        57L to { d ->
            addColumnIfMissing(d, "resource_collections", "index_status", "TEXT NOT NULL DEFAULT 'NOT_STARTED'")
            addColumnIfMissing(d, "resource_collections", "last_indexed_at", "TEXT")
            addColumnIfMissing(d, "resource_collections", "index_error", "TEXT")
        },
        58L to { d -> addColumnIfMissing(d, "chat_message_attachments", "storage_path", "VARCHAR(1024)") },

        61L to { d ->
            dropColumnIfExists(d, "projects", "space_id")
            dropColumnIfExists(d, "projects", "space_name")
        },

        65L to { d -> addColumnIfMissing(d, "resource_collections", "origin", "TEXT NOT NULL DEFAULT 'LOCAL'") },

        66L to { d -> addColumnIfMissing(d, "chat_messages", "deleted_at", "TEXT") },
    )

    fun callbacks(): Array<AfterVersion> = migrationActions.map { (version, action) ->
        AfterVersion(version) { d -> action(d) }
    }.toTypedArray()

    /**
     * Re-applies every conditional migration action unconditionally, regardless of
     * `PRAGMA user_version`. Safe to call on every startup: each action is guarded by an
     * existence check and is a no-op when the table/column is already in its current shape.
     * See the class doc for why this is necessary in addition to [callbacks].
     */
    fun repairAll(driver: SqlDriver) {
        migrationActions.forEach { (_, action) -> action(driver) }
    }

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
        driver.execute(null, "ALTER TABLE $table DROP COLUMN $column", 0)
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
