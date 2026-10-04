/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.domain.KnowledgeSourceSerializer
import io.askimo.core.chat.domain.ResourceCollection
import io.askimo.core.db.AbstractRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.db.Pageable
import io.askimo.core.db.resolvePageParams
import io.askimo.core.db.sqldelight.Resource_collections
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.PushDataToServerEvent
import io.askimo.core.logging.logger
import io.askimo.core.rag.state.IndexStatus
import io.askimo.core.util.TimeUtil
import java.util.UUID
import kotlin.time.Clock

/**
 * Maps a generated [Resource_collections] row to the shared [ResourceCollection] domain object.
 */
private fun Resource_collections.toResourceCollection(): ResourceCollection = ResourceCollection(
    id = id,
    name = name,
    description = description,
    knowledgeSources = KnowledgeSourceSerializer.deserialize(knowledge_sources_config),
    createdAt = TimeUtil.parseInstant(created_at),
    updatedAt = TimeUtil.parseInstant(updated_at),
    isSystemCollection = is_system_collection == 1L,
    indexStatus = runCatching { IndexStatus.valueOf(index_status) }.getOrDefault(IndexStatus.NOT_STARTED),
    lastIndexedAt = last_indexed_at?.let { TimeUtil.parseInstant(it) },
    indexError = index_error,
)

/**
 * Sortable columns for paged collection listings. Kept alongside the repository (rather
 * than in the UI layer) since the sort must be applied at the query level — pagination
 * offsets are computed against a specific DB ordering, so the UI cannot correctly re-sort
 * an already-paged result set once there's more than one page.
 */
enum class CollectionSortColumn { CREATED, MODIFIED }
enum class CollectionSortDirection { ASC, DESC }

/**
 * Repository for managing resource collections.
 */
class ResourceCollectionRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractRepository(databaseManager) {
    private val log = logger<ResourceCollectionRepository>()
    private val queries get() = db.resourceCollectionsQueries

    /**
     * Create a new resource collection.
     * @param collection The collection to create (id will be auto-generated if blank)
     * @return The created collection with generated id
     */
    fun createCollection(collection: ResourceCollection): ResourceCollection {
        val collectionWithId = collection.copy(
            id = collection.id.ifBlank { UUID.randomUUID().toString() },
        )

        queries.insertCollection(
            id = collectionWithId.id,
            name = collectionWithId.name,
            description = collectionWithId.description,
            knowledgeSourcesConfig = KnowledgeSourceSerializer.serialize(collectionWithId.knowledgeSources),
            createdAt = collectionWithId.createdAt.toString(),
            updatedAt = collectionWithId.updatedAt.toString(),
            isSystemCollection = if (collectionWithId.isSystemCollection) 1L else 0L,
            indexStatus = collectionWithId.indexStatus.name,
            lastIndexedAt = collectionWithId.lastIndexedAt?.toString(),
            indexError = collectionWithId.indexError,
        )

        log.debug("Created resource collection ${collectionWithId.id} with name '${collectionWithId.name}'")
        EventBus.post(PushDataToServerEvent(reason = "resource collection created"))
        return collectionWithId
    }

    /**
     * Get a collection by id.
     * @param collectionId The collection id
     * @return The collection or null if not found
     */
    fun getCollection(collectionId: String): ResourceCollection? = queries.selectById(collectionId).executeAsOneOrNull()?.toResourceCollection()

    /**
     * Get multiple collections by ids.
     * @param collectionIds List of collection ids
     * @return List of collections (only found ones)
     */
    fun getCollectionsByIds(collectionIds: List<String>): List<ResourceCollection> {
        if (collectionIds.isEmpty()) return emptyList()
        return queries.selectByIds(collectionIds).executeAsList().map { it.toResourceCollection() }
    }

    /**
     * Get all collections sorted by updated time (most recent first).
     * @return List of all collections
     */
    fun getAllCollections(): List<ResourceCollection> = queries.selectAllOrderedByUpdatedAtDesc().executeAsList().map { it.toResourceCollection() }

    /**
     * Count total collections.
     * @return Total number of collections
     */
    fun countAll(): Int = queries.countAll().executeAsOne().toInt()

