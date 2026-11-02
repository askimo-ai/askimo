/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.tools.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.askimo.core.exception.AuthenticationException
import io.askimo.core.exception.InsufficientCreditsException
import io.askimo.core.exception.SearchBackendException
import io.askimo.test.extensions.AskimoTestHome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for [SerpBaseBackend].
 *
 * Covers the two things that make this backend different from the GET-based ones: the POST +
 * `X-API-Key` request shape, and error detection from the JSON envelope rather than the HTTP
 * status code.
 */
@AskimoTestHome
class SerpBaseBackendTest {

    private lateinit var server: HttpServer
    private lateinit var endpoint: String

    private var responseStatus = 200
    private var responseBody = ""
    private var lastMethod: String? = null
    private var lastApiKey: String? = null
    private var lastBody: String? = null

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/google/search") { exchange -> respond(exchange) }
        server.start()
        endpoint = "http://127.0.0.1:${server.address.port}/google/search"
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `sends POST with api key header and json query`() {
        responseBody = envelope(0, organic = listOf(result("T", "https://a.test/1", "S")))

        SerpBaseBackend(apiKey = "test-key", endpoint = endpoint).search("kotlin coroutines", 5)

        assertEquals("POST", lastMethod)
        assertEquals("test-key", lastApiKey)
        val body = Json.parseToJsonElement(lastBody!!).jsonObject
        assertEquals("kotlin coroutines", body["q"]?.jsonPrimitive?.content)
        assertEquals("5", body["num"]?.jsonPrimitive?.content)
    }

    @Test
    fun `maps organic results to search results and trims to maxResults`() {
        responseBody = envelope(
            0,
            organic = listOf(
                result("First", "https://a.test/1", "Snippet one"),
                result("Second", "https://a.test/2", "Snippet two"),
                result("Third", "https://a.test/3", "Snippet three"),
            ),
        )

        val results = SerpBaseBackend("test-key", endpoint).search("q", 2)

        assertEquals(listOf("First", "Second"), results.map { it.title })
        assertEquals("https://a.test/1", results.first().url)
        assertEquals("Snippet one", results.first().snippet)
    }

    @Test
    fun `drops entries without a title or link`() {
        responseBody = envelope(
            0,
            organic = listOf(
                result("", "https://a.test/1", "no title"),
                result("No link", "", "no link"),
                result("Kept", "https://a.test/3", ""),
            ),
        )

        val results = SerpBaseBackend("test-key", endpoint).search("q", 10)

        assertEquals(1, results.size)
        assertEquals("Kept", results.single().title)
        assertEquals("", results.single().snippet)
    }

    @Test
    fun `treats zero results as an empty list rather than an error`() {
        responseBody = envelope(0, organic = emptyList())

        assertTrue(SerpBaseBackend("test-key", endpoint).search("q", 10).isEmpty())
    }

    @Test
    fun `maps unauthorized envelope status to AuthenticationException`() {
        responseBody = envelope(1001, message = "Invalid API key")

        assertFailsWith<AuthenticationException> {
            SerpBaseBackend("bad-key", endpoint).search("q", 10)
        }
    }

    @Test
    fun `maps out-of-credits envelope status to InsufficientCreditsException`() {
        responseBody = envelope(1020, message = "Insufficient credits")

        assertFailsWith<InsufficientCreditsException> {
            SerpBaseBackend("test-key", endpoint).search("q", 10)
        }
    }

    @Test
    fun `maps unknown envelope status to a localized backend error`() {
        responseBody = envelope(1503, message = "Upstream timeout")

        val error = assertFailsWith<SearchBackendException> {
            SerpBaseBackend("test-key", endpoint).search("q", 10)
        }

        assertEquals("SerpBase", error.backend)
        assertTrue(error.details.contains("1503"))
        assertTrue(error.details.contains("Upstream timeout"))
    }

    @Test
    fun `maps non-200 http response to a backend error`() {
        responseStatus = 503
        responseBody = "<html>Service Unavailable</html>"

        val error = assertFailsWith<SearchBackendException> {
            SerpBaseBackend("test-key", endpoint).search("q", 10)
        }

        assertEquals("HTTP 503", error.details)
    }

    private fun respond(exchange: HttpExchange) {
        lastMethod = exchange.requestMethod
        lastApiKey = exchange.requestHeaders.getFirst("X-API-Key")
        lastBody = exchange.requestBody.use { it.readAllBytes().toString(StandardCharsets.UTF_8) }

        val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun result(title: String, link: String, snippet: String): String =
        """{"title":"$title","link":"$link","snippet":"$snippet","position":1}"""

    private fun envelope(status: Int, organic: List<String> = emptyList(), message: String = ""): String {
        val messageField = if (message.isEmpty()) "" else ",\"message\":\"$message\""
        return """{"status":$status$messageField,"request_id":"req-1","organic":[${organic.joinToString(",")}]}"""
    }
}
