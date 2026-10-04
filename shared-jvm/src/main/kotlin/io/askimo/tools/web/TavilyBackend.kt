/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.tools.web

import io.askimo.core.logging.logger
import io.askimo.core.util.httpPost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Web search backend using the Tavily Search API.
 *
 * Docs: https://docs.tavily.com/docs/tavily-api/rest_api
 * Requires a free or paid API key from https://app.tavily.com
 *
 * Uses a POST request with a JSON body — Tavily's standard search endpoint.
 */
class TavilyBackend(private val apiKey: String) : SearchBackend {

    override val name: String = "Tavily"

    private val log = logger<TavilyBackend>()
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val BASE_URL = "https://api.tavily.com/search"
        private const val TIMEOUT_MS = 20_000L
    }

    override fun search(query: String, maxResults: Int): List<SearchResult> {
        log.debug("Tavily search: query='{}', maxResults={}", query, maxResults)

        val requestBody = buildJsonObject {
            put("query", query.trim())
            put("max_results", maxResults)
            put("search_depth", "basic")
            put("include_answer", false)
            put("api_key", apiKey)
        }.toString()

        val (status, body) = httpPost(
            url = BASE_URL,
            body = requestBody,
            headers = mapOf("Content-Type" to "application/json"),
            readTimeoutMs = TIMEOUT_MS,
        )

        if (status != 200) {
            log.warn("Tavily returned HTTP {}: {}", status, body.take(200))
            error("Tavily API returned HTTP $status")
        }

        return parseResults(body, maxResults)
    }

    private fun parseResults(body: String, maxResults: Int): List<SearchResult> = try {
        val root = json.parseToJsonElement(body).jsonObject
        val results = root["results"]?.jsonArray ?: return emptyList()

        results.take(maxResults).mapNotNull { element ->
            val obj = element.jsonObject
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
            val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
            // Tavily returns `content` (full snippet) or `raw_content`
            val snippet = obj["content"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: obj["raw_content"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: ""
            SearchResult(title = title, url = url, snippet = snippet.take(300))
        }
    } catch (e: Exception) {
        log.warn("Failed to parse Tavily response: {}", e.message)
        emptyList()
    }
}
