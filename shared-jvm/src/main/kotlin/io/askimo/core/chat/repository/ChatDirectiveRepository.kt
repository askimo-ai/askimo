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
import io.askimo.core.chat.domain.DIRECTIVE_CONTENT_MAX_LENGTH
import io.askimo.core.chat.domain.DIRECTIVE_NAME_MAX_LENGTH
import io.askimo.core.chat.domain.DirectiveScope
import io.askimo.core.db.AbstractRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.db.sqldelight.Chat_directives
import io.askimo.core.logging.logger
import io.askimo.core.util.TimeUtil
import io.askimo.core.util.walkResourceDirectory
import java.nio.file.Files
import java.util.UUID
import kotlin.time.Clock

/** Simple DTO for deserializing the bundled directive YAML files. */
private data class DirectiveYaml(val name: String = "", val content: String = "")

private val directiveYamlMapper: ObjectMapper = ObjectMapper(YAMLFactory())
    .registerModule(KotlinModule.Builder().build())
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

/**
 * Maps a generated [Chat_directives] row to the shared [ChatDirective] domain object.
 */
private fun Chat_directives.toChatDirective(): ChatDirective = ChatDirective(
    id = id,
    name = name,
    content = content,
    scope = runCatching { DirectiveScope.valueOf(scope) }.getOrDefault(DirectiveScope.PERSONAL),
    createdBy = created_by,
    createdAt = TimeUtil.parseInstant(created_at),
    updatedAt = TimeUtil.parseInstant(updated_at),
    deletedAt = deleted_at?.let { TimeUtil.parseInstant(it) },
)

private fun validateDirectiveLengths(directive: ChatDirective) {
    require(directive.name.length <= DIRECTIVE_NAME_MAX_LENGTH) {
        "Directive name cannot exceed $DIRECTIVE_NAME_MAX_LENGTH characters"
    }
    require(directive.content.length <= DIRECTIVE_CONTENT_MAX_LENGTH) {
        "Directive content cannot exceed $DIRECTIVE_CONTENT_MAX_LENGTH characters"
    }
}

class ChatDirectiveRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractRepository(databaseManager) {

    val log = logger<ChatDirectiveRepository>()
    private val queries get() = db.chatDirectivesQueries

    /**
     * Save a new directive or update existing one.
     * @throws IllegalArgumentException if name or content exceed max length
     */
    fun save(directive: ChatDirective): ChatDirective {
        validateDirectiveLengths(directive)

        db.transaction {
            val existing = queries.selectById(directive.id).executeAsOneOrNull()
            if (existing == null) {
                queries.upsertInsert(
                    id = directive.id,
                    name = directive.name,
                    content = directive.content,
                    scope = directive.scope.name,
                    createdBy = directive.createdBy,
                    createdAt = directive.createdAt.toString(),
                    updatedAt = directive.updatedAt.toString(),
                )
            } else {
                queries.upsertUpdate(
                    name = directive.name,
                    content = directive.content,
                    scope = directive.scope.name,
                    createdBy = directive.createdBy,
                    createdAt = directive.createdAt.toString(),
                    id = directive.id,
                )
            }
        }

        return directive
    }

    /** Get a directive by id. */
    fun get(id: String): ChatDirective? = queries.selectById(id).executeAsOneOrNull()?.toChatDirective()

    /** List all directives, ordered by name. */
    fun list(): List<ChatDirective> = queries.selectAllOrderedByName().executeAsList().map { it.toChatDirective() }

    /**
     * Update an existing directive.
     * @return true if updated, false if directive doesn't exist
     */
    fun update(directive: ChatDirective): Boolean {
        validateDirectiveLengths(directive)

        return queries.updateDirective(
            name = directive.name,
            content = directive.content,
            updatedAt = Clock.System.now().toString(),
            id = directive.id,
        ).value > 0
    }

    /**
     * Delete a directive by id.
     * @return true if deleted, false if directive doesn't exist
     */
    fun delete(id: String): Boolean = queries.deleteById(id).value > 0

    /** Check if a directive exists by id. */
    fun exists(id: String): Boolean = queries.selectById(id).executeAsOneOrNull() != null

