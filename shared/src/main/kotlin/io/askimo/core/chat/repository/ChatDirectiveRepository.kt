/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.askimo.core.chat.domain.ChatDirective
import io.askimo.core.chat.domain.ChatDirectivesTable
import io.askimo.core.chat.domain.ChatSessionsTable
import io.askimo.core.chat.domain.DIRECTIVE_CONTENT_MAX_LENGTH
import io.askimo.core.chat.domain.DIRECTIVE_NAME_MAX_LENGTH
import io.askimo.core.chat.domain.DirectiveScope
import io.askimo.core.db.AbstractSQLiteRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.logging.logger
import io.askimo.core.util.walkResourceDirectory
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.nio.file.Files
import java.time.Instant

/** Simple DTO for deserializing the bundled directive YAML files. */
private data class DirectiveYaml(val name: String = "", val content: String = "")

private val directiveYamlMapper: ObjectMapper = ObjectMapper(YAMLFactory())
    .registerModule(KotlinModule.Builder().build())
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

/**
 * Extension function to map an Exposed ResultRow to a ChatDirective object.
 * Eliminates duplication of mapping logic throughout the repository.
 */
private fun ResultRow.toChatDirective(): ChatDirective = ChatDirective(
    id = this[ChatDirectivesTable.id],
    name = this[ChatDirectivesTable.name],
    content = this[ChatDirectivesTable.content],
    scope = runCatching { DirectiveScope.valueOf(this[ChatDirectivesTable.scope]) }
        .getOrDefault(DirectiveScope.PERSONAL),
    createdBy = this[ChatDirectivesTable.createdBy],
    createdAt = this[ChatDirectivesTable.createdAt],
    updatedAt = this[ChatDirectivesTable.updatedAt],
    deletedAt = this[ChatDirectivesTable.deletedAt],
)

private fun validateDirectiveLengths(directive: ChatDirective) {
    require(directive.name.length <= DIRECTIVE_NAME_MAX_LENGTH) {
        "Directive name cannot exceed $DIRECTIVE_NAME_MAX_LENGTH characters"
    }
    require(directive.content.length <= DIRECTIVE_CONTENT_MAX_LENGTH) {
        "Directive content cannot exceed $DIRECTIVE_CONTENT_MAX_LENGTH characters"
    }
}

/**
 * Repository for managing chat directives stored in SQLite database.
 */
class ChatDirectiveRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractSQLiteRepository(databaseManager) {

    /**
     * Save a new directive or update existing one.
     * @throws IllegalArgumentException if name or content exceed max length
     */
    fun save(directive: ChatDirective): ChatDirective {
        validateDirectiveLengths(directive)

        transaction(database) {
            ChatDirectivesTable.upsert {
                it[id] = directive.id
                it[name] = directive.name
                it[content] = directive.content
                it[scope] = directive.scope.name
                it[createdBy] = directive.createdBy
                it[createdAt] = directive.createdAt
            }
        }

        return directive
    }

    /**
     * Get a directive by id.
     */
    fun get(id: String): ChatDirective? = transaction(database) {
        ChatDirectivesTable
            .selectAll()
            .where { ChatDirectivesTable.id eq id }
            .singleOrNull()
            ?.toChatDirective()
    }

    /**
     * List all directives, ordered by name.
     */
    fun list(): List<ChatDirective> = transaction(database) {
        ChatDirectivesTable
            .selectAll()
            .orderBy(ChatDirectivesTable.name to SortOrder.ASC)
            .map { it.toChatDirective() }
    }

    /**
     * Update an existing directive.
     * @return true if updated, false if directive doesn't exist
     */
    fun update(directive: ChatDirective): Boolean {
        validateDirectiveLengths(directive)

        return transaction(database) {
            ChatDirectivesTable.update({ ChatDirectivesTable.id eq directive.id }) {
                it[name] = directive.name
                it[content] = directive.content
            } > 0
        }
    }

    /**
     * Delete a directive by id.
     * @return true if deleted, false if directive doesn't exist
     */
    fun delete(id: String): Boolean = transaction(database) {
        ChatDirectivesTable.deleteWhere { ChatDirectivesTable.id eq id } > 0
    }

    /**
     * Check if a directive exists by id.
     */
    fun exists(id: String): Boolean = transaction(database) {
        ChatDirectivesTable
            .selectAll()
            .where { ChatDirectivesTable.id eq id }
            .empty()
            .not()
    }

    /**
     * Get multiple directives by ids.
     */
    fun getByIds(ids: List<String>): List<ChatDirective> = getByColumn(
        table = ChatDirectivesTable,
        column = ChatDirectivesTable.id,
        values = ids,
        orderBy = ChatDirectivesTable.name to SortOrder.ASC,
    ) { it.toChatDirective() }

    /**
     * Get multiple directives by names.
     */
    fun getByNames(names: List<String>): List<ChatDirective> = getByColumn(
        table = ChatDirectivesTable,
        column = ChatDirectivesTable.name,
        values = names,
        orderBy = ChatDirectivesTable.name to SortOrder.ASC,
    ) { it.toChatDirective() }

