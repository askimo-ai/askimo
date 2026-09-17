/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.domain.KnowledgeSourceSerializer
import io.askimo.core.chat.domain.ResourceCollection
import io.askimo.core.chat.domain.ResourceCollectionsTable
import io.askimo.core.db.AbstractSQLiteRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.db.Pageable
import io.askimo.core.db.resolvePageParams
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.PushDataToServerEvent
import io.askimo.core.logging.logger
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

/**
 * Extension to map ResultRow to ResourceCollection.
 */
private fun ResultRow.toResourceCollection(): ResourceCollection = ResourceCollection(
    id = this[ResourceCollectionsTable.id],
    name = this[ResourceCollectionsTable.name],
    description = this[ResourceCollectionsTable.description],
    knowledgeSources = KnowledgeSourceSerializer.deserialize(this[ResourceCollectionsTable.knowledgeSourcesConfig]),
    createdAt = this[ResourceCollectionsTable.createdAt],
    updatedAt = this[ResourceCollectionsTable.updatedAt],
    isStarred = this[ResourceCollectionsTable.isStarred] == 1,
    isSystemCollection = this[ResourceCollectionsTable.isSystemCollection] == 1,
)

/**
 * Repository for managing resource collections.
 *
 * Methods:
 * - CRUD: createCollection, getCollection, getAllCollections, updateCollection, deleteCollection
 * - Query: getCollectionsByIds, searchCollectionsByName, getStarredCollections
 * - Pagination: getCollectionsPaged
 * - Metadata: starCollection, countAll
 * - Sync: getUnsyncedCollections, markSynced, upsertFromServer
 */
class ResourceCollectionRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractSQLiteRepository(databaseManager) {
    private val log = logger<ResourceCollectionRepository>()

    /**
     * Create a new resource collection.
     * @param collection The collection to create (id will be auto-generated if blank)
     * @return The created collection with generated id
     */
    fun createCollection(collection: ResourceCollection): ResourceCollection {
        val collectionWithId = collection.copy(
            id = collection.id.ifBlank { UUID.randomUUID().toString() },
        )

        transaction(database) {
            ResourceCollectionsTable.insert {
                it[id] = collectionWithId.id
                it[name] = collectionWithId.name
                it[description] = collectionWithId.description
                it[knowledgeSourcesConfig] = KnowledgeSourceSerializer.serialize(collectionWithId.knowledgeSources)
                it[createdAt] = collectionWithId.createdAt
                it[updatedAt] = collectionWithId.updatedAt
                it[isStarred] = if (collectionWithId.isStarred) 1 else 0
                it[isSystemCollection] = if (collectionWithId.isSystemCollection) 1 else 0
            }
        }

        log.debug("Created resource collection ${collectionWithId.id} with name '${collectionWithId.name}'")
        EventBus.post(PushDataToServerEvent(reason = "resource collection created"))
        return collectionWithId
    }

    /**
     * Get a collection by id.
     * @param collectionId The collection id
     * @return The collection or null if not found
     */
    fun getCollection(collectionId: String): ResourceCollection? = transaction(database) {
        ResourceCollectionsTable
            .selectAll()
            .where { ResourceCollectionsTable.id eq collectionId }
            .map { it.toResourceCollection() }
            .firstOrNull()
    }

    /**
     * Get multiple collections by ids.
     * @param collectionIds List of collection ids
     * @return List of collections (only found ones)
     */
    fun getCollectionsByIds(collectionIds: List<String>): List<ResourceCollection> = transaction(database) {
        if (collectionIds.isEmpty()) return@transaction emptyList()
        ResourceCollectionsTable
            .selectAll()
            .where { ResourceCollectionsTable.id inList collectionIds }
            .map { it.toResourceCollection() }
    }

