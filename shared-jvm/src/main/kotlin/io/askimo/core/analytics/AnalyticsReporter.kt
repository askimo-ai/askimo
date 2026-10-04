/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.analytics

import io.askimo.core.logging.logger
import io.askimo.core.util.appJson
import io.askimo.core.util.httpPost
import kotlinx.serialization.builtins.ListSerializer
import java.net.http.HttpClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Flushes the [AnalyticsQueue] to the Askimo ingest endpoint in the background.
 *
 * - Runs on a single daemon thread - never blocks the UI or CLI.
 * - Scheduled flush every [FLUSH_INTERVAL_MINUTES]; callable on-demand via [flushNow].
 * - On POST failure, events are re-enqueued and retried on the next cycle.
 * - Completely inert when [Analytics.isEnabled] is false.
 */
internal class AnalyticsReporter(
    private val queue: AnalyticsQueue,
    private val endpoint: String,
) {
    private val log = logger<AnalyticsReporter>()

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "askimo-analytics").also { it.isDaemon = true }
    }

    companion object {
        const val FLUSH_INTERVAL_MINUTES = 30L
        private const val BATCH_SIZE = 50
        private const val TIMEOUT_SECONDS = 15L
    }

    /** Starts the scheduled flush loop. */
    fun start() {
        executor.scheduleWithFixedDelay(
            ::flush,
            FLUSH_INTERVAL_MINUTES,
            FLUSH_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        )
    }

    fun flushNow() {
        runCatching {
            executor.submit(::flush).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.onFailure { log.trace("Analytics: flushNow failed: ${it.message}") }
    }

    fun shutdown() {
        flushNow()
        executor.shutdown()
    }

    private fun flush() {
        if (queue.size() == 0) return
        val batch = queue.drainAll().take(BATCH_SIZE)
        if (batch.isEmpty()) return
        runCatching {
            val body = appJson.encodeToString(ListSerializer(AnalyticsEventPayload.serializer()), batch)
            log.trace("Analytics: flush data: $body")
            val (status, _) = httpPost(
                url = endpoint,
                body = body,
                connectTimeoutMs = 10_000,
                readTimeoutMs = TIMEOUT_SECONDS * 1_000,
                httpVersion = HttpClient.Version.HTTP_2,
            )
            if (status in 200..299) {
                log.debug("Analytics: flushed ${batch.size} events (HTTP $status)")
            } else {
                log.debug("Analytics: HTTP $status - re-queuing ${batch.size} events")
                batch.forEach { queue.enqueue(it) }
            }
        }.onFailure { e ->
            log.trace("Analytics: flush error (${e.message}) - re-queuing ${batch.size} events")
            batch.forEach { queue.enqueue(it) }
        }
    }
}
