/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event.error

import io.askimo.core.event.Event
import io.askimo.core.event.EventSource
import io.askimo.core.event.EventType
import java.time.Instant

/**
 * Event emitted when an indexing error occurs.
 * Allows UI layers to handle and display errors appropriately.
 */
data class IndexingErrorEvent(
    val projectId: String,
    val errorType: IndexingErrorType,
    val details: Map<String, String> = emptyMap(),
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.ERROR

    override fun getDetails(): String = "Indexing error for project $projectId: ${errorType.name} - $details"
}

/**
 * Types of indexing errors that can occur.
 */
enum class IndexingErrorType {
    EMBEDDING_MODEL_NOT_FOUND,

    /**
     * The embedding server rejected a segment because its token count exceeds the
     * model's configured physical batch size (e.g. "input (767 tokens) is too large
     * to process. increase the physical batch size (current batch size: 512)").
     *
     * The [IndexingErrorEvent.details] map contains:
     * - `"files"` – comma-separated list of affected file names
     * - `"message"` – the raw server error message
     */
    EMBEDDING_INPUT_TOO_LARGE,
    IO_ERROR,
    UNKNOWN_ERROR,
}
