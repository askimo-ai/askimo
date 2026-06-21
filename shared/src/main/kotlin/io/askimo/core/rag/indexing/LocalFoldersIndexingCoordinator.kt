/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.indexing

import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.store.embedding.EmbeddingStore
import io.askimo.core.chat.domain.LocalFoldersKnowledgeSourceConfig
import io.askimo.core.config.AppConfig
import io.askimo.core.context.AppContext
import io.askimo.core.event.EventBus
import io.askimo.core.event.user.IndexingInProgressEvent
import io.askimo.core.logging.logger
import io.askimo.core.rag.filter.FilterChain
import io.askimo.core.rag.state.IndexProgress
import io.askimo.core.rag.state.IndexStatus
import io.askimo.core.rag.watching.FileChangeHandler
import io.askimo.core.rag.watching.FileWatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.pathString

/**
 * Coordinates the indexing process for local files.
 *
 * Files are processed **sequentially** on a single IO thread — keeps resource usage low,
 * avoids mutex/semaphore complexity, and produces predictable progress on any machine.
 */
class LocalFoldersIndexingCoordinator(
    projectId: String,
    projectName: String,
    override val knowledgeSourceConfig: LocalFoldersKnowledgeSourceConfig,
    embeddingStore: EmbeddingStore<TextSegment>,
    embeddingModel: EmbeddingModel,
    appContext: AppContext,
) : BaseLocalIndexingCoordinator<LocalFoldersKnowledgeSourceConfig>(
    projectId = projectId,
    projectName = projectName,
    embeddingStore = embeddingStore,
    embeddingModel = embeddingModel,
    appContext = appContext,
    stateManagerScope = "folders",
    resourceId = knowledgeSourceConfig.resourceIdentifier,
) {
    private val log = logger<LocalFoldersIndexingCoordinator>()

    private val filePath = Paths.get(knowledgeSourceConfig.resourceIdentifier)
    private val filterChain: FilterChain = FilterChain.DEFAULT
    private val projectRoots = mutableListOf<Path>()
    private var fileWatcher: FileWatcher? = null

    @Volatile private var cancelled = false

    private fun shouldExcludePath(path: Path): Boolean {
        val absolutePath = path.toAbsolutePath()
        val rootPath = projectRoots.firstOrNull { root ->
            try {
                absolutePath.startsWith(root.toAbsolutePath())
            } catch (e: Exception) {
                log.warn("Failed to check if path $absolutePath starts with project root $root: ${e.message}", e)
                false
            }
        }
        if (rootPath != null && absolutePath == rootPath.toAbsolutePath()) return false
        return if (rootPath != null) filterChain.shouldExclude(path, rootPath) else filterChain.shouldExcludePath(path)
    }

    /**
     * Index all files under [path] sequentially on a single IO thread.
     */
    suspend fun indexPathWithProgress(path: Path): Boolean {
        cancelled = false
        updateProgress { IndexProgress(status = IndexStatus.INDEXING) }

        projectRoots.clear()
        projectRoots.addAll(path.toAbsolutePath())
        processedFilesCounter.set(0)
        totalFilesCounter.set(0)

        return withContext(Dispatchers.IO) {
            try {
                val allFiles = mutableListOf<Path>()
                collectIndexableFiles(path, allFiles)

                totalFilesCounter.set(allFiles.size)
                updateProgress { copy(totalFiles = allFiles.size) }
                log.info("Found ${allFiles.size} indexable files for project $projectId starting at $path")

                log.debug("Loading previous hashes for {} files from DB", allFiles.size)
                val previousHashes = stateManager.getHashesForFiles(allFiles.map { it.toAbsolutePath().toString() })
                log.debug("Loaded {} previous hashes from DB", previousHashes.size)
                val changedHashes = mutableMapOf<String, String>()
                val skippedNames = mutableListOf<String>()

                for (file in allFiles) {
                    if (cancelled) break
                    indexFileIncremental(file, previousHashes, changedHashes, skippedNames)
                }

                if (changedHashes.isNotEmpty()) {
                    stateManager.saveFileHashesBatch(changedHashes)
                }

                if (!cancelled) {
                    val currentPathSet = allFiles.mapTo(HashSet()) { it.toAbsolutePath().toString() }
                    val deletedPaths = stateManager.getStoredPaths() - currentPathSet
                    if (deletedPaths.isNotEmpty()) {
                        log.info("Detected ${deletedPaths.size} deleted files, removing from index...")
                        deletedPaths.forEach { hybridIndexer.removeFileFromIndex(Path.of(it)) }
                        stateManager.removeFilePaths(deletedPaths)
                    }
                }

                if (!hybridIndexer.flushRemainingSegments()) {
                    updateProgress { copy(status = IndexStatus.FAILED, error = "Failed to flush remaining segments") }
                    return@withContext false
                }

                updateProgress {
                    copy(
                        status = IndexStatus.READY,
                        processedFiles = processedFilesCounter.get(),
                        skippedFileNames = skippedNames,
                    )
                }
                log.info("Completed indexing for project $projectName: ${processedFilesCounter.get()} files processed at path $path")
                true
            } catch (e: CancellationException) {
                log.info("Indexing cancelled for project $projectName: ${e.message}")
                updateProgress { copy(status = IndexStatus.FAILED, error = "Indexing cancelled: ${e.message}") }
                throw e // must rethrow so the coroutine machinery knows it was cancelled
            } catch (e: Exception) {
                log.error("Indexing failed for project $projectName", e)
                updateProgress { copy(status = IndexStatus.FAILED, error = e.message ?: "Unknown error") }
                false
            }
        }
    }

    private fun collectIndexableFiles(path: Path, result: MutableList<Path>) {
        if (shouldExcludePath(path)) return
        when {
            path.isRegularFile() -> result.add(path)

            path.isDirectory() -> {
                try {
                    path.listDirectoryEntries().forEach { collectIndexableFiles(it, result) }
                } catch (e: Exception) {
                    log.warn("Failed to list directory ${path.pathString}: ${e.message}")
                }
            }
        }
    }

    private suspend fun indexFileIncremental(
        filePath: Path,
        previousHashes: Map<String, String>,
        changedHashes: MutableMap<String, String>,
        skippedNames: MutableList<String>,
    ) {
        if (cancelled) {
            log.trace("Indexing cancelled, skipping file: {}", filePath.pathString)
            return
        }
        val startTime = System.currentTimeMillis()
        try {
            val absolutePath = filePath.toAbsolutePath().toString()
            val hash = stateManager.calculateFileHash(filePath)
            log.trace("Indexing file: {}", absolutePath)

            if (previousHashes[absolutePath] == hash) {
                log.trace("Skipping unchanged file: {}", filePath.pathString)
                updateProgressAtomic(filePath)
                return
            }

            if (previousHashes.containsKey(absolutePath)) {
                log.trace("File changed, removing old segments: {}", filePath.pathString)
                hybridIndexer.removeFileFromIndex(filePath)
            }

            val segments = resourceContentProcessor.createSegmentsForFile(filePath)

            if (segments == null) {
                log.warn("Skipping file with no extractable content: {}", filePath.pathString)
                skippedNames.add(filePath.fileName.toString())
                changedHashes[absolutePath] = hash
                updateProgressAtomic(filePath)
                return
            }

            if (segments.isEmpty()) {
                log.debug("No valid chunks created for file: {}", filePath.pathString)
                changedHashes[absolutePath] = hash
                updateProgressAtomic(filePath)
                return
            }

            // Announce chunk count — UI progress bar starts moving immediately
            onFileChunksReady(filePath, segments.size)

            for ((index, segment) in segments.withIndex()) {
                if (!hybridIndexer.addSegmentToBatch(segment, filePath)) {
                    cancelled = true
                    return
                }
                val processedChunks = index + 1
                // Emit after each real batch flush (every BATCH_SIZE) or at the final segment
                if (processedChunks % AppConfig.indexing.embeddingBatchSize == 0 || processedChunks == segments.size) {
                    val elapsedMs = System.currentTimeMillis() - currentFileStartMs
                    currentFileChunksProcessed = processedChunks
                    updateProgress {
                        copy(
                            currentFileProcessedChunks = processedChunks,
                            currentFileTotalChunks = segments.size,
                            currentFileElapsedMs = elapsedMs,
                        )
                    }
                    EventBus.emit(
                        IndexingInProgressEvent(
                            projectId = projectId,
                            projectName = projectName,
                            filesIndexed = processedFilesCounter.get(),
                            totalFiles = totalFilesCounter.get(),
                            resourceId = stateManager.resourceId,
                            currentFile = filePath.toAbsolutePath().toString(),
                            chunksIndexed = processedChunks,
                            totalChunks = segments.size,
                            currentFileElapsedMs = elapsedMs,
                        ),
                    )
                }
            }

            changedHashes[absolutePath] = hash
            log.debug("Indexed {} ({} chunks) in {}ms", filePath.pathString, segments.size, System.currentTimeMillis() - startTime)
            updateProgressAtomic(filePath)
        } catch (e: Exception) {
            log.error("Failed to index file {} after {}ms: {}", filePath.pathString, System.currentTimeMillis() - startTime, e.message, e)
        }
    }

    override suspend fun startIndexing(): Boolean = indexPathWithProgress(filePath)

    override fun startWatching(scope: CoroutineScope) {
        if (fileWatcher != null) {
            log.debug("File watcher already active for project $projectId")
            return
        }
        val changeHandler = FileChangeHandler(
            projectId = projectId,
            embeddingStore = embeddingStore,
            embeddingModel = embeddingModel,
            appContext = appContext,
        )
        fileWatcher = FileWatcher(
            projectId = projectId,
            onFileChange = { path, kind -> changeHandler.handleFileChange(path, kind) },
        )
        fileWatcher?.startWatching(filePath, scope)
        log.info("Started file watching for project $projectId")
    }

    override fun stopWatching() {
        fileWatcher?.stopWatching()
        fileWatcher = null
        log.info("Stopped file watching $filePath for project $projectId")
    }

    /**
     * [cancelled] signals the sequential loop to stop at the next file boundary.
     */
    override fun close() {
        cancelled = true
        stopWatching()
        log.debug("Closed LocalFoldersIndexingCoordinator for project $projectId (cancelled in-progress indexing)")
    }
}
