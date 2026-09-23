/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.resourcecollection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.domain.LocalFoldersKnowledgeSourceConfig
import io.askimo.core.chat.domain.ResourceCollection
import io.askimo.core.chat.service.ResourceCollectionService
import io.askimo.core.context.AppContext
import io.askimo.core.db.DatabaseManager
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.IndexRemovalEvent
import io.askimo.core.event.internal.IndexingRequestedEvent
import io.askimo.core.event.internal.KnowledgeSourceWatchToggledEvent
import io.askimo.core.event.internal.ModelChangedEvent
import io.askimo.core.event.internal.ProviderInstanceSavedEvent
import io.askimo.core.event.user.IndexingCompletedEvent
import io.askimo.core.event.user.IndexingFailedEvent
import io.askimo.core.event.user.IndexingInProgressEvent
import io.askimo.core.event.user.IndexingQueuedEvent
import io.askimo.core.event.user.IndexingStartedEvent
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.logging.logger
import io.askimo.core.rag.container.IndexingContainerType
import io.askimo.core.rag.state.IndexProgress
import io.askimo.core.rag.state.IndexStateManager
import io.askimo.core.rag.state.IndexStatus
import io.askimo.desktop.knowledgesource.mergeKnowledgeSourceConfigs
import io.askimo.ui.util.ErrorHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
/**
 * ViewModel for displaying and managing a single resource collection.
 * Tracks collection details, indexing progress, and knowledge source operations.
 */
