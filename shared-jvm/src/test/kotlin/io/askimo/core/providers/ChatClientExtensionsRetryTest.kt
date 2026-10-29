/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import dev.langchain4j.data.message.Content
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.PartialThinking
import dev.langchain4j.service.TokenStream
import dev.langchain4j.service.tool.BeforeToolExecution
import dev.langchain4j.service.tool.ToolExecution
import io.askimo.core.context.AppContext
import io.askimo.core.context.ExecutionMode
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for the *immediate-retry* paths in [sendStreamingMessageWithCallback]'s
 * outer `while (contextRetryCount <= maxContextRetries)` loop:
 *
 * - A malformed tool call ([io.askimo.core.exception.MalformedToolCallException], e.g. the
 *   model streamed a `tool_calls` entry whose `arguments`/`name` never got populated) must be
 *   retried silently — the caller sees the successful second attempt's output, not an error.
 * - A context-length-exceeded error ([io.askimo.core.exception.ContextLengthException]) must
 *   trigger [ModelCapabilitiesCache.reduceContextSize] as a side effect *before* the retry, so
 *   the next attempt (in real usage, via `ChatRequestTransformers.enforceTokenBudget()`) has a
 *   chance to fit within the reduced budget.
 *
 * Both scenarios are driven by a hand-rolled [TokenStream] fake (not a real model/network call)
 * so `sendStreamingMessageWithCallback`'s control flow runs for real, synchronously, counting
 * exactly how many times [ChatClient.sendMessageStreaming] is invoked.
 */
class ChatClientExtensionsRetryTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var testBaseScope: AskimoHome.TestBaseScope

    @BeforeEach
    fun setUp() {
        testBaseScope = AskimoHome.withTestBase(tempDir)
        AppContext.reset()
        AppContext.initialize(ExecutionMode.STATELESS_MODE)
    }

    @AfterEach
    fun tearDown() {
        AppContext.reset()
        testBaseScope.close()
    }

    /** Minimal, synchronous [TokenStream] fake: records handlers, drives them on [start]. */
    private class FakeTokenStream(private val drive: FakeTokenStream.() -> Unit) : TokenStream {
        var partialResponseHandler: Consumer<String>? = null
        var completeResponseHandler: Consumer<ChatResponse>? = null
        var errorHandler: Consumer<Throwable>? = null

        override fun onPartialResponse(partialResponseHandler: Consumer<String>): TokenStream {
            this.partialResponseHandler = partialResponseHandler
            return this
        }
        override fun onPartialThinking(partialThinkingHandler: Consumer<PartialThinking>) = this
        override fun onRetrieved(contentHandler: Consumer<List<dev.langchain4j.rag.content.Content>>) = this
        override fun beforeToolExecution(beforeToolExecutionHandler: Consumer<BeforeToolExecution>): TokenStream = this
        override fun onToolExecuted(toolExecuteHandler: Consumer<ToolExecution>): TokenStream = this
        override fun onCompleteResponse(completeResponseHandler: Consumer<ChatResponse>): TokenStream {
            this.completeResponseHandler = completeResponseHandler
            return this
        }
        override fun onError(errorHandler: Consumer<Throwable>): TokenStream {
            this.errorHandler = errorHandler
            return this
        }
        override fun ignoreErrors() = this
        override fun start() = drive()
    }

    /**
     * A [ChatClient] whose [sendMessageStreaming] fails with [failureMessage] on its first
     * [failCount] invocation(s), then succeeds with [successText] on the attempt right after.
     * [invocationCount] lets the test assert exactly how many attempts were made.
     */
    private class FlakyThenSucceedsChatClient(
        private val failureMessage: String,
        private val failCount: Int = 1,
        private val successText: String = "done",
    ) : ChatClient {
        val invocationCount = AtomicInteger(0)

        override fun sendMessageStreaming(userContents: List<Content>): TokenStream {
            val attempt = invocationCount.incrementAndGet()
            return FakeTokenStream {
                if (attempt <= failCount) {
                    errorHandler?.accept(RuntimeException(failureMessage))
                } else {
                    // The real streaming API builds its result from `onPartialResponse` chunks,
                    // not from the final AiMessage's text — the AiMessage callback only fires
                    // image/token-usage side effects. Emit the chunk first so `sb` matches.
                    partialResponseHandler?.accept(successText)
                    completeResponseHandler?.accept(
                        ChatResponse.builder().aiMessage(dev.langchain4j.data.message.AiMessage.from(successText)).build(),
                    )
                }
            }
        }

        override fun sendMessage(prompt: String): String = error("not used")
    }

    @Test
    fun `malformed tool call is retried silently and the retried attempt's output is returned`() {
        // "ToolExecutionRequest.arguments must be provided" matches ExceptionMapper's
        // MalformedToolCallException pattern (see StreamingErrorClassifierTest) — classified
        // as RetryPolicy.IMMEDIATE, so the outer loop should retry without surfacing any error.
        val chatClient = FlakyThenSucceedsChatClient(
            failureMessage = "[error] ToolExecutionRequest.arguments must be provided",
            successText = "recovered after malformed tool call",
        )

        val output = chatClient.sendStreamingMessageWithCallback(userContents = listOf())

        assertEquals("recovered after malformed tool call", output)
        assertEquals(2, chatClient.invocationCount.get(), "Expected exactly one retry (two total attempts)")
    }

    @Test
    fun `context length exceeded reduces the cached context size and retries silently`() {
        val provider = AppContext.getInstance().getActiveProvider()
        val model = AppContext.getInstance().params.model
        val modelKey = ModelCapabilitiesCache.modelKey(provider, model)
        val sizeBeforeRetry = ModelCapabilitiesCache.get(modelKey).contextSize

        val chatClient = FlakyThenSucceedsChatClient(
            failureMessage = "context length exceeded",
            successText = "recovered after context length error",
        )

        val output = chatClient.sendStreamingMessageWithCallback(userContents = listOf())

        assertEquals("recovered after context length error", output)
        assertEquals(2, chatClient.invocationCount.get(), "Expected exactly one retry (two total attempts)")

        val sizeAfterRetry = ModelCapabilitiesCache.get(modelKey).contextSize
        assertTrue(
            sizeAfterRetry < sizeBeforeRetry,
            "Expected reduceContextSize to shrink the cached context size for $modelKey: " +
                "before=$sizeBeforeRetry, after=$sizeAfterRetry",
        )
        assertTrue(
            ModelCapabilitiesCache.isContextSizeLearned(modelKey),
            "Expected the context size to be marked as learned after a reduction cycle",
        )
    }

    @Test
    fun `repeated malformed tool calls beyond the retry budget eventually surface as terminal`() {
        // maxContextRetries is 20 (21 total attempts allowed) — fail every single attempt so the
        // loop exhausts its budget and the *last* attempt's error is rendered as terminal rather
        // than retried forever.
        val chatClient = FlakyThenSucceedsChatClient(
            failureMessage = "ToolExecutionRequest.name must not be blank",
            failCount = Int.MAX_VALUE,
        )

        val result = runCatching {
            chatClient.sendStreamingMessageWithCallback(userContents = listOf())
        }

        assertTrue(result.isFailure, "Expected the call to eventually fail once the retry budget is exhausted")
        assertEquals(21, chatClient.invocationCount.get(), "Expected 1 initial attempt + 20 retries = 21 total attempts")
    }
}
