/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.db

import java.sql.Connection

/**
 * A single, immutable schema change. Migrations run **in order, exactly once** per
 * database file, tracked via SQLite's `PRAGMA user_version` counter (see [DatabaseManager]).
 *
 * Rules for adding new migrations:
 *  - NEVER edit or remove an entry in [SchemaMigrations.all] — it's an append-only
 *    changelog. Reordering/editing history desyncs already-migrated databases.
 *  - Always add new migrations at the END of the list.
 *  - Keep each migration small and focused (one table / column / index).
 */
fun interface Migration {
    fun apply(conn: Connection)
}

/**
 * Ordered changelog of every schema change ever applied to the Askimo SQLite database.
 *
 * The position in the list (1-based) IS the schema version: `all[0]` is version 1,
 * `all[1]` is version 2, etc. [DatabaseManager] compares this list's size against the
 * stored `PRAGMA user_version` and applies only migrations that haven't run yet.
 */
object SchemaMigrations {

    /**
     * Migration count at the time this versioned system was introduced. A pre-existing
     * database (has `user_profiles` but `user_version` 0) is assumed to already reflect
     * this baseline — kept up to date by the old ad-hoc `CREATE TABLE IF NOT EXISTS` /
     * `ALTER TABLE` + try/catch approach — so it's stamped with this version instead of
     * replaying every historical statement (which would fail on existing columns).
     *
     * NOTE: only correctly captures databases fully up to date under the old ad-hoc
     * system; one that skipped several ad-hoc migrations before upgrading is an
     * unhandled edge case.
     */
    const val BASELINE_VERSION = 58

