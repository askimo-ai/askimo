/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.state

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
    val resourceIdentifier: String? = null,
    val currentFile: String? = null,
    val error: String? = null,
    /** File names (not full paths) skipped because no text could be extracted (e.g. image-only PDFs). */
    val skippedFileNames: List<String> = emptyList(),
) {
    val skippedFiles: Int get() = skippedFileNames.size

    val progressPercent: Int
        get() = if (totalFiles > 0) (processedFiles * 100) / totalFiles else 0

    val isComplete: Boolean
        get() = status == IndexStatus.READY || status == IndexStatus.WATCHING

    val hasFailed: Boolean
        get() = status == IndexStatus.FAILED
}

/**
 * Persisted state of an index
 */
data class IndexPersistedState(
    val totalFilesIndexed: Int,
    val lastIndexedTimestamp: Long,
    val fileHashes: Map<String, String>,
)
