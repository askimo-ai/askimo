/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.indexing

import dev.langchain4j.community.store.embedding.jvector.JVectorEmbeddingStore
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.store.embedding.EmbeddingStore
import io.askimo.core.chat.repository.ResourceSegmentRepository
import io.askimo.core.config.AppConfig
import io.askimo.core.db.DatabaseManager
import io.askimo.core.event.EventBus
import io.askimo.core.event.system.ShellErrorEvent
import io.askimo.core.logging.logger
import io.askimo.core.rag.LuceneIndexer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.apache.lucene.store.AlreadyClosedException
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * Coordinates hybrid indexing of text segments into both:
 * - JVector embedding store (for semantic/vector search)
 * - Lucene keyword index (for BM25/keyword search)
 *
 * Handles batching for efficient embedding generation and tracks
 * file-to-segment mappings for removal support.
 */
class HybridIndexer(
    private val embeddingStore: EmbeddingStore<TextSegment>,
    private val embeddingModel: EmbeddingModel,
    private val projectId: String,
    private val segmentRepository: ResourceSegmentRepository = DatabaseManager.getInstance().getResourceSegmentRepository(),
) {
    private val log = logger<HybridIndexer>()
    private val luceneIndexer = LuceneIndexer.getInstance(projectId)

    // Mutex for thread-safe batch operations
    private val batchMutex = Mutex()

    private val segmentBatch = mutableListOf<Pair<TextSegment, Path>>() // Track file path with segment
    private val pendingMappings = mutableListOf<Triple<Path, String, Int>>() // (filePath, segmentId, chunkIndex)

    /**
     * Add segment to batch. If the batch is full, snapshots and clears the batch under
     * the lock, then flushes **outside** the lock — so the expensive `embedAll` call
     * never holds [batchMutex], preventing other coroutines from blocking indefinitely.
     */
    suspend fun addSegmentToBatch(
        segment: TextSegment,
        filePath: Path,
    ): Boolean {
        log.trace("Adding segment {} to batch for project {}", segment.metadata().getString("file_name"), projectId)

        val snapshot: List<Pair<TextSegment, Path>>? = batchMutex.withLock {
            segmentBatch.add(segment to filePath)
            if (segmentBatch.size >= AppConfig.indexing.embeddingBatchSize) {
                val snap = segmentBatch.toList()
                segmentBatch.clear()
                snap
            } else {
                null
            }
        }

        return if (snapshot != null) flushSnapshot(snapshot) else true
    }

    /**
     * Flush any remaining segments. Snapshots under the lock, then flushes outside.
     */
    suspend fun flushRemainingSegments(): Boolean {
        val snapshot = batchMutex.withLock {
            if (segmentBatch.isNotEmpty()) {
                log.trace("Flushing {} remaining segments", segmentBatch.size)
                val snap = segmentBatch.toList()
                segmentBatch.clear()
                snap
            } else {
                null
            }
        }
        return if (snapshot != null) {
            flushSnapshot(snapshot)
        } else {
            // Nothing to flush — still persist any pending mappings
            try {
                batchMutex.withLock { savePendingMappings() }
                true
            } catch (e: Exception) {
                log.warn("Failed to save pending mappings", e)
                false
            }
        }
    }

    /**
     * Flush a snapshotted batch. Must NOT be called while holding [batchMutex].
     */
    private suspend fun flushSnapshot(snapshot: List<Pair<TextSegment, Path>>): Boolean {
        val segments = snapshot.map { it.first }
        val filePaths = snapshot.map { it.second }

        return try {
            withContext(Dispatchers.IO) {
                log.debug("embedAll: calling embeddingModel for {} segments, project {}", segments.size, projectId)
                val embeddings = withTimeout(60.seconds) {
                    embeddingModel.embedAll(segments).content()
                }
                log.debug("embedAll: completed for {} segments, project {}", segments.size, projectId)

                val segmentIds = segments.map { generateSegmentId(it) }

                embeddingStore.addAll(embeddings, segments)
                (embeddingStore as? JVectorEmbeddingStore)?.save()

                luceneIndexer.indexSegments(segments)

                val mappings = segments.mapIndexed { i, seg ->
                    Triple(filePaths[i], segmentIds[i], seg.metadata().getInteger("chunk_index") ?: 0)
                }

                batchMutex.withLock {
                    pendingMappings.addAll(mappings)
                    savePendingMappings()
                }

                log.trace("Hybrid indexed batch of {} segments (vector + keyword) for project {}", segments.size, projectId)
            }
            true
        } catch (_: AlreadyClosedException) {
            log.debug("Lucene IndexWriter already closed for project {} — indexing was cancelled", projectId)
            false
        } catch (e: TimeoutCancellationException) {
            // TimeoutCancellationException is a CancellationException subclass — must be caught
            // explicitly before the generic Exception handler, otherwise it propagates silently
            // and kills the entire coroutine scope with no log output.
            val fileNames = filePaths.map { it.fileName.toString() }.toSet()
            val displayNames = if (fileNames.size <= 3) fileNames.joinToString() else "${fileNames.take(3).joinToString()} and ${fileNames.size - 3} more"
            log.error(
                "embedAll timed out after 60s for project {} — batch of {} segments, files: {}",
                projectId,
                segments.size,
                displayNames,
            )
            EventBus.emit(
                ShellErrorEvent(
                    cause = e,
                    errorMessage = "Embedding timed out for [$displayNames] — the embedding model may be overloaded. Try re-indexing.",
                ),
            )
            false
        } catch (e: Exception) {
            // FK violation = project deleted while indexing. Already logged as WARN in
            // savePendingMappings — do NOT surface a user-facing error for this.
            if (e.isForeignKeyViolation()) {
                return false
            }

            val maxSegmentChars = segments.maxOfOrNull { it.text().length } ?: 0
            val largestSegmentIndex = segments.indexOfFirst { it.text().length == maxSegmentChars }
            val largestSegment = if (largestSegmentIndex >= 0) segments[largestSegmentIndex] else null

            log.error(
                "Failed to flush segment batch for project {}: {} - " +
                    "Batch size: {} segments, " +
                    "Largest segment: {} chars ({} est. tokens), " +
                    "File: {}, Chunk: {}, Index in batch: {}",
                projectId,
                e.message,
                segments.size,
                maxSegmentChars,
                maxSegmentChars / 2,
                largestSegment?.metadata()?.getString("file_name") ?: "unknown",
                largestSegment?.metadata()?.getInteger("chunk_index") ?: 0,
                largestSegmentIndex,
                e,
            )

            val fileNames = filePaths.map { it.fileName.toString() }.toSet()
            val displayNames = if (fileNames.size <= 3) {
                fileNames.joinToString()
            } else {
                "${fileNames.take(3).joinToString()} and ${fileNames.size - 3} more"
            }
            EventBus.emit(
                ShellErrorEvent(
                    cause = e,
                    errorMessage = "Failed to index files [$displayNames]: ${e.message}",
                ),
            )
            false
        }
    }

    /**
     * Save pending segment mappings to database.
     *
     * Always clears [pendingMappings] — on success AND on failure — so a subsequent
     * call never retries the same mappings and causes a cascade of FK errors.
     *
     * Throws on any database error so the caller ([flushSnapshot] / [flushRemainingSegments])
     * can return `false` and stop the indexing loop immediately.
     */
    private fun savePendingMappings() {
        if (pendingMappings.isEmpty()) return

        try {
            // Group by file path for batch insertion
            val groupedByFile = pendingMappings.groupBy { it.first }

            for ((filePath, mappings) in groupedByFile) {
                val segmentData = mappings.map { (_, segmentId, chunkIndex) ->
                    segmentId to chunkIndex
                }
                segmentRepository.saveSegmentMappings(projectId, filePath, segmentData)
            }

            log.trace("Saved {} segment mappings to database", pendingMappings.size)
            pendingMappings.clear()
        } catch (e: Exception) {
            // Always clear so the next call doesn't retry and trigger another FK error
            pendingMappings.clear()
            if (e.isForeignKeyViolation()) {
                // Expected when the project is deleted while indexing is still running.
                // Logged as WARN — not an application error.
                log.warn(
                    "Project {} no longer exists in DB — abandoning segment mapping saves. " +
                        "This is expected when a project is deleted while indexing is in progress.",
                    projectId,
                )
            } else {
                log.error("Failed to save segment mappings: {}", e.message, e)
            }
            throw e // propagate so the caller returns false and the indexing loop stops
        }
    }

    private fun Exception.isForeignKeyViolation(): Boolean = message?.contains("SQLITE_CONSTRAINT_FOREIGNKEY") == true ||
        cause?.message?.contains("SQLITE_CONSTRAINT_FOREIGNKEY") == true

    /**
     * Remove all segments for a file from both the embedding store, keyword index, and tracking database
     */
    fun removeFileFromIndex(filePath: Path) {
        try {
            val segmentIds = segmentRepository.getSegmentIdsForFile(projectId, filePath)

            if (segmentIds.isNotEmpty()) {
                log.trace("Found {} segments for file {} - removing from hybrid index", segmentIds.size, filePath.fileName)

                // Remove from embedding store
                embeddingStore.removeAll(segmentIds)
                log.trace("Removed {} embeddings from vector store", segmentIds.size)

                // Remove from keyword index
                luceneIndexer.removeFile(filePath.toString())
                log.trace("Removed segments from keyword index for file {}", filePath.fileName)

                // Remove from database
                val removed = segmentRepository.removeSegmentMappingsForFile(projectId, filePath)
                log.trace("Removed {} segment mappings from database for file {}", removed, filePath.fileName)
            } else {
                log.trace("No segments found for file {}", filePath.fileName)
            }
        } catch (e: Exception) {
            log.error("Failed to remove file from hybrid index: {}", filePath.fileName, e)
        }
    }

    /**
     * Remove all segments for every file under [dirPath] from the embedding store,
     * keyword index, and tracking database.
     */
    fun removeDirectoryFromIndex(dirPath: Path) {
        try {
            val dirPrefix = dirPath.toAbsolutePath().toString()
            val segmentIds = segmentRepository.getSegmentIdsForDirectory(projectId, dirPrefix)

            if (segmentIds.isNotEmpty()) {
                log.debug("Removing {} segments for directory {} from hybrid index", segmentIds.size, dirPath.fileName)

                // Remove from embedding store
                embeddingStore.removeAll(segmentIds)

                // Remove from Lucene keyword index — removeFile per stored path
                // Lucene entries are keyed by file path string, so we need per-file removal.
                // Re-query to get distinct resource_ids (file paths) under this dir.
                luceneIndexer.removeDirectory(dirPrefix)

                // Remove all DB mappings under this directory prefix
                val removed = segmentRepository.removeSegmentMappingsForDirectory(projectId, dirPrefix)
                log.debug("Removed {} segment mappings from database for directory {}", removed, dirPath.fileName)
            } else {
                log.debug("No segments found under directory {}", dirPath.fileName)
            }
        } catch (e: Exception) {
            log.error("Failed to remove directory from hybrid index: {}", dirPath.fileName, e)
        }
    }

    private fun generateSegmentId(segment: TextSegment): String {
        val filePath = segment.metadata().getString("file_path") ?: "unknown"
        val chunkIndex = segment.metadata().getInteger("chunk_index") ?: 0

        // Hash the file path to a fixed 8-character hex string for compact, deterministic IDs
        val fileHash = filePath.hashCode().toString(16).padStart(8, '0')

        return "$projectId:$fileHash:$chunkIndex"
    }
}
