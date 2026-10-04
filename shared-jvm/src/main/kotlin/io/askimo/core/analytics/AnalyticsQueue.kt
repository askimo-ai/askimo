/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.analytics

import io.askimo.core.logging.logger
import io.askimo.core.util.AskimoHome
import io.askimo.core.util.appJson
import kotlinx.serialization.builtins.ListSerializer
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe in-memory queue for [AnalyticsEventPayload]s.
 *
 * Events are persisted to [QUEUE_FILE] on every mutation so they survive app restarts.
 * [AnalyticsReporter] drains this queue on a scheduled background flush.
 *
 * Capacity is capped at [MAX_EVENTS]. When full, the oldest event is dropped to
 * prevent unbounded disk growth when the network is unavailable for an extended period.
 */
internal class AnalyticsQueue {
    private val log = logger<AnalyticsQueue>()
    private val events = CopyOnWriteArrayList<AnalyticsEventPayload>()

    companion object {
        const val MAX_EVENTS = 500
        private const val QUEUE_FILE = "analytics_queue.json"
    }

    private val queuePath get() = AskimoHome.base().resolve(QUEUE_FILE)

    init {
        loadFromDisk()
    }

    /** Adds [event] to the tail of the queue, dropping the oldest entry when at capacity. */
    fun enqueue(event: AnalyticsEventPayload) {
        if (events.size >= MAX_EVENTS) events.removeAt(0)
        events.add(event)
        persistToDisk()
    }

    /**
     * Returns all queued events as an immutable snapshot and clears the queue.
     * The caller is responsible for re-enqueuing on delivery failure.
     */
    fun drainAll(): List<AnalyticsEventPayload> {
        val snapshot = events.toList()
        events.clear()
        persistToDisk()
        return snapshot
    }

    fun size(): Int = events.size

    /** Deletes the on-disk queue file — called on opt-out. */
    fun deleteDisk() {
        runCatching { Files.deleteIfExists(queuePath) }
            .onFailure { log.debug("Analytics: failed to delete queue file: ${it.message}") }
    }

    private fun persistToDisk() {
        runCatching {
            Files.createDirectories(queuePath.parent)
            val json = appJson.encodeToString(ListSerializer(AnalyticsEventPayload.serializer()), events.toList())
            Files.writeString(queuePath, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        }.onFailure { log.debug("Analytics: queue persist failed: ${it.message}") }
    }

    private fun loadFromDisk() {
        runCatching {
            if (!Files.exists(queuePath)) return
            val json = Files.readString(queuePath)
            val loaded = appJson.decodeFromString(ListSerializer(AnalyticsEventPayload.serializer()), json)
            events.addAll(loaded.takeLast(MAX_EVENTS))
            log.debug("Analytics: loaded ${events.size} queued events from disk")
        }.onFailure { log.debug("Analytics: queue load failed: ${it.message}") }
    }
}
