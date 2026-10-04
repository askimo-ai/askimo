/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.tools.web

import io.askimo.core.AppConstants.DOMAIN
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
 * Web search backend using a self-hosted (or public) SearxNG instance.
 *
 * SearxNG is a free, open-source, privacy-respecting metasearch engine.
 * Users can run their own instance or point to a public one (e.g. https://searx.be).
 *
 * Docs: https://docs.searxng.org/dev/search_api.html
 * No API key required — just a running SearxNG endpoint.
 */
class SearxNGBackend(private val endpoint: String) : SearchBackend {

    override val name: String = "SearxNG"

    private val log = logger<SearxNGBackend>()
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TIMEOUT_MS = 15_000L
        private const val USER_AGENT = "Mozilla/5.0 (compatible; Askimo/1.0; +https://$DOMAIN)"
    }

    override fun search(query: String, maxResults: Int): List<SearchResult> {
        val base = endpoint.trimEnd('/')
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
        val url = "$base/search?q=$encoded&format=json&categories=general&language=en"

        log.debug("SearxNG search: query='{}', endpoint='{}'", query, base)

        val (status, body) = httpGet(
            url = url,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json",
            ),
            readTimeoutMs = TIMEOUT_MS,
        )

        if (status != 200) {
            log.warn("SearxNG returned HTTP {}: {}", status, body.take(200))
            error("SearxNG returned HTTP $status — check the endpoint URL in Settings → Web Search")
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
            val snippet = obj["content"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
            SearchResult(title = title, url = url, snippet = snippet)
        }
    } catch (e: Exception) {
        log.warn("Failed to parse SearxNG response: {}", e.message)
        emptyList()
    }
}