    /**
     * Get all collections sorted by starred first, then by updated time (most recent first).
     * @return List of all collections
     */
    fun getAllCollections(): List<ResourceCollection> = transaction(database) {
        ResourceCollectionsTable
            .selectAll()
            .orderBy(ResourceCollectionsTable.isStarred, SortOrder.DESC)
            .orderBy(ResourceCollectionsTable.updatedAt, SortOrder.DESC)
            .map { it.toResourceCollection() }
    }

    /**
     * Get starred collections only.
     * @return List of starred collections
     */
    fun getStarredCollections(): List<ResourceCollection> = transaction(database) {
        ResourceCollectionsTable
            .selectAll()
            .where { ResourceCollectionsTable.isStarred eq 1 }
            .orderBy(ResourceCollectionsTable.updatedAt, SortOrder.DESC)
            .map { it.toResourceCollection() }
    }

    /**
     * Count total collections.
     * @return Total number of collections
     */
    fun countAll(): Int = transaction(database) {
        val count = ResourceCollectionsTable.id.count()
        ResourceCollectionsTable.select(count).first()[count].toInt()
    }

    /**
     * Get collections with pagination.
     * @param page 1-based page number
     * @param pageSize Number of items per page
     * @return Paginated results
     */
    fun getCollectionsPaged(page: Int = 1, pageSize: Int = 10): Pageable<ResourceCollection> = transaction(database) {
        val countExpr = ResourceCollectionsTable.id.count()
        val totalItems = ResourceCollectionsTable.select(countExpr).first()[countExpr].toInt()
        val pageParams = resolvePageParams(totalItems, page, pageSize)
            ?: return@transaction Pageable.empty(pageSize)

        val pageCollections = ResourceCollectionsTable
            .selectAll()
            .orderBy(ResourceCollectionsTable.isStarred, SortOrder.DESC)
            .orderBy(ResourceCollectionsTable.updatedAt, SortOrder.DESC)
            .limit(pageSize)
            .offset(pageParams.offset)
            .map { it.toResourceCollection() }

        Pageable(
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
     * @return Paginated results matching the query
     */
    fun searchCollectionsPaged(nameQuery: String, page: Int = 1, pageSize: Int = 10): Pageable<ResourceCollection> = transaction(database) {
        val pattern = "%${nameQuery.trim()}%"

        val countExpr = ResourceCollectionsTable.id.count()
        val totalItems = ResourceCollectionsTable
            .select(countExpr)
            .where { ResourceCollectionsTable.name like pattern }
            .first()[countExpr].toInt()
        val pageParams = resolvePageParams(totalItems, page, pageSize)
            ?: return@transaction Pageable.empty(pageSize)

        val pageCollections = ResourceCollectionsTable
            .selectAll()
            .where { ResourceCollectionsTable.name like pattern }
            .orderBy(ResourceCollectionsTable.isStarred, SortOrder.DESC)
            .orderBy(ResourceCollectionsTable.updatedAt, SortOrder.DESC)
            .limit(pageSize)
            .offset(pageParams.offset)
            .map { it.toResourceCollection() }

        Pageable(
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
    ): Boolean = transaction(database) {
        val updated = ResourceCollectionsTable.update({ ResourceCollectionsTable.id eq collectionId }) {
            it[ResourceCollectionsTable.name] = name
            it[ResourceCollectionsTable.description] = description
            it[knowledgeSourcesConfig] = KnowledgeSourceSerializer.serialize(knowledgeSources)
            it[updatedAt] = Instant.now()
        } > 0

        if (updated) {
            log.debug("Updated resource collection $collectionId")
            EventBus.post(PushDataToServerEvent(reason = "resource collection updated"))
        }
        updated
    }

    /**
     * Star/unstar a collection (for sidebar pinning).
     * @param collectionId The collection id
     * @param isStarred true to star, false to unstar
     * @return true if updated successfully
     */
    fun starCollection(collectionId: String, isStarred: Boolean): Boolean = transaction(database) {
        ResourceCollectionsTable.update({ ResourceCollectionsTable.id eq collectionId }) {
            it[ResourceCollectionsTable.isStarred] = if (isStarred) 1 else 0
        } > 0
    }.also {
        if (it) {
            EventBus.post(PushDataToServerEvent(reason = "resource collection starred"))
        }
    }

    /**
     * Delete a resource collection.
     * Note: Doesn't delete indexed files — those are cleaned up by ResourceCollectionService
     * or ProjectIndexer when called appropriately.
     *
     * @param collectionId The collection id to delete
     * @return true if deleted successfully
     */
    fun deleteCollection(collectionId: String): Boolean = transaction(database) {
        ResourceCollectionsTable.deleteWhere { ResourceCollectionsTable.id eq collectionId } > 0
    }.also {
        if (it) {
            log.debug("Deleted resource collection $collectionId")
            EventBus.post(PushDataToServerEvent(reason = "resource collection deleted"))
        }
    }

    /**
     * Get unsynced collections (never pushed to server or modified locally after last sync).
     * @param limit Max number to return
     * @return List of unsynced collections
     */
    fun getUnsyncedCollections(limit: Int = 50): List<ResourceCollection> = transaction(database) {
        ResourceCollectionsTable
            .selectAll()
            .orderBy(ResourceCollectionsTable.updatedAt, SortOrder.ASC)
            .mapNotNull { row ->
                val syncedAt = row[ResourceCollectionsTable.syncedAt]
                val updatedAt = row[ResourceCollectionsTable.updatedAt].toString()
                if (syncedAt == null || updatedAt > syncedAt) row.toResourceCollection() else null
            }
            .take(limit)
    }

    /**
     * Mark a collection as successfully synced to server.
     * @param collectionId The collection id
     * @return true if updated successfully
     */
    fun markSynced(collectionId: String): Boolean = transaction(database) {
        ResourceCollectionsTable.update({ ResourceCollectionsTable.id eq collectionId }) {
            it[syncedAt] = Instant.now().toString()
        } > 0
    }

    /**
     * Upsert collections from server (for sync).
     * @param collections List of collections from server
     */
    fun upsertFromServer(collections: List<ResourceCollection>) {
        if (collections.isEmpty()) return
        transaction(database) {
            val nowStr = Instant.now().toString()
            val existingById = ResourceCollectionsTable
                .selectAll()
                .where { ResourceCollectionsTable.id inList collections.map { it.id } }
                .associate { row -> row[ResourceCollectionsTable.id] to row[ResourceCollectionsTable.updatedAt] }

            for (collection in collections) {
                val storedUpdatedAt = existingById[collection.id]
                if (storedUpdatedAt == null) {
                    // Insert new
                    ResourceCollectionsTable.insert {
                        it[id] = collection.id
                        it[name] = collection.name
                        it[description] = collection.description
                        it[knowledgeSourcesConfig] = KnowledgeSourceSerializer.serialize(collection.knowledgeSources)
                        it[createdAt] = collection.createdAt
                        it[updatedAt] = collection.updatedAt
                        it[isStarred] = if (collection.isStarred) 1 else 0
                        it[isSystemCollection] = if (collection.isSystemCollection) 1 else 0
                        it[syncedAt] = nowStr
                    }
                    log.debug("upsertFromServer: inserted collection ${collection.id}")
                } else if (collection.updatedAt.isAfter(storedUpdatedAt)) {
                    // Update if server is newer
                    ResourceCollectionsTable.update({ ResourceCollectionsTable.id eq collection.id }) {
                        it[name] = collection.name
                        it[description] = collection.description
                        it[knowledgeSourcesConfig] = KnowledgeSourceSerializer.serialize(collection.knowledgeSources)
                        it[updatedAt] = collection.updatedAt
                        it[syncedAt] = nowStr
                        it[isSystemCollection] = if (collection.isSystemCollection) 1 else 0
                    }
                    log.debug("upsertFromServer: updated collection ${collection.id} (server newer)")
                } else {
                    log.debug("upsertFromServer: skipped collection ${collection.id} (local is same or newer)")
                }
            }
        }
    }
}