    /**
     * Get collections with pagination.
     * @param page 1-based page number
     * @param pageSize Number of items per page
     * @param sortColumn Column to sort by before paging (page boundaries depend on this)
     * @param sortDirection Sort direction
     * @return Paginated results
     */
    fun getCollectionsPaged(
        page: Int = 1,
        pageSize: Int = 10,
        sortColumn: CollectionSortColumn = CollectionSortColumn.MODIFIED,
        sortDirection: CollectionSortDirection = CollectionSortDirection.DESC,
    ): Pageable<ResourceCollection> {
        val totalItems = queries.countAll().executeAsOne().toInt()
        val pageParams = resolvePageParams(totalItems, page, pageSize) ?: return Pageable.empty(pageSize)

        val limit = pageSize.toLong()
        val offset = pageParams.offset
        val pageCollections = when (sortColumn to sortDirection) {
            CollectionSortColumn.CREATED to CollectionSortDirection.ASC -> queries.pagedCreatedAsc(limit, offset)
            CollectionSortColumn.CREATED to CollectionSortDirection.DESC -> queries.pagedCreatedDesc(limit, offset)
            CollectionSortColumn.MODIFIED to CollectionSortDirection.ASC -> queries.pagedModifiedAsc(limit, offset)
            else -> queries.pagedModifiedDesc(limit, offset)
        }.executeAsList().map { it.toResourceCollection() }

        return Pageable(
            items = pageCollections,
            currentPage = pageParams.validPage,
            totalPages = pageParams.totalPages,
            totalItems = totalItems,
            pageSize = pageSize,
        )
    }

    /**
     * Search collections by name (case-insensitive LIKE) with pagination.
     * @param nameQuery Search term matched against collection names
     * @param page 1-based page number
     * @param pageSize Number of items per page
     * @param sortColumn Column to sort by before paging (page boundaries depend on this)
     * @param sortDirection Sort direction
     * @return Paginated results matching the query
     */
    fun searchCollectionsPaged(
        nameQuery: String,
        page: Int = 1,
        pageSize: Int = 10,
        sortColumn: CollectionSortColumn = CollectionSortColumn.MODIFIED,
        sortDirection: CollectionSortDirection = CollectionSortDirection.DESC,
    ): Pageable<ResourceCollection> {
        val pattern = "%${nameQuery.trim()}%"

        val totalItems = queries.countSearch(pattern).executeAsOne().toInt()
        val pageParams = resolvePageParams(totalItems, page, pageSize) ?: return Pageable.empty(pageSize)

        val limit = pageSize.toLong()
        val offset = pageParams.offset
        val pageCollections = when (sortColumn to sortDirection) {
            CollectionSortColumn.CREATED to CollectionSortDirection.ASC -> queries.searchPagedCreatedAsc(pattern, limit, offset)
            CollectionSortColumn.CREATED to CollectionSortDirection.DESC -> queries.searchPagedCreatedDesc(pattern, limit, offset)
            CollectionSortColumn.MODIFIED to CollectionSortDirection.ASC -> queries.searchPagedModifiedAsc(pattern, limit, offset)
            else -> queries.searchPagedModifiedDesc(pattern, limit, offset)
        }.executeAsList().map { it.toResourceCollection() }

        return Pageable(
            items = pageCollections,
            currentPage = pageParams.validPage,
            totalPages = pageParams.totalPages,
            totalItems = totalItems,
            pageSize = pageSize,
        )
    }

    /**
     * Update a collection's metadata and knowledge sources.
     * @param collectionId The collection id
     * @param name New name
     * @param description New description (nullable)
     * @param knowledgeSources New knowledge sources list
     * @return true if updated successfully
     */
    fun updateCollection(
        collectionId: String,
        name: String,
        description: String? = null,
        knowledgeSources: List<KnowledgeSourceConfig>,
    ): Boolean {
        val newConfig = KnowledgeSourceSerializer.serialize(knowledgeSources)

        // Reset persisted index status if sources changed — otherwise a silently-skipped
        // re-index (e.g. no embedding model configured) leaves a stale READY/WATCHING
        // status that no longer matches the actual vectors.
        val previousConfig = queries.selectKnowledgeSourcesConfigById(collectionId).executeAsOneOrNull()
        val sourcesChanged = previousConfig != null && previousConfig != newConfig
        val nowStr = Clock.System.now().toString()

        val updated = if (sourcesChanged) {
            queries.updateCollectionResetIndex(
                name = name,
                description = description,
                knowledgeSourcesConfig = newConfig,
                updatedAt = nowStr,
                indexStatus = IndexStatus.NOT_STARTED.name,
                id = collectionId,
            ).value > 0
        } else {
            queries.updateCollection(
                name = name,
                description = description,
                knowledgeSourcesConfig = newConfig,
                updatedAt = nowStr,
                id = collectionId,
            ).value > 0
        }

        if (updated) {
            log.debug("Updated resource collection $collectionId")
            EventBus.post(PushDataToServerEvent(reason = "resource collection updated"))
        }
        return updated
    }

