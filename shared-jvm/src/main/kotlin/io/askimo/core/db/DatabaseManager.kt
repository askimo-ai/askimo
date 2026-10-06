/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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
import io.askimo.core.db.sqldelight.Agent_run_history
import io.askimo.core.db.sqldelight.Chat_directives
import io.askimo.core.db.sqldelight.Chat_messages
import io.askimo.core.db.sqldelight.Chat_sessions
import io.askimo.core.db.sqldelight.File_attachments
import io.askimo.core.db.sqldelight.File_segments
import io.askimo.core.db.sqldelight.Index_file_state
import io.askimo.core.db.sqldelight.Llm_usage_records
import io.askimo.core.db.sqldelight.Model_classifications
import io.askimo.core.db.sqldelight.Plan_executions
import io.askimo.core.db.sqldelight.Projects
import io.askimo.core.db.sqldelight.Resource_collections
import io.askimo.core.db.sqldelight.Session_memory
import io.askimo.core.db.sqldelight.User_interests
import io.askimo.core.db.sqldelight.User_memory
import io.askimo.core.db.sqldelight.User_preferences
import io.askimo.core.db.sqldelight.User_profiles
import io.askimo.core.db.sqldelight.Workspaces
import io.askimo.core.db.sqldelight.generated.AskimoDatabase
import io.askimo.core.plan.repository.PlanExecutionRepository
import io.askimo.core.rag.state.IndexStateRepository
import io.askimo.core.telemetry.LlmUsageRepository
import io.askimo.core.user.repository.UserProfileRepository
import io.askimo.core.util.AskimoHome
import java.util.Properties

/**
 * Singleton manager for the SQLDelight-backed SQLite connection.
 */
