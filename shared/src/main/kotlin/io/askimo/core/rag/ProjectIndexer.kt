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
import io.askimo.core.chat.domain.Project
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
 * Manager for project indexing with RAG (Retrieval-Augmented Generation).
 * Each project can have multiple coordinators - one per knowledge source.
 *
 * Concurrency model: a single [Channel]-based queue ensures only one indexing job runs at a
 * time across all projects. This avoids aggressive concurrent embedding calls to remote
 * endpoints (Ollama, Docker AI, etc.). A [ProjectReIndexEvent] or [ProjectDeletedEvent] for
 * the currently-running project cancels it immediately; other projects wait their turn in the
 * FIFO queue.
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

    // ── Single-queue concurrency model ──────────────────────────────────────────────────────
    // Tasks are processed one at a time by a single consumer coroutine. ReIndex and Delete
    // tasks preempt a running job for the *same* project; otherwise tasks wait in FIFO order.

    private sealed class IndexingTask {
        abstract val projectId: String

        data class Index(
            override val projectId: String,
            val event: ProjectIndexingRequestedEvent,
        ) : IndexingTask()

        data class ReIndex(
            override val projectId: String,
            val event: ProjectReIndexEvent,
        ) : IndexingTask()

        data class Delete(override val projectId: String) : IndexingTask()

        data class RemoveSource(
            override val projectId: String,
            val event: ProjectIndexRemovalEvent,
        ) : IndexingTask()
    }

    // The ID of the project whose task is currently executing. Written only by the consumer
    // coroutine; read by event-collector coroutines to detect when to show QUEUED.
    @Volatile private var activeProjectId: String? = null

    // Active job reference — written by the consumer coroutine, read + cancelled by the
    // ProjectReIndexEvent collector to preempt a running job immediately on re-index request.
    @Volatile private var activeJob: Job? = null

    // Name of the project currently being indexed.
    // Set in the consumer right alongside activeProjectId — before the child job launches —
    // so any event-collector coroutine that sees activeProjectId != null also sees this name.
    @Volatile private var activeBlockingProjectName: String? = null

    private val taskChannel = Channel<IndexingTask>(capacity = Channel.UNLIMITED)

    // Prevents duplicate ReIndex tasks from accumulating for a queued (non-active) project.
    // Uses ConcurrentHashMap.newKeySet for thread-safe add/remove/contains.
    private val pendingReIndexIds = ConcurrentHashMap.newKeySet<String>()

    init {
        scope.launch {
            EventBus.internalEvents
                .filterIsInstance<ProjectDeletedEvent>()
                .collect { event ->
                    log.info("Project deleted, cleaning up coordinator: ${event.projectId}")
                    taskChannel.send(IndexingTask.Delete(event.projectId))
                }
        }

        scope.launch {
            EventBus.internalEvents
                .filterIsInstance<ProjectReIndexEvent>()
                .collect { event ->
                    log.info("Re-index requested for project ${event.projectId}: ${event.reason}")
                    val isActive = activeProjectId == event.projectId
                    if (isActive) {
                        // Cancel the running job immediately so the consumer's join() unblocks
                        // right away rather than waiting for the current indexing to complete.
                        // Without this, the current indexing finishes normally and THEN the
                        // re-index starts — causing two full cycles instead of one.
                        log.info("Re-index for active project ${event.projectId} — cancelling current indexing immediately")
                        activeJob?.cancel()
                    } else if (!pendingReIndexIds.add(event.projectId)) {
                        // Not the active project but already has a ReIndex task queued — ignore duplicate.
                        log.debug("Re-index for project ${event.projectId} already queued, ignoring duplicate")
                        return@collect
                    } else if (activeProjectId != null) {
                        // A different project is running, this one will wait in the queue
                        updateProjectProgress(event.projectId, IndexProgress(status = IndexStatus.QUEUED, blockedByName = activeBlockingProjectName))
                    }
                    taskChannel.send(IndexingTask.ReIndex(event.projectId, event))
                }
        }

        scope.launch {
            EventBus.internalEvents
                .filterIsInstance<ProjectIndexingRequestedEvent>()
                .collect { event ->
                    log.info("Indexing requested for project ${event.projectId}")
                    if (activeProjectId != null && activeProjectId != event.projectId) {
                        updateProjectProgress(event.projectId, IndexProgress(status = IndexStatus.QUEUED, blockedByName = activeBlockingProjectName))
                    }

                    taskChannel.send(IndexingTask.Index(event.projectId, event))
                }
        }

        scope.launch {
            EventBus.internalEvents
                .filterIsInstance<ProjectIndexRemovalEvent>()
                .collect { event ->
                    log.info("Indexing removal requested for project ${event.projectId}")
                    taskChannel.send(IndexingTask.RemoveSource(event.projectId, event))
                }
        }

        // ── Single consumer — one task at a time ────────────────────────────────────────────
        scope.launch {
            for (task in taskChannel) {
                // ReIndex and Delete preempt a running job for the *same* project.
                // Jobs for *different* projects are never cancelled — they wait their turn.
                if ((task is IndexingTask.ReIndex || task is IndexingTask.Delete) &&
                    task.projectId == activeProjectId
                ) {
                    log.info(
                        "Preempting active indexing for project ${task.projectId} " +
                            "(incoming: ${task::class.simpleName})",
                    )
                    activeJob?.cancel()
                    activeJob?.join()
                    activeProjectId = null
                }

                activeProjectId = task.projectId
                activeBlockingProjectName = projectRepository.getProject(task.projectId)?.name
                activeJob = scope.launch {
                    when (task) {
                        is IndexingTask.Index -> handleIndexingRequest(task.event)

                        is IndexingTask.ReIndex -> {
                            pendingReIndexIds.remove(task.projectId)
                            handleReIndexRequest(task.event)
                        }

                        is IndexingTask.Delete -> removeCoordinator(task.projectId, deleteProjectFolder = true)

                        is IndexingTask.RemoveSource -> handleRemoveIndexEvent(task.event)
                    }
                }
                activeJob?.join() // process tasks sequentially — next task only starts after this one finishes
                activeProjectId = null
                activeBlockingProjectName = null
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
        project: Project,
        knowledgeSources: List<KnowledgeSourceConfig>,
        embeddingStore: EmbeddingStore<TextSegment>,
        embeddingModel: EmbeddingModel,
        watchForChanges: Boolean,
        appendCoordinators: Boolean = false,
    ) {
        val projectId = project.id
        val projectName = project.name
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

        // If this project was waiting in the queue, notify the UI now that we're starting.
        if (projectProgressFlow(projectId).value.status == IndexStatus.QUEUED) {
            EventBus.emit(
                IndexingQueuedEvent(
                    projectId = projectId,
                    projectName = projectName,
                    blockedByProjectName = activeBlockingProjectName ?: projectName,
                ),
            )
        }

        // ── Begin indexing (channel consumer guarantees only one project indexes at a time) ──

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
            val allSkippedFileNames = projectCoordinators.flatMap { it.progress.value.skippedFileNames }

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
                    skippedFileNames = allSkippedFileNames,
                ),
            )
            updateProjectProgress(
                projectId,
                IndexProgress(
                    status = IndexStatus.READY,
                    processedFiles = totalFilesIndexed,
                    totalFiles = totalFilesIndexed,
                    skippedFileNames = allSkippedFileNames,
                ),
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

    /**
     * Handle re-index request event.
     * Re-index always takes priority — if the project is currently being indexed, the consumer
     * coroutine has already cancelled and joined that job before this handler runs.
     */
    private suspend fun handleReIndexRequest(event: ProjectReIndexEvent) {
        try {
            val projectId = event.projectId

            // Always stop and clean up regardless of current status — re-index takes priority.
            // Note: no job cancellation needed here; the channel consumer handles that before
            // dispatching this handler.
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
                    project = project,
                    knowledgeSources = project.knowledgeSources,
                    embeddingStore = embeddingStore,
                    embeddingModel = embeddingModel,
                    watchForChanges = true,
                )

                log.info("Re-indexing initiated for project $projectId")
            }
        } catch (e: CancellationException) {
            // Job was cancelled (e.g. project deleted while re-indexing) — not an error.
            log.info("Re-index job cancelled for project ${event.projectId}: ${e.message}")
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
                        project = project,
                        knowledgeSources = newSources,
                        embeddingStore = embeddingStore,
                        embeddingModel = embeddingModel,
                        watchForChanges = event.watchForChanges,
                        appendCoordinators = true,
                    )
                } else {
                    // Full project index (startup / first-time)
                    performIndexing(
                        project = project,
                        knowledgeSources = project.knowledgeSources,
                        embeddingStore = embeddingStore,
                        embeddingModel = embeddingModel,
                        watchForChanges = event.watchForChanges,
                    )
                }
            }
        } catch (e: CancellationException) {
            // Job was cancelled (e.g. project deleted while indexing) — not an error.
            log.info("Indexing job cancelled for project ${event.projectId}: ${e.message}")
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
