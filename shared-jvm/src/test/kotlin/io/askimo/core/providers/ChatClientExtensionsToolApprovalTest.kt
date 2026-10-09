/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.Content
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.invocation.InvocationContext
import dev.langchain4j.memory.ChatMemory
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.PartialThinking
import dev.langchain4j.service.TokenStream
import dev.langchain4j.service.tool.BeforeToolExecution
import dev.langchain4j.service.tool.ToolExecution
import io.askimo.core.context.AppContext
import io.askimo.core.context.ExecutionMode
import io.askimo.core.exception.ToolExecutionException
import io.askimo.core.intent.ToolApprovalPolicy
import io.askimo.core.intent.ToolCategory
import io.askimo.core.intent.ToolConfig
import io.askimo.core.intent.ToolSource
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Regression tests for the tool-approval guardrail in [sendStreamingMessageWithCallback]:
 * on denial/timeout, every unresolved `tool_use` request from the current AI turn must get a
 * synthetic [ToolExecutionResultMessage] appended to [ChatMemory] — not just the single request
 * whose approval callback happened to fire — otherwise Anthropic rejects the next turn with
 * "tool_use ids were found without tool_result blocks" for the sibling request(s).
 *
 * Uses a hand-rolled [TokenStream] fake (not a model/network call) so the full approval
 * control-flow inside `sendStreamingMessageWithCallback` runs for real, driven synchronously
 * by the test.
 */
class ChatClientExtensionsToolApprovalTest {

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

    /** Minimal recording [ChatMemory] fake — only [add] is exercised by the code under test. */
    private class FakeChatMemory : ChatMemory {
        val added = mutableListOf<ChatMessage>()
        override fun id(): Any = "test-session"
        override fun add(message: ChatMessage) {
            added += message
        }
        override fun messages(): List<ChatMessage> = added
        override fun clear() = added.clear()
    }

    /** Minimal, synchronous [TokenStream] fake: records handlers, drives them on [start]. */
    private class FakeTokenStream(private val drive: FakeTokenStream.() -> Unit) : TokenStream {
        var completeResponseHandler: Consumer<ChatResponse>? = null
        var beforeToolExecutionHandler: Consumer<BeforeToolExecution>? = null
        var toolExecutedHandler: Consumer<ToolExecution>? = null
        var errorHandler: Consumer<Throwable>? = null

        override fun onPartialResponse(partialResponseHandler: Consumer<String>) = this
        override fun onPartialThinking(partialThinkingHandler: Consumer<PartialThinking>) = this
        override fun onRetrieved(contentHandler: Consumer<List<dev.langchain4j.rag.content.Content>>) = this
        override fun beforeToolExecution(beforeToolExecutionHandler: Consumer<BeforeToolExecution>): TokenStream {
            this.beforeToolExecutionHandler = beforeToolExecutionHandler
            return this
        }
        override fun onToolExecuted(toolExecuteHandler: Consumer<ToolExecution>): TokenStream {
            this.toolExecutedHandler = toolExecuteHandler
            return this
        }
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

    private fun toolRequest(id: String, name: String) = ToolExecutionRequest.builder()
        .id(id)
        .name(name)
        .arguments("{}")
        .build()

    /** A [ToolConfig] requiring approval, matching [name] — needed so the guardrail actually triggers. */
    private fun requireApprovalConfig(name: String) = ToolConfig(
        specification = ToolSpecification.builder().name(name).build(),
        category = ToolCategory.DATABASE,
        strategy = 0,
        source = ToolSource.ASKIMO_BUILTIN,
        approvalPolicy = ToolApprovalPolicy.REQUIRE_APPROVAL,
    )

    private fun beforeToolExecution(request: ToolExecutionRequest) = BeforeToolExecution.builder()
        .request(request)
        .invocationContext(InvocationContext.builder().build())
        .build()

