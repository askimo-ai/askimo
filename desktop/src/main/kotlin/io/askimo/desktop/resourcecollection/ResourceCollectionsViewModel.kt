/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.resourcecollection

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.domain.ResourceCollection
import io.askimo.core.chat.repository.CollectionSortColumn
import io.askimo.core.chat.repository.CollectionSortDirection
import io.askimo.core.chat.service.ResourceCollectionService
import io.askimo.core.context.AppContext
import io.askimo.core.db.DatabaseManager
import io.askimo.core.db.Pageable
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.ModelChangedEvent
import io.askimo.core.event.internal.ProviderInstanceSavedEvent
import io.askimo.core.event.internal.ReIndexEvent
import io.askimo.core.event.user.IndexingCompletedEvent
import io.askimo.core.event.user.IndexingFailedEvent
import io.askimo.core.event.user.IndexingQueuedEvent
import io.askimo.core.event.user.IndexingStartedEvent
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.logging.logger
import io.askimo.core.rag.container.IndexingContainerType
import io.askimo.ui.util.ErrorHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * ViewModel for managing resource collections in the desktop application.
 */
class ResourceCollectionsViewModel(
    private val scope: CoroutineScope,
    private val resourceCollectionService: ResourceCollectionService = GlobalContext.get().get(),
) {
    private val log = logger<ResourceCollectionsViewModel>()
    private val collectionRepository = DatabaseManager.getInstance().getResourceCollectionRepository()

    var collections by mutableStateOf<List<ResourceCollection>>(emptyList())
        private set

    var pagedCollections by mutableStateOf<Pageable<ResourceCollection>?>(null)
        private set

    var isLoading by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var searchQuery by mutableStateOf("")
        private set

    var sortColumn by mutableStateOf(CollectionSortColumn.MODIFIED)
        private set

    var sortDirection by mutableStateOf(CollectionSortDirection.DESC)
        private set

    /**
     * True when the active provider has an embedding model configured, meaning RAG
     * collection indexing can run. Kept in sync via [subscribeToEmbeddingModelEvents].
     */
    var embeddingModelConfigured by mutableStateOf(AppContext.getInstance().isEmbeddingModelConfigured())
        private set

    /**
     * True when the active provider's factory supports embedding models at all
     * (regardless of whether one is configured) — false for providers like
     * Anthropic/xAI. Switches the "RAG disabled" banner's copy/action between
     * "configure embedding model" and "switch provider".
     */
    var embeddingSupportedByProvider by mutableStateOf(AppContext.getInstance().activeProviderSupportsEmbedding())
        private set

    private var searchDebounceJob: Job? = null

    private var collectionsPerPage = 10

    fun setPageSize(size: Int) {
        collectionsPerPage = size
        loadCollectionsPaged(1)
    }

    init {
        loadCollections()
        loadCollectionsPaged(1)
        subscribeToCollectionEvents()
        subscribeToEmbeddingModelEvents()
    }

    /**
     * Re-checks [embeddingModelConfigured] and [embeddingSupportedByProvider] against
     * [AppContext]. Called on init and by [subscribeToEmbeddingModelEvents].
     */
    fun refreshEmbeddingModelStatus() {
        embeddingModelConfigured = AppContext.getInstance().isEmbeddingModelConfigured()
        embeddingSupportedByProvider = AppContext.getInstance().activeProviderSupportsEmbedding()
    }

    /**
     * Refreshes embedding-model status on provider/model changes or provider instance
     * saves (including inline model overrides).
     */
    private fun subscribeToEmbeddingModelEvents() {
        scope.launch {
            merge(
                EventBus.internalEvents.filterIsInstance<ModelChangedEvent>(),
                EventBus.internalEvents.filterIsInstance<ProviderInstanceSavedEvent>(),
            ).collect {
                refreshEmbeddingModelStatus()
            }
        }
    }

    /**
     * Load all collections from the database.
     */
    fun loadCollections() {
        scope.launch {
            try {
                isLoading = true
                collections = withContext(Dispatchers.IO) {
                    collectionRepository.getAllCollections()
                }
                log.debug("Loaded ${collections.size} collections")
            } catch (e: Exception) {
                log.error("Failed to load collections", e)
                collections = emptyList()
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * Loads collections for [page], respecting the active [searchQuery] and
     * [sortColumn]/[sortDirection]. Sorting is applied at the query level, so page
     * boundaries can't be corrected client-side once there's more than one page.
     */
    fun loadCollectionsPaged(page: Int = 1) {
        isLoading = true
        errorMessage = null

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (searchQuery.isBlank()) {
                        collectionRepository.getCollectionsPaged(page, collectionsPerPage, sortColumn, sortDirection)
                    } else {
                        collectionRepository.searchCollectionsPaged(searchQuery, page, collectionsPerPage, sortColumn, sortDirection)
                    }
                }
                pagedCollections = result
            } catch (e: Exception) {
                errorMessage = ErrorHandler.getUserFriendlyError(
                    e,
                    "loading collections",
                    LocalizationManager.getString("resourcecollections.error.loading"),
                )
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * Changes sort column/direction and reloads page 1. Toggles direction if the same
     * column is clicked again, matching the table header behavior.
     */
    fun setSort(column: CollectionSortColumn) {
        if (sortColumn == column) {
            sortDirection = if (sortDirection == CollectionSortDirection.DESC) CollectionSortDirection.ASC else CollectionSortDirection.DESC
        } else {
            sortColumn = column
            sortDirection = CollectionSortDirection.DESC
        }
        loadCollectionsPaged(1)
    }

    /**
     * Update the search query and reload page 1 with a 300 ms debounce.
     */
    fun updateSearch(query: String) {
        searchQuery = query
        searchDebounceJob?.cancel()
        searchDebounceJob = scope.launch {
            delay(300.milliseconds)
            loadCollectionsPaged(1)
        }
    }

    /**
     * Clear the search query and reload.
     */
    fun clearSearch() {
        searchQuery = ""
        searchDebounceJob?.cancel()
        loadCollectionsPaged(1)
    }

    /**
     * Reload the current page.
     */
    fun refresh() {
        loadCollectionsPaged(pagedCollections?.currentPage ?: 1)
        loadCollections()
    }

    /**
     * Go to the next page.
     */
    fun nextPage() {
        pagedCollections?.let {
            if (it.hasNextPage) {
                loadCollectionsPaged(it.currentPage + 1)
            }
        }
    }

    /**
     * Go to the previous page.
     */
    fun previousPage() {
        pagedCollections?.let {
            if (it.hasPreviousPage) {
                loadCollectionsPaged(it.currentPage - 1)
            }
        }
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        errorMessage = null
    }

    /**
     * Delete a collection by ID.
     */
    fun deleteCollection(collectionId: String) {
        scope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) {
                    resourceCollectionService.deleteCollection(collectionId)
                }
                if (deleted) {
                    refresh()
                } else {
                    errorMessage = LocalizationManager.getString("resourcecollections.error.not.found")
                }
            } catch (e: Exception) {
                errorMessage = ErrorHandler.getUserFriendlyError(
                    e,
                    "deleting collection",
                    LocalizationManager.getString("resourcecollections.error.deleting"),
                )
            }
        }
    }

    /**
     * Update a collection's metadata and knowledge sources.
     * @return true if the update was persisted successfully.
     */
    suspend fun updateCollection(
        collectionId: String,
        name: String,
        description: String?,
        knowledgeSources: List<KnowledgeSourceConfig>,
    ): Boolean = try {
        val updated = withContext(Dispatchers.IO) {
            resourceCollectionService.updateCollection(collectionId, name, description, knowledgeSources)
        }
        if (updated) {
            refresh()
        } else {
            errorMessage = LocalizationManager.getString("resourcecollections.error.not.found")
        }
        updated
    } catch (e: Exception) {
        errorMessage = ErrorHandler.getUserFriendlyError(
            e,
            "updating collection",
            LocalizationManager.getString("resourcecollections.error.updating"),
        )
        false
    }

    /**
     * Requests a re-index, mirroring Project's re-index action; RagIndexer clears and
     * rebuilds the index.
     */
    fun reindexCollection(collectionId: String) {
        EventBus.post(
            ReIndexEvent(
                containerId = collectionId,
                containerType = IndexingContainerType.RESOURCE_COLLECTION,
                reason = "Manual re-index requested from collections list",
            ),
        )
    }

    /**
     * Keeps the collection list updated on relevant events.
     *
     * [io.askimo.core.rag.RagIndexer] persists indexing status directly to the DB without
     * emitting [ModelChangedEvent], so the second subscription below is needed —
     * otherwise [ResourceCollection.indexStatus] would stay stale until an unrelated
     * refresh happened to fire.
     */
    private fun subscribeToCollectionEvents() {
        scope.launch {
            EventBus.internalEvents
                .filterIsInstance<ModelChangedEvent>()
                .collect {
                    refresh()
                }
        }

        scope.launch {
            merge(
                EventBus.internalEvents.filterIsInstance<IndexingQueuedEvent>()
                    .filter { it.containerType == IndexingContainerType.RESOURCE_COLLECTION },
                EventBus.internalEvents.filterIsInstance<IndexingStartedEvent>()
                    .filter { it.containerType == IndexingContainerType.RESOURCE_COLLECTION },
                EventBus.internalEvents.filterIsInstance<IndexingCompletedEvent>()
                    .filter { it.containerType == IndexingContainerType.RESOURCE_COLLECTION },
                EventBus.internalEvents.filterIsInstance<IndexingFailedEvent>()
                    .filter { it.containerType == IndexingContainerType.RESOURCE_COLLECTION },
            ).collect {
                refresh()
            }
        }
    }
}