    /**
     * Find a directive by session ID.
     * Joins with chat_sessions table to find the directive associated with the given session.
     * @param sessionId The session ID to look up
     * @return The directive associated with the session, or null if not found or session has no directive
     */
    fun findDirectiveBySessionId(sessionId: String): ChatDirective? = transaction(database) {
        ChatDirectivesTable
            .join(ChatSessionsTable, JoinType.INNER, ChatDirectivesTable.id, ChatSessionsTable.directiveId)
            .selectAll()
            .where { ChatSessionsTable.id eq sessionId }
            .singleOrNull()
            ?.toChatDirective()
    }

    /**
     * Returns all PERSONAL directives whose [syncedAt] is NULL or older than [updatedAt],
     * meaning they have local changes that have not yet been pushed to the server.
     *
     * TEAM directives are excluded — they are read-only on the client and must never
     * be pushed back to the server.
     */
    fun getUnsyncedDirectives(limit: Int = 50): List<ChatDirective> = transaction(database) {
        ChatDirectivesTable
            .selectAll()
            .orderBy(ChatDirectivesTable.updatedAt, SortOrder.ASC)
            .mapNotNull { row ->
                // Never push TEAM directives — they are read-only on the client
                if (row[ChatDirectivesTable.scope] == DirectiveScope.TEAM.name) return@mapNotNull null
                val syncedAt = row[ChatDirectivesTable.syncedAt]
                val updatedAt = row[ChatDirectivesTable.updatedAt].toString()
                if (syncedAt == null || updatedAt > syncedAt) row.toChatDirective() else null
            }
            .take(limit)
    }

    /**
     * Stamps [syncedAt] with the current timestamp to record a successful push.
     */
    fun markSynced(directiveId: String): Boolean = transaction(database) {
        ChatDirectivesTable.update({ ChatDirectivesTable.id eq directiveId }) {
            it[syncedAt] = Instant.now().toString()
        } > 0
    }

    /**
     * Merges directives received from the server into the local database.
     * Inserts rows that don't exist locally; overwrites rows where the server
     * version is strictly newer than the locally stored [updatedAt].
     */
    fun upsertFromServer(directives: List<ChatDirective>) {
        if (directives.isEmpty()) return

        transaction(database) {
            val nowStr = Instant.now().toString()
            val ids = directives.map { it.id }

            val existingById = ChatDirectivesTable
                .selectAll()
                .where { ChatDirectivesTable.id inList ids }
                .associate { row ->
                    row[ChatDirectivesTable.id] to row[ChatDirectivesTable.updatedAt]
                }

            for (directive in directives) {
                validateDirectiveLengths(directive)
                val storedUpdatedAt = existingById[directive.id]

                if (storedUpdatedAt == null) {
                    ChatDirectivesTable.insert {
                        it[id] = directive.id
                        it[name] = directive.name
                        it[content] = directive.content
                        it[scope] = directive.scope.name
                        it[createdBy] = directive.createdBy
                        it[createdAt] = directive.createdAt
                        it[updatedAt] = directive.updatedAt
                        it[deletedAt] = directive.deletedAt
                        it[syncedAt] = nowStr
                    }
                } else if (directive.updatedAt.isAfter(storedUpdatedAt)) {
                    ChatDirectivesTable.update({ ChatDirectivesTable.id eq directive.id }) {
                        it[name] = directive.name
                        it[content] = directive.content
                        it[scope] = directive.scope.name
                        it[createdBy] = directive.createdBy
                        it[updatedAt] = directive.updatedAt
                        it[deletedAt] = directive.deletedAt
                        it[syncedAt] = nowStr
                    }
                }
            }
        }
    }

    /**
     * Permanently removes a directive from the local database.
     * Called when the server signals that a directive has been soft-deleted.
     */
    fun hardDelete(directiveId: String): Boolean = transaction(database) {
        ChatDirectivesTable.deleteWhere { ChatDirectivesTable.id eq directiveId } > 0
    }

    /**
     * Seeds built-in default directives from `/directives/` classpath resources
     * into the local database. Only runs when the directives table is empty, so it
     * executes once on first launch and never overwrites user-created directives.
     */
    fun seedDefaultDirectives() {
        val log = logger<ChatDirectiveRepository>()
        val resourceUrl = ChatDirectiveRepository::class.java.getResource("/directives/")
        if (resourceUrl == null) {
            log.debug("No /directives/ resource directory found on classpath — skipping seed")
            return
        }

        var seeded = 0
        walkResourceDirectory(resourceUrl, "/directives/", "yml") { path ->
            runCatching {
                val yaml = Files.readString(path)
                val dto = directiveYamlMapper.readValue(yaml, DirectiveYaml::class.java)
                if (dto.name.isBlank() || dto.content.isBlank()) return@runCatching
                save(ChatDirective(name = dto.name.trim(), content = dto.content.trim()))
                seeded++
            }.onFailure { e ->
                log.warn("Skipped default directive '{}': {}", path.fileName, e.message)
            }
        }
        log.debug("Seeded {} default directive(s)", seeded)
    }
}
