/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.memory

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import io.askimo.core.chat.repository.SessionMemoryRepository
import io.askimo.core.config.AppConfig
import io.askimo.core.context.AppContext
import io.askimo.core.context.AppContextParams
import io.askimo.core.context.ExecutionMode
import io.askimo.core.providers.ChatClient
import io.askimo.core.providers.HttpVersion
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.openaicompatible.OpenAiApiMode
import io.askimo.core.providers.openaicompatible.OpenAiCompatibleModelFactory
import io.askimo.core.providers.openaicompatible.OpenAiCompatibleSettings
import io.askimo.core.providers.openaicompatible.OpenAiCompatibleTemplate
import io.askimo.test.extensions.AskimoTestHome
import io.askimo.test.extensions.RetryOnFailure
import io.askimo.testcontainers.SharedOllama
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.testcontainers.junit.jupiter.Testcontainers

@DisabledIfEnvironmentVariable(
    named = "DISABLE_DOCKER_TESTS",
    matches = "(?i)true|1|yes",
)
@Testcontainers
@TestInstance(Lifecycle.PER_CLASS)
@AskimoTestHome
class TokenAwareSummarizingMemoryOllamaIT {

    private val model = "qwen2.5:0.5b"
    private val sessionId = "ollama-live-session"

    private lateinit var mockAppContext: AppContext
    private lateinit var mockRepository: SessionMemoryRepository
    private lateinit var mockParams: AppContextParams
    private lateinit var memory: TokenAwareSummarizingMemory

    @BeforeEach
    fun setUp() {
        // The factory internally reaches for the global AppContext singleton (e.g. for its
        // telemetry listener) even though TokenAwareSummarizingMemory itself only uses the
        // mocked instance below — both must be set up for model creation to succeed.
        AppContext.reset()
        AppContext.initialize(mode = ExecutionMode.STATELESS_MODE)

        val baseUrl = ollamaBaseUrl()
        val settings = ollamaSettings(baseUrl)
        val factory = OpenAiCompatibleModelFactory()

        mockAppContext = mock()
        mockRepository = mock()
        mockParams = mock()

        val liveUtilityClient: ChatClient = factory.createUtilityClient(settings)

        whenever(mockAppContext.createUtilityClient()).thenReturn(liveUtilityClient)
        whenever(mockAppContext.createSecondaryModel()).thenReturn(factory.createSecondaryModel(settings))
        whenever(mockAppContext.getActiveProvider()).thenReturn(ModelProvider.OPENAI_COMPATIBLE)
        whenever(mockAppContext.params).thenReturn(mockParams)
        whenever(mockParams.model).thenReturn(model)
        whenever(mockAppContext.buildUserMemoryPrefix()).thenReturn("")
        whenever(mockRepository.getBySessionId(any())).thenReturn(null)
    }

    @AfterEach
    fun tearDown() {
        if (::memory.isInitialized) {
            memory.close()
        }
    }

    private fun ollamaBaseUrl(): String {
        val ollama = SharedOllama.container
        assertTrue(ollama.isRunning, "Ollama container should be running")
        SharedOllama.ensureModelPulled(model)
        val baseUrl = "http://${ollama.host}:${ollama.getMappedPort(11434)}/v1"
        println("Ollama container running at: $baseUrl")
        return baseUrl
    }

    private fun ollamaSettings(baseUrl: String) = OpenAiCompatibleSettings(
        baseUrl = baseUrl,
        defaultModel = model,
        utilityModel = model,
        apiMode = OpenAiApiMode.CHAT_COMPLETIONS,
        httpVersion = HttpVersion.HTTP_1_1,
        isTemplate = true,
        templateName = OpenAiCompatibleTemplate.OLLAMA.name,
    )

    private fun createMemory(summarizationThreshold: Double = 0.4): TokenAwareSummarizingMemory {
        // Drive threshold through AppConfig — @AskimoTestHome resets config between tests.
        AppConfig.updateField("memory.summarizationThreshold", summarizationThreshold)
        return TokenAwareSummarizingMemory(
            appContext = mockAppContext,
            sessionId = sessionId,
            sessionMemoryRepository = mockRepository,
            asyncSummarization = true,
            summarizationTimeoutSeconds = 60,
        )
    }

    /** Polls [TokenAwareSummarizingMemory.isSummarizing] until it settles, with a hard timeout. */
    private fun awaitSummarization(memory: TokenAwareSummarizingMemory, timeoutMs: Long = 60_000) {
        // Give the async cycle a moment to start before polling for completion.
        Thread.sleep(500)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (memory.isSummarizing.value && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
        }
        assertFalse(memory.isSummarizing.value, "Summarization did not complete within ${timeoutMs}ms")
    }