    val all: List<Migration> = listOf(
        // 1: user_profiles
        Migration { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS user_profiles (
                        id TEXT PRIMARY KEY,
                        name TEXT,
                        email TEXT,
                        preferred_title TEXT,
                        occupation TEXT,
                        location TEXT,
                        timezone TEXT,
                        bio TEXT,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        },
        // 2: user_interests
        Migration { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS user_interests (
                        id TEXT PRIMARY KEY,
                        profile_id TEXT NOT NULL,
                        interest TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        FOREIGN KEY (profile_id) REFERENCES user_profiles(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        },
        // 3: user_preferences
        Migration { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS user_preferences (
                        id TEXT PRIMARY KEY,
                        profile_id TEXT NOT NULL,
                        key TEXT NOT NULL,
                        value TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        FOREIGN KEY (profile_id) REFERENCES user_profiles(id) ON DELETE CASCADE,
                        UNIQUE(profile_id, key)
                    )
                    """.trimIndent(),
                )
            }
        },
        // 4: projects (base)
        Migration { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS projects (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT,
                        indexed_paths TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        synced_at TEXT
                    )
                    """.trimIndent(),
                )
            }
        },
        // 5: projects.synced_at
        Migration { conn -> addColumnIfMissing(conn, "projects", "synced_at", "TEXT") },
        // 6: projects.is_starred
        Migration { conn -> addColumnIfMissing(conn, "projects", "is_starred", "INTEGER DEFAULT 0") },
        // 7: projects.space_id
        Migration { conn -> addColumnIfMissing(conn, "projects", "space_id", "TEXT") },
        // 8: projects.space_name
        Migration { conn -> addColumnIfMissing(conn, "projects", "space_name", "TEXT") },
        // 9: projects.default_directive_id
        Migration { conn -> addColumnIfMissing(conn, "projects", "default_directive_id", "TEXT") },
        // 10: resource_collections (base)
        Migration { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS resource_collections (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT,
                        knowledge_sources_config TEXT NOT NULL DEFAULT '{}',
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        is_system_collection INTEGER NOT NULL DEFAULT 0,
                        synced_at TEXT
                    )
                    """.trimIndent(),
                )
            }
        },
        // 11: resource_collections.index_status
        Migration { conn -> addColumnIfMissing(conn, "resource_collections", "index_status", "TEXT NOT NULL DEFAULT 'NOT_STARTED'") },
        // 12: resource_collections.last_indexed_at
        Migration { conn -> addColumnIfMissing(conn, "resource_collections", "last_indexed_at", "TEXT") },
        // 13: resource_collections.index_error
        Migration { conn -> addColumnIfMissing(conn, "resource_collections", "index_error", "TEXT") },
        // 14: chat_sessions (base)
        Migration { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS chat_sessions (
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
            }
        },
        // 15: chat_sessions.project_id
        Migration { conn ->
            addColumnIfMissing(conn, "chat_sessions", "project_id", "TEXT REFERENCES projects(id) ON DELETE CASCADE")
        },
        // 16: chat_sessions.synced_at
        Migration { conn -> addColumnIfMissing(conn, "chat_sessions", "synced_at", "TEXT") },
        // 17: chat_sessions.is_user_renamed
        Migration { conn -> addColumnIfMissing(conn, "chat_sessions", "is_user_renamed", "INTEGER DEFAULT 0") },
        // 18: chat_sessions.active_resource_collection_ids
        Migration { conn ->
            addColumnIfMissing(conn, "chat_sessions", "active_resource_collection_ids", "VARCHAR(2000) DEFAULT '[]'")
        },
        // 19: chat_sessions drop sort_order
        Migration { conn ->
            try {
                conn.createStatement().use { it.executeUpdate("ALTER TABLE chat_sessions DROP COLUMN sort_order") }
            } catch (_: Exception) {
                // Column doesn't exist or SQLite version too old — safe to ignore.
            }
        },
        // 20: chat_messages (base)
        Migration { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS chat_messages (
                        id TEXT PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        is_outdated INTEGER DEFAULT 0,
                        edit_parent_id TEXT,
                        is_edited INTEGER DEFAULT 0,
                        synced_at TEXT,
                        FOREIGN KEY (session_id) REFERENCES chat_sessions (id) ON DELETE CASCADE,
                        FOREIGN KEY (edit_parent_id) REFERENCES chat_messages (id) ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
            }
        },
        // 21: chat_messages.is_edited
        Migration { conn -> addColumnIfMissing(conn, "chat_messages", "is_edited", "INTEGER DEFAULT 0") },
        // 22: chat_messages.is_failed
        Migration { conn -> addColumnIfMissing(conn, "chat_messages", "is_failed", "INTEGER DEFAULT 0") },
        // 23: chat_messages.synced_at
        Migration { conn -> addColumnIfMissing(conn, "chat_messages", "synced_at", "TEXT") },
        // 24: chat_messages.input_tokens
        Migration { conn -> addColumnIfMissing(conn, "chat_messages", "input_tokens", "INTEGER") },
        // 25: chat_messages.output_tokens
        Migration { conn -> addColumnIfMissing(conn, "chat_messages", "output_tokens", "INTEGER") },
        // 26: chat_messages.total_tokens
        Migration { conn -> addColumnIfMissing(conn, "chat_messages", "total_tokens", "INTEGER") },
        // 27: chat_messages.duration_ms
        Migration { conn -> addColumnIfMissing(conn, "chat_messages", "duration_ms", "INTEGER") },
        // 28: chat_messages.is_bookmarked
        Migration { conn -> addColumnIfMissing(conn, "chat_messages", "is_bookmarked", "INTEGER DEFAULT 0") },
        // 29: chat_messages.content_json
        Migration { conn -> addColumnIfMissing(conn, "chat_messages", "content_json", "TEXT") },
        // 30: chat_messages.used_resource_collection_ids
        Migration { conn ->
            addColumnIfMissing(conn, "chat_messages", "used_resource_collection_ids", "VARCHAR(2000) DEFAULT '[]'")
        },
        // 31 & 32: chat_messages indexes
        Migration { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_messages_session_created ON chat_messages (session_id, created_at)",
                )
                stmt.executeUpdate(
                    """
                    CREATE INDEX IF NOT EXISTS idx_messages_session_outdated_created
                    ON chat_messages (session_id, is_outdated, created_at)
                    """.trimIndent(),
                )
            }
        },
        // 33: chat_message_attachments (base + indexes)
        Migration { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS chat_message_attachments (
                        id TEXT PRIMARY KEY,
                        message_id TEXT NOT NULL,
                        session_id TEXT NOT NULL,
                        file_name TEXT NOT NULL,
                        mime_type TEXT NOT NULL,
                        size INTEGER NOT NULL,
                        created_at TEXT NOT NULL,
                        FOREIGN KEY (message_id) REFERENCES chat_messages (id) ON DELETE CASCADE,
                        FOREIGN KEY (session_id) REFERENCES chat_sessions (id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                stmt.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_attachments_message_id ON chat_message_attachments (message_id)",
                )
                stmt.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_attachments_session_id ON chat_message_attachments (session_id)",
                )
            }
        },
        // 34: conversation_summaries
        Migration { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS conversation_summaries (
                        session_id TEXT PRIMARY KEY,
                        key_facts TEXT NOT NULL,
                        main_topics TEXT NOT NULL,
                        recent_context TEXT NOT NULL,
                        last_summarized_message_id TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        FOREIGN KEY (session_id) REFERENCES chat_sessions (id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        },
        // 35: chat_directives (base)
        Migration { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS chat_directives (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        content TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL DEFAULT (datetime('now')),
                        deleted_at TEXT,
                        synced_at TEXT
                    )
                    """.trimIndent(),
                )
            }
        },
        // 36: drop legacy unique index on chat_directives.name
        Migration { conn ->
            conn.createStatement().use { stmt ->
                try {
                    stmt.executeUpdate("DROP INDEX IF EXISTS chat_directives_name")
                } catch (_: Exception) {
                    // Ignore - index might not exist
                }
            }
        },
        // 37: chat_directives.updated_at (added after table already had rows without a default)
        Migration { conn ->
            addColumnIfMissing(conn, "chat_directives", "updated_at", "TEXT NOT NULL DEFAULT '1970-01-01T00:00:00'")
        },
        // 38: chat_directives.deleted_at
        Migration { conn -> addColumnIfMissing(conn, "chat_directives", "deleted_at", "TEXT") },
        // 39: chat_directives.synced_at
        Migration { conn -> addColumnIfMissing(conn, "chat_directives", "synced_at", "TEXT") },
        // 40: chat_directives.scope
        Migration { conn -> addColumnIfMissing(conn, "chat_directives", "scope", "TEXT NOT NULL DEFAULT 'PERSONAL'") },
        // 41: chat_directives.created_by
        Migration { conn -> addColumnIfMissing(conn, "chat_directives", "created_by", "TEXT") },
        // 42: session_memory
        Migration { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS session_memory (
                        session_id TEXT PRIMARY KEY,
                        memory_summary TEXT,
                        memory_messages TEXT NOT NULL,
                        last_updated TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        FOREIGN KEY (session_id) REFERENCES chat_sessions (id) ON DELETE CASCADE ON UPDATE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        },
        // 43: user_memory
        Migration { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS user_memory (
                        id TEXT PRIMARY KEY DEFAULT 'default',
                        memory_json TEXT NOT NULL,
                        last_updated TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        },
        // 44: file_segments (base + legacy project_id -> container_id rename + indexes)
        Migration { conn -> migrateFileSegmentsTable(conn) },
        // 45: model_classifications (base + unique index)
        Migration { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS model_classifications (
                        id TEXT PRIMARY KEY,
                        provider TEXT NOT NULL,
                        model_name TEXT NOT NULL,
                        supports_text INTEGER DEFAULT 1,
                        supports_image INTEGER DEFAULT 0,
                        supports_audio INTEGER DEFAULT 0,
                        supports_video INTEGER DEFAULT 0,
                        supports_tools INTEGER DEFAULT 0,
                        supports_sampling INTEGER DEFAULT 1,
                        supports_streaming INTEGER DEFAULT 1,
                        description TEXT,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                stmt.executeUpdate(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_model_classifications_provider_model
                    ON model_classifications (provider, model_name)
                    """.trimIndent(),
                )
            }
        },
        // 46: index_file_state (base + legacy schema drop/recreate + indexes)
        Migration { conn -> migrateIndexFileStateTable(conn) },
        // 47: plan_executions (base + index)
        Migration { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS plan_executions (
                        id            TEXT PRIMARY KEY,
                        plan_id       TEXT NOT NULL,
                        plan_name     TEXT NOT NULL,
                        inputs        TEXT NOT NULL DEFAULT '',
                        status        TEXT NOT NULL DEFAULT 'IDLE',
                        run_count     INTEGER NOT NULL DEFAULT 1,
                        session_id    TEXT,
                        output        TEXT,
                        step_outputs  TEXT,
                        error_message TEXT,
                        created_at    TEXT NOT NULL,
                        updated_at    TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                stmt.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_plan_executions_plan_id ON plan_executions (plan_id, created_at)",
                )
            }
        },
        // 48: plan_executions.step_outputs
        Migration { conn -> addColumnIfMissing(conn, "plan_executions", "step_outputs", "TEXT") },
        // 49: plan_executions.total_input_tokens
        Migration { conn -> addColumnIfMissing(conn, "plan_executions", "total_input_tokens", "INTEGER") },
        // 50: plan_executions.total_output_tokens
        Migration { conn -> addColumnIfMissing(conn, "plan_executions", "total_output_tokens", "INTEGER") },
        // 51: plan_executions.total_tokens
        Migration { conn -> addColumnIfMissing(conn, "plan_executions", "total_tokens", "INTEGER") },
        // 52: plan_executions.total_duration_ms
        Migration { conn -> addColumnIfMissing(conn, "plan_executions", "total_duration_ms", "INTEGER") },
        // 53: workspaces (base + indexes) — created before agent_run_history, which FKs to it
        Migration { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS workspaces (
                        id            TEXT PRIMARY KEY,
                        name          TEXT NOT NULL,
                        path          TEXT NOT NULL,
                        created_at    TEXT NOT NULL,
                        last_used_at  TEXT NOT NULL,
                        pinned        INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                stmt.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_workspaces_path ON workspaces (path)")
                stmt.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_workspaces_last_used ON workspaces (pinned, last_used_at)",
                )
            }
        },
        // 54: agent_run_history (base + indexes)
        Migration { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS agent_run_history (
                        id               TEXT PRIMARY KEY,
                        workspace_id     TEXT NOT NULL REFERENCES workspaces(id),
                        conversation_id  TEXT NOT NULL,
                        title            TEXT NOT NULL DEFAULT '',
                        user_input       TEXT NOT NULL DEFAULT '',
                        response         TEXT NOT NULL DEFAULT '',
                        error            TEXT,
                        agent_session_id TEXT,
                        activity_log     TEXT NOT NULL DEFAULT '',
                        input_tokens     INTEGER,
                        output_tokens    INTEGER,
                        total_tokens     INTEGER,
                        duration_ms      INTEGER,
                        created_at       TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                stmt.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_agent_run_history_workspace ON agent_run_history (workspace_id, created_at)",
                )
                stmt.executeUpdate(
                    """
                    CREATE INDEX IF NOT EXISTS idx_agent_run_history_conversation
                    ON agent_run_history (conversation_id, created_at)
                    """.trimIndent(),
                )
            }
        },
        // 55: agent_run_history.content_json
        Migration { conn -> addColumnIfMissing(conn, "agent_run_history", "content_json", "TEXT") },
        // 56: agent_run_history.agent_id
        Migration { conn -> addColumnIfMissing(conn, "agent_run_history", "agent_id", "TEXT") },
        // 57: agent_run_history.is_cancelled
        Migration { conn -> addColumnIfMissing(conn, "agent_run_history", "is_cancelled", "INTEGER NOT NULL DEFAULT 0") },
        // 58: llm_usage_records (base + index)
        Migration { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS llm_usage_records (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        timestamp     TEXT    NOT NULL,
                        provider      TEXT    NOT NULL,
                        model         TEXT    NOT NULL,
                        instance_id   TEXT,
                        prompt_tokens INTEGER NOT NULL DEFAULT 0,
                        output_tokens INTEGER NOT NULL DEFAULT 0,
                        total_tokens  INTEGER NOT NULL DEFAULT 0,
                        duration_ms   INTEGER NOT NULL DEFAULT 0,
                        is_error      INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                stmt.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_llm_usage_records_timestamp ON llm_usage_records (timestamp)",
                )
            }
        },

        // --- Add new migrations below this line. Never edit the entries above. ---
    )

    /**
     * Adds [column] to [table] only if it doesn't already exist. Prefer this over a bare
     * `ALTER TABLE ... ADD COLUMN` + swallow-all try/catch, since it fails loudly on
     * unexpected errors instead of silently ignoring them.
     */
    private fun addColumnIfMissing(conn: Connection, table: String, column: String, columnDefinition: String) {
        if (column in tableColumns(conn, table)) return
        conn.createStatement().use { stmt ->
            stmt.executeUpdate("ALTER TABLE $table ADD COLUMN $column $columnDefinition")
        }
    }

    private fun tableColumns(conn: Connection, table: String): Set<String> = conn.createStatement().use { stmt ->
        stmt.executeQuery("PRAGMA table_info($table)").use { rs ->
            val columns = mutableSetOf<String>()
            while (rs.next()) {
                columns.add(rs.getString("name"))
            }
            columns
        }
    }

    /**
     * `file_segments` used to have `project_id` with a hard FK to `projects(id)`, but this
     * table is shared by Projects AND Resource Collections — the column actually holds
     * either a project id or a resource_collection id. Renames it to `container_id` and
     * drops the now-invalid FK by recreating the table.
     */
    private fun migrateFileSegmentsTable(conn: Connection) {
        conn.createStatement().use { stmt ->
            val existingColumns = tableColumns(conn, "file_segments")
            val tableExists = existingColumns.isNotEmpty()
            val hasLegacyColumn = tableExists && "container_id" !in existingColumns

            if (hasLegacyColumn) {
                stmt.executeUpdate("PRAGMA foreign_keys = OFF")
                stmt.executeUpdate("ALTER TABLE file_segments RENAME TO file_segments_old")
                stmt.executeUpdate(
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
                )
                stmt.executeUpdate(
                    """
                    INSERT INTO file_segments (container_id, file_path, segment_id, chunk_index, created_at)
                    SELECT project_id, file_path, segment_id, chunk_index, created_at FROM file_segments_old
                    """.trimIndent(),
                )
                stmt.executeUpdate("DROP TABLE file_segments_old")
                stmt.executeUpdate("PRAGMA foreign_keys = ON")
            }

            stmt.executeUpdate(
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
            )

            stmt.executeUpdate("DROP INDEX IF EXISTS idx_file_segments_project_file")
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_file_segments_container_file ON file_segments (container_id, file_path)",
            )
        }
    }

    /**
     * `index_file_state` gained a `resource_id` column and was renamed from `project_id`
     * to `container_id` (shared by Projects and Resource Collections). Since this table
     * is a derived cache rebuilt on the next indexing run, the legacy schema is simply
     * dropped and recreated instead of migrated in place.
     */
    private fun migrateIndexFileStateTable(conn: Connection) {
        conn.createStatement().use { stmt ->
            val existingColumns = tableColumns(conn, "index_file_state")
            val tableExists = existingColumns.isNotEmpty()
            val hasCurrentSchema = "resource_id" in existingColumns && "container_id" in existingColumns

            if (tableExists && !hasCurrentSchema) {
                stmt.executeUpdate("DROP TABLE IF EXISTS index_file_state")
            }

            stmt.executeUpdate(
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
            )

            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_index_file_state_hash ON index_file_state (file_hash)",
            )
            stmt.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_index_file_state_container_resource_source
                ON index_file_state (container_id, resource_id, source_type)
                """.trimIndent(),
            )
        }
    }
}
