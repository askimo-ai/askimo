/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.askimo.core.agent.repository.AgentRunHistoryRepository
import io.askimo.core.agent.repository.WorkspaceRepository
import io.askimo.core.chat.repository.ChatDirectiveRepository
import io.askimo.core.chat.repository.ChatMessageAttachmentRepository
import io.askimo.core.chat.repository.ChatMessageRepository
import io.askimo.core.chat.repository.ChatSessionRepository
import io.askimo.core.chat.repository.ModelClassificationRepository
import io.askimo.core.chat.repository.ProjectRepository
import io.askimo.core.chat.repository.ResourceCollectionRepository
import io.askimo.core.chat.repository.ResourceSegmentRepository
import io.askimo.core.chat.repository.SessionMemoryRepository
import io.askimo.core.chat.repository.UserMemoryRepository
import io.askimo.core.plan.repository.PlanExecutionRepository
import io.askimo.core.telemetry.LlmUsageRepository
import io.askimo.core.user.repository.UserProfileRepository
import io.askimo.core.util.AskimoHome
import java.sql.Connection
import javax.sql.DataSource

/**
 * Singleton manager for database connections and schema initialization.
 * Maintains one HikariDataSource per database file so all repositories share
 * the same connection pool, avoiding resource waste and test isolation issues.
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
     * @param databaseFileName Name of the database file (e.g., "askimo.db")
     * @param useInMemory If true, creates an in-memory database (for testing)
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
            maximumPoolSize = if (useInMemory) 1 else 10 // single connection for in-memory
            minimumIdle = if (useInMemory) 1 else 2
            connectionTimeout = 30000
            idleTimeout = 600000
            maxLifetime = 1800000
            connectionInitSql = "PRAGMA foreign_keys = ON;"
            addDataSourceProperty("cachePrepStmts", "true")
            addDataSourceProperty("prepStmtCacheSize", "250")
            addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
            // Reduce SQLITE_BUSY under concurrent access (indexing, watchers, telemetry).
            // Set as native SQLiteConfig properties, not connectionInitSql, since the JDBC
            // driver only compiles the first statement of that string.
            addDataSourceProperty("journal_mode", "WAL")
            addDataSourceProperty("busy_timeout", "5000")
            addDataSourceProperty("synchronous", "NORMAL")
        }

        return HikariDataSource(config).also { ds ->
            ds.connection.use { conn ->
                initializeTables(conn)
            }
        }
    }

    /** Datasource for obtaining database connections, shared by all repositories. */
    val dataSource: DataSource get() = hikariDataSource

    /**
     * Applies any pending [SchemaMigrations.all] entries, tracked via SQLite's
     * `PRAGMA user_version` counter. Called automatically during datasource creation.
     *
     * Every migration in [SchemaMigrations.all] is written to be idempotent (`CREATE TABLE
     * IF NOT EXISTS`, `addColumnIfMissing`-style checks, or try/catch-swallowed DROP
     * statements), so a pre-existing database (created before this versioned system, or one
     * that only partially kept up with the old ad-hoc approach) is simply replayed through
     * the full list from version 0 — there is no need to trust/guess how far the old ad-hoc
     * system got. This used to jump straight to a hardcoded baseline version, but that skipped
     * real migrations for any database not perfectly in sync with that assumption (e.g. missing
     * a column added by an earlier migration), causing "no such table/column" errors later.
     *
     * @param connection An open database connection for executing initialization SQL
     */
    private fun initializeTables(connection: Connection) {
        var version = getUserVersion(connection)

        val migrations = SchemaMigrations.all
        while (version < migrations.size) {
            migrations[version].apply(connection)
            version++
            setUserVersion(connection, version)
        }
    }

    private fun getUserVersion(conn: Connection): Int = conn.createStatement().use { stmt ->
        stmt.executeQuery("PRAGMA user_version").use { rs -> rs.getInt(1) }
    }

    private fun setUserVersion(conn: Connection, version: Int) {
        conn.createStatement().use { it.executeUpdate("PRAGMA user_version = $version") }
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

    private val _agentRunHistoryRepository: AgentRunHistoryRepository by lazy {
        AgentRunHistoryRepository(this)
    }

    private val _workspaceRepository: WorkspaceRepository by lazy {
        WorkspaceRepository(this)
    }

    private val _resourceCollectionRepository: ResourceCollectionRepository by lazy {
        ResourceCollectionRepository(this)
    }
    private val _llmUsageRepository: LlmUsageRepository by lazy {
        LlmUsageRepository(this)
    }

    /** Singleton ChatSessionRepository for all chat session access. */
    fun getChatSessionRepository(): ChatSessionRepository = _chatSessionRepository

    /** Singleton ChatMessageRepository for all chat message access. */
    fun getChatMessageRepository(): ChatMessageRepository = _chatMessageRepository

    /** Singleton ChatMessageAttachmentRepository for all chat message attachment access. */
    fun getChatMessageAttachmentRepository(): ChatMessageAttachmentRepository = _chatMessageAttachmentRepository

    /** Singleton ChatDirectiveRepository for all chat directive access. */
    fun getChatDirectiveRepository(): ChatDirectiveRepository = _chatDirectiveRepository

    /** Singleton SessionMemoryRepository for all session memory access. */
    fun getSessionMemoryRepository(): SessionMemoryRepository = _sessionMemoryRepository

    /** Singleton UserMemoryRepository for the persistent cross-session user memory store. */
    fun getUserMemoryRepository(): UserMemoryRepository = _userMemoryRepository

    /** Singleton ProjectRepository for all project access. */
    fun getProjectRepository(): ProjectRepository = _projectRepository

    /** Singleton ResourceSegmentRepository for all resource-segment mapping access. */
    fun getResourceSegmentRepository(): ResourceSegmentRepository = _resourceSegmentRepository

    /** Singleton ModelClassificationRepository for all model classification access. */
    fun getModelClassificationRepository(): ModelClassificationRepository = _modelClassificationRepository

    /** Singleton UserProfileRepository for all user profile access. */
    fun getUserProfileRepository(): UserProfileRepository = _userProfileRepository

    /** Singleton PlanExecutionRepository for all plan execution record access. */
    fun getPlanExecutionRepository(): PlanExecutionRepository = _planExecutionRepository

    /** Singleton AgentRunHistoryRepository for all agent run history access. */
    fun getAgentRunHistoryRepository(): AgentRunHistoryRepository = _agentRunHistoryRepository

    /** Singleton WorkspaceRepository for all agent workspace access. */
    fun getWorkspaceRepository(): WorkspaceRepository = _workspaceRepository

    /** Singleton ResourceCollectionRepository for all resource collection access. */
    fun getResourceCollectionRepository(): ResourceCollectionRepository = _resourceCollectionRepository

    /** Singleton LlmUsageRepository for all individual LLM call record access. */
    fun getLlmUsageRepository(): LlmUsageRepository = _llmUsageRepository

    /** Closes the HikariCP connection pool and releases all database resources. */
    override fun close() {
        if (!hikariDataSource.isClosed) {
            hikariDataSource.close()
        }
    }

    companion object {
        @Volatile
        private var instance: DatabaseManager? = null

        /**
         * Get the singleton DatabaseManager for production use (default "askimo.db" file).
         */
        @Synchronized
        fun getInstance(): DatabaseManager = instance ?: DatabaseManager().also { instance = it }

        /**
         * Create an in-memory test DatabaseManager — useful when SQLite native libraries
         * aren't available, or for faster test execution without file I/O.
         *
         * @param testScope The test class instance (typically `this` in companion object)
         * @return A new DatabaseManager with an in-memory database
         */
        fun getInMemoryTestInstance(testScope: Any): DatabaseManager {
            val testDbName = "test_${testScope.javaClass.simpleName}_${System.nanoTime()}_memory.db"
            return DatabaseManager(databaseFileName = testDbName, useInMemory = true)
        }

        /**
         * Reset the singleton instance (testing only) — closes the current instance
         * and clears the reference.
         */
        @Synchronized
        fun reset() {
            instance?.close()
            instance = null
        }
    }
}
