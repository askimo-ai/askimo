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
 * Web search backend using the Brave Search API.
 *
 * Docs: https://api.search.brave.com/app/documentation/web-search/get-started
 * Requires a free or paid API key from https://brave.com/search/api/
 */
class BraveBackend(private val apiKey: String) : SearchBackend {

    override val name: String = "Brave Search"

    private val log = logger<BraveBackend>()
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val BASE_URL = "https://api.search.brave.com/res/v1/web/search"
        private const val TIMEOUT_MS = 15_000L
    }

    override fun search(query: String, maxResults: Int): List<SearchResult> {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
        val url = "$BASE_URL?q=$encoded&count=$maxResults"

        log.debug("Brave Search: query='{}', count={}", query, maxResults)

        val (status, body) = httpGet(
            url = url,
            headers = mapOf(
                "X-Subscription-Token" to apiKey,
                "Accept" to "application/json",
                "Accept-Encoding" to "gzip",
            ),
            readTimeoutMs = TIMEOUT_MS,
        )

        if (status != 200) {
            log.warn("Brave Search returned HTTP {}: {}", status, body.take(200))
            error("Brave Search API returned HTTP $status")
        }

        return parseResults(body, maxResults)
    }

    private fun parseResults(body: String, maxResults: Int): List<SearchResult> = try {
        val root = json.parseToJsonElement(body).jsonObject
        val webResults = root["web"]?.jsonObject
            ?.get("results")?.jsonArray
            ?: return emptyList()

        webResults.take(maxResults).mapNotNull { element ->
            val obj = element.jsonObject
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
            val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
            val snippet = obj["description"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
            SearchResult(title = title, url = url, snippet = snippet)
        }
    } catch (e: Exception) {
        log.warn("Failed to parse Brave Search response: {}", e.message)
        emptyList()
    }
}
