/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.state

import io.askimo.core.util.NumberFormatUtil

/**
 * Enumeration of indexing status states
 */
enum class IndexStatus {
    NOT_STARTED,
    QUEUED,
    INDEXING,
    READY,
    WATCHING,
    FAILED,
}

/**
 * Progress information for indexing operations
 */
data class IndexProgress(
    val status: IndexStatus = IndexStatus.NOT_STARTED,
    val totalFiles: Int = 0,
    val processedFiles: Int = 0,
    /** Total chunks in the file currently being embedded (0 = no chunk-level progress available). */
    val currentFileTotalChunks: Int = 0,
    /** Chunks embedded so far in the current file — resets to 0 when the file completes. */
    val currentFileProcessedChunks: Int = 0,
    val resourceIdentifier: String? = null,
    val currentFile: String? = null,
    /** Milliseconds elapsed since the current file started processing. */
    val currentFileElapsedMs: Long = 0L,
    val error: String? = null,
    /** File names (not full paths) skipped because no text could be extracted (e.g. image-only PDFs). */
    val skippedFileNames: List<String> = emptyList(),
    /**
     * Name of the project currently being indexed that is blocking this one.
     * Only non-null when [status] is [IndexStatus.QUEUED].
     */
    val blockedByName: String? = null,
) {

    /**
     * Smooth 0→100 progress that never stalls on large files.
     *
     * Each file contributes [1 / totalFiles] to the total.
     * Within the current file, the processed chunks fraction is added as a
     * sub-contribution so the bar advances continuously while a large file
     * is being embedded — even before [processedFiles] increments.
     *
     * Formula: (processedFiles + currentFileProcessedChunks/currentFileTotalChunks) / totalFiles * 100
     */
    val progressPercent: Float
        get() {
            if (totalFiles <= 0) return 0f
            val fileContribution = processedFiles.toFloat() / totalFiles
            val chunkContribution = if (currentFileTotalChunks > 0 && processedFiles < totalFiles) {
                currentFileProcessedChunks.toFloat() / currentFileTotalChunks / totalFiles
            } else {
                0f
            }
            return ((fileContribution + chunkContribution) * 100f).coerceIn(0f, 100f)
        }

    /**
     * Locale-aware, 4-decimal-place string of [progressPercent].
     * Delegates to [NumberFormatUtil.formatPercent] — no formatting logic needed in UI.
     * Example: "35.3800" (en-US) or "35,3800" (de-DE).
     */
    val progressPercentFormatted: String
        get() = NumberFormatUtil.formatPercent(progressPercent)

    val isComplete: Boolean
        get() = status == IndexStatus.READY || status == IndexStatus.WATCHING
}

/**
 * Persisted state of an index
 */
data class IndexPersistedState(
    val totalFilesIndexed: Int,
    val lastIndexedTimestamp: Long,
    val fileHashes: Map<String, String>,
)
