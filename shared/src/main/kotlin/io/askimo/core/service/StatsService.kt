/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.service

import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.logging.logger
import io.askimo.core.util.JsonUtils.json
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Serializable
data class AppStats(
    val stars: Int,
    val downloads: Int,
    val updatedAt: String,
)

/**
 * Fetches public Askimo stats (stars, downloads) from the public API.
 * Results are cached for [cacheDurationMs] to avoid repeated network calls.
 */
class StatsService {
    private val log = logger<StatsService>()
    private val statsUrl = "https://api.$DOMAIN/stats/downloads"
    private val cacheDurationMs = 6 * 60 * 60 * 1000L // 6 hours

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .build()

    @Volatile private var cached: AppStats? = null

    @Volatile private var cachedAt: Long = 0L

    /** Returns cached stats if fresh, otherwise fetches. Returns null on any failure. */
    fun getStats(): AppStats? {
        val now = System.currentTimeMillis()
        if (cached != null && (now - cachedAt) < cacheDurationMs) return cached

        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(statsUrl))
                .header("Accept", "application/json")
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val stats = json.decodeFromString(AppStats.serializer(), response.body())
                cached = stats
                cachedAt = now
                stats
            } else {
                log.warn("Stats fetch returned status ${response.statusCode()}")
                null
            }
        } catch (e: Exception) {
            log.debug("Stats fetch failed (non-critical)", e)
            null
        }
    }

    companion object {
        private val instance = StatsService()
        fun getInstance(): StatsService = instance
    }
}
