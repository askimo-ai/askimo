/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.service

import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.domain.ResourceCollection
import io.askimo.core.chat.repository.ResourceCollectionRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.db.Pageable
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.ContainerDeletedEvent
import io.askimo.core.event.internal.IndexingRequestedEvent
import io.askimo.core.logging.logger
import io.askimo.core.rag.RagIndexer
import io.askimo.core.rag.container.IndexingContainerType
import java.time.Instant

/**
 * Service for managing resource collections.
 *
 * A resource collection groups indexed knowledge sources (files, URLs) that can be
 * reused across multiple chat sessions. Collections are independent of projects
 * and provide RAG context on-demand or persistently in any conversation.
 *
 * Responsibilities:
 * - CRUD operations on collections
 * - Triggering indexing of knowledge sources
 * - Querying collections for UI and RAG retrieval
 */
class ResourceCollectionService(
    private val collectionRepository: ResourceCollectionRepository = DatabaseManager.getInstance().getResourceCollectionRepository(),
    private val ragIndexer: RagIndexer,
) {
    private val log = logger<ResourceCollectionService>()

    /**
     * Create a new resource collection.
     *
     * Note: Knowledge sources indexing is not triggered here — use [addKnowledgeSourcesToCollection]
     * after creation if indexing is needed.
     *
     * @param name Collection name (will be trimmed)
     * @param description Optional description
     * @param knowledgeSources Initial knowledge sources (typically empty — add later)
     * @param isSystemCollection Whether this is org-wide (true) or personal (false)
     * @return The created collection with generated id
     */
    fun createCollection(
        name: String,
        description: String? = null,
        knowledgeSources: List<KnowledgeSourceConfig> = emptyList(),
        isSystemCollection: Boolean = false,
    ): ResourceCollection {
        val collection = ResourceCollection(
            id = "",
            name = name.trim(),
            description = description?.takeIf { it.isNotBlank() },
            knowledgeSources = knowledgeSources,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            isSystemCollection = isSystemCollection,
        )

        val created = collectionRepository.createCollection(collection)

        log.debug("Created resource collection ${created.id} with name '${created.name}'")

        // Trigger indexing if knowledge sources were provided at creation time
        if (created.knowledgeSources.isNotEmpty()) {
            EventBus.post(
                IndexingRequestedEvent(
                    containerId = created.id,
                    containerType = IndexingContainerType.RESOURCE_COLLECTION,
                    watchForChanges = true,
                ),
            )
            log.debug("Emitted indexing event for collection ${created.id}")
        }

        return created
    }

    /**
     * Get a collection by id.
     * @param collectionId The collection id
     * @return The collection or null if not found
     */
    fun getCollection(collectionId: String): ResourceCollection? = collectionRepository.getCollection(collectionId)

    /**
     * Get all collections.
     * @return List of all collections sorted by updated time
     */
    fun getAllCollections(): List<ResourceCollection> = collectionRepository.getAllCollections()

    /**
     * Get multiple collections by ids (useful for retrieving session's active collections).
     * @param collectionIds List of ids to retrieve
     * @return List of collections found (non-found ids are silently skipped)
     */
    fun getCollectionsByIds(collectionIds: List<String>): List<ResourceCollection> {
        if (collectionIds.isEmpty()) return emptyList()
        return collectionRepository.getCollectionsByIds(collectionIds)
    }

    /**
     * Get collections with pagination (for sidebar display).
     * @param page 1-based page number
     * @param pageSize Items per page
     * @return Paginated results
     */
    fun getCollectionsPaged(page: Int = 1, pageSize: Int = 10): Pageable<ResourceCollection> = collectionRepository.getCollectionsPaged(page, pageSize)

    /**
     * Search collections by name with pagination.
     * @param nameQuery Search term (case-insensitive substring match)
     * @param page 1-based page number
     * @param pageSize Items per page
     * @return Paginated results matching the query
     */
    fun searchCollectionsPaged(nameQuery: String, page: Int = 1, pageSize: Int = 10): Pageable<ResourceCollection> = collectionRepository.searchCollectionsPaged(nameQuery, page, pageSize)

    /**
     * Update a collection's metadata.
     * @param collectionId The collection id
     * @param name New name
     * @param description New description
     * @param knowledgeSources Updated knowledge sources list
     * @return true if updated successfully
     */
    fun updateCollection(
        collectionId: String,
        name: String,
        description: String? = null,
        knowledgeSources: List<KnowledgeSourceConfig> = emptyList(),
    ): Boolean {
        val updated = collectionRepository.updateCollection(
            collectionId,
            name.trim(),
            description?.takeIf { it.isNotBlank() },
            knowledgeSources,
        )

        if (updated) {
            log.debug("Updated collection $collectionId")
        }
        return updated
    }

    /**
     * Add or update knowledge sources in a collection and trigger indexing.
     *
     * Replaces the collection's knowledge sources with the provided list and emits
     * an indexing event so files are indexed into the embedding store.
     *
     * @param collectionId The collection id
     * @param newKnowledgeSources Knowledge sources to index (files, URLs, etc)
     * @return The updated collection or null if not found
     */
    fun addKnowledgeSourcesToCollection(
        collectionId: String,
        newKnowledgeSources: List<KnowledgeSourceConfig>,
    ): ResourceCollection? {
        val collection = collectionRepository.getCollection(collectionId)
            ?: return null

        // Merge with existing (or replace — depends on product decision)
        // For now: replace for simplicity
        val updated = updateCollection(
            collectionId,
            collection.name,
            collection.description,
            newKnowledgeSources,
        )

        if (updated) {
            // Trigger indexing using RagIndexer (collections reuse same infrastructure as projects)
            EventBus.post(
                IndexingRequestedEvent(
                    containerId = collectionId,
                    containerType = IndexingContainerType.RESOURCE_COLLECTION,
                    watchForChanges = true,
                ),
            )
            log.debug("Triggered indexing for collection $collectionId with ${newKnowledgeSources.size} knowledge sources")
        }

        return collectionRepository.getCollection(collectionId)
    }

    /**
     * Delete a collection. Emits [ContainerDeletedEvent] so [RagIndexer] can clean
     * up the collection's coordinators, embedding store, and on-disk index data.
     *
     * @param collectionId The collection id
     * @return true if successfully deleted
     */
    fun deleteCollection(collectionId: String): Boolean {
        val deleted = collectionRepository.deleteCollection(collectionId)
        if (deleted) {
            log.debug("Deleted collection $collectionId")
            EventBus.post(
                ContainerDeletedEvent(
                    containerId = collectionId,
                    containerType = IndexingContainerType.RESOURCE_COLLECTION,
                ),
            )
        }
        return deleted
    }

    /**
     * Get unsynced collections for server synchronization.
     * @param limit Max number to retrieve
     * @return List of unsynced collections
     */
    fun getUnsyncedCollections(limit: Int = 50): List<ResourceCollection> = collectionRepository.getUnsyncedCollections(limit)

    /**
     * Mark a collection as synced to server.
     * @param collectionId The collection id
     * @return true if successful
     */
    fun markCollectionSynced(collectionId: String): Boolean = collectionRepository.markSynced(collectionId)
}
