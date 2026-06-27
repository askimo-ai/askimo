/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.service

import io.askimo.core.chat.domain.ChatDirective
import io.askimo.core.chat.domain.DirectiveScope
import io.askimo.core.chat.repository.ChatDirectiveRepository
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.DirectiveDeletedEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Result of a directive import operation.
 */
data class DirectiveImportResult(
    val imported: Int,
    /** Directives that were renamed due to a name collision (e.g. "my-directive (2)"). */
    val renamed: Int,
    /** Directives skipped because they had blank name or content. */
    val skipped: Int,
)

// ── Internal serialization models ────────────────────────────────────────────

@Serializable
private data class DirectiveExportFile(
    val version: Int = 1,
    val directives: List<DirectiveExportItem>,
)

@Serializable
private data class DirectiveExportItem(
    val name: String,
    val content: String,
)

private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

/**
 * Service for managing chat directives and building system prompts for chat sessions.
 */
class ChatDirectiveService(
    private val repository: ChatDirectiveRepository,
) {
    /**
     * Create a new directive.
     */
    fun createDirective(
        name: String,
        content: String,
    ): ChatDirective {
        val directive = ChatDirective(
            name = name,
            content = content,
        )
        return repository.save(directive)
    }

    /**
     * Update an existing directive.
     *
     * TEAM-scoped directives are read-only on the client — this method returns
     * false without making any changes if [id] resolves to a TEAM directive.
     *
     * @return true if updated successfully
     */
    fun updateDirective(
        id: String,
        name: String,
        content: String,
    ): Boolean {
        val existing = repository.get(id) ?: return false
        if (existing.scope == DirectiveScope.TEAM) return false // TEAM directives are read-only
        val directive = existing.copy(
            name = name,
            content = content,
        )
        return repository.update(directive)
    }

    /**
     * Delete a directive.
     *
     * TEAM-scoped directives are managed by the server and cannot be deleted
     * locally — they would simply reappear on the next pull. Returns false
     * without deleting if [id] resolves to a TEAM directive.
     *
     * Emits [DirectiveDeletedEvent] on success so the sync layer can propagate
     * the deletion to the server without tight coupling.
     *
     * @return true if deleted successfully
     */
    fun deleteDirective(id: String): Boolean {
        val existing = repository.get(id) ?: return false
        if (existing.scope == DirectiveScope.TEAM) return false // TEAM directives are read-only
        val deleted = repository.delete(id)
        if (deleted) {
            EventBus.post(DirectiveDeletedEvent(directiveId = id))
        }
        return deleted
    }

    /**
     * List all directives.
     */
    fun listAllDirectives(): List<ChatDirective> = repository.list()

    /**
     * Serializes all (or a given subset of) directives to a shareable JSON string.
     */
    fun exportToJson(directives: List<ChatDirective> = repository.list()): String {
        val file = DirectiveExportFile(
            directives = directives.map { DirectiveExportItem(it.name, it.content) },
        )
        return json.encodeToString(file)
    }

    /**
     * Parses [jsonString] and persists each directive.
     * - Blank name/content entries are skipped.
     * - Name collisions are resolved by appending a numeric suffix, e.g. "my-directive (2)".
     *
     * @return A [DirectiveImportResult] summarising the operation.
     * @throws IllegalArgumentException if [jsonString] cannot be parsed.
     */
    fun importFromJson(jsonString: String): DirectiveImportResult {
        val exportFile = try {
            json.decodeFromString<DirectiveExportFile>(jsonString)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid directive file format", e)
        }

        val existingNames = repository.list().map { it.name }.toMutableSet()
        var imported = 0
        var renamed = 0
        var skipped = 0

        for (item in exportFile.directives) {
            if (item.name.isBlank() || item.content.isBlank()) {
                skipped++
                continue
            }

            var finalName = item.name
            if (finalName in existingNames) {
                var counter = 2
                while ("$finalName ($counter)" in existingNames) counter++
                finalName = "$finalName ($counter)"
                renamed++
            }

            repository.save(ChatDirective(name = finalName, content = item.content))
            existingNames.add(finalName)
            imported++
        }

        return DirectiveImportResult(imported = imported, renamed = renamed, skipped = skipped)
    }
}
