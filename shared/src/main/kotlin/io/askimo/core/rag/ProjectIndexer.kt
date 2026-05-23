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
import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.chat.repository.ProjectRepository
import io.askimo.core.context.AppContext
import io.askimo.core.db.DatabaseManager
import io.askimo.core.event.EventBus
import io.askimo.core.event.error.AppErrorEvent
import io.askimo.core.event.internal.ProjectDeletedEvent
import io.askimo.core.event.internal.ProjectIndexRemovalEvent
import io.askimo.core.event.internal.ProjectIndexingRequestedEvent
import io.askimo.core.event.internal.ProjectReIndexEvent
import io.askimo.core.event.user.IndexingCompletedEvent
import io.askimo.core.event.user.IndexingFailedEvent
import io.askimo.core.event.user.IndexingQueuedEvent
import io.askimo.core.event.user.IndexingStartedEvent
import io.askimo.core.logging.logger
import io.askimo.core.rag.indexing.IndexingCoordinator
import io.askimo.core.rag.indexing.IndexingCoordinatorFactory
import io.askimo.core.rag.state.IndexProgress
import io.askimo.core.rag.state.IndexStatus
import io.askimo.core.util.AskimoHome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Manager for project indexing with RAG (Retrieval-Augmented Generation).
 * Each project can have multiple coordinators - one per knowledge source.
 */
