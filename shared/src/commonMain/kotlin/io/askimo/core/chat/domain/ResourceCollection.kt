/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.domain

import io.askimo.core.rag.state.IndexStatus
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Where a [ResourceCollection] row's content/indexing lives.
 */
enum class ResourceCollectionOrigin {
    /** On-device collection, indexed and retrieved entirely locally (community RAG). */
    LOCAL,

    /**
     * Synced read-only from a remote, server-managed ResourceCollection (used by
     * closed-source editions). Indexing and retrieval happen entirely on that
     * remote backend — [ResourceCollection.knowledgeSources] is always empty for
     * these rows, and they must never enter the personal push-sync protocol
     * (`ResourceCollectionRepository.getUnsyncedCollections` in `shared-jvm`).
     */
    TEAM_MANAGED,
}

/**
 * A collection of indexed knowledge resources (files, URLs, documents).
 * Collections are reusable across sessions and can be used on-demand via @mention
 * or persistently via chip selection in any chat.
 *
 * Collections are independent of projects — they exist to provide RAG context
 * to any session that requests them.
 */
data class ResourceCollection(
    val id: String,
    val name: String, // "HR Handbook", "Tech Docs", etc
    val description: String? = null,
    val knowledgeSources: List<KnowledgeSourceConfig> = emptyList(),
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
    val isSystemCollection: Boolean = false, // Org-wide vs personal
    /** Last known indexing status, persisted so list/detail views show it without a live event. */
    val indexStatus: IndexStatus = IndexStatus.NOT_STARTED,
    /** Timestamp of the last successful index completion, or null if never indexed. */
    val lastIndexedAt: Instant? = null,
    /** Error from the last failed indexing attempt, or null if it succeeded (or never ran). */
    val indexError: String? = null,
    /** Local on-device vs. synced read-only from an askimo-team tenant — see [ResourceCollectionOrigin]. */
    val origin: ResourceCollectionOrigin = ResourceCollectionOrigin.LOCAL,
)
