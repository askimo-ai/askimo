/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.tools.web

import io.askimo.core.exception.AuthenticationException
import io.askimo.core.exception.InsufficientCreditsException
import io.askimo.core.exception.RateLimitException
import io.askimo.core.exception.SearchBackendException
import io.askimo.core.exception.UserException
import io.askimo.core.logging.logger
import io.askimo.core.util.httpPost
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Web search backend using the SerpBase API, which returns Google organic results.
 *
 * Docs: https://serpbase.dev/docs
 * Requires an API key from https://serpbase.dev/api-keys. One key covers every endpoint on the
 * API; this backend only uses the Google Web Search endpoint.
 *
 * Two details differ from the other HTTP backends in this package:
 *  - the API is **POST-only** and authenticates with an `X-API-Key` header. A key passed as a
 *    query parameter is rejected, so there is no GET fallback to reach for;
 *  - failures still come back as **HTTP 200** with a non-zero `status` in the JSON envelope, so
 *    the HTTP status code alone can't be used to decide whether the search worked.
 */
class SerpBaseBackend(
    private val apiKey: String,
    private val endpoint: String = DEFAULT_ENDPOINT,
) : SearchBackend {

    override val name: String = "SerpBase"

    private val log = logger<SerpBaseBackend>()
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val DEFAULT_ENDPOINT = "https://api.serpbase.dev/google/search"
        private const val TIMEOUT_MS = 30_000L

        // Envelope status codes we can explain to the user — see https://serpbase.dev/docs#errors
        private const val STATUS_SUCCESS = 0
        private const val STATUS_UNAUTHORIZED = 1001
        private const val STATUS_INSUFFICIENT_CREDITS = 1020
        private const val STATUS_RATE_LIMITED = 1029
    }

    override fun search(query: String, maxResults: Int): List<SearchResult> {
        log.debug("SerpBase search: query='{}', num={}", query, maxResults)

        val (httpStatus, body) = httpPost(
            url = endpoint,
            body = buildJsonObject {
                put("q", query.trim())
                put("num", maxResults)
            }.toString(),
            headers = mapOf(
                "X-API-Key" to apiKey,
                "Accept" to "application/json",
            ),
            readTimeoutMs = TIMEOUT_MS,
        )

        // A non-200 here is a transport/gateway problem, not a business error — SerpBase reports
        // the latter with HTTP 200 plus a non-zero envelope status.
        if (httpStatus != 200) {
            log.warn("SerpBase returned HTTP {}: {}", httpStatus, body.take(200))
            throw SearchBackendException(name, "HTTP $httpStatus")
        }

        val root = parseEnvelope(body)
        root.failureOrNull()?.let { throw it }

        return parseOrganic(root, maxResults)
    }

    private fun parseEnvelope(body: String): JsonObject = try {
        json.parseToJsonElement(body).jsonObject
    } catch (e: SerializationException) {
        log.warn("Failed to parse SerpBase response: {}", e.message)
        throw SearchBackendException(name, "unreadable response body", e)
    } catch (e: IllegalStateException) {
        // Body was valid JSON but not an object.
        log.warn("Unexpected SerpBase response shape: {}", e.message)
        throw SearchBackendException(name, "unexpected response shape", e)
    }

    /**
     * Maps a non-zero envelope `status` to the matching user-facing error, or null on success.
     *
     * Statuses we don't recognise are surfaced as [SearchBackendException] rather than an empty
     * result list: a silent empty list here reads as "no matches for your query", which hides a
     * real failure from the user.
     */
    private fun JsonObject.failureOrNull(): UserException? = when (val status = intOrNull("status")) {
        STATUS_SUCCESS -> null
        STATUS_UNAUTHORIZED -> AuthenticationException(name)
        STATUS_INSUFFICIENT_CREDITS -> InsufficientCreditsException()
        STATUS_RATE_LIMITED -> RateLimitException()
        else -> {
            val message = stringOrNull("message") ?: "unexpected status"
            log.warn("SerpBase error envelope: status={}, message={}", status, message.take(200))
            SearchBackendException(name, "status $status: $message")
        }
    }

    private fun parseOrganic(root: JsonObject, maxResults: Int): List<SearchResult> {
        // `num` is only a soft upper bound upstream, so trim here as well.
        val organic = root["organic"] as? JsonArray ?: return emptyList()
        return organic.asSequence()
            .mapNotNull { it.toSearchResultOrNull() }
            .take(maxResults)
            .toList()
    }

    private fun JsonElement.toSearchResultOrNull(): SearchResult? {
        val obj = this as? JsonObject ?: return null
        val title = obj.stringOrNull("title") ?: return null
        val url = obj.stringOrNull("link") ?: return null
        return SearchResult(title = title, url = url, snippet = obj.stringOrNull("snippet").orEmpty())
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
}