class ProjectIndexer(
    private val appContext: AppContext,
    private val projectRepository: ProjectRepository,
) {
    private val log = logger<ProjectIndexer>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Map of projectId -> List of coordinators (one per knowledge source)
    private val coordinators = ConcurrentHashMap<String, List<IndexingCoordinator<*>>>()

    // Map of projectId -> EmbeddingStore, so we can close it when the project is removed
    private val embeddingStores = ConcurrentHashMap<String, EmbeddingStore<TextSegment>>()

    // Per-project persistent progress StateFlow — updated eagerly at every state transition.
    // This ensures getProgressFlow() always returns the current state immediately, even when
    // the ViewModel subscribes after the event was emitted (hot flow miss).
    private val projectProgressFlows = ConcurrentHashMap<String, MutableStateFlow<IndexProgress>>()

    private fun projectProgressFlow(projectId: String): MutableStateFlow<IndexProgress> = projectProgressFlows.getOrPut(projectId) { MutableStateFlow(IndexProgress()) }

    private fun updateProjectProgress(projectId: String, progress: IndexProgress) {
        projectProgressFlow(projectId).value = progress
    }

    // Global mutex — only one project indexes at a time to avoid contention on the
    // shared embedding model endpoint (Ollama, Docker AI, etc.).
    // Projects that arrive while another is indexing show QUEUED status.
    private val indexingMutex = Mutex()
    init {
        scope.launch {
            EventBus.internalEvents
                .filterIsInstance<ProjectDeletedEvent>()
                .collect { event ->
                    log.info("Project deleted, cleaning up coordinator: ${event.projectId}")
                    removeCoordinator(event.projectId, true)
                }
        }

        scope.launch {
            EventBus.internalEvents
                .filterIsInstance<ProjectReIndexEvent>()
                .collect { event ->
                    log.info("Re-index requested for project ${event.projectId}: ${event.reason}")
                    scope.launch { handleReIndexRequest(event) }
                }
        }

        scope.launch {
            EventBus.internalEvents
                .filterIsInstance<ProjectIndexingRequestedEvent>()
                .collect { event ->
                    log.info("Indexing requested for project ${event.projectId}")
                    // Launch each request in its own coroutine so the collector never blocks.
                    // Without this: when Project A suspends inside indexingMutex.withLock,
                    // the collector is stuck — Project B's event queues up but handleIndexingRequest(B)
                    // only runs after A finishes, at which point isLocked=false and QUEUED is missed.
                    scope.launch { handleIndexingRequest(event) }
                }
        }

        scope.launch {
            EventBus.internalEvents
                .filterIsInstance<ProjectIndexRemovalEvent>()
                .collect { event ->
                    log.info("Indexing removal requested for project ${event.projectId}")
                    handleRemoveIndexEvent(event)
                }
        }
    }

    /**
     * Remove coordinator and cleanup resources.
     * @param projectId The project ID
     * @param deleteProjectFolder When true the entire project folder is deleted (project was deleted).
     *                            When false only index data is cleaned up (re-index scenario).
     */
    private fun removeCoordinator(projectId: String, deleteProjectFolder: Boolean) {
        coordinators.remove(projectId)?.forEach {
            it.clearAll()
            it.close()
        }

        // Release the JVector embedding store (holds all vectors in-memory)
        (embeddingStores.remove(projectId) as? java.io.Closeable)?.let {
            try {
                it.close()
            } catch (e: Exception) {
                log.warn("Failed to close embedding store for project $projectId", e)
            }
        }

        if (deleteProjectFolder) {
            try {
                val projectDir = AskimoHome.projectsDir().resolve(projectId)
                if (projectDir.toFile().exists()) {
                    projectDir.toFile().deleteRecursively()
                }
            } catch (e: Exception) {
                log.error("Failed to delete project folder for project $projectId", e)
            }
        } else {
            cleanupIndexData(projectId)
        }
        // Reset the persistent progress so the UI shows NOT_STARTED after cleanup
        updateProjectProgress(projectId, IndexProgress())
    }

    /**
     * Clean up all index data for a project without deleting the project folder itself.
     * Covers:
     *  - Lucene keyword index (in-memory instance + disk files)
     *  - JVector embedding store (disk files inside the index dir)
     *  - Database segment mappings
     *  - index.meta dimension marker
     *
     * Used by both re-index and embedding-dimension-mismatch flows.
     */
    private fun cleanupIndexData(projectId: String) {
        // 1. Remove in-memory Lucene instance
        LuceneIndexer.removeInstance(projectId)

        // 2. Delete index files on disk (jvector + lucene + index.meta)
        try {
            val indexDir = RagUtils.getProjectIndexDir(projectId, createIfNotExists = false)
            if (indexDir.toFile().exists()) {
                indexDir.toFile().deleteRecursively()
            }
        } catch (e: Exception) {
            log.error("Failed to delete index files for project $projectId", e)
        }

        // 3. Remove segment mappings from the database
        try {
            DatabaseManager.getInstance()
                .getResourceSegmentRepository()
                .removeAllSegmentMappingsForProject(projectId)
        } catch (e: Exception) {
            log.error("Failed to remove segment mappings from database for project $projectId", e)
        }
    }

    /**
     * Common indexing logic used by both initial indexing and re-indexing.
     *
     * @param appendCoordinators When true, new coordinators are merged with any existing ones
     *   (used when adding a new knowledge source to an already-indexed project).
     *   When false (default), the coordinator list is replaced entirely.
     */
    private suspend fun performIndexing(
        projectId: String,
        projectName: String,
        knowledgeSources: List<KnowledgeSourceConfig>,
        embeddingStore: EmbeddingStore<TextSegment>,
        embeddingModel: EmbeddingModel,
        watchForChanges: Boolean,
        appendCoordinators: Boolean = false,
    ) {
        val indexingStartMs = System.currentTimeMillis()

        val dimension = RagUtils.getDimensionForModel(embeddingModel)
        RagUtils.saveEmbeddingDimension(projectId, dimension)

        // Register the store so removeCoordinator can close it and free in-memory vectors
        embeddingStores[projectId] = embeddingStore

        val projectCoordinators = try {
            knowledgeSources.map { source ->
                IndexingCoordinatorFactory.createCoordinator(
                    projectId = projectId,
                    projectName = projectName,
                    knowledgeSource = source,
                    embeddingStore = embeddingStore,
                    embeddingModel = embeddingModel,
                    appContext = appContext,
                )
            }
        } catch (e: Exception) {
            log.error("Failed to create indexing coordinators for project $projectId", e)
            EventBus.emit(
                IndexingFailedEvent(
                    projectId = projectId,
                    projectName = projectName,
                    errorMessage = e.message ?: "Failed to create indexing coordinators",
                ),
            )
            return
        }

        coordinators[projectId] = if (appendCoordinators) {
            (coordinators[projectId] ?: emptyList()) + projectCoordinators
        } else {
            projectCoordinators
        }

        // Mark as QUEUED if the mutex is already held by another project's indexing
        if (indexingMutex.isLocked) {
            log.info("Project $projectId queued — another project is currently indexing")
            projectCoordinators.forEach { it.markQueued() }
            val queuedProgress = IndexProgress(status = IndexStatus.QUEUED)
            updateProjectProgress(projectId, queuedProgress)
            EventBus.emit(IndexingQueuedEvent(projectId = projectId, projectName = projectName))
        }

        indexingMutex.withLock {
            // ── Begin serialized indexing ────────────────────────────────────

            val startedProgress = IndexProgress(status = IndexStatus.INDEXING)
            updateProjectProgress(projectId, startedProgress)
            EventBus.emit(
                IndexingStartedEvent(
                    projectId = projectId,
                    projectName = projectName,
                ),
            )

            // Index sources sequentially — all coordinators share the same embeddingModel and
            // the embedding endpoint (Docker AI, Ollama, etc.) handles one request at a time.
            // Running in parallel causes concurrent embedAll calls that queue up and appear stuck.
            val results = projectCoordinators.map { coordinator ->
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
                            log.error("Failed to index knowledge source for project $projectId", e)
                            false
                        }
                    }
                }
            }

            val success = results.all { it }

            if (success) {
                if (watchForChanges) {
                    projectCoordinators.forEach { coordinator ->
                        try {
                            coordinator.startWatching(scope)
                        } catch (e: Exception) {
                            log.error("Failed to start watching for project $projectId", e)
                        }
                    }
                }

                val totalFilesIndexed = projectCoordinators.sumOf { it.progress.value.processedFiles }

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
                    ),
                )

                EventBus.emit(
                    IndexingCompletedEvent(
                        projectId = projectId,
                        projectName = projectName,
                        filesIndexed = totalFilesIndexed,
                    ),
                )
                updateProjectProgress(
                    projectId,
                    IndexProgress(status = IndexStatus.READY, processedFiles = totalFilesIndexed, totalFiles = totalFilesIndexed),
                )
            } else {
                val errors = projectCoordinators
                    .mapNotNull { it.progress.value.error }
                    .joinToString("; ")
                    .takeIf { it.isNotEmpty() } ?: "Unknown error"

                EventBus.emit(
                    IndexingFailedEvent(
                        projectId = projectId,
                        projectName = projectName,
                        errorMessage = errors,
                    ),
                )
                updateProjectProgress(projectId, IndexProgress(status = IndexStatus.FAILED, error = errors))
            }

            log.info(
                "Indexing ${if (success) "completed" else "failed"} for project $projectId " +
                    "(${projectCoordinators.size} knowledge source(s))",
            )
        }
    }

    /**
     * Handle re-index request event.
     * Re-index always takes priority — if the project is currently being indexed,
     * the in-progress indexing is stopped and a fresh re-index is started.
     */
    private suspend fun handleReIndexRequest(event: ProjectReIndexEvent) {
        try {
            val projectId = event.projectId

            val existingCoordinators = coordinators[projectId]
            if (existingCoordinators != null &&
                existingCoordinators.any { it.progress.value.status == IndexStatus.INDEXING }
            ) {
                log.info(
                    "Project $projectId is currently indexing — stopping it, re-index takes priority. Reason: ${event.reason}",
                )
            }

            // Always stop and clean up regardless of current status — re-index takes priority
            removeCoordinator(projectId, false)
            log.info("Cleaned up existing index data for project $projectId, starting re-index")

            val project = try {
                projectRepository.getProject(projectId)
            } catch (e: Exception) {
                log.error("Failed to get project $projectId for re-indexing", e)
                return
            }

            if (project != null) {
                val embeddingModel = appContext.getEmbeddingModel()
                checkEmbeddingModelAvailable(embeddingModel)

                val dimension = RagUtils.getDimensionForModel(embeddingModel)
                val embeddingStore = RagUtils.getEmbeddingStoreWithDimension(projectId, dimension)

                performIndexing(
                    projectId = projectId,
                    projectName = project.name,
                    knowledgeSources = project.knowledgeSources,
                    embeddingStore = embeddingStore,
                    embeddingModel = embeddingModel,
                    watchForChanges = true,
                )

                log.info("Re-indexing initiated for project $projectId")
            }
        } catch (e: Exception) {
            log.error("Failed to handle re-index request for project ${event.projectId}", e)
            EventBus.emit(
                AppErrorEvent(
                    title = "Failed to index knowledge source for project",
                    message = e.message.takeIf { !it.isNullOrBlank() } ?: "Unknown error",
                ),
            )
            EventBus.emit(
                IndexingFailedEvent(
                    projectId = event.projectId,
                    projectName = event.projectId,
                    errorMessage = e.message.takeIf { !it.isNullOrBlank() } ?: "Unknown error",
                ),
            )
        }
    }

    private fun handleRemoveIndexEvent(event: ProjectIndexRemovalEvent) {
        try {
            val projectId = event.projectId
            val knowledgeSource = event.knowledgeSource

            val projectCoordinators = coordinators[projectId]
            if (projectCoordinators != null) {
                val coordinatorToRemove = projectCoordinators.find {
                    it.knowledgeSourceConfig == event.knowledgeSource
                }
                if (coordinatorToRemove != null) {
                    coordinatorToRemove.clearAll()
                    coordinatorToRemove.close()

                    // Remove from the list of coordinators for this project
                    coordinators[projectId] = projectCoordinators.filterNot {
                        it.knowledgeSourceConfig.resourceIdentifier == event.knowledgeSource.resourceIdentifier
                    }

                    log.info("Removed index for knowledge source ${knowledgeSource.resourceIdentifier} from project $projectId")
                } else {
                    log.warn("No coordinator found for knowledge source ${knowledgeSource.resourceIdentifier} in project $projectId")
                }
            } else {
                log.warn("No coordinators found for project $projectId when trying to remove index for source ${knowledgeSource.resourceIdentifier}")
            }
        } catch (e: Exception) {
            log.error("Failed to handle index removal request for project ${event.projectId}", e)
        }
    }

    /**
     * Handle project indexing request event.
     * This method receives embedding components from the requester (e.g., ChatSessionService)
     * and uses them directly to create the indexer instance, avoiding duplicate initialization.
     * Uses the same duplicate prevention logic as handleReIndexRequest to prevent race conditions.
     *
     * Also detects embedding dimension mismatches (model was changed since last index) and
     * silently cleans up stale index data before re-indexing with the new model.
     */
    private suspend fun handleIndexingRequest(event: ProjectIndexingRequestedEvent) {
        try {
            val projectId = event.projectId

            // Create embedding model early so we can check the dimension before anything else
            val embeddingModel = run {
                val model = appContext.getEmbeddingModel()
                checkEmbeddingModelAvailable(model)
                model
            }

            // ── Dimension mismatch check ──────────────────────────────────────
            // If the project was previously indexed with a different embedding model
            // (detected via stored dimension in index.meta), wipe all stale index data
            // and let indexing proceed from scratch with the current model.
            val currentDimension = RagUtils.getDimensionForModel(embeddingModel)
            val storedDimension = RagUtils.getStoredEmbeddingDimension(projectId)
            if (storedDimension != null && storedDimension != currentDimension) {
                log.warn(
                    "Embedding dimension mismatch for project {} (stored={}, current={}). " +
                        "Clearing stale index data and re-indexing with the new model.",
                    projectId,
                    storedDimension,
                    currentDimension,
                )
                // Close any existing coordinators and wipe everything (Lucene, JVector, DB)
                coordinators.remove(projectId)?.forEach {
                    it.clearAll()
                    it.close()
                }
                cleanupIndexData(projectId)
                // Fall through — indexing will now run with a clean slate
            } else {
                // ── Normal duplicate / in-progress guard ─────────────────────
                // Only applies when re-indexing the whole project (event.knowledgeSources == null).
                // When specific sources are provided (e.g. a newly added reference material),
                // skip the guard so those sources are always indexed and appended.
                if (event.knowledgeSources == null) {
                    val existingCoordinators = coordinators[projectId]

                    if (existingCoordinators != null && existingCoordinators.all { it.progress.value.isComplete }) {
                        log.debug("Project $projectId already indexed, skipping duplicate request")
                        return
                    }

                    if (existingCoordinators != null && existingCoordinators.any { it.progress.value.status == IndexStatus.INDEXING }) {
                        log.debug("Project $projectId is currently being indexed, skipping duplicate request")
                        return
                    }
                }
            }

            val embeddingStore = RagUtils.getEmbeddingStoreWithDimension(projectId, currentDimension)

            val project = try {
                projectRepository.getProject(projectId)
            } catch (e: Exception) {
                log.error("Failed to get project $projectId for indexing", e)
                return
            }
            if (project != null) {
                val newSources = event.knowledgeSources
                if (newSources != null) {
                    // Specific sources requested (e.g. newly added reference material) —
                    // index only those sources and append coordinators to the existing list.
                    log.info(
                        "Indexing {} new knowledge source(s) for project $projectId",
                        newSources.size,
                    )
                    performIndexing(
                        projectId = projectId,
                        projectName = project.name,
                        knowledgeSources = newSources,
                        embeddingStore = embeddingStore,
                        embeddingModel = embeddingModel,
                        watchForChanges = event.watchForChanges,
                        appendCoordinators = true,
                    )
                } else {
                    // Full project index (startup / first-time)
                    performIndexing(
                        projectId = projectId,
                        projectName = project.name,
                        knowledgeSources = project.knowledgeSources,
                        embeddingStore = embeddingStore,
                        embeddingModel = embeddingModel,
                        watchForChanges = event.watchForChanges,
                    )
                }
            }
        } catch (e: Exception) {
            log.error("Failed to handle indexing request for project ${event.projectId}", e)

            EventBus.emit(
                AppErrorEvent(
                    title = "Failed to index knowledge source",
                    message = e.message.takeIf { !it.isNullOrBlank() } ?: "Unknown error",
                ),
            )

            EventBus.emit(
                IndexingFailedEvent(
                    projectId = event.projectId,
                    projectName = event.projectId,
                    errorMessage = e.message.takeIf { !it.isNullOrBlank() } ?: "Unknown error",
                ),
            )
        }
    }

    /**
     * Returns a persistent [MutableStateFlow] for the given project's index progress.
     *
     * Unlike the old coordinator-based combine flow, this StateFlow is updated eagerly at
     * every state transition (QUEUED → INDEXING → READY/FAILED), so a newly-created
     * ViewModel always gets the current state immediately without missing events.
     */
    fun getProgressFlow(projectId: String): Flow<IndexProgress> = projectProgressFlow(projectId)

    /**
     * Check if a project has been successfully indexed
     * @param projectId The project ID to check
     * @return true if the project has a valid index, false otherwise
     */
    fun isProjectIndexed(projectId: String): Boolean {
        // Check if coordinators exist and all indexing is complete
        val projectCoordinators = coordinators[projectId]
        if (projectCoordinators != null && projectCoordinators.all { it.progress.value.isComplete }) {
            return true
        }

        // Check if index files exist on disk (for projects indexed in previous sessions)
        val indexDir = RagUtils.getProjectIndexDir(projectId, createIfNotExists = false)
        if (!indexDir.toFile().exists()) {
            return false
        }

        // Verify the index is valid by checking for essential files
        val indexFiles = indexDir.toFile().listFiles() ?: return false
        return indexFiles.any { it.name.startsWith("segments_") } // Lucene segment files indicate valid index
    }

    /**
     * Check if embedding model is available and functional
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
