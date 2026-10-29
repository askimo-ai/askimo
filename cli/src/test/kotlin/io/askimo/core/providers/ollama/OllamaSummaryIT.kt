/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.ollama

import dev.langchain4j.model.chat.ChatModel
import io.askimo.core.context.AppContext
import io.askimo.core.context.ExecutionMode
import io.askimo.core.memory.SessionConversationSummary
import io.askimo.core.providers.HttpVersion
import io.askimo.core.providers.getSummary
import io.askimo.core.providers.openaicompatible.OpenAiApiMode
import io.askimo.core.providers.openaicompatible.OpenAiCompatibleModelFactory
import io.askimo.core.providers.openaicompatible.OpenAiCompatibleSettings
import io.askimo.core.providers.openaicompatible.OpenAiCompatibleTemplate
import io.askimo.test.extensions.AskimoTestHome
import io.askimo.test.extensions.RetryOnFailure
import io.askimo.testcontainers.SharedOllama
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertTrue

/**
 * Live-model regression test for [getSummary].
 *
 * Guards the root cause of the truncated-summary bug: a `keyFacts` object schema with no
 * declared properties gave grammar-constrained decoders no concrete next token, so the model
 * emitted whitespace until the token cap and produced JSON that failed with
 * "Expected end of the object '}', but had 'EOF' instead at path: $.keyFacts".
 *
 * Ollama applies real JSON-schema-constrained decoding (via `response_format`), so it
 * reproduces that class of failure in a way mocked tests cannot.
 */
@DisabledIfEnvironmentVariable(
    named = "DISABLE_DOCKER_TESTS",
    matches = "(?i)true|1|yes",
)
@Testcontainers
@TestInstance(Lifecycle.PER_CLASS)
@AskimoTestHome
class OllamaSummaryIT {

    private val model = "qwen2.5:0.5b"

    @BeforeEach
    fun setUp() {
        AppContext.reset()
        AppContext.initialize(mode = ExecutionMode.STATELESS_MODE)
    }

    private fun secondaryModel(): ChatModel {
        val ollama = SharedOllama.container
        assertTrue(ollama.isRunning, "Ollama container should be running")
        SharedOllama.ensureModelPulled(model)

        val baseUrl = "http://${ollama.host}:${ollama.getMappedPort(11434)}/v1"
        println("Ollama container running at: $baseUrl")

        val settings = OpenAiCompatibleSettings(
            baseUrl = baseUrl,
            defaultModel = model,
            utilityModel = model,
            apiMode = OpenAiApiMode.CHAT_COMPLETIONS,
            httpVersion = HttpVersion.HTTP_1_1,
            isTemplate = true,
            templateName = OpenAiCompatibleTemplate.OLLAMA.name,
        )

        return OpenAiCompatibleModelFactory().createSecondaryModel(settings)
    }

    private fun assertUsable(summary: SessionConversationSummary) {
        println("keyFacts      = ${summary.keyFacts}")
        println("mainTopics    = ${summary.mainTopics}")
        println("recentContext = ${summary.recentContext}")

        val populated = summary.keyFacts.isNotEmpty() ||
            summary.mainTopics.isNotEmpty() ||
            summary.recentContext.isNotBlank()

        assertTrue(populated, "Expected at least one populated field, got: $summary")

        // Truncation symptom: the fallback returns the raw conversation tail verbatim
        // (see getSummary()'s minimal-context fallback: conversationText.takeLast(500)).
        assertTrue(
            summary.recentContext.length < 500,
            "recentContext looks like a raw conversation tail, not a summary: ${summary.recentContext}",
        )
        summary.keyFacts.keys.forEach {
            assertTrue(it.isNotBlank(), "keyFacts contained a blank key: ${summary.keyFacts}")
        }
    }

    @Test
    @RetryOnFailure(maxAttempts = 3)
    @DisplayName("getSummary returns parsable structured JSON from a live schema-constrained model")
    fun returnsParsableSummary() {
        val conversation = """
            User: I'm building a Kotlin CLI that talks to Ollama and OpenAI.
            Assistant: Nice — are you using LangChain4j for the provider abstraction?
            User: Yes. I'm stuck on conversation summarization: the JSON comes back truncated.
            Assistant: That usually means the response schema leaves the decoder with no valid next token.
            User: Right. I need it to return keyFacts, mainTopics and recentContext reliably.
        """.trimIndent()

        assertUsable(secondaryModel().getSummary(conversation))
    }

    @Test
    @RetryOnFailure(maxAttempts = 3)
    @DisplayName("getSummary survives a short low-signal conversation without truncating")
    fun handlesLowSignalConversation() {
        // Few facts to extract — the case most likely to make a model stall on an open map schema.
        val conversation = """
            User: hi
            Assistant: Hello! How can I help?
            User: thanks, nothing for now
        """.trimIndent()

        val summary = secondaryModel().getSummary(conversation)

        println("low-signal summary = $summary")
        summary.keyFacts.keys.forEach {
            assertTrue(it.isNotBlank(), "keyFacts contained a blank key: ${summary.keyFacts}")
        }
    }

    @Test
    @RetryOnFailure(maxAttempts = 3)
    @DisplayName("getSummary extracts facts from a fact-dense conversation")
    fun extractsFactsFromDenseConversation() {
        val conversation = """
            User: I'm a backend engineer in Ho Chi Minh City, mostly Kotlin and Spring Boot.
            Assistant: Got it. What are you working on?
            User: Migrating a Postgres schema to support multi-tenant row-level security.
            Assistant: Are you sharding or using a tenant_id discriminator?
            User: tenant_id discriminator. I want to finish the migration script this week.
        """.trimIndent()

        val summary = secondaryModel().getSummary(conversation)
        assertUsable(summary)

        val haystack = (
            summary.keyFacts.entries.joinToString(" ") { "${it.key} ${it.value}" } +
                " " + summary.mainTopics.joinToString(" ") +
                " " + summary.recentContext
            ).lowercase()

        // qwen2.5:0.5b is small, so assert loosely on recall of the dominant subject.
        val recalled = listOf("postgres", "tenant", "kotlin", "migration", "spring")
            .count { it in haystack }

        assertTrue(recalled >= 1, "Summary recalled none of the key subjects: $summary")
    }
}