class DatabaseManager private constructor(
    val databaseFileName: String = "askimo.db",
    useInMemory: Boolean = false,
) : AutoCloseable {

    /** Underlying SQLDelight JDBC driver — a single pooled connection is sufficient for SQLite. */
    val driver: JdbcSqliteDriver = createDriver(databaseFileName, useInMemory)

    val db: AskimoDatabase = AskimoDatabase(
        driver = driver,
        agent_run_historyAdapter = Agent_run_history.Adapter(
            created_atAdapter = InstantColumnAdapter,
        ),
        chat_directivesAdapter = Chat_directives.Adapter(
            created_atAdapter = InstantColumnAdapter,
            updated_atAdapter = InstantColumnAdapter,
            deleted_atAdapter = InstantColumnAdapter,
            synced_atAdapter = InstantColumnAdapter,
        ),
        chat_messagesAdapter = Chat_messages.Adapter(
            created_atAdapter = InstantColumnAdapter,
            synced_atAdapter = InstantColumnAdapter,
        ),
        chat_sessionsAdapter = Chat_sessions.Adapter(
            created_atAdapter = InstantColumnAdapter,
            updated_atAdapter = InstantColumnAdapter,
            synced_atAdapter = InstantColumnAdapter,
        ),
        file_attachmentsAdapter = File_attachments.Adapter(
            created_atAdapter = InstantColumnAdapter,
        ),
        file_segmentsAdapter = File_segments.Adapter(
            created_atAdapter = InstantColumnAdapter,
        ),
        index_file_stateAdapter = Index_file_state.Adapter(
            indexed_atAdapter = InstantColumnAdapter,
        ),
        llm_usage_recordsAdapter = Llm_usage_records.Adapter(
            timestampAdapter = InstantColumnAdapter,
        ),
        model_classificationsAdapter = Model_classifications.Adapter(
            created_atAdapter = InstantColumnAdapter,
            updated_atAdapter = InstantColumnAdapter,
        ),
        plan_executionsAdapter = Plan_executions.Adapter(
            created_atAdapter = InstantColumnAdapter,
            updated_atAdapter = InstantColumnAdapter,
        ),
        projectsAdapter = Projects.Adapter(
            created_atAdapter = InstantColumnAdapter,
            updated_atAdapter = InstantColumnAdapter,
            synced_atAdapter = InstantColumnAdapter,
        ),
        resource_collectionsAdapter = Resource_collections.Adapter(
            created_atAdapter = InstantColumnAdapter,
            updated_atAdapter = InstantColumnAdapter,
            synced_atAdapter = InstantColumnAdapter,
            last_indexed_atAdapter = InstantColumnAdapter,
        ),
        session_memoryAdapter = Session_memory.Adapter(
            last_updatedAdapter = InstantColumnAdapter,
            created_atAdapter = InstantColumnAdapter,
        ),
        user_interestsAdapter = User_interests.Adapter(
            created_atAdapter = InstantColumnAdapter,
        ),
        user_memoryAdapter = User_memory.Adapter(
            last_updatedAdapter = InstantColumnAdapter,
            created_atAdapter = InstantColumnAdapter,
        ),
        user_preferencesAdapter = User_preferences.Adapter(
            created_atAdapter = InstantColumnAdapter,
            updated_atAdapter = InstantColumnAdapter,
        ),
        user_profilesAdapter = User_profiles.Adapter(
            created_atAdapter = InstantColumnAdapter,
            updated_atAdapter = InstantColumnAdapter,
        ),
        workspacesAdapter = Workspaces.Adapter(
            created_atAdapter = InstantColumnAdapter,
            last_used_atAdapter = InstantColumnAdapter,
        ),
    )

    private fun createDriver(databaseFileName: String, useInMemory: Boolean): JdbcSqliteDriver {
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

        val properties = Properties().apply {
            setProperty("foreign_keys", "true")
            setProperty("journal_mode", "WAL")
            setProperty("busy_timeout", "5000")
            setProperty("synchronous", "NORMAL")
        }

        val driver = JdbcSqliteDriver(jdbcUrl, properties)
        migrateIfNeeded(driver)
        return driver
    }

    /**
     * Applies [AskimoDatabase.Schema] migrations (backed by `shared/src/main/sqldelight/migrations`
     * + [SqlDelightSchemaMigrations.callbacks]) up to the latest version, tracked via the same
     * `PRAGMA user_version` counter used by [io.askimo.core.db.DatabaseManager]. Always migrates
     * from the stored version (including 0) through every `.sqm` step in order — mirrors the old
     * JDBC loop's "replay everything, idempotently" behavior for legacy/ad-hoc databases.
     */
    private fun migrateIfNeeded(driver: JdbcSqliteDriver) {
        val oldVersion = currentUserVersion(driver)
        val newVersion = AskimoDatabase.Schema.version
        if (oldVersion < newVersion) {
            AskimoDatabase.Schema.migrate(driver, oldVersion, newVersion, *SqlDelightSchemaMigrations.callbacks())
            setUserVersion(driver, newVersion)
        }
    }

    private fun currentUserVersion(driver: JdbcSqliteDriver): Long = driver.executeQuery(
        null,
        "PRAGMA user_version",
        { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0) ?: 0L)
        },
        0,
    ).value

    private fun setUserVersion(driver: JdbcSqliteDriver, version: Long) {
        driver.execute(null, "PRAGMA user_version = $version", 0)
    }

    override fun close() {
        driver.close()
    }

    private val _chatSessionRepository: ChatSessionRepository by lazy {
        ChatSessionRepository(this)
    }

    fun getChatSessionRepository(): ChatSessionRepository = _chatSessionRepository

    private val _chatMessageAttachmentRepository: ChatMessageAttachmentRepository by lazy {
        ChatMessageAttachmentRepository(this)
    }

    fun getChatMessageAttachmentRepository(): ChatMessageAttachmentRepository = _chatMessageAttachmentRepository

    private val _chatMessageRepository: ChatMessageRepository by lazy {
        ChatMessageRepository(this, _chatMessageAttachmentRepository)
    }

    fun getChatMessageRepository(): ChatMessageRepository = _chatMessageRepository

    private val _chatDirectiveRepository: ChatDirectiveRepository by lazy {
        ChatDirectiveRepository(this)
    }

    fun getChatDirectiveRepository(): ChatDirectiveRepository = _chatDirectiveRepository

    private val _modelClassificationRepository: ModelClassificationRepository by lazy {
        ModelClassificationRepository(this)
    }

    fun getModelClassificationRepository(): ModelClassificationRepository = _modelClassificationRepository

    private val _sessionMemoryRepository: SessionMemoryRepository by lazy {
        SessionMemoryRepository(this)
    }

    fun getSessionMemoryRepository(): SessionMemoryRepository = _sessionMemoryRepository

    private val _projectRepository: ProjectRepository by lazy {
        ProjectRepository(this)
    }

    fun getProjectRepository(): ProjectRepository = _projectRepository

    private val _resourceCollectionRepository: ResourceCollectionRepository by lazy {
        ResourceCollectionRepository(this)
    }

    fun getResourceCollectionRepository(): ResourceCollectionRepository = _resourceCollectionRepository

    private val _resourceSegmentRepository: ResourceSegmentRepository by lazy {
        ResourceSegmentRepository(this)
    }

    fun getResourceSegmentRepository(): ResourceSegmentRepository = _resourceSegmentRepository

    private val _userMemoryRepository: UserMemoryRepository by lazy {
        UserMemoryRepository(this)
    }

    fun getUserMemoryRepository(): UserMemoryRepository = _userMemoryRepository

    private val _userProfileRepository: UserProfileRepository by lazy {
        UserProfileRepository(this)
    }

    fun getUserProfileRepository(): UserProfileRepository = _userProfileRepository

    private val _planExecutionRepository: PlanExecutionRepository by lazy {
        PlanExecutionRepository(this)
    }

    fun getPlanExecutionRepository(): PlanExecutionRepository = _planExecutionRepository

    private val _agentRunHistoryRepository: AgentRunHistoryRepository by lazy {
        AgentRunHistoryRepository(this)
    }

    fun getAgentRunHistoryRepository(): AgentRunHistoryRepository = _agentRunHistoryRepository

    private val _workspaceRepository: WorkspaceRepository by lazy {
        WorkspaceRepository(this)
    }

    fun getWorkspaceRepository(): WorkspaceRepository = _workspaceRepository

    private val _llmUsageRepository: LlmUsageRepository by lazy {
        LlmUsageRepository(this)
    }

    fun getLlmUsageRepository(): LlmUsageRepository = _llmUsageRepository

    private val _indexStateRepository: IndexStateRepository by lazy {
        IndexStateRepository(this)
    }

    fun getIndexStateRepository(): IndexStateRepository = _indexStateRepository

    companion object {
        @Volatile
        private var instance: DatabaseManager? = null

        /** Get the singleton manager for production use (default "askimo.db" file). */
        @Synchronized
        fun getInstance(): DatabaseManager = instance ?: DatabaseManager().also { instance = it }

        /**
         * Create an in-memory test manager — mirrors
         * [io.askimo.core.db.DatabaseManager.getInMemoryTestInstance].
         */
        fun getInMemoryTestInstance(testScope: Any): DatabaseManager {
            val testDbName = "test_${testScope.javaClass.simpleName}_${System.nanoTime()}_memory.db"
            return DatabaseManager(databaseFileName = testDbName, useInMemory = true)
        }

        /** Reset the singleton instance (testing only). */
        @Synchronized
        fun reset() {
            instance?.close()
            instance = null
        }
    }
}
