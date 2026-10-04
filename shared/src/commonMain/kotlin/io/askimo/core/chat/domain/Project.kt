/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.domain

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Represents a project that groups chat sessions and provides RAG context
 * through indexed knowledge sources (files, web pages, etc.).
 *
 * Projects enable knowledge base organization:
 * - Each project has its own Lucene index for RAG
 * - Sessions belong to projects to share project-level context
 * - Knowledge sources define where content comes from (local files, web, SEC, etc.)
 */
data class Project(
    val id: String,
    val name: String,
    val description: String? = null,
    val knowledgeSources: List<KnowledgeSourceConfig>,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
    val isStarred: Boolean = false,
    /**
     * Id of the directive automatically applied to new chats started within this project.
     * Takes precedence over the user's global default directive. Null means no project-level default.
     */
    val defaultDirectiveId: String? = null,
)

