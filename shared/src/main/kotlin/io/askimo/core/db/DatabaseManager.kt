/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.askimo.core.chat.repository.ChatDirectiveRepository
import io.askimo.core.chat.repository.ChatMessageAttachmentRepository
import io.askimo.core.chat.repository.ChatMessageRepository
import io.askimo.core.chat.repository.ChatSessionRepository
import io.askimo.core.chat.repository.ModelClassificationRepository
import io.askimo.core.chat.repository.ProjectRepository
import io.askimo.core.chat.repository.ResourceSegmentRepository
import io.askimo.core.chat.repository.SessionMemoryRepository
import io.askimo.core.chat.repository.UserMemoryRepository
import io.askimo.core.plan.repository.PlanExecutionRepository
import io.askimo.core.skills.repository.SkillRunHistoryRepository
import io.askimo.core.user.repository.UserProfileRepository
import io.askimo.core.util.AskimoHome
import java.sql.Connection
import javax.sql.DataSource

/**
 * Singleton manager for database connections and schema initialization.
 * Maintains a single HikariDataSource per database file for resource efficiency.
 *
 * This centralizes database lifecycle management and ensures all repositories
 * share the same connection pool, avoiding resource waste and test isolation issues.
 */
class DatabaseManager private constructor(
    val databaseFileName: String = "askimo.db",
    useInMemory: Boolean = false,
) : AutoCloseable {

    private val hikariDataSource: HikariDataSource = createSQLiteDataSource(
        databaseFileName = databaseFileName,
        useInMemory = useInMemory,
    )

    /**
     * Creates a HikariDataSource for a SQLite database file in the Askimo home directory.
     *
     * @param databaseFileName The name of the database file (e.g., "askimo.db")
     * @param useInMemory If true, creates an in-memory database (useful for testing)
     * @return A configured HikariDataSource
     */
    private fun createSQLiteDataSource(
        databaseFileName: String,
        useInMemory: Boolean,
    ): HikariDataSource {
        val jdbcUrl = if (useInMemory) {
            "jdbc:sqlite:file:memdb_${System.nanoTime()}?mode=memory&cache=shared"
        } else {
            val askimoHome = AskimoHome.base()
            if (!askimoHome.toFile().exists()) {
                askimoHome.toFile().mkdirs()
            }
            val dbPath = askimoHome.resolve(databaseFileName).toString()
            "jdbc:sqlite:$dbPath"
        }

        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            driverClassName = "org.sqlite.JDBC"
            maximumPoolSize = if (useInMemory) 1 else 10 // Single connection for in-memory
            minimumIdle = if (useInMemory) 1 else 2
            connectionTimeout = 30000
            idleTimeout = 600000
            maxLifetime = 1800000
            connectionInitSql = "PRAGMA foreign_keys = ON;"
            addDataSourceProperty("cachePrepStmts", "true")
            addDataSourceProperty("prepStmtCacheSize", "250")
            addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
        }

        return HikariDataSource(config).also { ds ->
            ds.connection.use { conn ->
                initializeTables(conn)
            }
        }
    }

    /**
     * The datasource for obtaining database connections.
     * All repositories using this manager share this connection pool.
     */
    val dataSource: DataSource get() = hikariDataSource

    /**
     * Initialize all database tables in correct dependency order.
     * This method is called automatically during datasource creation.
     *
     * @param connection An open database connection for executing initialization SQL
     */
    private fun initializeTables(connection: Connection) {
        // Create tables in dependency order (respecting foreign key constraints)
        createUserProfilesTable(connection)
        createUserInterestsTable(connection)
        createUserPreferencesTable(connection)
        createProjectsTable(connection)
        createSessionsTable(connection)
        createMessagesTable(connection)
        createAttachmentsTable(connection)
        createSummariesTable(connection)
        createDirectivesTable(connection)
        createSessionMemoryTable(connection)
        createUserMemoryTable(connection)
        createFileSegmentsTable(connection)
        createModelClassificationsTable(connection)
        createIndexFileStateTable(connection)
        createPlanExecutionsTable(connection)
        createSkillRunHistoryTable(connection)
    }

    private fun createUserProfilesTable(conn: Connection) {
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
    }

    private fun createUserInterestsTable(conn: Connection) {
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
    }

    private fun createUserPreferencesTable(conn: Connection) {
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
    }

    private fun createProjectsTable(conn: Connection) {
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
                """,
            )

            try {
                stmt.executeUpdate(
                    "ALTER TABLE projects ADD COLUMN synced_at TEXT",
                )
            } catch (_: Exception) {
                // Column already exists — safe to ignore.
            }

            try {
                stmt.executeUpdate(
                    "ALTER TABLE projects ADD COLUMN is_starred INTEGER DEFAULT 0",
                )
            } catch (_: Exception) {
                // Column already exists — safe to ignore.
            }

            try {
                stmt.executeUpdate(
                    "ALTER TABLE projects ADD COLUMN space_id TEXT",
                )
            } catch (_: Exception) {
                // Column already exists — safe to ignore.
            }

            try {
                stmt.executeUpdate(
                    "ALTER TABLE projects ADD COLUMN space_name TEXT",
                )
            } catch (_: Exception) {
                // Column already exists — safe to ignore.
            }
        }
    }

    private fun createSessionsTable(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA foreign_keys = ON")

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
                """,
            )

            // Migration: Add project_id column if it doesn't exist (for existing databases)
            try {
                stmt.executeUpdate(
                    """
                    ALTER TABLE chat_sessions ADD COLUMN project_id TEXT REFERENCES projects(id) ON DELETE CASCADE
                    """,
                )
            } catch (_: Exception) {
                // Column already exists — safe to ignore
            }

            // Migration: Add synced_at column if it doesn't exist.
            // NULL  = row has never been pushed to the sync server.
            // Non-null = ISO-8601 timestamp of the last successful push for this row.
            // Used by ConversationSyncService to identify rows that need pushing.
            try {
                stmt.executeUpdate(
                    "ALTER TABLE chat_sessions ADD COLUMN synced_at TEXT",
                )
            } catch (_: Exception) {
                // Column already exists — safe to ignore.
            }

            try {
                stmt.executeUpdate(
                    "ALTER TABLE chat_sessions DROP COLUMN sort_order",
                )
            } catch (_: Exception) {
                // Column doesn't exist or SQLite version too old — safe to ignore.
            }
        }
    }

    private fun createMessagesTable(conn: Connection) {
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
                """,
            )

            // Migration: Add is_edited column if it doesn't exist (for existing databases)
            try {
                stmt.executeUpdate(
                    """
                    ALTER TABLE chat_messages ADD COLUMN is_edited INTEGER DEFAULT 0
                    """,
                )
            } catch (_: Exception) {
                // Column already exists, ignore the error
            }

            // Migration: Add is_failed column if it doesn't exist (for existing databases)
            try {
                stmt.executeUpdate(
                    """
                    ALTER TABLE chat_messages ADD COLUMN is_failed INTEGER DEFAULT 0
                    """,
                )
            } catch (_: Exception) {
                // Column already exists, ignore the error
            }

            // Migration: Add synced_at column if it doesn't exist.
            // NULL  = row has never been pushed to the sync server.
            // Non-null = ISO-8601 timestamp of the last successful push for this row.
            // Used by ConversationSyncService to identify rows that need pushing.
            try {
                stmt.executeUpdate(
                    "ALTER TABLE chat_messages ADD COLUMN synced_at TEXT",
                )
            } catch (_: Exception) {
                // Column already exists — safe to ignore.
            }

            // Migration: Add per-message token usage columns.
            // NULL = token data was not available (older messages / providers that don't report it).
            try {
                stmt.executeUpdate("ALTER TABLE chat_messages ADD COLUMN input_tokens INTEGER")
            } catch (_: Exception) {}
            try {
                stmt.executeUpdate("ALTER TABLE chat_messages ADD COLUMN output_tokens INTEGER")
            } catch (_: Exception) {}
            try {
                stmt.executeUpdate("ALTER TABLE chat_messages ADD COLUMN total_tokens INTEGER")
            } catch (_: Exception) {}
            try {
                stmt.executeUpdate("ALTER TABLE chat_messages ADD COLUMN duration_ms INTEGER")
            } catch (_: Exception) {}

            // Create composite index for efficient session-based queries with time ordering
            stmt.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_messages_session_created
                ON chat_messages (session_id, created_at)
                """,
            )

            // Create composite index for efficient active messages queries
            stmt.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_messages_session_outdated_created
                ON chat_messages (session_id, is_outdated, created_at)
                """,
            )
        }
    }

    private fun createAttachmentsTable(conn: Connection) {
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
                """,
            )

            // Create index for efficient lookups by message_id
            stmt.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_attachments_message_id
                ON chat_message_attachments (message_id)
                """,
            )

            // Create index for efficient lookups by session_id
            stmt.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_attachments_session_id
                ON chat_message_attachments (session_id)
                """,
            )
        }
    }

    private fun createSummariesTable(conn: Connection) {
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
                """,
            )
        }
    }

    private fun createDirectivesTable(conn: Connection) {
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
                """,
            )

            // Migration: Drop unique index on name if it exists (for existing databases)
            try {
                stmt.executeUpdate("DROP INDEX IF EXISTS chat_directives_name")
            } catch (_: Exception) {
                // Ignore - index might not exist
            }

            // Migration: Add updated_at column if it doesn't exist (for existing databases)
            try {
                stmt.executeUpdate("ALTER TABLE chat_directives ADD COLUMN updated_at TEXT NOT NULL DEFAULT '1970-01-01T00:00:00'")
            } catch (_: Exception) {
                // Column already exists — safe to ignore.
            }

            // Migration: Add deleted_at column if it doesn't exist (for existing databases)
            try {
                stmt.executeUpdate("ALTER TABLE chat_directives ADD COLUMN deleted_at TEXT")
            } catch (_: Exception) {
                // Column already exists — safe to ignore.
            }

            // Migration: Add synced_at column if it doesn't exist.
            // NULL  = row has never been pushed to the sync server.
            // Non-null = ISO-8601 timestamp of the last successful push for this row.
            try {
                stmt.executeUpdate("ALTER TABLE chat_directives ADD COLUMN synced_at TEXT")
            } catch (_: Exception) {
                // Column already exists — safe to ignore.
            }

            // Migration: Add scope column — PERSONAL (default) or TEAM.
            // TEAM directives are read-only on the client and never pushed back to the server.
            try {
                stmt.executeUpdate("ALTER TABLE chat_directives ADD COLUMN scope TEXT NOT NULL DEFAULT 'PERSONAL'")
            } catch (_: Exception) {
                // Column already exists — safe to ignore.
            }

            // Migration: Add created_by column — userId of whoever created this directive.
            // NULL is valid for locally-seeded default directives.
            try {
                stmt.executeUpdate("ALTER TABLE chat_directives ADD COLUMN created_by TEXT")
            } catch (_: Exception) {
                // Column already exists — safe to ignore.
            }
        }
    }

    private fun createSessionMemoryTable(conn: Connection) {
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
                """,
            )
        }
    }

    private fun createUserMemoryTable(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS user_memory (
                    id TEXT PRIMARY KEY DEFAULT 'default',
                    memory_json TEXT NOT NULL,
                    last_updated TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
                """,
            )
        }
    }

    private fun createFileSegmentsTable(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS file_segments (
                    project_id TEXT NOT NULL,
                    file_path TEXT NOT NULL,
                    segment_id TEXT NOT NULL,
                    chunk_index INTEGER NOT NULL,
                    created_at TEXT NOT NULL,
                    PRIMARY KEY (project_id, file_path, segment_id),
                    FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
                )
                """,
            )

            // Create index for fast lookups by project and file
            stmt.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_file_segments_project_file
                ON file_segments (project_id, file_path)
                """,
            )
        }
    }

    private fun createModelClassificationsTable(conn: Connection) {
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
                """,
            )

            // Create unique index on provider + model_name to prevent duplicates
            stmt.executeUpdate(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS idx_model_classifications_provider_model
                ON model_classifications (provider, model_name)
                """,
            )
        }
    }

    private fun createIndexFileStateTable(conn: Connection) {
        conn.createStatement().use { stmt ->
            // Only drop and recreate if the old schema is detected (missing resource_id column).
            // Checking PRAGMA table_info avoids wiping the index state on every startup.
            val hasResourceId = conn.createStatement().use { infoStmt ->
                val rs = infoStmt.executeQuery("PRAGMA table_info(index_file_state)")
                var found = false
                while (rs.next()) {
                    if (rs.getString("name") == "resource_id") {
                        found = true
                        break
                    }
                }
                found
            }

            if (!hasResourceId) {
                // Old schema or table doesn't exist yet — drop and recreate cleanly.
                // Index state is rebuilt automatically on the next indexing run.
                stmt.executeUpdate("DROP TABLE IF EXISTS index_file_state")
            }

            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS index_file_state (
                    project_id  TEXT NOT NULL,
                    resource_id TEXT NOT NULL,
                    file_path   TEXT NOT NULL,
                    file_hash   TEXT NOT NULL,
                    source_type TEXT NOT NULL,
                    indexed_at  TEXT NOT NULL,
                    PRIMARY KEY (project_id, resource_id, file_path)
                )
                """.trimIndent(),
            )

            // Index on file_hash for fast hash lookups
            stmt.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_index_file_state_hash
                ON index_file_state (file_hash)
                """.trimIndent(),
            )

            // Composite index on project_id, resource_id and source_type for coordinator-scoped filtering
            stmt.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_index_file_state_project_resource_source
                ON index_file_state (project_id, resource_id, source_type)
                """.trimIndent(),
            )
        }
    }

    private fun createPlanExecutionsTable(conn: Connection) {
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
                """
                CREATE INDEX IF NOT EXISTS idx_plan_executions_plan_id
                ON plan_executions (plan_id, created_at)
                """.trimIndent(),
            )

            // Migration: add step_outputs column for databases created before this was introduced.
            try {
                stmt.executeUpdate("ALTER TABLE plan_executions ADD COLUMN step_outputs TEXT")
            } catch (_: Exception) {
                // Column already exists — safe to ignore.
            }

            // Migration: add token usage columns for plan executions.
            try {
                stmt.executeUpdate("ALTER TABLE plan_executions ADD COLUMN total_input_tokens INTEGER")
            } catch (_: Exception) {
                // Column already exists — safe to ignore.
            }
            try {
                stmt.executeUpdate("ALTER TABLE plan_executions ADD COLUMN total_output_tokens INTEGER")
            } catch (_: Exception) {
                // Column already exists — safe to ignore.
            }
            try {
                stmt.executeUpdate("ALTER TABLE plan_executions ADD COLUMN total_tokens INTEGER")
            } catch (_: Exception) {
                // Column already exists — safe to ignore.
            }
            try {
                stmt.executeUpdate("ALTER TABLE plan_executions ADD COLUMN total_duration_ms INTEGER")
            } catch (_: Exception) {
                // Column already exists — safe to ignore.
            }
        }
    }

    private fun createSkillRunHistoryTable(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS skill_run_history (
                    id           TEXT PRIMARY KEY,
                    skill_path   TEXT NOT NULL,
                    user_input   TEXT NOT NULL DEFAULT '',
                    response     TEXT NOT NULL DEFAULT '',
                    error        TEXT,
                    agent_session_id TEXT,
                    workspace_dir TEXT,
                    activity_log TEXT NOT NULL DEFAULT '',
                    created_at   TEXT NOT NULL
                )
                """.trimIndent(),
            )

            // Migration: add agent session/workspace columns for existing databases.
            try {
                stmt.executeUpdate("ALTER TABLE skill_run_history ADD COLUMN agent_session_id TEXT")
            } catch (_: Exception) {
                // Column already exists — safe to ignore.
            }
            try {
                stmt.executeUpdate("ALTER TABLE skill_run_history ADD COLUMN workspace_dir TEXT")
            } catch (_: Exception) {
                // Column already exists — safe to ignore.
            }

            stmt.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_skill_run_history_skill_path
                ON skill_run_history (skill_path, created_at)
                """.trimIndent(),
            )
        }
    }

    private val _chatSessionRepository: ChatSessionRepository by lazy {
        ChatSessionRepository(this)
    }

    private val _chatMessageAttachmentRepository: ChatMessageAttachmentRepository by lazy {
        ChatMessageAttachmentRepository(this)
    }

    private val _chatMessageRepository: ChatMessageRepository by lazy {
        ChatMessageRepository(this, _chatMessageAttachmentRepository)
    }

    private val _chatDirectiveRepository: ChatDirectiveRepository by lazy {
        ChatDirectiveRepository(this)
    }

    private val _sessionMemoryRepository: SessionMemoryRepository by lazy {
        SessionMemoryRepository(this)
    }

    private val _userMemoryRepository: UserMemoryRepository by lazy {
        UserMemoryRepository(this)
    }

    private val _projectRepository: ProjectRepository by lazy {
        ProjectRepository(this)
    }

    private val _resourceSegmentRepository: ResourceSegmentRepository by lazy {
        ResourceSegmentRepository(this)
    }

    private val _modelClassificationRepository: ModelClassificationRepository by lazy {
        ModelClassificationRepository(this)
    }

    private val _userProfileRepository: UserProfileRepository by lazy {
        UserProfileRepository(this)
    }

    private val _planExecutionRepository: PlanExecutionRepository by lazy {
        PlanExecutionRepository(this)
    }

    private val _skillRunHistoryRepository: SkillRunHistoryRepository by lazy {
        SkillRunHistoryRepository(this)
    }

    /**
     * Get the singleton ChatSessionRepository instance.
     * All access to chat sessions should go through this repository.
     */
    fun getChatSessionRepository(): ChatSessionRepository = _chatSessionRepository

    /**
     * Get the singleton ChatMessageRepository instance.
     * All access to chat messages should go through this repository.
     */
    fun getChatMessageRepository(): ChatMessageRepository = _chatMessageRepository

    /**
     * Get the singleton ChatMessageAttachmentRepository instance.
     * All access to chat message attachments should go through this repository.
     */
    fun getChatMessageAttachmentRepository(): ChatMessageAttachmentRepository = _chatMessageAttachmentRepository

    /**
     * Get the singleton ChatDirectiveRepository instance.
     * All access to chat directives should go through this repository.
     */
    fun getChatDirectiveRepository(): ChatDirectiveRepository = _chatDirectiveRepository

    /**
     * Get the singleton SessionMemoryRepository instance.
     * All access to session memory should go through this repository.
     */
    fun getSessionMemoryRepository(): SessionMemoryRepository = _sessionMemoryRepository

    /**
     * Get the singleton UserMemoryRepository instance.
     * Provides access to the persistent cross-session user memory store.
     */
    fun getUserMemoryRepository(): UserMemoryRepository = _userMemoryRepository

    /**
     * Get the singleton ProjectRepository instance.
     * All access to projects should go through this repository.
     */
    fun getProjectRepository(): ProjectRepository = _projectRepository

    /**
     * Get the singleton ResourceSegmentRepository instance.
     * All access to resource-segment mappings should go through this repository.
     */
    fun getResourceSegmentRepository(): ResourceSegmentRepository = _resourceSegmentRepository

    /**
     * Get the singleton ModelClassificationRepository instance.
     * All access to model classifications should go through this repository.
     */
    fun getModelClassificationRepository(): ModelClassificationRepository = _modelClassificationRepository

    /**
     * Get the singleton UserProfileRepository instance.
     * All access to user profiles should go through this repository.
     */
    fun getUserProfileRepository(): UserProfileRepository = _userProfileRepository

    /**
     * Get the singleton PlanExecutionRepository instance.
     * All access to plan execution records should go through this repository.
     */
    fun getPlanExecutionRepository(): PlanExecutionRepository = _planExecutionRepository

    /**
     * Get the singleton SkillRunHistoryRepository instance.
     * All access to skill run history should go through this repository.
     */
    fun getSkillRunHistoryRepository(): SkillRunHistoryRepository = _skillRunHistoryRepository

    /**
     * Get the singleton FileSegmentRepository instance (deprecated - use getResourceSegmentRepository).
     * All access to file-segment mappings should go through this repository.
     * @deprecated Use getResourceSegmentRepository() instead
     */
    @Deprecated("Use getResourceSegmentRepository() instead", ReplaceWith("getResourceSegmentRepository()"))
    fun getFileSegmentRepository(): ResourceSegmentRepository = _resourceSegmentRepository

    /**
     * Closes the HikariCP connection pool and releases all database resources.
     */
    override fun close() {
        if (!hikariDataSource.isClosed) {
            hikariDataSource.close()
        }
    }

    companion object {
        @Volatile
        private var instance: DatabaseManager? = null

        /**
         * Get the singleton DatabaseManager instance for production use.
         * Uses the default "askimo.db" database file.
         */
        @Synchronized
        fun getInstance(): DatabaseManager = instance ?: DatabaseManager().also { instance = it }

        /**
         * Create a test-scoped DatabaseManager with a unique database file.
         * This allows test isolation by using different database files per test class.
         *
         * @param testScope The test class instance (typically use `this` in companion object)
         * @return A new DatabaseManager instance with a unique database file
         */
        fun getTestInstance(testScope: Any): DatabaseManager {
            val testDbName = "test_${testScope.javaClass.simpleName}_${System.nanoTime()}.db"
            return DatabaseManager(databaseFileName = testDbName)
        }

        /**
         * Create an in-memory test DatabaseManager.
         * This is useful for environments where SQLite native libraries are not available
         * or for faster test execution without file I/O.
         *
         * @param testScope The test class instance (typically use `this` in companion object)
         * @return A new DatabaseManager instance with an in-memory database
         */
        fun getInMemoryTestInstance(testScope: Any): DatabaseManager {
            val testDbName = "test_${testScope.javaClass.simpleName}_${System.nanoTime()}_memory.db"
            return DatabaseManager(databaseFileName = testDbName, useInMemory = true)
        }

        /**
         * Reset the singleton instance (for testing purposes only).
         * Closes the current instance and clears the singleton reference.
         */
        @Synchronized
        fun reset() {
            instance?.close()
            instance = null
        }
    }
}
