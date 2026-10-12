/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.llama

import io.askimo.core.util.AskimoHome
import io.askimo.core.util.httpGet
import io.askimo.core.util.httpPost
import io.askimo.test.extensions.AskimoTestHome
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the reverse-proxy request-handling paths in [LlamaServerProcessManager] that don't
 * require an actual `llama-server` binary/model: CORS rejection, request validation, the
 * dynamic `GET /models` listing, and the [AskimoHomeModelsFallback] path-traversal/zero-byte
 * guards. Spawning a real `llama-server` child process (actual chat/embedding inference) needs a
 * real multi-GB model and isn't practical to exercise in an automated unit test, so that part of
 * the class is deliberately out of scope here.
 *
 * `@AskimoTestHome` registers a temp-directory-backed `AskimoHome` *globally* (not just
 * thread-local), which matters here specifically because the reverse proxy serves requests on
 * its own executor threads, not the test thread.
 */
@AskimoTestHome
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LlamaServerProcessManagerTest {

    private lateinit var baseUrl: String

    @BeforeAll
    fun startProxy() {
        LlamaServerProcessManager.startProxy()
        val port = LlamaServerProcessManager.proxyStatus.value.port
            ?: error("Proxy did not report a port after startProxy().")
        baseUrl = "http://127.0.0.1:$port"
    }

    @AfterAll
    fun stopProxy() {
        runBlocking { LlamaServerProcessManager.stopProxy() }
    }

    @BeforeEach
    fun ensureModelsDirExists() {
        // @AskimoTestHome gives each @Test a fresh temp AskimoHome.base(), so llamaModelsDir()
        // starts empty per test.
        Files.createDirectories(AskimoHome.llamaModelsDir())
    }

    // ── CORS rejection ───────────────────────────────────────────────────────

    @Test
    fun `rejects any request carrying an Origin header`() {
        val (status, _) = httpGet(
            url = "$baseUrl/v1/models",
            headers = mapOf("Origin" to "http://evil.example.com"),
        )
        assertEquals(403, status)
    }

    // ── Request validation ───────────────────────────────────────────────────

    @Test
    fun `rejects a request body missing the model field`() {
        val (status, body) = httpPost(url = "$baseUrl/v1/chat/completions", body = "{}")
        assertEquals(400, status)
        assertTrue(body.contains("model"), "Error message should mention the missing \"model\" field: $body")
    }

    @Test
    fun `rejects a model that is not downloaded`() {
        val (status, body) = httpPost(
            url = "$baseUrl/v1/chat/completions",
            body = """{"model":"does-not-exist.gguf"}""",
        )
        assertEquals(404, status)
        assertTrue(body.contains("not downloaded"), body)
    }

    // ── GET /models ──────────────────────────────────────────────────────────

    @Test
    fun `models list reflects gguf files actually on disk, not just the curated catalog`() {
        val fileName = "my-custom-finetune.gguf"
        Files.write(AskimoHome.llamaModelsDir().resolve(fileName), byteArrayOf(1, 2, 3, 4))

        val (status, body) = httpGet(url = "$baseUrl/v1/models")

        assertEquals(200, status)
        assertTrue(body.contains(fileName), "Expected dynamically-downloaded model in: $body")
    }

    @Test
    fun `models list ignores non-gguf files`() {
        Files.write(AskimoHome.llamaModelsDir().resolve("readme.txt"), "hello".toByteArray())

        val (status, body) = httpGet(url = "$baseUrl/v1/models")

        assertEquals(200, status)
        assertFalse(body.contains("readme.txt"), body)
    }

    // ── AskimoHomeModelsFallback path-traversal guard ────────────────────────

    @Test
    fun `rejects a path-traversal model name instead of resolving it`() {
        // A real file that exists *outside* llamaModelsDir — if the traversal guard failed,
        // this is the file a malicious request would trick llama-server into "loading".
        Files.write(AskimoHome.base().resolve("outside.gguf"), byteArrayOf(1, 2, 3))

        val (status, body) = httpPost(
            url = "$baseUrl/v1/chat/completions",
            body = """{"model":"../../outside.gguf"}""",
        )

        // Must be treated as "not downloaded", never resolved to the file outside the models dir.
        assertEquals(404, status)
        assertTrue(body.contains("not downloaded"), body)
    }

    @Test
    fun `rejects a non-gguf model name even if a matching file exists`() {
        Files.write(AskimoHome.llamaModelsDir().resolve("not-a-model.txt"), byteArrayOf(1))

        val (status, _) = httpPost(
            url = "$baseUrl/v1/chat/completions",
            body = """{"model":"not-a-model.txt"}""",
        )

        assertEquals(404, status)
    }

    // ── Zero-byte / corrupted file detection ─────────────────────────────────

    @Test
    fun `rejects a zero-byte dynamically-discovered model file`() {
        val fileName = "truncated-download.gguf"
        Files.write(AskimoHome.llamaModelsDir().resolve(fileName), ByteArray(0))

        val (status, body) = httpPost(
            url = "$baseUrl/v1/chat/completions",
            body = """{"model":"$fileName"}""",
        )

        assertEquals(500, status)
        assertTrue(body.contains("empty"), body)
    }
}