    /** Get multiple directives by ids. */
    fun getByIds(ids: List<String>): List<ChatDirective> {
        if (ids.isEmpty()) return emptyList()
        return queries.selectByIds(ids).executeAsList().map { it.toChatDirective() }
    }

    /** Get multiple directives by names. */
    fun getByNames(names: List<String>): List<ChatDirective> {
        if (names.isEmpty()) return emptyList()
        return queries.selectByNames(names).executeAsList().map { it.toChatDirective() }
    }

    /**
     * Find a directive by session ID.
     * Joins with chat_sessions table to find the directive associated with the given session.
     * @param sessionId The session ID to look up
     * @return The directive associated with the session, or null if not found or session has no directive
     */
    fun findDirectiveBySessionId(sessionId: String): ChatDirective? = queries.findDirectiveBySessionId(sessionId).executeAsOneOrNull()?.toChatDirective()

    /**
     * Returns all PERSONAL directives whose [syncedAt] is NULL or older than [updatedAt],
     * meaning they have local changes that have not yet been pushed to the server.
     *
     * TEAM directives are excluded — they are read-only on the client and must never
     * be pushed back to the server.
     */
    fun getUnsyncedDirectives(limit: Int = 50): List<ChatDirective> = queries.selectAllOrderedByUpdatedAt().executeAsList()
        .mapNotNull { row ->
            // Never push TEAM directives — they are read-only on the client
            if (row.scope == DirectiveScope.TEAM.name) return@mapNotNull null
            if (row.synced_at == null || row.updated_at > row.synced_at) row.toChatDirective() else null
        }
        .take(limit)

    /** Stamps [syncedAt] with the current timestamp to record a successful push. */
    fun markSynced(directiveId: String): Boolean = queries.markSynced(Clock.System.now().toString(), directiveId).value > 0

    /**
     * Merges directives received from the server into the local database.
     * Inserts rows that don't exist locally; overwrites rows where the server
     * version is strictly newer than the locally stored [updatedAt].
     */
    fun upsertFromServer(directives: List<ChatDirective>) {
        if (directives.isEmpty()) return

        db.transaction {
            val nowStr = Clock.System.now().toString()
            val ids = directives.map { it.id }

            val existingById = queries.selectExistingByIds(ids).executeAsList()
                .associate { it.id to TimeUtil.parseInstant(it.updated_at) }

            for (directive in directives) {
                validateDirectiveLengths(directive)
                val storedUpdatedAt = existingById[directive.id]

                if (storedUpdatedAt == null) {
                    queries.insertFromServer(
                        id = directive.id,
                        name = directive.name,
                        content = directive.content,
                        scope = directive.scope.name,
                        createdBy = directive.createdBy,
                        createdAt = directive.createdAt.toString(),
                        updatedAt = directive.updatedAt.toString(),
                        deletedAt = directive.deletedAt?.toString(),
                        syncedAt = nowStr,
                    )
                } else if (directive.updatedAt > storedUpdatedAt) {
                    queries.updateFromServer(
                        name = directive.name,
                        content = directive.content,
                        scope = directive.scope.name,
                        createdBy = directive.createdBy,
                        updatedAt = directive.updatedAt.toString(),
                        deletedAt = directive.deletedAt?.toString(),
                        syncedAt = nowStr,
                        id = directive.id,
                    )
                }
            }
        }
    }

    /**
     * Permanently removes a directive from the local database.
     * Called when the server signals that a directive has been soft-deleted.
     */
    fun hardDelete(directiveId: String): Boolean = queries.deleteById(directiveId).value > 0

    /**
     * Seeds built-in default directives from `/directives/` classpath resources
     * into the local database.
     */
    fun seedDefaultDirectives() {
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
                val stableId = UUID.nameUUIDFromBytes(
                    "default:${dto.name.trim().lowercase()}".toByteArray(Charsets.UTF_8),
                ).toString()
                save(ChatDirective(id = stableId, name = dto.name.trim(), content = dto.content.trim()))
                seeded++
            }.onFailure { e ->
                log.warn("Skipped default directive '{}': {}", path.fileName, e.message)
            }
        }
        log.debug("Seeded {} default directive(s)", seeded)
    }
}