    @Test
    fun `denying one of two parallel tool calls appends synthetic results for both`() {
        val chatMemory = FakeChatMemory()
        val requestA = toolRequest("call_A", "toolA")
        val requestB = toolRequest("call_B", "toolB")

        val chatClient = object : ChatClient {
            override fun sendMessageStreaming(userContents: List<Content>): TokenStream = FakeTokenStream {
                // Simulates LangChain4j's real behavior: the AiMessage requesting both parallel
                // tool calls is persisted to chatMemory *before* beforeToolExecution fires for
                // any of its requests — `onCompleteResponse` is the terminal callback and would
                // only fire *after* all tool rounds complete, so it must NOT be invoked here;
                // denial aborts the turn before a final response is ever produced.
                chatMemory.add(AiMessage.from(listOf(requestA, requestB)))
                // Framework invokes beforeToolExecution for the first request; denial aborts
                // the loop before toolB's beforeToolExecution ever fires.
                beforeToolExecutionHandler?.accept(beforeToolExecution(requestA))
            }

            override fun sendMessage(prompt: String): String = error("not used")
        }

        assertFailsWith<ToolExecutionException> {
            chatClient.sendStreamingMessageWithCallback(
                userContents = listOf(),
                resolvedTools = listOf(requireApprovalConfig("toolA"), requireApprovalConfig("toolB")),
                onToolApprovalRequired = { _, _, _, deny -> deny() },
                chatMemory = chatMemory,
            )
        }

        // Both sibling tool_use ids must have a matching tool_result — not just requestA's.
        val resultIds = chatMemory.added.filterIsInstance<ToolExecutionResultMessage>().map { it.id() }
        assertTrue(requestA.id() in resultIds, "Expected a tool_result for requestA (${requestA.id()}), got: $resultIds")
        assertTrue(requestB.id() in resultIds, "Expected a tool_result for requestB (${requestB.id()}), got: $resultIds")
    }

    @Test
    fun `denying the only tool call appends exactly one synthetic result`() {
        val chatMemory = FakeChatMemory()
        val request = toolRequest("call_solo", "toolSolo")

        val chatClient = object : ChatClient {
            override fun sendMessageStreaming(userContents: List<Content>): TokenStream = FakeTokenStream {
                // Simulates LangChain4j persisting the tool-calling AiMessage to chatMemory
                // before beforeToolExecution fires — see comment in the test above.
                chatMemory.add(AiMessage.from(listOf(request)))
                beforeToolExecutionHandler?.accept(beforeToolExecution(request))
            }

            override fun sendMessage(prompt: String): String = error("not used")
        }

        assertFailsWith<ToolExecutionException> {
            chatClient.sendStreamingMessageWithCallback(
                userContents = listOf(),
                resolvedTools = listOf(requireApprovalConfig("toolSolo")),
                onToolApprovalRequired = { _, _, _, deny -> deny() },
                chatMemory = chatMemory,
            )
        }

        val resultIds = chatMemory.added.filterIsInstance<ToolExecutionResultMessage>().map { it.id() }
        assertTrue(resultIds == listOf(request.id()), "Expected exactly one tool_result for ${request.id()}, got: $resultIds")
    }

    @Test
    fun `approval timeout appends synthetic results for all pending requests`() {
        val chatMemory = FakeChatMemory()
        val requestA = toolRequest("call_A", "toolA")
        val requestB = toolRequest("call_B", "toolB")

        val chatClient = object : ChatClient {
            override fun sendMessageStreaming(userContents: List<Content>): TokenStream = FakeTokenStream {
                // Simulates LangChain4j persisting the tool-calling AiMessage before
                // beforeToolExecution fires — see comment in the denial test above.
                chatMemory.add(AiMessage.from(listOf(requestA, requestB)))
                beforeToolExecutionHandler?.accept(beforeToolExecution(requestA))
            }

            override fun sendMessage(prompt: String): String = error("not used")
        }

        assertFailsWith<ToolExecutionException> {
            chatClient.sendStreamingMessageWithCallback(
                userContents = listOf(),
                resolvedTools = listOf(requireApprovalConfig("toolA"), requireApprovalConfig("toolB")),
                // Never invokes approve/deny — forces the timeout branch, independent from
                // the denial branch exercised by the tests above.
                onToolApprovalRequired = { _, _, _, _ -> },
                // Tiny timeout so this test doesn't actually wait out the real 120s default.
                toolApprovalTimeoutMs = 50L,
                chatMemory = chatMemory,
            )
        }

        // Both sibling tool_use ids must have a matching tool_result — not just requestA's.
        val resultIds = chatMemory.added.filterIsInstance<ToolExecutionResultMessage>().map { it.id() }
        assertTrue(requestA.id() in resultIds, "Expected a tool_result for requestA (${requestA.id()}), got: $resultIds")
        assertTrue(requestB.id() in resultIds, "Expected a tool_result for requestB (${requestB.id()}), got: $resultIds")
    }

