/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import dev.langchain4j.exception.InternalServerException
import dev.langchain4j.exception.ModelNotFoundException
import io.askimo.core.exception.ContextLengthException
import io.askimo.core.exception.MalformedToolCallException
import io.askimo.core.exception.RateLimitException
import io.askimo.core.exception.RetryPolicy
import io.askimo.core.exception.ToolExecutionException
import io.askimo.core.exception.UnsupportedSamplingException
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.CompletionException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StreamingErrorClassifierTest {

    // ── Retryable ─────────────────────────────────────────────────────────────

    @Test
    fun `context length exceeded is retryable`() {
        val e = RuntimeException("context length exceeded")
        assertIs<StreamingErrorResult.Retryable>(classifyStreamingError(e, ModelProvider.OPENAI_COMPATIBLE, "llama3"))
    }

    @Test
    fun `context length - token limit variant is retryable`() {
        // Must contain both "context" and a matching second keyword to trigger isContextLengthError
        val e = RuntimeException("context token limit has been exceeded")
        assertIs<StreamingErrorResult.Retryable>(classifyStreamingError(e, ModelProvider.OPENAI_COMPATIBLE, "llama3"))
    }

    @Test
    fun `context length - maximum context variant is retryable`() {
        val e = RuntimeException("maximum context size exceeded")
        assertIs<StreamingErrorResult.Retryable>(classifyStreamingError(e, ModelProvider.OPENAI_COMPATIBLE, "gemma4"))
    }

    @Test
    fun `unsupported temperature is retryable`() {
        val e = RuntimeException("temperature does not support values above 1")
        assertIs<StreamingErrorResult.Retryable>(classifyStreamingError(e, ModelProvider.OPENAI_COMPATIBLE, "llama3"))
    }

    @Test
    fun `unsupported top_p is retryable`() {
        val e = RuntimeException("top_p is not supported by this model")
        assertIs<StreamingErrorResult.Retryable>(classifyStreamingError(e, ModelProvider.OPENAI_COMPATIBLE, "mistral"))
    }

    @Test
    fun `topP cannot both be specified is retryable`() {
        val e = RuntimeException("temperature and topP cannot both be specified")
        assertIs<StreamingErrorResult.Retryable>(classifyStreamingError(e, ModelProvider.OPENAI, "gpt-4o"))
    }

    // ── Retryable: malformed tool call ─────────────────────────────────────────

    @Test
    fun `malformed tool call - missing arguments is retryable`() {
        val e = RuntimeException("[error] ToolExecutionRequest.arguments must be provided")
        assertIs<StreamingErrorResult.Retryable>(classifyStreamingError(e, ModelProvider.OPENAI_COMPATIBLE, "llama3"))
    }

    @Test
    fun `malformed tool call - missing name is retryable`() {
        val e = RuntimeException("ToolExecutionRequest.name must not be blank")
        assertIs<StreamingErrorResult.Retryable>(classifyStreamingError(e, ModelProvider.OPENAI_COMPATIBLE, "qwq"))
    }

    // ── Retryable: the classified exception is exposed to the caller ───────────

    @Test
    fun `Retryable result exposes the classified exception for side-effect dispatch`() {
        val e = RuntimeException("context length exceeded")
        val result = classifyStreamingError(e, ModelProvider.OPENAI_COMPATIBLE, "llama3") as StreamingErrorResult.Retryable
        assertIs<ContextLengthException>(result.exception)
        assertEquals(RetryPolicy.IMMEDIATE, result.exception.retryPolicy)
    }

    @Test
    fun `malformed tool call classifies as MalformedToolCallException with IMMEDIATE policy`() {
        val e = RuntimeException("ToolExecutionRequest.arguments must be provided")
        val result = classifyStreamingError(e, ModelProvider.OPENAI_COMPATIBLE, "llama3") as StreamingErrorResult.Retryable
        assertIs<MalformedToolCallException>(result.exception)
        assertEquals(RetryPolicy.IMMEDIATE, result.exception.retryPolicy)
    }

    @Test
    fun `unsupported sampling classifies as UnsupportedSamplingException with IMMEDIATE policy`() {
        val e = RuntimeException("temperature does not support values above 1")
        val result = classifyStreamingError(e, ModelProvider.OPENAI_COMPATIBLE, "llama3") as StreamingErrorResult.Retryable
        assertIs<UnsupportedSamplingException>(result.exception)
        assertEquals(RetryPolicy.IMMEDIATE, result.exception.retryPolicy)
    }

    // ── BACKOFF-classified errors are still Terminal here (no backoff loop wired up
    // at this call site yet) — see `classifyStreamingError`'s doc comment. ──────────

    @Test
    fun `rate limit message is BACKOFF policy but Terminal at this call site`() {
        val e = RuntimeException("rate limit exceeded, please slow down")
        val result = classifyStreamingError(e, ModelProvider.OPENAI, "gpt-4o")
        assertIs<StreamingErrorResult.Terminal>(result)
    }

    @Test
    fun `RateLimitException retryPolicy is BACKOFF`() {
        assertEquals(RetryPolicy.BACKOFF, RateLimitException().retryPolicy)
    }

    // ── Terminal: local server crash ───────────────────────────────────────────

    @Test
    fun `llama-server crash is terminal`() {
        val e = RuntimeException("llama-server process has terminated unexpectedly")
        assertIs<StreamingErrorResult.Terminal>(classifyStreamingError(e, ModelProvider.OPENAI_COMPATIBLE, "llama3"))
    }

    @Test
    fun `process has terminated is terminal`() {
        val e = RuntimeException("process has terminated with exit code 1")
        assertIs<StreamingErrorResult.Terminal>(classifyStreamingError(e, ModelProvider.OPENAI_COMPATIBLE, "llama3"))
    }

    @Test
    fun `llama_model_loader failure is terminal`() {
        val e = RuntimeException("llama_model_loader: failed to load model")
        assertIs<StreamingErrorResult.Terminal>(classifyStreamingError(e, ModelProvider.OPENAI_COMPATIBLE, "llama3"))
    }

    @Test
    fun `error loading model is terminal`() {
        val e = RuntimeException("error loading model: no such file")
        assertIs<StreamingErrorResult.Terminal>(classifyStreamingError(e, ModelProvider.OPENAI_COMPATIBLE, "llama3"))
    }

    @Test
    fun `api_error with exit status is terminal`() {
        val e = RuntimeException("api_error: exit status 1")
        assertIs<StreamingErrorResult.Terminal>(classifyStreamingError(e, ModelProvider.OPENAI_COMPATIBLE, "llama3"))
    }

    // ── Terminal: empty HTTP response ─────────────────────────────────────────

    @Test
    fun `empty HTTP response on direct message is terminal`() {
        val e = IOException("HTTP/1.1 header parser received no bytes")
        val result = classifyStreamingError(e, ModelProvider.OPENAI_COMPATIBLE, "gemma4")
        assertIs<StreamingErrorResult.Terminal>(result)
    }

    @Test
    fun `empty HTTP response on cause message is terminal`() {
        val cause = IOException("HTTP/1.1 header parser received no bytes")
        val e = RuntimeException("streaming failed", cause)
        assertIs<StreamingErrorResult.Terminal>(classifyStreamingError(e, ModelProvider.OPENAI_COMPATIBLE, "llama3"))
    }

    @Test
    fun `empty HTTP response wrapped 3 levels deep is still terminal and classified correctly`() {
        // Regression test: HTTP clients commonly wrap the originating IOException several
        // levels deep (e.g. CompletionException -> RuntimeException -> IOException). A shallow
        // 1-level cause check would miss this and misclassify it as a generic system error.
        val ioException = IOException("HTTP/1.1 header parser received no bytes")
        val wrapped = RuntimeException("request failed", ioException)
        val e = CompletionException(wrapped)
        val result = classifyStreamingError(e, ModelProvider.OPENAI_COMPATIBLE, "gemma4") as StreamingErrorResult.Terminal
        assertContains(result.message, "error.empty_http_response")
    }

    @Test
    fun `empty HTTP response for local provider is terminal`() {
        val e = IOException("HTTP/1.1 header parser received no bytes")
        val result = classifyStreamingError(e, ModelProvider.OPENAI_COMPATIBLE, "gemma4") as StreamingErrorResult.Terminal
        // When i18n resources aren't loaded, LocalizationManager returns the key itself
        assertContains(result.message, "error.empty_http_response")
    }

    @Test
    fun `empty HTTP response for Ollama is terminal`() {
        val e = IOException("HTTP/1.1 header parser received no bytes")
        val result = classifyStreamingError(e, ModelProvider.OPENAI_COMPATIBLE, "llama3") as StreamingErrorResult.Terminal
        assertContains(result.message, "error.empty_http_response")
    }

    @Test
    fun `empty HTTP response for openai compatible is terminal`() {
        val e = IOException("HTTP/1.1 header parser received no bytes")
        val result = classifyStreamingError(e, ModelProvider.OPENAI_COMPATIBLE, "mistral") as StreamingErrorResult.Terminal
        assertContains(result.message, "error.empty_http_response")
    }

    // ── Terminal: network ──────────────────────────────────────────────────────

    @Test
    fun `unresolved address is terminal and classified as network error`() {
        val cause = UnresolvedAddressException()
        val e = IOException("Connection failed", cause)
        val result = classifyStreamingError(e, ModelProvider.OPENAI_COMPATIBLE, "llama3") as StreamingErrorResult.Terminal
        // When i18n resources aren't loaded, LocalizationManager returns the key itself.
        assertContains(result.message, "error.network")
    }

    @Test
    fun `bare ConnectException is terminal and classified as network error`() {
        // Regression test: this previously leaked the raw "[error] java.net.ConnectException"
        // text to the UI because the fallback `else` branch skipped ExceptionHandler/ExceptionMapper
        // entirely. ConnectException is often wrapped in a CompletionException by the underlying
        // HTTP client, so the mapper must find it by walking the full cause chain.
        val cause = ConnectException("Connection refused")
        val e = CompletionException(cause)
        val result = classifyStreamingError(e, ModelProvider.OPENAI, "gpt-4o") as StreamingErrorResult.Terminal
        assertContains(result.message, "error.network")
    }

    // ── Terminal: InternalServerException (HTTP 5xx) ───────────────────────────

    @Test
    fun `InternalServerException is terminal`() {
        val e = InternalServerException("500 Internal Server Error")
        assertIs<StreamingErrorResult.Terminal>(classifyStreamingError(e, ModelProvider.OPENAI, "gpt-4o"))
    }

    // ── Terminal: model not found ──────────────────────────────────────────────

    @Test
    fun `ModelNotFoundException is terminal`() {
        val e = ModelNotFoundException("model not found")
        assertIs<StreamingErrorResult.Terminal>(classifyStreamingError(e, ModelProvider.OPENAI_COMPATIBLE, "bad-model"))
    }

    @Test
    fun `model is required message is terminal`() {
        val e = RuntimeException("model is required")
        assertIs<StreamingErrorResult.Terminal>(classifyStreamingError(e, ModelProvider.OPENAI, ""))
    }

    @Test
    fun `No model provided message is terminal`() {
        val e = RuntimeException("No model provided in request")
        assertIs<StreamingErrorResult.Terminal>(classifyStreamingError(e, ModelProvider.OPENAI, ""))
    }

    @Test
    fun `invalid model message is terminal`() {
        val e = RuntimeException("invalid model: gpt-5-turbo")
        assertIs<StreamingErrorResult.Terminal>(classifyStreamingError(e, ModelProvider.OPENAI, "gpt-5-turbo"))
    }

    // ── Terminal: authentication ───────────────────────────────────────────────

    @Test
    fun `Incorrect API key is terminal`() {
        val e = RuntimeException("Incorrect API key provided")
        assertIs<StreamingErrorResult.Terminal>(classifyStreamingError(e, ModelProvider.OPENAI, "gpt-4o"))
    }

    @Test
    fun `invalid_api_key is terminal`() {
        val e = RuntimeException("invalid_api_key: check your credentials")
        assertIs<StreamingErrorResult.Terminal>(classifyStreamingError(e, ModelProvider.OPENAI, "gpt-4o"))
    }

    @Test
    fun `unauthorized is terminal`() {
        val e = RuntimeException("unauthorized: 401")
        assertIs<StreamingErrorResult.Terminal>(classifyStreamingError(e, ModelProvider.OPENAI, "gpt-4o"))
    }

    @Test
    fun `LangChain4j AuthenticationException is terminal`() {
        val e = dev.langchain4j.exception.AuthenticationException("auth failed")
        assertIs<StreamingErrorResult.Terminal>(classifyStreamingError(e, ModelProvider.OPENAI, "gpt-4o"))
    }

    // ── Terminal: tool execution ───────────────────────────────────────────────

    @Test
    fun `ToolExecutionException is terminal and classified as tool execution error`() {
        val e = ToolExecutionException(toolName = "search", errorDetails = "search service timed out")
        val result = classifyStreamingError(e, ModelProvider.OPENAI, "gpt-4o") as StreamingErrorResult.Terminal
        // When i18n resources aren't loaded, LocalizationManager returns the key itself —
        // the {details} substitution ("search service timed out") only happens with real
        // resource bundles loaded (see desktop-shared/src/main/resources/i18n).
        assertContains(result.message, "error.tool_execution")
    }

    // ── Terminal: InsufficientContextException ─────────────────────────────────

    @Test
    fun `InsufficientContextException is terminal`() {
        val e = InsufficientContextException(
            currentModel = "ollama:llama3",
            contextSize = 4096,
            usedByMessages = 4000,
            availableForResponse = 96,
        )
        val result = classifyStreamingError(e, ModelProvider.OPENAI_COMPATIBLE, "llama3") as StreamingErrorResult.Terminal
        assertContains(result.message, "Insufficient context window space")
    }

    // ── Terminal: unknown ──────────────────────────────────────────────────────

    @Test
    fun `unknown error is terminal and classified as system error`() {
        val e = RuntimeException("some completely unknown error XYZ-9999")
        val result = classifyStreamingError(e, ModelProvider.OPENAI, "gpt-4o") as StreamingErrorResult.Terminal
        // When i18n resources aren't loaded, LocalizationManager returns the key itself —
        // the {errorCode} substitution (which would include the raw message) only happens
        // with real resource bundles loaded (see desktop-shared/src/main/resources/i18n).
        assertContains(result.message, "error.system")
    }
}