class ResourceCollectionDetailViewModel(
    private val scope: CoroutineScope,
    private val collectionId: String,
    private val resourceCollectionService: ResourceCollectionService = GlobalContext.get().get(),
) {
    private val log = logger<ResourceCollectionDetailViewModel>()
    private val collectionRepository = DatabaseManager.getInstance().getResourceCollectionRepository()

    // State
    var currentCollection by mutableStateOf<ResourceCollection?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var indexProgress by mutableStateOf(IndexProgress())
        private set

    /**
     * True when the active provider instance has an embedding model configured.
     */
    var embeddingModelConfigured by mutableStateOf(AppContext.getInstance().isEmbeddingModelConfigured())
        private set

    /**
     * True when the active provider instance's factory supports embedding models.
     */
    var embeddingSupportedByProvider by mutableStateOf(AppContext.getInstance().activeProviderSupportsEmbedding())
        private set

    /**
     * Normalized set of local file paths already indexed for this collection.
     * Used by the reference-materials tree view to show per-file indexed status.
     */
    var indexedPaths by mutableStateOf<Set<String>>(emptySet())
        private set
    init {
        loadCollection()
        observeIndexProgress()
        refreshIndexedPaths()
        observeEmbeddingModelEvents()
    }

    /**
     * Load the current collection from database
     */
    fun loadCollection() {
        scope.launch {
            try {
                isLoading = true
                errorMessage = null
                val collection = withContext(Dispatchers.IO) {
                    collectionRepository.getCollection(collectionId)
                }
                currentCollection = collection
                log.debug("Loaded collection: ${collection?.name} (${collection?.id})")
            } catch (e: Exception) {
                log.error("Failed to load collection", e)
                errorMessage = ErrorHandler.getUserFriendlyError(
                    e,
                    "loading collection",
                    LocalizationManager.getString("resourcecollections.error.loading"),
                )
            } finally {
                isLoading = false
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
     * Update the collection's metadata and knowledge sources.
     */
    fun updateCollection(
        name: String,
        description: String?,
        knowledgeSources: List<KnowledgeSourceConfig>,
    ) {
        scope.launch {
            try {
                resourceCollectionService.updateCollection(
                    collectionId,
                    name,
                    description,
                    knowledgeSources,
                )
                loadCollection()
            } catch (e: Exception) {
                log.error("Failed to update collection", e)
                errorMessage = ErrorHandler.getUserFriendlyError(
                    e,
                    "updating collection",
                    LocalizationManager.getString("resourcecollections.error.updating"),
                )
            }
        }
    }

    /**
     * Delete the current collection (including its persisted row and index data).
     * @return true if the collection was deleted, false if it was already gone.
     */
    suspend fun deleteCollection(): Boolean = try {
        withContext(Dispatchers.IO) {
            resourceCollectionService.deleteCollection(collectionId)
        }
    } catch (e: Exception) {
        log.error("Failed to delete collection", e)
        errorMessage = ErrorHandler.getUserFriendlyError(
            e,
            "deleting collection",
            LocalizationManager.getString("resourcecollections.error.deleting"),
        )
        false
    }

    /**
     * Add new sources, merged with existing ones, then trigger indexing for just
     * the new sources (mirrors ProjectView's "Add reference material" flow).
     */
    fun addKnowledgeSources(newSources: List<KnowledgeSourceConfig>) {
        val current = currentCollection ?: return
        scope.launch {
            try {
                val merged = mergeKnowledgeSourceConfigs(existing = current.knowledgeSources, new = newSources)
                resourceCollectionService.updateCollection(
                    collectionId,
                    current.name,
                    current.description,
                    merged,
                )
                loadCollection()

                // Only index if embeddings are configured; otherwise sources are saved
                // but left un-indexed until the user sets up an embedding model.
                if (embeddingModelConfigured) {
                    EventBus.post(
                        IndexingRequestedEvent(
                            containerId = collectionId,
                            containerType = IndexingContainerType.RESOURCE_COLLECTION,
                            knowledgeSources = newSources,
                            watchForChanges = true,
                        ),
                    )
                }
            } catch (e: Exception) {
                log.error("Failed to add knowledge sources", e)
                errorMessage = ErrorHandler.getUserFriendlyError(
                    e,
                    "adding knowledge sources",
                    LocalizationManager.getString("resourcecollections.error.updating"),
                )
            }
        }
    }

    /**
     * Delete a knowledge source from the collection.
     */
    fun deleteKnowledgeSource(source: KnowledgeSourceConfig) {
        val current = currentCollection ?: return
        scope.launch {
            try {
                val updated = current.knowledgeSources.filterNot { it.resourceIdentifier == source.resourceIdentifier }
                resourceCollectionService.updateCollection(
                    collectionId,
                    current.name,
                    current.description,
                    updated,
                )

                // Config update alone doesn't remove the source's embeddings/segments from the
                // index — RagIndexer only reacts to this event to actually delete them.
                EventBus.post(
                    IndexRemovalEvent(
                        containerId = collectionId,
                        containerType = IndexingContainerType.RESOURCE_COLLECTION,
                        knowledgeSource = source,
                        reason = "Knowledge source removed by user",
                    ),
                )

                loadCollection()
            } catch (e: Exception) {
                log.error("Failed to delete knowledge source", e)
                errorMessage = ErrorHandler.getUserFriendlyError(
                    e,
                    "deleting knowledge source",
                    LocalizationManager.getString("resourcecollections.error.updating"),
                )
            }
        }
    }

    /**
     * Toggle "watch for changes" for a local folder source. Persists the
     * preference and lets RagIndexer start/stop the watcher — no re-index needed.
     */
    fun toggleWatchForChanges(source: LocalFoldersKnowledgeSourceConfig, watch: Boolean) {
        val current = currentCollection ?: return
        scope.launch {
            try {
                val updatedSource = source.copy(watchForChanges = watch)
                val updatedSources = current.knowledgeSources.map {
                    if (it.resourceIdentifier == source.resourceIdentifier) updatedSource else it
                }

                withContext(Dispatchers.IO) {
                    resourceCollectionService.updateCollection(
                        collectionId,
                        current.name,
                        current.description,
                        updatedSources,
                    )
                }

                EventBus.post(
                    KnowledgeSourceWatchToggledEvent(
                        containerId = collectionId,
                        containerType = IndexingContainerType.RESOURCE_COLLECTION,
                        knowledgeSource = updatedSource,
                        watchForChanges = watch,
                    ),
                )

                loadCollection()
            } catch (e: Exception) {
                log.error("Failed to toggle watch for changes", e)
                errorMessage = ErrorHandler.getUserFriendlyError(
                    e,
                    "toggling watch for changes",
                    LocalizationManager.getString("resourcecollections.error.updating"),
                )
            }
        }
    }

    /**
     * Refresh the set of indexed local file paths for this collection.
     */
    private fun refreshIndexedPaths() {
        scope.launch {
            indexedPaths = withContext(Dispatchers.IO) {
                IndexStateManager.getIndexedLocalPathsForContainer(collectionId)
            }
        }
    }

    /** Re-checks [embeddingModelConfigured] and [embeddingSupportedByProvider] against [AppContext]. */
    fun refreshEmbeddingModelStatus() {
        embeddingModelConfigured = AppContext.getInstance().isEmbeddingModelConfigured()
        embeddingSupportedByProvider = AppContext.getInstance().activeProviderSupportsEmbedding()
    }

    /** Refreshes embedding-model status on provider/model changes, so it doesn't go stale while this view is open. */
    private fun observeEmbeddingModelEvents() {
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
     * Update [indexProgress] from EventBus indexing events — event-driven,
     * no polling or race conditions.
     */
    private fun observeIndexProgress() {
        scope.launch {
            merge(
                EventBus.internalEvents.filterIsInstance<IndexingQueuedEvent>()
                    .filter { it.containerType == IndexingContainerType.RESOURCE_COLLECTION && it.containerId == collectionId },
                EventBus.internalEvents.filterIsInstance<IndexingStartedEvent>()
                    .filter { it.containerType == IndexingContainerType.RESOURCE_COLLECTION && it.containerId == collectionId },
                EventBus.internalEvents.filterIsInstance<IndexingInProgressEvent>()
                    .filter { it.containerType == IndexingContainerType.RESOURCE_COLLECTION && it.containerId == collectionId },
                EventBus.internalEvents.filterIsInstance<IndexingCompletedEvent>()
                    .filter { it.containerType == IndexingContainerType.RESOURCE_COLLECTION && it.containerId == collectionId },
                EventBus.internalEvents.filterIsInstance<IndexingFailedEvent>()
                    .filter { it.containerType == IndexingContainerType.RESOURCE_COLLECTION && it.containerId == collectionId },
            ).collect { event ->
                indexProgress = when (event) {
                    is IndexingQueuedEvent -> IndexProgress(
                        status = IndexStatus.QUEUED,
                        blockedByName = event.blockedByContainerName,
                    )

                    is IndexingStartedEvent -> IndexProgress(status = IndexStatus.INDEXING)

                    is IndexingInProgressEvent -> IndexProgress(
                        status = IndexStatus.INDEXING,
                        totalFiles = event.totalFiles,
                        processedFiles = event.filesIndexed,
                        currentFileTotalChunks = event.totalChunks,
                        currentFileProcessedChunks = event.chunksIndexed,
                        resourceIdentifier = event.resourceId,
                        currentFile = event.currentFile,
                        currentFileElapsedMs = event.currentFileElapsedMs,
                    )

                    is IndexingCompletedEvent -> IndexProgress(
                        status = IndexStatus.READY,
                        processedFiles = event.filesIndexed,
                        totalFiles = event.filesIndexed,
                        skippedFileNames = event.skippedFileNames,
                    )

                    is IndexingFailedEvent -> IndexProgress(
                        status = IndexStatus.FAILED,
                        error = event.errorMessage,
                    )

                    else -> indexProgress
                }
                refreshIndexedPaths()
            }
        }
    }
}
