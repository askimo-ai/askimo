/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.domain

import io.askimo.core.db.sqliteInstant
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant
import java.util.UUID

/**
 * Discriminates who owns and can mutate a directive.
 *
 * - [PERSONAL] — owned by an individual user; synced across their own devices only.
 * - [TEAM]     — created by a tenant admin; distributed read-only to all tenant members
 *                via the pull delta. Clients must never push TEAM directives back.
 */
enum class DirectiveScope { PERSONAL, TEAM }

/**
 * Represents a custom instruction/directive that users can apply to chat sessions
 * to influence AI behavior (tone, format, style, etc.)
 */
data class ChatDirective(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val content: String,
    /** Ownership scope — defaults to [DirectiveScope.PERSONAL] for all existing rows. */
    val scope: DirectiveScope = DirectiveScope.PERSONAL,
    /** userId of the creator; null for legacy personal directives seeded locally. */
    val createdBy: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null,
)

const val DIRECTIVE_NAME_MAX_LENGTH = 128
const val DIRECTIVE_CONTENT_MAX_LENGTH = 8192

/**
 * Exposed table definition for chat_directives.
 * Co-located with domain class for easier maintenance and foreign key references.
 */
object ChatDirectivesTable : Table("chat_directives") {
    val id = varchar("id", 36)
    val name = varchar("name", DIRECTIVE_NAME_MAX_LENGTH)
    val content = varchar("content", DIRECTIVE_CONTENT_MAX_LENGTH)

    /** PERSONAL (default) or TEAM — stored as plain string for SQLite compatibility. */
    val scope = varchar("scope", 16).default(DirectiveScope.PERSONAL.name)

    /** userId of creator; null for locally-seeded default directives. */
    val createdBy = varchar("created_by", 36).nullable()
    val createdAt = sqliteInstant("created_at")
    val updatedAt = sqliteInstant("updated_at")
    val deletedAt = sqliteInstant("deleted_at").nullable()

    /** ISO-8601 UTC timestamp of the last successful push to the sync server. NULL = never synced. */
    val syncedAt = varchar("synced_at", 32).nullable()

    override val primaryKey = PrimaryKey(id)
}
