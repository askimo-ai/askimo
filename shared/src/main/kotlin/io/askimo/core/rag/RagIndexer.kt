/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag

import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.exception.ModelNotFoundException
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.store.embedding.EmbeddingStore
import io.askimo.core.analytics.Analytics
import io.askimo.core.analytics.AnalyticsEvent
import io.askimo.core.chat.domain.LocalFoldersKnowledgeSourceConfig
import io.askimo.core.chat.repository.ProjectRepository
import io.askimo.core.chat.repository.ResourceCollectionRepository
import io.askimo.core.context.AppContext
import io.askimo.core.db.DatabaseManager
import io.askimo.core.event.EventBus
import io.askimo.core.event.error.AppErrorEvent
import io.askimo.core.event.internal.ContainerDeletedEvent
import io.askimo.core.event.internal.IndexRemovalEvent
import io.askimo.core.event.internal.IndexingRequestedEvent
import io.askimo.core.event.internal.KnowledgeSourceRescanRequestedEvent
import io.askimo.core.event.internal.KnowledgeSourceWatchToggledEvent
import io.askimo.core.event.internal.ReIndexEvent
import io.askimo.core.event.user.IndexingCompletedEvent
import io.askimo.core.event.user.IndexingFailedEvent
import io.askimo.core.event.user.IndexingQueuedEvent
import io.askimo.core.event.user.IndexingStartedEvent
import io.askimo.core.exception.EmbeddingModelNotConfiguredException
import io.askimo.core.exception.ExceptionHandler
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.logging.logger
import io.askimo.core.rag.container.IndexingContainer
import io.askimo.core.rag.container.IndexingContainerType
import io.askimo.core.rag.container.asIndexingContainer
import io.askimo.core.rag.indexing.IndexingCoordinator
import io.askimo.core.rag.indexing.IndexingCoordinatorFactory
import io.askimo.core.rag.state.IndexProgress
import io.askimo.core.rag.state.IndexStatus
import io.askimo.core.util.AskimoHome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Manager for RAG (Retrieval-Augmented Generation) indexing of any [IndexingContainer]
 * — a [io.askimo.core.chat.domain.Project] or a [io.askimo.core.chat.domain.ResourceCollection].
 * Each container can have multiple coordinators — one per knowledge source.
 *
 * Concurrency model: a single [Channel]-based queue ensures only one indexing job runs at a
 * time across all containers (projects and collections alike). This avoids aggressive
 * concurrent embedding calls to remote endpoints (Ollama, Docker AI, etc.). A [ReIndexEvent]
 * or [ContainerDeletedEvent] for the currently-running container cancels it immediately;
 * other containers wait their turn in the FIFO queue.
 */
