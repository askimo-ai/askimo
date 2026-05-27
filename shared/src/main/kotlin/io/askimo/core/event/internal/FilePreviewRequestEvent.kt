/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event.internal

import io.askimo.core.event.Event
import io.askimo.core.event.EventSource
import io.askimo.core.event.EventType
import io.askimo.core.util.parseFileUrl
import java.time.Instant

/**
 * Fired when the user clicks a file:// link inside a markdown message.
 * Listeners (e.g. ProjectSidePanel) can intercept this to show the file
 * in the in-app file viewer instead of the OS file browser.
 *
 * @param filePath Absolute path on disk (URL fragment stripped).
 * @param lineRange Optional line range parsed from a #LN-LM fragment.
 */
data class FilePreviewRequestEvent(
    val filePath: String,
    val lineRange: IntRange? = null,
    override val timestamp: Instant = Instant.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.INTERNAL

    override fun getDetails() = "File preview requested: $filePath" +
        (lineRange?.let { " (lines ${it.first}-${it.last})" } ?: "")
}

/**
 * Parse a file:// URL into a [FilePreviewRequestEvent].
 * Handles optional #LN-LM line range fragments.
 */
fun parseFilePreviewRequestEvent(url: String): FilePreviewRequestEvent {
    val parsed = parseFileUrl(url)
    return FilePreviewRequestEvent(filePath = parsed.filePath, lineRange = parsed.lineRange)
}