    /**
     * Regression test for the async/sync race documented on `notifyToolFinishedAndAwaitIfPossible`:
     * the timeout branch must synchronously await the caller's `onToolFinishedAwaitable` completion
     * future *before* invoking `onToolApprovalTimedOut`/throwing — otherwise a caller like
     * `SessionManager` that persists UI state inside `onToolApprovalTimedOut` would observe the
     * tool still marked RUNNING. Asserting the exact invocation order (not just "both eventually
     * ran") is what actually catches a regression back to the fire-and-forget launch.
     */
    @Test
    fun `approval timeout invokes onToolFinishedAwaitable before onToolApprovalTimedOut, both exactly once`() {
        val chatMemory = FakeChatMemory()
        val request = toolRequest("call_solo", "toolTimeout")
        val invocationOrder = mutableListOf<String>()
        var toolFinishedCalls = 0
        var timedOutCalls = 0
        var toolFinishedHasFailed: Boolean? = null

        val chatClient = object : ChatClient {
            override fun sendMessageStreaming(userContents: List<Content>): TokenStream = FakeTokenStream {
                chatMemory.add(AiMessage.from(listOf(request)))
                beforeToolExecutionHandler?.accept(beforeToolExecution(request))
            }

            override fun sendMessage(prompt: String): String = error("not used")
        }

        assertFailsWith<ToolExecutionException> {
            chatClient.sendStreamingMessageWithCallback(
                userContents = listOf(),
                resolvedTools = listOf(requireApprovalConfig("toolTimeout")),
                // Never invokes approve/deny — forces the timeout branch.
                onToolApprovalRequired = { _, _, _, _ -> },
                onToolFinishedAwaitable = { _, _, _, hasFailed ->
                    toolFinishedCalls++
                    toolFinishedHasFailed = hasFailed
                    invocationOrder += "onToolFinishedAwaitable"
                    CompletableFuture.completedFuture(Unit)
                },
                onToolApprovalTimedOut = {
                    timedOutCalls++
                    invocationOrder += "onToolApprovalTimedOut"
                },
                toolApprovalTimeoutMs = 50L,
                chatMemory = chatMemory,
            )
        }

        assertEquals(1, toolFinishedCalls, "Expected onToolFinishedAwaitable to be invoked exactly once, got $toolFinishedCalls")
        assertEquals(1, timedOutCalls, "Expected onToolApprovalTimedOut to be invoked exactly once, got $timedOutCalls")
        assertEquals(true, toolFinishedHasFailed, "Expected onToolFinishedAwaitable's hasFailed to be true on timeout")
        assertEquals(
            listOf("onToolFinishedAwaitable", "onToolApprovalTimedOut"),
            invocationOrder,
            "Expected onToolFinishedAwaitable's completion future to be awaited before onToolApprovalTimedOut fires",
        )
    }

    /**
     * Companion to the timeout test above: denial must still notify `onToolFinishedAwaitable` (so UI
     * state like a RUNNING tool entry gets flipped to DONE/failed) but must NOT invoke
     * `onToolApprovalTimedOut` — that callback exists solely to clear pending-approval UI state
     * left behind by a timeout, which never applies on an explicit deny.
     */
    @Test
    fun `approval denial invokes onToolFinishedAwaitable but never onToolApprovalTimedOut`() {
        val chatMemory = FakeChatMemory()
        val request = toolRequest("call_deny", "toolDeny")
        var toolFinishedCalls = 0
        var timedOutCalls = 0
        var toolFinishedHasFailed: Boolean? = null

        val chatClient = object : ChatClient {
            override fun sendMessageStreaming(userContents: List<Content>): TokenStream = FakeTokenStream {
                chatMemory.add(AiMessage.from(listOf(request)))
                beforeToolExecutionHandler?.accept(beforeToolExecution(request))
            }

            override fun sendMessage(prompt: String): String = error("not used")
        }

        assertFailsWith<ToolExecutionException> {
            chatClient.sendStreamingMessageWithCallback(
                userContents = listOf(),
                resolvedTools = listOf(requireApprovalConfig("toolDeny")),
                onToolApprovalRequired = { _, _, _, deny -> deny() },
                onToolFinishedAwaitable = { _, _, _, hasFailed ->
                    toolFinishedCalls++
                    toolFinishedHasFailed = hasFailed
                    CompletableFuture.completedFuture(Unit)
                },
                onToolApprovalTimedOut = {
                    timedOutCalls++
                },
                chatMemory = chatMemory,
            )
        }

        assertEquals(1, toolFinishedCalls, "Expected onToolFinishedAwaitable to be invoked exactly once on denial, got $toolFinishedCalls")
        assertEquals(true, toolFinishedHasFailed, "Expected onToolFinishedAwaitable's hasFailed to be true on denial")
        assertEquals(0, timedOutCalls, "onToolApprovalTimedOut must never fire on explicit denial, got $timedOutCalls calls")
    }
}
