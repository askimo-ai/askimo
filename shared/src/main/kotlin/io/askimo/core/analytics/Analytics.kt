/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.analytics

import io.askimo.core.VersionInfo
import io.askimo.core.config.AppConfig
import io.askimo.core.logging.logger
import io.askimo.core.util.AskimoHome
import io.askimo.core.util.httpPost
import java.net.http.HttpClient
import java.nio.file.Files

/**
 * Business analytics singleton — tracks anonymous feature usage for Askimo.t.
 *
 * ## Privacy contract
 * - Default: **disabled**. Zero data leaves the device until the user explicitly opts in.
 * - Opt-in is presented once via [io.askimo.ui.shell.analyticsConsentDialog].
 * - Opt-out immediately stops collection, clears the queue, and deletes the disk file.
 * - No conversation content, prompts, responses, file paths, or API keys — ever.
 *
 * ## Usage
 * ```kotlin
 * Analytics.initialize(AppConfig.analytics)
 * Analytics.track(AnalyticsEvent.APP_STARTED, mapOf("mode" to "desktop"))
 * Analytics.shutdown() // on clean app exit
 * ```
 */
object Analytics {
    private val log = logger<Analytics>()

    @Volatile private var enabled = false

    @Volatile private var initialized = false

    @Volatile private var reporterStarted = false

    private lateinit var queue: AnalyticsQueue
    private lateinit var reporter: AnalyticsReporter

    /** Flag file name written after a successful install ping. */
    private const val INSTALL_PING_FILE = ".install_ping_sent"

    /** Epoch-ms at [initialize] time, used to bucket session duration in [trackSessionEnd]. */
    @Volatile private var sessionStartMs: Long = 0L

    /** True when the user has opted in and analytics is actively collecting. */
    val isEnabled: Boolean get() = enabled

    /**
     * Returns `"local"` for self-hosted providers (Ollama, LMStudio, LocalAI, Docker),
     * `"cloud"` for all managed / API-key providers.
     * Never includes the model ID — only the tier matters for analytics.
     */
    fun modelTier(provider: String): String {
        val local = setOf("OLLAMA", "LMSTUDIO", "LOCALAI", "DOCKER")
        return if (provider.uppercase() in local) "local" else "cloud"
    }

    /**
     * Convenience helper — tracks [AnalyticsEvent.APP_SESSION_ENDED] with bucketed duration
     * and message count. Call on clean app exit after [shutdown].
     *
     * @param messageCount total number of chat messages sent in this session.
     */
    fun trackSessionEnd(messageCount: Int) {
        if (!enabled || !initialized) return
        val durationMs = System.currentTimeMillis() - sessionStartMs
        val durationBucket = when {
            durationMs < 60_000L -> "<1m"
            durationMs < 600_000L -> "1-10m"
            durationMs < 3_600_000L -> "10-60m"
            else -> ">1h"
        }
        val messageBucket = when {
            messageCount <= 5 -> "1-5"
            messageCount <= 20 -> "6-20"
            else -> ">20"
        }
        track(
            AnalyticsEvent.APP_SESSION_ENDED,
            mapOf(
                "session_duration_bucket" to durationBucket,
                "message_count_bucket" to messageBucket,
            ),
        )
    }

    /**
     * Fires [AnalyticsEvent.RETURNING_USER] when [launchCount] hits a retention milestone
     * (2nd, 7th, or 30th launch). No-op for other counts or when analytics is disabled.
     *
     * @param launchCount The current launch count returned by `ApplicationPreferences.incrementLaunchCount()`.
     */
    fun trackRetentionMilestone(launchCount: Int) {
        val bucket = when (launchCount) {
            2 -> "2"
            7 -> "7"
            30 -> "30"
            else -> return
        }
        track(AnalyticsEvent.RETURNING_USER, mapOf("launch_count_bucket" to bucket))
    }

