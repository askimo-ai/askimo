/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.event.error

import io.askimo.core.event.Event
import io.askimo.core.event.EventSource
import io.askimo.core.event.EventType
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Emitted when the built-in local LLM's `llama-server` child process fails to start or become
 * healthy (e.g. a missing/incompatible system library such as an outdated glibc, a corrupted
 * binary, or an unsupported model file) — see
 * [io.askimo.core.providers.llama.LlamaServerProcessManager]'s `startSlotLocked`/`waitForHealthy`.
 *
 * Carries the raw [cause] (rather than just its message) so the UI can show the full stack
 * trace/output as expandable technical details — [reason] is usually the most actionable part
 * (e.g. the exact missing `GLIBC_x.y` symbol version, embedded in the exception message by
 * `waitForHealthy`'s `recentOutputTail`), but the full exception helps diagnose rarer failures
 * where the message alone isn't enough.
 *
 * @param modelFileName The `.gguf` file name that was being loaded when the failure occurred.
 * @param isEmbedding True if the failure happened in the embedding slot, false for the chat slot.
 * @param cause The exception thrown while starting/health-checking the child process.
 */
data class LlamaServerStartFailedEvent(
    val modelFileName: String,
    val isEmbedding: Boolean,
    val cause: Throwable,
    override val timestamp: Instant = Clock.System.now(),
    override val source: EventSource = EventSource.SYSTEM,
) : Event {
    override val type = EventType.ERROR

    /** Short, human-readable reason — [cause]'s message, falling back to its class name. */
    val reason: String get() = cause.message ?: cause.toString()

    override fun getDetails() = "llama-server failed to start for model '$modelFileName': $reason"
}
