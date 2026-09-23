/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event.user

import io.askimo.core.event.Event
import io.askimo.core.event.EventSource
import io.askimo.core.event.EventType
import io.askimo.core.rag.container.IndexingContainerType
import java.time.Instant

/**
 * Emitted when a container (project or resource collection) is queued for indexing
 * because another container is currently indexing. Starts once the queue clears.
 */
data class IndexingQueuedEvent(
    val containerId: String,
    val containerName: String,
    val containerType: IndexingContainerType,
    /** Name of the container currently being indexed that is blocking this one. */
    val blockedByContainerName: String,
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL
    override fun getDetails(): String = "'$containerName' is queued for indexing, waiting for '$blockedByContainerName'"
}

/** Emitted when indexing starts for a container. User-facing, shown in the notification footer. */
data class IndexingStartedEvent(
    val containerId: String,
    val containerName: String,
    val containerType: IndexingContainerType,
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails(): String = "Indexing '$containerName' ..."
}

/** Emitted periodically during indexing to show progress. User-facing, shown in the notification footer. */
data class IndexingInProgressEvent(
    val containerId: String,
    val containerName: String,
    val containerType: IndexingContainerType,
    val filesIndexed: Int,
    val totalFiles: Int,
    val resourceId: String,
    val currentFile: String? = null,
    val chunksIndexed: Int = 0,
    val totalChunks: Int = 0,
    val currentFileElapsedMs: Long = 0L,
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails(): String {
        val percentage = if (totalChunks > 0) {
            (chunksIndexed * 100 / totalChunks)
        } else if (totalFiles > 0) {
            (filesIndexed * 100 / totalFiles)
        } else {
            0
        }
        val fileInfo = currentFile?.let { " | current: $it" }.orEmpty()
        return "Indexing '$containerName': $filesIndexed/$totalFiles files, $chunksIndexed/$totalChunks chunks ($percentage%) [resource: $resourceId$fileInfo]"
    }
}

/** Emitted when indexing completes successfully for a container. User-facing, shown in the notification footer. */
data class IndexingCompletedEvent(
    val containerId: String,
    val containerName: String,
    val containerType: IndexingContainerType,
    val filesIndexed: Int,
    val skippedFileNames: List<String> = emptyList(),
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails(): String {
        val skippedNote = if (skippedFileNames.isNotEmpty()) {
            " (${skippedFileNames.size} skipped — no extractable text)"
        } else {
            ""
        }
        return "Successfully indexed $filesIndexed file(s) for '$containerName'$skippedNote"
    }
}

/** Emitted when indexing fails for a container. User-facing, shown in the notification footer. */
data class IndexingFailedEvent(
    val containerId: String,
    val containerName: String,
    val containerType: IndexingContainerType,
    val errorMessage: String,
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails(): String = "Failed to index '$containerName': $errorMessage"
}

/**
 * Emitted when the file watcher detects a watched file (or directory) was deleted from
 * disk and it's been removed from the container's index. User-facing, shown in the
 * notification footer.
 */
data class FileRemovedFromIndexEvent(
    val containerId: String,
    val containerName: String,
    val containerType: IndexingContainerType,
    val fileName: String,
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails(): String = "'$fileName' was detected as removed and has been removed from the index for '$containerName'"
}