    fun sendInstallPingIfNeeded() {
        val flagPath = AskimoHome.base().resolve(INSTALL_PING_FILE)
        val currentVersion = VersionInfo.version
        // Re-send if the flag file is absent OR contains a different (older) version
        if (Files.exists(flagPath) && runCatching { Files.readString(flagPath).trim() }.getOrNull() == currentVersion) return
        val endpoint = AppConfig.analytics.endpoint
        val payload = buildInstallPingPayload()
        Thread({
            runCatching {
                val (status, _) = httpPost(
                    url = endpoint,
                    body = payload,
                    connectTimeoutMs = 10_000,
                    readTimeoutMs = 15_000,
                    httpVersion = HttpClient.Version.HTTP_2,
                )
                if (status in 200..299) {
                    runCatching {
                        Files.createDirectories(flagPath.parent)
                        // Write the current version so upgrades trigger a new ping
                        Files.writeString(flagPath, currentVersion)
                    }
                    log.debug("Install ping sent for version $currentVersion (HTTP $status)")
                } else {
                    log.trace("Install ping HTTP $status — will retry on next launch")
                }
            }.onFailure { log.trace("Install ping failed: ${it.message} — will retry on next launch") }
        }, "askimo-install-ping").also { it.isDaemon = true }.start()
    }

    private fun buildInstallPingPayload(): String {
        val os = System.getProperty("os.name", "unknown")
            .replace("\\", "\\\\").replace("\"", "\\\"")
        val version = VersionInfo.version
            .replace("\\", "\\\\").replace("\"", "\\\"")
        return """[{"event":"${AnalyticsEvent.INSTALL_PING.eventName}","appVersion":"$version","os":"$os"}]"""
    }

    /**
     * Reads opt-in state and endpoint from [AppConfig.analytics], then starts
     * the reporter if the user has already opted in.
     * Must be called once at app startup before any [track] calls.
     */
    fun initialize() {
        synchronized(this) {
            if (initialized) return
            val config = AppConfig.analytics
            queue = AnalyticsQueue()
            reporter = AnalyticsReporter(queue, config.endpoint)
            enabled = config.optedIn
            initialized = true
            sessionStartMs = System.currentTimeMillis()
            if (enabled) {
                reporter.start()
                reporterStarted = true
            }
            log.debug("Analytics initialized, enabled=$enabled")
        }
    }

    /**
     * Records a feature usage event. No-op when not opted in or not initialized.
     * Call sites must never pass conversation content, file paths, or user identity.
     */
    fun track(event: AnalyticsEvent, properties: Map<String, String> = emptyMap()) {
        if (!enabled || !initialized) return
        queue.enqueue(AnalyticsEventPayload(event = event.eventName, properties = properties))
    }

    /**
     * Starts the reporter if not already running and persists the choice.
     */
    fun optIn() {
        synchronized(this) {
            if (!initialized) return
            enabled = true
            if (!reporterStarted) {
                reporter.start()
                reporterStarted = true
            }
        }
        track(AnalyticsEvent.ANALYTICS_OPT_IN)
        persistChoice(true)
        log.info("Analytics: user opted in")
    }

    /**
     * User explicitly opted out (e.g. toggled off in Settings).
     * Stops collection, clears the queue, and deletes the disk file immediately.
     */
    fun optOut() {
        track(AnalyticsEvent.ANALYTICS_OPT_OUT)
        synchronized(this) {
            enabled = false
            if (initialized) {
                runCatching { reporter.flushNow() }
                queue.drainAll()
                queue.deleteDisk()
            }
        }
        persistChoice(false)
        log.info("Analytics: user opted out")
    }

    /**
     * Flushes remaining queued events and shuts the reporter down.
     * Call once on clean app exit.
     */
    fun shutdown() {
        if (!enabled || !initialized) return
        runCatching { reporter.shutdown() }
    }

    private fun persistChoice(value: Boolean) {
        runCatching { AppConfig.updateField("analytics.opted_in", value) }
            .onFailure { log.debug("Analytics: failed to persist opt-in: ${it.message}") }
    }
}
