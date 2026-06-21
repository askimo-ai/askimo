/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event.user

import io.askimo.core.event.Event
import io.askimo.core.event.EventSource
import io.askimo.core.event.EventType
import java.time.Instant

/**
 * Event emitted when a project is queued for indexing because another project
 * is currently being indexed. The project will start indexing once the queue clears.
 */
data class IndexingQueuedEvent(
    val projectId: String,
    val projectName: String,
    /** Name of the project currently being indexed that is blocking this one. */
    val blockedByProjectName: String,
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL
    override fun getDetails(): String = "Project '$projectName' is queued for indexing, waiting for '$blockedByProjectName'"
}

/**
 * Event emitted when project indexing starts.
 * This is a user-facing event shown in the notification footer.
 */
data class IndexingStartedEvent(
    val projectId: String,
    val projectName: String,
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails(): String = "Indexing project '$projectName' ..."
}

/**
 * Event emitted periodically during project indexing to show progress.
 * This is a user-facing event shown in the notification footer.
 */
data class IndexingInProgressEvent(
    val projectId: String,
    val projectName: String,
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
        return "Indexing project '$projectName': $filesIndexed/$totalFiles files, $chunksIndexed/$totalChunks chunks ($percentage%) [resource: $resourceId$fileInfo]"
    }
}

/**
 * Event emitted when project indexing completes successfully.
 * This is a user-facing event shown in the notification footer.
 */
data class IndexingCompletedEvent(
    val projectId: String,
    val projectName: String,
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
        return "Successfully indexed $filesIndexed file(s) for project '$projectName'$skippedNote"
    }
}

/**
 * Event emitted when project indexing fails.
 * This is a user-facing event shown in the notification footer.
 */
data class IndexingFailedEvent(
    val projectId: String,
    val projectName: String,
    val errorMessage: String,
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails(): String = "Failed to index project '$projectName': $errorMessage"
}