    /**
     * Persist a collection's indexing status. Called by RagIndexer at each state
     * transition (queued, indexing, ready, failed) so status survives restarts and
     * can be shown without live event state.
     *
     * @param collectionId The collection id
     * @param status The new [IndexStatus]
     * @param error Error message when [status] is [IndexStatus.FAILED]; ignored otherwise
     * @return true if updated successfully
     */
    fun updateIndexStatus(
        collectionId: String,
        status: IndexStatus,
        error: String? = null,
    ): Boolean = when (status) {
        IndexStatus.READY -> queries.updateIndexStatusReady(status.name, Clock.System.now().toString(), collectionId)
        IndexStatus.FAILED -> queries.updateIndexStatusFailed(status.name, error, collectionId)
        else -> queries.updateIndexStatusOther(status.name, collectionId)
    }.value > 0

    /**
     * Marks every collection left in [IndexStatus.QUEUED] or [IndexStatus.INDEXING] as
     * [IndexStatus.FAILED] with [error] — used by RagIndexer's startup recovery to flag
     * collections whose indexing was interrupted by an app crash/force-quit (on fresh
     * startup no job can genuinely still be running).
     *
     * Filters and updates entirely in SQL, in a single transaction, instead of loading
     * every collection via [getAllCollections] (deserializing knowledge sources, etc. for
     * rows that don't even match) and issuing a separate `update` per interrupted row —
     * avoidable latency/memory overhead on startup for large collection counts.
     *
     * @return ids of the collections that were updated, for the caller to log.
     */
    fun markInterruptedIndexingAsFailed(error: String): List<String> {
        val interruptedStatuses = listOf(IndexStatus.QUEUED.name, IndexStatus.INDEXING.name)

        return db.transactionWithResult {
            val interruptedIds = queries.selectIdsByIndexStatuses(interruptedStatuses).executeAsList()

            if (interruptedIds.isNotEmpty()) {
                queries.updateIndexStatusForStatuses(IndexStatus.FAILED.name, error, interruptedStatuses)
            }
            interruptedIds
        }
    }

    /**
     * Delete a resource collection. Note: does not delete indexed files — those are
     * cleaned up separately by ResourceCollectionService/ProjectIndexer.
     *
     * @param collectionId The collection id to delete
     * @return true if deleted successfully
     */
    fun deleteCollection(collectionId: String): Boolean {
        val deleted = queries.deleteCollection(collectionId).value > 0
        if (deleted) {
            log.debug("Deleted resource collection $collectionId")
            EventBus.post(PushDataToServerEvent(reason = "resource collection deleted"))
        }
        return deleted
    }

    /**
     * Get unsynced collections (never pushed to server or modified locally after last sync).
     * @param limit Max number to return
     * @return List of unsynced collections
     */
    fun getUnsyncedCollections(limit: Int = 50): List<ResourceCollection> = queries.selectAllOrderedByUpdatedAtAsc().executeAsList()
        .mapNotNull { row ->
            if (row.synced_at == null || row.updated_at > row.synced_at) row.toResourceCollection() else null
        }
        .take(limit)

    /**
     * Mark a collection as successfully synced to server.
     * @param collectionId The collection id
     * @return true if updated successfully
     */
    fun markSynced(collectionId: String): Boolean = queries.markSynced(Clock.System.now().toString(), collectionId).value > 0

    /**
     * Upsert collections from server (for sync).
     * @param collections List of collections from server
     */
    fun upsertFromServer(collections: List<ResourceCollection>) {
        if (collections.isEmpty()) return

        db.transaction {
            val nowStr = Clock.System.now().toString()
            val ids = collections.map { it.id }

            val existingById = queries.selectExistingByIds(ids).executeAsList()
                .associate { it.id to TimeUtil.parseInstant(it.updated_at) }

            for (collection in collections) {
                val storedUpdatedAt = existingById[collection.id]
                if (storedUpdatedAt == null) {
                    queries.insertFromServer(
                        id = collection.id,
                        name = collection.name,
                        description = collection.description,
                        knowledgeSourcesConfig = KnowledgeSourceSerializer.serialize(collection.knowledgeSources),
                        createdAt = collection.createdAt.toString(),
                        updatedAt = collection.updatedAt.toString(),
                        isSystemCollection = if (collection.isSystemCollection) 1L else 0L,
                        syncedAt = nowStr,
                    )
                    log.debug("upsertFromServer: inserted collection ${collection.id}")
                } else if (collection.updatedAt > storedUpdatedAt) {
                    queries.updateFromServer(
                        name = collection.name,
                        description = collection.description,
                        knowledgeSourcesConfig = KnowledgeSourceSerializer.serialize(collection.knowledgeSources),
                        updatedAt = collection.updatedAt.toString(),
                        syncedAt = nowStr,
                        isSystemCollection = if (collection.isSystemCollection) 1L else 0L,
                        id = collection.id,
                    )
                    log.debug("upsertFromServer: updated collection ${collection.id} (server newer)")
                } else {
                    log.debug("upsertFromServer: skipped collection ${collection.id} (local is same or newer)")
                }
            }
        }
    }
}