    @Test
    @RetryOnFailure(maxAttempts = 2)
    @DisplayName("summarizes a long multi-turn conversation with a live Ollama model")
    fun summarizesLongConversationWithLiveModel() {
        memory = createMemory(summarizationThreshold = 0.05)

        // Padding turns pushed earlier in history so they become the oldest (non-protected)
        // candidates — real conversations routinely contain filler like this (pasted logs,
        // long error output) before the threshold trips. Large enough, repeated enough times,
        // to reliably cross the token threshold regardless of the small model's behavior.
        repeat(15) { i ->
            memory.add(UserMessage.from("Here's some context, turn $i: " + "lorem ipsum dolor sit amet ".repeat(40)))
            memory.add(AiMessage.from("Noted, turn $i: " + "consectetur adipiscing elit ".repeat(40)))
        }

        // Meaningful tail — these should stay within the protected recent turns and survive
        // verbatim even after the padding above gets summarized away.
        val turns = listOf(
            "I'm building a Kotlin CLI that talks to Ollama and OpenAI." to
                "Nice — are you using LangChain4j for the provider abstraction?",
            "Yes. I'm migrating a Postgres schema to support multi-tenant row-level security." to
                "Are you sharding or using a tenant_id discriminator?",
            "tenant_id discriminator. I want to finish the migration script this week." to
                "Sounds reasonable. Do you have an index on tenant_id already?",
            "Not yet, that's next. I'm also adding integration tests with Testcontainers." to
                "Good call — that'll catch RLS policy bugs early.",
            "Exactly. I'm a backend engineer based in Ho Chi Minh City, mostly Kotlin and Spring Boot." to
                "Got it, I'll keep that context in mind going forward.",
        )

        turns.forEach { (user, ai) ->
            memory.add(UserMessage.from(user))
            memory.add(AiMessage.from(ai))
        }

        awaitSummarization(memory)

        val summary = memory.exportState().summary
        println("structured summary = $summary")
        assertNotNull(summary, "Expected a structured summary after crossing the threshold")

        val populated = summary!!.keyFacts.isNotEmpty() || summary.mainTopics.isNotEmpty() || summary.recentContext.isNotBlank()
        assertTrue(populated, "Expected at least one populated summary field, got: $summary")

        val messages = memory.messages()
        assertTrue(
            messages.any { it is SystemMessage },
            "Expected a structured-summary SystemMessage to be injected into messages()",
        )
        assertTrue(
            messages.any { it is UserMessage && it.getTextContent().contains("Ho Chi Minh City") },
            "Most recent user turn should remain verbatim (protected recent turns)",
        )
    }

    @Test
    @RetryOnFailure(maxAttempts = 2)
    @DisplayName("forceCompact preserves tool-call round-trips with a live Ollama model")
    fun forceCompactPreservesToolCallRoundTripsWithLiveModel() {
        // High threshold: only the explicit forceCompact() call below should trigger summarization.
        memory = createMemory(summarizationThreshold = 0.9)

        memory.add(UserMessage.from("Count the files in the current directory."))
        memory.add(
            AiMessage.from(
                listOf(
                    ToolExecutionRequest.builder()
                        .id("call-1")
                        .name("countEntries")
                        .arguments("{}")
                        .build(),
                ),
            ),
        )
        memory.add(ToolExecutionResultMessage.from("call-1", "countEntries", "12 files, 3 directories"))
        memory.add(AiMessage.from("There are 12 files and 3 directories in the current directory."))

        repeat(5) { i ->
            memory.add(UserMessage.from("Follow-up question number $i about the file count."))
            memory.add(AiMessage.from("Follow-up answer number $i."))
        }

        memory.forceCompact()
        awaitSummarization(memory)

        val messages = memory.messages()
        assertTrue(messages.isNotEmpty(), "Memory should still contain messages after forceCompact")

        // The tool-call round-trip was among the oldest units, so COMPACT pruning should have
        // summarized it away entirely — no orphaned tool-call AiMessage/ToolExecutionResultMessage.
        assertTrue(
            messages.none { it is ToolExecutionResultMessage },
            "Tool-call round-trip should have been summarized away by forceCompact",
        )
        assertTrue(
            messages.none { it is AiMessage && it.hasToolExecutionRequests() },
            "Tool-call AiMessage should have been summarized away by forceCompact",
        )
        assertTrue(
            messages.any { it is UserMessage && it.getTextContent().contains("Follow-up question number 4") },
            "Most recent follow-up turn should remain verbatim (protected recent turns)",
        )
    }
}
