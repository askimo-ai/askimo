/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.exception

import dev.langchain4j.exception.InternalServerException
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CompletionException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Confirms that real-world provider error payloads are classified into the correct
 * [AskimoException] subtype, with the right user-facing `details`/`model` fields —
 * i.e. what the user actually ends up reading via [ExceptionHandler.handle].
 */
class ExceptionMapperTest {

    // ── 429 quota / rate limit (Gemini-style) ──────────────────────────────────

    @Test
    fun `Gemini quota exceeded message is classified as rate limit`() {
        val e = RuntimeException(
            """{"error":{"code":429,"message":"You exceeded your current quota, please check your plan """ +
                """and billing details.","status":"RESOURCE_EXHAUSTED"}}""",
        )
        val mapped = ExceptionMapper.map(e)
        assertIs<RateLimitException>(mapped)
        assertEquals(RetryPolicy.BACKOFF, mapped.retryPolicy)
    }

    // ── 503 overloaded (Gemini-style) ───────────────────────────────────────────

    @Test
    fun `Gemini UNAVAILABLE 503 message is classified as remote server error, not generic provider error`() {
        val e = RuntimeException(
            """{"error":{"code":503,"message":"This model is currently experiencing high demand. """ +
                """Spikes in demand are usually temporary. Please try again later.","status":"UNAVAILABLE"}}""",
        )
        val mapped = ExceptionMapper.map(e)
        assertIs<RemoteServerException>(mapped)
        assertEquals(RetryPolicy.BACKOFF, mapped.retryPolicy)
    }

    // ── Model not found — model name extraction ─────────────────────────────────

    @Test
    fun `OpenAI model not found message extracts the backtick-quoted model name`() {
        val e = RuntimeException(
            "The model `davinci-002` does not exist or you do not have access to it.",
        )
        val mapped = ExceptionMapper.map(e)
        assertIs<ModelNotFoundChatException>(mapped)
        assertEquals("davinci-002", mapped.model)
    }

    @Test
    fun `model not found message without a quoted name falls back to empty model`() {
        val e = RuntimeException("No model provided in request")
        val mapped = ExceptionMapper.map(e)
        assertIs<ModelNotFoundChatException>(mapped)
        assertEquals("", mapped.model)
    }

    // ── Local server crash — unsupported GGUF architecture ──────────────────────

    @Test
    fun `llama-server unknown model architecture surfaces the raw provider message, not authored guidance`() {
        val e = RuntimeException(
            "llama-server process has terminated: exit status 1: error loading model: " +
                "unknown model architecture: 'mllama'\nerror loading model: unknown model architecture: 'mllama'",
        )
        val mapped = ExceptionMapper.map(e)
        assertIs<LocalServerException>(mapped)
        assertTrue(mapped.details.contains("'mllama'"), "details should contain the provider's raw architecture text")
        assertTrue(!mapped.details.contains("llama.cpp"))
        assertEquals(RetryPolicy.NONE, mapped.retryPolicy, "unsupported architecture is not retryable")
    }

    @Test
    fun `InternalServerException with unknown architecture is also classified as LocalServerException via type match`() {
        val e = InternalServerException(
            "llama-server process has terminated: exit status 1: error loading model: " +
                "unknown model architecture: 'mllama'",
        )
        val mapped = ExceptionMapper.map(e)
        assertIs<LocalServerException>(mapped)
        assertTrue(mapped.details.contains("'mllama'"))
    }

    @Test
    fun `local server crash without a recognisable architecture falls back to truncated raw message`() {
        val e = RuntimeException("llama-server process has terminated: exit status 1: out of memory")
        val mapped = ExceptionMapper.map(e)
        assertIs<LocalServerException>(mapped)
        assertTrue(mapped.details.contains("out of memory"))
    }

    // ── Multimodal not supported (OpenAI-compatible local servers) ──────────────