class RagIndexer(
    private val appContext: AppContext,
    private val projectRepository: ProjectRepository,
    private val resourceCollectionRepository: ResourceCollectionRepository,
) {
    private val log = logger<RagIndexer>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Composite key identifying a container across both projects and resource collections. */
    private data class ContainerKey(val id: String, val type: IndexingContainerType)

    private fun key(containerId: String, containerType: IndexingContainerType) = ContainerKey(containerId, containerType)

    /** Resolve the current [IndexingContainer] for a given key, or null if it no longer exists. */
    private fun resolveContainer(containerId: String, containerType: IndexingContainerType): IndexingContainer? = when (containerType) {
        IndexingContainerType.PROJECT -> projectRepository.getProject(containerId)?.asIndexingContainer()
        IndexingContainerType.RESOURCE_COLLECTION -> resourceCollectionRepository.getCollection(containerId)?.asIndexingContainer()
    }

    // Map of container key -> List of coordinators (one per knowledge source)
    private val coordinators = ConcurrentHashMap<ContainerKey, List<IndexingCoordinator<*>>>()

    // Map of container key -> EmbeddingStore, so we can close it when the container is removed
    private val embeddingStores = ConcurrentHashMap<ContainerKey, EmbeddingStore<TextSegment>>()

    // Per-container persistent progress StateFlow — updated eagerly at every state transition.
    // This ensures getProgressFlow() always returns the current state immediately, even when
    // the ViewModel subscribes after the event was emitted (hot flow miss).
    private val containerProgressFlows = ConcurrentHashMap<ContainerKey, MutableStateFlow<IndexProgress>>()

    private fun containerProgressFlow(containerId: String, containerType: IndexingContainerType): MutableStateFlow<IndexProgress> = containerProgressFlows.getOrPut(key(containerId, containerType)) { MutableStateFlow(IndexProgress()) }

    private fun updateContainerProgress(containerId: String, containerType: IndexingContainerType, progress: IndexProgress) {
        containerProgressFlow(containerId, containerType).value = progress
        persistCollectionIndexStatus(containerId, containerType, progress.status, progress.error)
    }

    /**
     * Persist the indexing status to the database for resource collections so it survives
     * app restarts and can be shown in list/detail views without depending on live in-memory
     * event state. No-op for projects (which don't currently persist index status).
     */
    private fun persistCollectionIndexStatus(
        containerId: String,
        containerType: IndexingContainerType,
        status: IndexStatus,
        error: String? = null,
    ) {
        if (containerType != IndexingContainerType.RESOURCE_COLLECTION) return
        try {
            resourceCollectionRepository.updateIndexStatus(containerId, status, error)
        } catch (e: Exception) {
            log.warn("Failed to persist index status for resource collection $containerId", e)
        }
    }

    /**
     * Re-broadcasts current progress for a container whose indexing request was deduped
     * because it's already fully indexed. Without this, a late/re-subscribed listener (e.g.
     * a ViewModel created after indexing finished, defaulting to NOT_STARTED) would never
     * learn the container is READY/WATCHING and would show "waiting to start" forever.
     */
    private fun reportAlreadyIndexed(
        containerId: String,
        containerType: IndexingContainerType,
        existingCoordinators: List<IndexingCoordinator<*>>,
    ) {
        val isWatching = existingCoordinators.any { it.progress.value.status == IndexStatus.WATCHING }
        val totalFilesIndexed = existingCoordinators.sumOf { it.progress.value.processedFiles }
        val allSkippedFileNames = existingCoordinators.flatMap { it.progress.value.skippedFileNames }

        updateContainerProgress(
            containerId,
            containerType,
            IndexProgress(
                status = if (isWatching) IndexStatus.WATCHING else IndexStatus.READY,
                processedFiles = totalFilesIndexed,
                totalFiles = totalFilesIndexed,
                skippedFileNames = allSkippedFileNames,
            ),
        )

        val containerName = try {
            resolveContainer(containerId, containerType)?.name
        } catch (e: Exception) {
            log.warn("Failed to resolve container name for already-indexed report: $containerId", e)
            null
        } ?: containerId

        EventBus.post(
            IndexingCompletedEvent(
                containerId = containerId,
                containerName = containerName,
                containerType = containerType,
                filesIndexed = totalFilesIndexed,
                skippedFileNames = allSkippedFileNames,
            ),
        )
    }

    // ── Single-queue concurrency model ──────────────────────────────────────────────────────
    // A single consumer coroutine processes tasks one at a time. ReIndex and Delete preempt
    // a running job for the *same* container; otherwise tasks wait in FIFO order. One global
    // queue is shared by projects and resource collections so we never run concurrent
    // embedding calls against the same (often rate/cost limited) endpoint.

    private sealed class IndexingTask {
        abstract val containerId: String
        abstract val containerType: IndexingContainerType

        data class Index(
            override val containerId: String,
            override val containerType: IndexingContainerType,
            val event: IndexingRequestedEvent,
        ) : IndexingTask()

        data class ReIndex(
            override val containerId: String,
            override val containerType: IndexingContainerType,
            val event: ReIndexEvent,
        ) : IndexingTask()

        data class Delete(
            override val containerId: String,
            override val containerType: IndexingContainerType,
        ) : IndexingTask()

        data class RemoveSource(
            override val containerId: String,
            override val containerType: IndexingContainerType,
            val event: IndexRemovalEvent,
        ) : IndexingTask()

        data class ToggleWatch(
            override val containerId: String,
            override val containerType: IndexingContainerType,
            val event: KnowledgeSourceWatchToggledEvent,
        ) : IndexingTask()

        data class RescanSource(
            override val containerId: String,
            override val containerType: IndexingContainerType,
            val event: KnowledgeSourceRescanRequestedEvent,
        ) : IndexingTask()
    }

    // Key of the container whose task is currently executing. Written only by the consumer
    // coroutine; read by event-collectors to detect when to show QUEUED.
    @Volatile private var activeContainerKey: ContainerKey? = null

    // Active job reference — written by the consumer, read + cancelled by the ReIndexEvent
    // collector to preempt a running job immediately on re-index request.
    @Volatile private var activeJob: Job? = null

    // Name of the container currently being indexed. Set alongside activeContainerKey,
    // before the child job launches, so collectors that see one also see the other.
    @Volatile private var activeBlockingContainerName: String? = null

    private val taskChannel = Channel<IndexingTask>(capacity = Channel.UNLIMITED)

    // Prevents duplicate ReIndex tasks from piling up for a queued (non-active) container.
    // Uses ConcurrentHashMap.newKeySet for thread-safe add/remove/contains.
    private val pendingReIndexKeys = ConcurrentHashMap.newKeySet<ContainerKey>()

    init {
        scope.launch {
            EventBus.internalEvents
                .filterIsInstance<ContainerDeletedEvent>()
                .collect { event ->
                    log.info("${event.containerType} deleted, cleaning up coordinator: ${event.containerId}")
                    taskChannel.send(IndexingTask.Delete(event.containerId, event.containerType))
                }
        }

        scope.launch {
            EventBus.internalEvents
                .filterIsInstance<ReIndexEvent>()
                .collect { event ->
                    log.info("Re-index requested for ${event.containerType} ${event.containerId}: ${event.reason}")
                    val taskKey = key(event.containerId, event.containerType)
                    val isActive = activeContainerKey == taskKey
                    if (isActive) {
                        // Cancel the running job immediately so the consumer's join() unblocks
                        // right away instead of waiting for the current indexing to finish first
                        // (otherwise it finishes normally, then re-index starts — two full cycles).
                        log.info("Re-index for active container $taskKey — cancelling current indexing immediately")
                        activeJob?.cancel()
                    } else if (!pendingReIndexKeys.add(taskKey)) {
                        // Already queued for this container — ignore duplicate.
                        log.debug("Re-index for container {} already queued, ignoring duplicate", taskKey)
                        return@collect
                    } else if (activeContainerKey != null) {
                        // A different container is running; this one waits in the queue
                        updateContainerProgress(
                            event.containerId,
                            event.containerType,
                            IndexProgress(status = IndexStatus.QUEUED, blockedByName = activeBlockingContainerName),
                        )
                    }
                    taskChannel.send(IndexingTask.ReIndex(event.containerId, event.containerType, event))
                }
        }

        scope.launch {
            EventBus.internalEvents
                .filterIsInstance<IndexingRequestedEvent>()
                .collect { event ->
                    log.info("Indexing requested for ${event.containerType} ${event.containerId}")
                    val taskKey = key(event.containerId, event.containerType)
                    if (activeContainerKey != null && activeContainerKey != taskKey) {
                        updateContainerProgress(
                            event.containerId,
                            event.containerType,
                            IndexProgress(status = IndexStatus.QUEUED, blockedByName = activeBlockingContainerName),
                        )
                    }

                    taskChannel.send(IndexingTask.Index(event.containerId, event.containerType, event))
                }
        }

        scope.launch {
            EventBus.internalEvents
                .filterIsInstance<IndexRemovalEvent>()
                .collect { event ->
                    log.info("Indexing removal requested for ${event.containerType} ${event.containerId}")
                    taskChannel.send(IndexingTask.RemoveSource(event.containerId, event.containerType, event))
                }
        }

        scope.launch {
            EventBus.internalEvents
                .filterIsInstance<KnowledgeSourceWatchToggledEvent>()
                .collect { event ->
                    log.info(
                        "Watch toggle requested for ${event.containerType} ${event.containerId}, " +
                            "source ${event.knowledgeSource.resourceIdentifier}: ${event.watchForChanges}",
                    )
                    taskChannel.send(IndexingTask.ToggleWatch(event.containerId, event.containerType, event))
                }
        }

        scope.launch {
            EventBus.internalEvents
                .filterIsInstance<KnowledgeSourceRescanRequestedEvent>()
                .collect { event ->
                    log.info(
                        "Rescan requested for ${event.containerType} ${event.containerId}, " +
                            "source ${event.knowledgeSource.resourceIdentifier}",
                    )
                    taskChannel.send(IndexingTask.RescanSource(event.containerId, event.containerType, event))
                }
        }

        // ── Single consumer — one task at a time ────────────────────────────────────────────
        scope.launch {
            for (task in taskChannel) {
                val taskKey = key(task.containerId, task.containerType)

                // ReIndex and Delete preempt a running job for the *same* container.
                // Jobs for *different* containers are never cancelled — they wait their turn.
                if ((task is IndexingTask.ReIndex || task is IndexingTask.Delete) &&
                    taskKey == activeContainerKey
                ) {
                    log.info(
                        "Preempting active indexing for container $taskKey " +
                            "(incoming: ${task::class.simpleName})",
                    )
                    activeJob?.cancel()
                    activeJob?.join()
                    activeContainerKey = null
                }

                activeContainerKey = taskKey
                activeBlockingContainerName = resolveContainer(task.containerId, task.containerType)?.name
                activeJob = scope.launch {
                    when (task) {
                        is IndexingTask.Index -> handleIndexingRequest(task.event)

                        is IndexingTask.ReIndex -> {
                            pendingReIndexKeys.remove(taskKey)
                            handleReIndexRequest(task.event)
                        }

                        is IndexingTask.Delete -> removeCoordinator(task.containerId, task.containerType, deleteContainerFolder = true)

                        is IndexingTask.RemoveSource -> handleRemoveIndexEvent(task.event)

                        is IndexingTask.ToggleWatch -> handleWatchToggleEvent(task.event)

                        is IndexingTask.RescanSource -> handleRescanSourceEvent(task.event)
                    }
                }
                activeJob?.join() // sequential — next task starts only after this one finishes
                activeContainerKey = null
                activeBlockingContainerName = null
            }
        }
    }

    /**
     * Remove a container's coordinator and cleanup its resources.
     * @param containerId The container ID
     * @param containerType Whether this is a project or resource collection
     * @param deleteContainerFolder When true, deletes the entire container folder (container
     *   was deleted). When false, only index data is cleaned up (re-index scenario).
     */
    private fun removeCoordinator(containerId: String, containerType: IndexingContainerType, deleteContainerFolder: Boolean) {
        val containerKey = key(containerId, containerType)
        coordinators.remove(containerKey)?.forEach {
            it.clearAll()
            it.close()
        }

        // Release the JVector embedding store (holds all vectors in-memory)
        (embeddingStores.remove(containerKey) as? java.io.Closeable)?.let {
            try {
                it.close()
            } catch (e: Exception) {
                log.warn("Failed to close embedding store for container $containerId", e)
            }
        }

        if (deleteContainerFolder) {
            try {
                val containerDir = AskimoHome.projectsDir().resolve(containerId)
                if (containerDir.toFile().exists()) {
                    containerDir.toFile().deleteRecursively()
                }
            } catch (e: Exception) {
                log.error("Failed to delete container folder for container $containerId", e)
            }
        } else {
            cleanupIndexData(containerId)
        }
        // Reset persistent progress so the UI shows NOT_STARTED after cleanup
        updateContainerProgress(containerId, containerType, IndexProgress())
    }

    /**
     * Clean up all index data for a container without deleting the container folder itself:
     * Lucene keyword index (in-memory + disk), JVector embedding store, DB segment mappings,
     * and the index.meta dimension marker. Used by both re-index and dimension-mismatch flows.
     */
    private fun cleanupIndexData(containerId: String) {
        // Remove in-memory Lucene instance
        LuceneIndexer.removeInstance(containerId)

        // Delete index files on disk (jvector + lucene + index.meta)
        try {
            val indexDir = RagUtils.getProjectIndexDir(containerId, createIfNotExists = false)
            if (indexDir.toFile().exists()) {
                indexDir.toFile().deleteRecursively()
            }
        } catch (e: Exception) {
            log.error("Failed to delete index files for container $containerId", e)
        }

        // Remove segment mappings from the database
        try {
            DatabaseManager.getInstance()
                .getResourceSegmentRepository()
                .removeAllSegmentMappingsForProject(containerId)
        } catch (e: Exception) {
            log.error("Failed to remove segment mappings from database for container $containerId", e)
        }
    }

    /**
     * Common indexing logic used by both initial indexing and re-indexing.
     *
     * @param embeddingModelId The provider-model identity snapshot captured by the caller at
     *   the same time as [embeddingModel]. Passed in rather than re-read from [appContext]
     *   here, since this method can run long after that snapshot was taken (tasks wait in
     *   the FIFO queue). Re-querying at save time could read a different provider than the
     *   one that produced [embeddingModel] if the user switched providers mid-flight — which
     *   would persist vectors tagged with the wrong identity and cause future mismatch checks
     *   to silently serve stale, incompatible vectors.
     * @param appendCoordinators When true, merges new coordinators with existing ones (used
     *   when adding a knowledge source to an already-indexed container). When false
     *   (default), replaces the coordinator list entirely.
     */
    private suspend fun performIndexing(
        container: IndexingContainer,
        knowledgeSources: List<io.askimo.core.chat.domain.KnowledgeSourceConfig>,
        embeddingStore: EmbeddingStore<TextSegment>,
        embeddingModel: EmbeddingModel,
        embeddingModelId: String?,
        watchForChanges: Boolean,
        appendCoordinators: Boolean = false,
    ) {
        val containerId = container.id
        val containerName = container.name
        val containerType = container.type
        val containerKey = key(containerId, containerType)
        val indexingStartMs = System.currentTimeMillis()

        val dimension = RagUtils.getDimensionForModel(embeddingModel)
        RagUtils.saveEmbeddingMetadata(containerId, dimension, embeddingModelId)

        // Register the store so removeCoordinator can close it and free in-memory vectors
        embeddingStores[containerKey] = embeddingStore

        val containerCoordinators = try {
            knowledgeSources.map { source ->
                IndexingCoordinatorFactory.createCoordinator(
                    containerId = containerId,
                    containerName = containerName,
                    containerType = containerType,
                    knowledgeSource = source,
                    embeddingStore = embeddingStore,
                    embeddingModel = embeddingModel,
                    appContext = appContext,
                )
            }
        } catch (e: Exception) {
            log.error("Failed to create indexing coordinators for container $containerId", e)
            val errorMessage = e.message ?: "Failed to create indexing coordinators"
            EventBus.emit(
                IndexingFailedEvent(
                    containerId = containerId,
                    containerName = containerName,
                    containerType = containerType,
                    errorMessage = errorMessage,
                ),
            )
            updateContainerProgress(containerId, containerType, IndexProgress(status = IndexStatus.FAILED, error = errorMessage))
            return
        }

        coordinators[containerKey] = if (appendCoordinators) {
            (coordinators[containerKey] ?: emptyList()) + containerCoordinators
        } else {
            containerCoordinators
        }

        // If this container was waiting in the queue, notify the UI now that we're starting.
        if (containerProgressFlow(containerId, containerType).value.status == IndexStatus.QUEUED) {
            EventBus.emit(
                IndexingQueuedEvent(
                    containerId = containerId,
                    containerName = containerName,
                    containerType = containerType,
                    blockedByContainerName = activeBlockingContainerName ?: containerName,
                ),
            )
        }

        // ── Begin indexing (channel consumer guarantees only one container indexes at a time) ──

        val startedProgress = IndexProgress(status = IndexStatus.INDEXING)
        updateContainerProgress(containerId, containerType, startedProgress)
        EventBus.emit(
            IndexingStartedEvent(
                containerId = containerId,
                containerName = containerName,
                containerType = containerType,
            ),
        )

        // Index sources sequentially — the embedding endpoint (Docker AI, Ollama, etc.)
        // handles one request at a time; parallel calls would just queue up and appear stuck.
        val results = containerCoordinators.map { coordinator ->
            val status = coordinator.progress.value.status
            when {
                status == IndexStatus.INDEXING -> {
                    log.debug(
                        "Coordinator for {} is already indexing — skipping duplicate startIndexing",
                        coordinator.knowledgeSourceConfig.resourceIdentifier,
                    )
                    true
                }

                coordinator.progress.value.isComplete -> {
                    log.debug(
                        "Coordinator for {} is already complete — skipping startIndexing",
                        coordinator.knowledgeSourceConfig.resourceIdentifier,
                    )
                    true
                }

                else -> {
                    log.info(
                        "Starting indexing for knowledge source: {}",
                        coordinator.knowledgeSourceConfig.resourceIdentifier,
                    )
                    try {
                        coordinator.startIndexing()
                    } catch (e: Exception) {
                        log.error("Failed to index knowledge source for container $containerId", e)
                        false
                    }
                }
            }
        }

        val success = results.all { it }

        if (success) {
            if (watchForChanges) {
                containerCoordinators.forEach { coordinator ->
                    try {
                        val shouldWatch = when (val config = coordinator.knowledgeSourceConfig) {
                            is LocalFoldersKnowledgeSourceConfig -> config.watchForChanges
                            else -> true
                        }
                        if (shouldWatch) {
                            coordinator.startWatching(scope)
                        } else {
                            return@forEach
                        }
                    } catch (e: Exception) {
                        log.error("Failed to start watching for container $containerId", e)
                    }
                }
            }

            val totalFilesIndexed = containerCoordinators.sumOf { it.progress.value.processedFiles }
            val allSkippedFileNames = containerCoordinators.flatMap { it.progress.value.skippedFileNames }

            val indexDurationMs = System.currentTimeMillis() - indexingStartMs
            val durationBucket = when {
                indexDurationMs < 5_000L -> "<5s"
                indexDurationMs < 30_000L -> "5-30s"
                else -> ">30s"
            }
            Analytics.track(
                AnalyticsEvent.RAG_INDEXED,
                mapOf(
                    "file_count" to totalFilesIndexed.toString(),
                    "index_duration_bucket" to durationBucket,
                    "container_type" to containerType.name,
                ),
            )

            EventBus.emit(
                IndexingCompletedEvent(
                    containerId = containerId,
                    containerName = containerName,
                    containerType = containerType,
                    filesIndexed = totalFilesIndexed,
                    skippedFileNames = allSkippedFileNames,
                ),
            )
            updateContainerProgress(
                containerId,
                containerType,
                IndexProgress(
                    status = IndexStatus.READY,
                    processedFiles = totalFilesIndexed,
                    totalFiles = totalFilesIndexed,
                    skippedFileNames = allSkippedFileNames,
                ),
            )
        } else {
            val errors = containerCoordinators
                .mapNotNull { it.progress.value.error }
                .joinToString("; ")
                .takeIf { it.isNotEmpty() } ?: "Unknown error"

            EventBus.emit(
                IndexingFailedEvent(
                    containerId = containerId,
                    containerName = containerName,
                    containerType = containerType,
                    errorMessage = errors,
                ),
            )
            updateContainerProgress(containerId, containerType, IndexProgress(status = IndexStatus.FAILED, error = errors))
        }

        log.info(
            "Indexing ${if (success) "completed" else "failed"} for container $containerId " +
                "(${containerCoordinators.size} knowledge source(s))",
        )
    }

    /**
     * Handle a re-index request. Re-index always takes priority — if the container was
     * being indexed, the consumer coroutine already cancelled and joined that job first.
     */
    private suspend fun handleReIndexRequest(event: ReIndexEvent) {
        try {
            val containerId = event.containerId
            val containerType = event.containerType

            val container = try {
                resolveContainer(containerId, containerType)
            } catch (e: Exception) {
                log.error("Failed to get container $containerId for re-indexing", e)
                return
            }

            if (container != null) {
                val embeddingModel = appContext.getEmbeddingModel()
                checkEmbeddingModelAvailable(embeddingModel)
                val embeddingModelId = appContext.activeEmbeddingModelIdentity()

                removeCoordinator(containerId, containerType, false)
                log.info("Cleaned up existing index data for container $containerId, starting re-index")

                val dimension = RagUtils.getDimensionForModel(embeddingModel)
                val embeddingStore = RagUtils.getEmbeddingStoreWithDimension(containerId, dimension)

                performIndexing(
                    container = container,
                    knowledgeSources = container.knowledgeSources,
                    embeddingStore = embeddingStore,
                    embeddingModel = embeddingModel,
                    embeddingModelId = embeddingModelId,
                    watchForChanges = true,
                )
            }
        } catch (e: CancellationException) {
            // Job cancelled (e.g. container deleted while re-indexing) — not an error.
            log.info("Re-index job cancelled for container ${event.containerId}: ${e.message}")
        } catch (e: EmbeddingModelNotConfiguredException) {
            // No embedding model configured — a normal, recoverable state surfaced via the
            // "configure embedding model" banner, not an unexpected error. Skip quietly.
            log.debug("Skipping re-index for container ${event.containerId}: ${e.message}")
        } catch (e: Exception) {
            log.error("Failed to handle re-index request for container ${event.containerId}", e)
            val errorMessage = e.message.takeIf { !it.isNullOrBlank() } ?: "Unknown error"
            EventBus.emit(
                AppErrorEvent(
                    title = "Failed to index knowledge source for container",
                    message = errorMessage,
                ),
            )
            EventBus.emit(
                IndexingFailedEvent(
                    containerId = event.containerId,
                    containerName = event.containerId,
                    containerType = event.containerType,
                    errorMessage = errorMessage,
                ),
            )
            updateContainerProgress(event.containerId, event.containerType, IndexProgress(status = IndexStatus.FAILED, error = errorMessage))
        }
    }

    private fun handleRemoveIndexEvent(event: IndexRemovalEvent) {
        try {
            val containerId = event.containerId
            val containerType = event.containerType
            val containerKey = key(containerId, containerType)
            val knowledgeSource = event.knowledgeSource

            val containerCoordinators = coordinators[containerKey]
            if (containerCoordinators != null) {
                val coordinatorToRemove = containerCoordinators.find {
                    it.knowledgeSourceConfig == event.knowledgeSource
                }
                if (coordinatorToRemove != null) {
                    coordinatorToRemove.clearAll()
                    coordinatorToRemove.close()

                    // Remove from the list of coordinators for this container
                    coordinators[containerKey] = containerCoordinators.filterNot {
                        it.knowledgeSourceConfig.resourceIdentifier == event.knowledgeSource.resourceIdentifier
                    }

                    log.info("Removed index for knowledge source ${knowledgeSource.resourceIdentifier} from container $containerId")
                } else {
                    log.warn("No coordinator found for knowledge source ${knowledgeSource.resourceIdentifier} in container $containerId")
                }
            } else {
                log.warn("No coordinators found for container $containerId when trying to remove index for source ${knowledgeSource.resourceIdentifier}")
            }
        } catch (e: Exception) {
            log.error("Failed to handle index removal request for container ${event.containerId}", e)
        }
    }

    private suspend fun handleWatchToggleEvent(event: KnowledgeSourceWatchToggledEvent) {
        try {
            val containerId = event.containerId
            val containerKey = key(containerId, event.containerType)
            val knowledgeSource = event.knowledgeSource

            val containerCoordinators = coordinators[containerKey]
            if (containerCoordinators != null) {
                val coordinatorToToggle = containerCoordinators.find {
                    it.knowledgeSourceConfig.resourceIdentifier == event.knowledgeSource.resourceIdentifier
                }

                if (coordinatorToToggle != null) {
                    if (event.watchForChanges) {
                        try {
                            coordinatorToToggle.startWatching(scope)
                        } catch (e: Exception) {
                            log.error(
                                "Failed to start watching for container ${event.containerId}",
                                e,
                            )
                            EventBus.emit(
                                AppErrorEvent(
                                    title = "Failed to watch knowledge source for changes",
                                    message = e.message.takeIf { !it.isNullOrBlank() }
                                        ?: "Unknown error",
                                ),
                            )
                        }
                    } else {
                        coordinatorToToggle.stopWatching()
                    }

                    log.info(
                        "Updated watch state for knowledge source ${knowledgeSource.resourceIdentifier} " +
                            "in container $containerId (watching=${event.watchForChanges})",
                    )
                } else {
                    log.warn("No coordinator found for knowledge source ${knowledgeSource.resourceIdentifier} in container $containerId")
                }
            } else {
                log.warn("No coordinators found for container $containerId when trying to toggle watch for source ${knowledgeSource.resourceIdentifier}")
            }
        } catch (e: Exception) {
            log.error(
                "The request to toggle change tracking could not be processed ${event.containerId}",
                e,
            )
        }
    }

    private suspend fun handleRescanSourceEvent(event: KnowledgeSourceRescanRequestedEvent) {
        try {
            val containerId = event.containerId
            val containerType = event.containerType
            val containerKey = key(containerId, containerType)
            val knowledgeSource = event.knowledgeSource

            val containerCoordinators = coordinators[containerKey]
            if (containerCoordinators != null) {
                val coordinatorToRescan = containerCoordinators.find {
                    it.knowledgeSourceConfig.resourceIdentifier == event.knowledgeSource.resourceIdentifier
                }

                if (coordinatorToRescan != null) {
                    try {
                        val indexing = coordinatorToRescan.startIndexing()
                        if (indexing) {
                            EventBus.emit(
                                IndexingCompletedEvent(
                                    containerId = containerId,
                                    containerName = resolveContainer(containerId, containerType)?.name ?: "",
                                    containerType = containerType,
                                    filesIndexed = coordinatorToRescan.progress.value.processedFiles,
                                    skippedFileNames = coordinatorToRescan.progress.value.skippedFileNames,
                                ),
                            )
                        } else {
                            log.warn(
                                "Rescan did not complete successfully for knowledge source " +
                                    "${knowledgeSource.resourceIdentifier} in container $containerId",
                            )
                            EventBus.emit(
                                AppErrorEvent(
                                    title = "Failed to rescan knowledge source",
                                    message = "Rescan of ${knowledgeSource.resourceIdentifier} did not complete successfully.",
                                ),
                            )
                        }
                    } catch (e: Exception) {
                        log.error(
                            "Failed to rescan knowledge source for container ${event.containerId}",
                            e,
                        )
                        EventBus.emit(
                            AppErrorEvent(
                                title = "Failed to rescan knowledge source",
                                message = e.message.takeIf { !it.isNullOrBlank() }
                                    ?: "Unknown error",
                            ),
                        )
                    }
                } else {
                    log.warn("No coordinator found for knowledge source ${knowledgeSource.resourceIdentifier} in container $containerId")
                    EventBus.emit(
                        AppErrorEvent(
                            title = "Failed to rescan knowledge source",
                            message = "${knowledgeSource.resourceIdentifier} hasn't finished indexing yet. " +
                                "Try rescanning again once indexing completes.",
                        ),
                    )
                }
            } else {
                log.warn("No coordinators found for container $containerId when trying to rescan source ${knowledgeSource.resourceIdentifier}")
                EventBus.emit(
                    AppErrorEvent(
                        title = "Failed to rescan knowledge source",
                        message = "${knowledgeSource.resourceIdentifier} hasn't finished indexing yet. " +
                            "Try rescanning again once indexing completes.",
                    ),
                )
            }
        } catch (e: Exception) {
            log.error(
                "The request to rescan knowledge source could not be processed for container ${event.containerId}",
                e,
            )
        }
    }

    /**
     * Handle an indexing request for any container (project or resource collection). Uses
     * embedding components supplied by the requester (e.g. ChatSessionService) directly,
     * avoiding duplicate initialization. Shares duplicate-prevention logic with
     * handleReIndexRequest to prevent race conditions.
     *
     * Also detects embedding dimension mismatches (model changed since last index) and
     * silently cleans up stale index data before re-indexing with the new model.
     */
    private suspend fun handleIndexingRequest(event: IndexingRequestedEvent) {
        try {
            val containerId = event.containerId
            val containerType = event.containerType
            val containerKey = key(containerId, containerType)

            // Create embedding model early so we can check the dimension before anything else
            val embeddingModel = run {
                val model = appContext.getEmbeddingModel()
                checkEmbeddingModelAvailable(model)
                model
            }

            // ── Dimension / model-identity mismatch check ──────────────────────
            // If the container was previously indexed with a different embedding model,
            // wipe stale index data and re-index from scratch with the current model.
            // Two signals are checked: dimension (catches any model with a different output
            // vector size) and model identity (catches a different model with the same
            // dimension, which a dimension-only check would miss and silently reuse
            // incompatible stale vectors). modelId comparison only applies when both stored
            // and current values are known — older indexes have no stored modelId and fall
            // back to the dimension check alone.
            val currentDimension = RagUtils.getDimensionForModel(embeddingModel)
            val currentModelId = appContext.activeEmbeddingModelIdentity()
            val storedDimension = RagUtils.getStoredEmbeddingDimension(containerId)
            val storedModelId = RagUtils.getStoredEmbeddingModelId(containerId)

            val dimensionMismatch = storedDimension != null && storedDimension != currentDimension
            val modelIdMismatch = storedModelId != null && currentModelId != null && storedModelId != currentModelId

            if (storedDimension != null && (dimensionMismatch || modelIdMismatch)) {
                log.warn(
                    "Embedding model changed for container {} (dimension stored={}, current={}; " +
                        "model stored={}, current={}). Clearing stale index data and re-indexing.",
                    containerId,
                    storedDimension,
                    currentDimension,
                    storedModelId,
                    currentModelId,
                )
                // Close existing coordinators and wipe everything (Lucene, JVector, DB)
                coordinators.remove(containerKey)?.forEach {
                    it.clearAll()
                    it.close()
                }
                cleanupIndexData(containerId)
                // Fall through — indexing runs with a clean slate
            } else {
                // ── Normal duplicate / in-progress guard ─────────────────────
                // Only applies to whole-container re-indexing (event.knowledgeSources == null).
                // When specific sources are provided (e.g. a newly added reference material),
                // skip the guard so those sources are always indexed and appended.
                if (event.knowledgeSources == null) {
                    val existingCoordinators = coordinators[containerKey]

                    if (existingCoordinators != null && existingCoordinators.all { it.progress.value.isComplete }) {
                        log.debug("Container $containerId already indexed, skipping duplicate request")
                        reportAlreadyIndexed(containerId, containerType, existingCoordinators)
                        return
                    }

                    if (existingCoordinators != null && existingCoordinators.any { it.progress.value.status == IndexStatus.INDEXING }) {
                        log.debug("Container $containerId is currently being indexed, skipping duplicate request")
                        return
                    }
                }
            }

            val embeddingStore = RagUtils.getEmbeddingStoreWithDimension(containerId, currentDimension)

            val container = try {
                resolveContainer(containerId, containerType)
            } catch (e: Exception) {
                log.error("Failed to get container $containerId for indexing", e)
                return
            }
            if (container != null) {
                val newSources = event.knowledgeSources
                if (newSources != null) {
                    // Specific sources requested (e.g. newly added reference material) —
                    // index only those sources and append coordinators to the existing list.
                    log.info(
                        "Indexing {} new knowledge source(s) for container $containerId",
                        newSources.size,
                    )
                    performIndexing(
                        container = container,
                        knowledgeSources = newSources,
                        embeddingStore = embeddingStore,
                        embeddingModel = embeddingModel,
                        embeddingModelId = currentModelId,
                        watchForChanges = event.watchForChanges,
                        appendCoordinators = true,
                    )
                } else {
                    // Full container index (startup / first-time)
                    performIndexing(
                        container = container,
                        knowledgeSources = container.knowledgeSources,
                        embeddingStore = embeddingStore,
                        embeddingModel = embeddingModel,
                        embeddingModelId = currentModelId,
                        watchForChanges = event.watchForChanges,
                    )
                }
            }
        } catch (e: CancellationException) {
            // Job cancelled (e.g. container deleted while indexing) — not an error.
            log.info("Indexing job cancelled for container ${event.containerId}: ${e.message}")
        } catch (e: EmbeddingModelNotConfiguredException) {
            // No embedding model configured — a normal, recoverable state surfaced via the
            // "configure embedding model" banner, not an unexpected error. Skip quietly.
            log.debug("Skipping indexing for container ${event.containerId}: ${e.message}")
        } catch (e: Exception) {
            log.error("Failed to handle indexing request for container ${event.containerId}", e)

            val message = ExceptionHandler.handle(e, contextId = event.containerId)

            EventBus.emit(
                AppErrorEvent(
                    title = LocalizationManager.getString("error.indexing.failed.title"),
                    message = message,
                ),
            )

            EventBus.emit(
                IndexingFailedEvent(
                    containerId = event.containerId,
                    containerName = event.containerId,
                    containerType = event.containerType,
                    errorMessage = message,
                ),
            )
            updateContainerProgress(event.containerId, event.containerType, IndexProgress(status = IndexStatus.FAILED, error = message))
        }
    }

    /**
     * Returns a persistent [MutableStateFlow] for a container's index progress, updated
     * eagerly on every state transition (QUEUED → INDEXING → READY/FAILED) so a newly
     * created ViewModel always gets the current state without missing events.
     */
    fun getProgressFlow(containerId: String, containerType: IndexingContainerType): Flow<IndexProgress> = containerProgressFlow(containerId, containerType)

    /**
     * Check if a container has been successfully indexed.
     * @param containerId The container ID to check
     * @param containerType Whether this is a project or resource collection
     * @return true if the container has a valid index
     */
    fun isContainerIndexed(containerId: String, containerType: IndexingContainerType): Boolean {
        // Coordinators exist and all indexing is complete
        val containerCoordinators = coordinators[key(containerId, containerType)]
        if (containerCoordinators != null && containerCoordinators.all { it.progress.value.isComplete }) {
            return true
        }

        // Index files exist on disk (container indexed in a previous session)
        val indexDir = RagUtils.getProjectIndexDir(containerId, createIfNotExists = false)
        if (!indexDir.toFile().exists()) {
            return false
        }

        // Verify validity by checking for essential files
        val indexFiles = indexDir.toFile().listFiles() ?: return false
        return indexFiles.any { it.name.startsWith("segments_") } // Lucene segment files indicate valid index
    }

    /**
     * Check if the embedding model is available and functional.
     * @throws ModelNotFoundException if the model is not found or unavailable
     */
    private fun checkEmbeddingModelAvailable(embeddingModel: EmbeddingModel) {
        try {
            embeddingModel.embed(TextSegment.from("ping"))
        } catch (e: Exception) {
            if (e is ModelNotFoundException ||
                e.message?.contains("model not found", ignoreCase = true) == true
            ) {
                log.error("Embedding model not found: ${e.message}")
                throw ModelNotFoundException("Embedding model not found: ${e.message}", e)
            } else {
                throw e
            }
        }
    }
}
