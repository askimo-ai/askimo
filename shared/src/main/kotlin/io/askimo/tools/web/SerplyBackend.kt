/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.tools.web

import io.askimo.core.logging.logger
import io.askimo.core.util.httpGet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Web search backend using the Serply API, which returns Google organic results.
 *
 * Docs: https://serply.io/docs
 * Requires a free or paid API key from https://serply.io
 */
class SerplyBackend(private val apiKey: String) : SearchBackend {

    override val name: String = "Serply"

    private val log = logger<SerplyBackend>()
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val BASE_URL = "https://api.serply.io/v1/search/"
        private const val TIMEOUT_MS = 15_000L
    }

    override fun search(query: String, maxResults: Int): List<SearchResult> {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
        val url = "$BASE_URL?q=$encoded&num=$maxResults"

        log.debug("Serply search: query='{}', num={}", query, maxResults)

        val (status, body) = httpGet(
            url = url,
            headers = mapOf(
                "X-Api-Key" to apiKey,
                "Accept" to "application/json",
                "Accept-Encoding" to "gzip",
            ),
            readTimeoutMs = TIMEOUT_MS,
        )

        if (status != 200) {
            log.warn("Serply returned HTTP {}: {}", status, body.take(200))
            error("Serply API returned HTTP $status")
        }

        return parseResults(body, maxResults)
    }

    private fun parseResults(body: String, maxResults: Int): List<SearchResult> = try {
        val root = json.parseToJsonElement(body).jsonObject
        val results = root["results"]?.jsonArray ?: return emptyList()

        // `num` is an upper bound Serply treats loosely, so trim here as well.
        results.take(maxResults).mapNotNull { element ->
            val obj = element.jsonObject
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
            val url = obj["link"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
            val snippet = obj["description"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
            SearchResult(title = title, url = url, snippet = snippet)
        }
    } catch (e: Exception) {
        log.warn("Failed to parse Serply response: {}", e.message)
        emptyList()
    }
}