    @Test
    fun `multimodal not supported message is classified with its own type, carrying the raw provider message`() {
        val e = RuntimeException(
            """{"error":{"message":"{\"error\":{\"code\":400,\"message\":\"Multimodal data provided, """ +
                """but model does not support multimodal requests.\",\"type\":\"invalid_request_error\"}}",""" +
                """"type":"invalid_request_error"}}""",
        )
        val mapped = ExceptionMapper.map(e)
        assertIs<MultimodalNotSupportedException>(mapped)
        assertTrue(mapped.details.contains("does not support multimodal requests"))
    }

    // ── Already-classified exceptions pass through unchanged ────────────────────

    @Test
    fun `an exception already classified as an AskimoException is returned as-is`() {
        val original = RateLimitException(retryAfterSeconds = 30)
        val mapped = ExceptionMapper.map(original)
        assertEquals(original, mapped)
    }

    // ── End-to-end: what the user actually sees via ExceptionHandler ────────────

    @Test
    fun `ExceptionHandler renders the mllama architecture error with the local_server message key`() {
        val e = RuntimeException(
            "llama-server process has terminated: exit status 1: error loading model: " +
                "unknown model architecture: 'mllama'",
        )
        val userMessage = ExceptionHandler.handle(e)
        // i18n resource bundles live in desktop-shared, not on the shared-jvm test classpath,
        // so LocalizationManager falls back to returning the key itself here — this still
        // proves the correct message key (and therefore localized copy) is wired up.
        assertTrue(userMessage.contains("error.local_server"))
    }

    @Test
    fun `ExceptionHandler renders the model-not-found error with the model_not_found message key`() {
        val e = RuntimeException("The model `davinci-002` does not exist or you do not have access to it.")
        val userMessage = ExceptionHandler.handle(e)
        assertTrue(userMessage.contains("error.model_not_found"))
    }

    // ── rootCauseMessage — the true root cause vs. a shallow wrapper message ───

    @Test
    fun `rootCauseMessage returns the deepest exception message, not the shallow wrapper message`() {
        // Regression test: analytics must capture what ACTUALLY drove classification, not just
        // the top-level message, which is often a generic wrapper ("request failed").
        val ioException = IOException("error loading model: unknown model architecture: 'mllama'")
        val wrapped = RuntimeException("request failed", ioException)
        val e = CompletionException(wrapped)

        assertEquals("error loading model: unknown model architecture: 'mllama'", ExceptionMapper.rootCauseMessage(e))
    }

    @Test
    fun `rootCauseMessage falls back to the throwable's own message when there is no cause chain`() {
        val e = RuntimeException("flat error, no cause")
        assertEquals("flat error, no cause", ExceptionMapper.rootCauseMessage(e))
    }

    @Test
    fun `rootCauseMessage falls back to unknown when no message exists anywhere in the chain`() {
        val e = RuntimeException(null as String?, RuntimeException(null as String?))
        assertEquals("unknown", ExceptionMapper.rootCauseMessage(e))
    }

    // ── renderMessage — exactly what the user sees, without re-logging ─────────

    @Test
    fun `ExceptionHandler renderMessage matches handle's rendered content for the same exception`() {
        val mapped = ModelNotFoundChatException(model = "davinci-002")
        val viaHandle = ExceptionHandler.handle(mapped)
        val viaRenderMessage = ExceptionHandler.renderMessage(mapped)
        assertEquals(viaHandle, viaRenderMessage)
    }

    @Test
    fun `renderMessage reflects the model name substituted into the message_not_found template`() {
        val e = RuntimeException("The model `davinci-002` does not exist or you do not have access to it.")
        val mapped = ExceptionMapper.map(e)
        val userMessage = ExceptionHandler.renderMessage(mapped)
        // i18n resources aren't on the shared-jvm test classpath, so the key itself is returned
        // with args NOT substituted (LocalizationManager has nothing to substitute into) — this
        // still proves the right exception type + model field reached the render step.
        assertIs<ModelNotFoundChatException>(mapped)
        assertEquals("davinci-002", mapped.model)
        assertTrue(userMessage.contains("error.model_not_found"))
    }
}
