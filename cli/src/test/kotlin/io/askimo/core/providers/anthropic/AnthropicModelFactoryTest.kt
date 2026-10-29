/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.anthropic

import dev.langchain4j.data.message.TextContent
import io.askimo.core.context.AppContext
import io.askimo.core.context.AppContextParams
import io.askimo.core.context.ExecutionMode
import io.askimo.core.providers.ChatClient
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ProviderInstance
import io.askimo.core.providers.TestToolProviderFactory
import io.askimo.core.providers.sendStreamingMessageWithCallback
import io.askimo.test.extensions.AskimoTestHome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertTrue

/**
 * Model-tier matrix: Anthropic's tool-call JSON-schema handling (required vs. optional
 * params, boxed-boolean defaults, etc.) is known to vary across model generations/tiers —
 * not just across providers. Keep this list in sync with the model ids currently available
 * on the test account; see `ai-provider-testing.md` at the repo root for the open follow-ups
 * around keeping this list current and extending the same matrix to other providers.
 *
 * NOTE: these ids are a best-effort snapshot (Opus / Sonnet / Haiku tiers) and have **not**
 * been verified against a live Anthropic account on this machine — ANTHROPIC_API_KEY isn't
 * available here. Verify/update the ids in another session before relying on this matrix.
 */
private val ANTHROPIC_MODEL_TIERS = listOf(
    "claude-opus-4-6",
    "claude-sonnet-4-6",
    "claude-haiku-4-5",
)

@EnabledIfEnvironmentVariable(
    named = "ANTHROPIC_API_KEY",
    matches = ".+",
    disabledReason = "ANTHROPIC_API_KEY environment variable is required for Anthropic tests",
)
@TestInstance(Lifecycle.PER_CLASS)
@AskimoTestHome
class AnthropicModelFactoryTest {

    private val apiKey = System.getenv("ANTHROPIC_API_KEY")
        ?: throw IllegalStateException("ANTHROPIC_API_KEY environment variable is required")

    @BeforeEach
    fun setUp() {
        AppContext.reset()
        val instance = ProviderInstance.create(
            displayName = "Anthropic",
            providerType = ModelProvider.ANTHROPIC,
        )
        AppContext.initialize(
            mode = ExecutionMode.STATELESS_MODE,
            AppContextParams(
                currentInstanceId = instance.id,
                providerInstances = mutableListOf(instance),
            ),
        )
    }

    @Test
    @DisplayName("AnthropicModelFactory can stream responses from Anthropic API")
    fun canCreateChatServiceAndStream() {
        val settings = AnthropicSettings(apiKey = apiKey, defaultModel = "claude-sonnet-4-6")

        val chatClient: ChatClient =
            AnthropicModelFactory().create(
                settings = settings,
                toolProvider = TestToolProviderFactory.createCountEntriesToolProvider(),
                retriever = null,
                executionMode = ExecutionMode.STATELESS_MODE,
            )

        val prompt = "Reply with a single short word."
        val output = chatClient.sendStreamingMessageWithCallback(null, listOf(TextContent(prompt)), onToken = { _ -> }).trim()

        assertTrue(output.isNotBlank(), "Expected a non-empty response from Anthropic, but got blank: '$output'")
    }

    /**
     * Drives a tool call against each model tier in [ANTHROPIC_MODEL_TIERS], using
     * [TestToolProviderFactory.createMultiToolProvider] so the request includes three tools
     * with distinct schema shapes at once (optional booleans, a single defaulted string, and
     * two required strings) — the combination most likely to expose tier-specific JSON-schema
     * quirks instead of a single trivial tool call.
     *
     * Each model is independent (separate `ChatClient`, separate `AppContext` instance via
     * [setUp]'s `@BeforeEach`), so one tier failing doesn't mask results from the others.
     */
    @ParameterizedTest(name = "model = {0}")
    @MethodSource("io.askimo.core.providers.anthropic.AnthropicModelFactoryTest#anthropicModelTiers")
    @DisplayName("Anthropic can call tools with mixed schema shapes across model tiers")
    fun canCallToolsAcrossModelTiers(model: String) {
        val settings = AnthropicSettings(apiKey = apiKey, defaultModel = model)

        val chatClient: ChatClient = AnthropicModelFactory().create(
            settings = settings,
            toolProvider = TestToolProviderFactory.createMultiToolProvider(),
            retriever = null,
            executionMode = ExecutionMode.STATELESS_MODE,
        )

        val prompt = "Use the countEntries tool to count files and directories in the current directory (\".\")."
        val output = chatClient.sendStreamingMessageWithCallback(null, listOf(TextContent(prompt)), onToken = { _ -> }).trim()

        assertTrue(output.isNotBlank(), "Expected a non-empty response from Anthropic model '$model', but got blank")

        val outputLower = output.lowercase()
        val toolWasCalled = outputLower.contains("file") ||
            outputLower.contains("director") ||
            outputLower.contains("count") ||
            outputLower.matches(".*\\d+.*".toRegex())

        assertTrue(
            toolWasCalled,
            "Expected response from model '$model' to show evidence the countEntries tool ran, but got: '$output'",
        )
    }

    @Test
    @DisplayName("AnthropicModelFactory returns available models when API key provided")
    fun availableModelsReturnsModelsWithApiKey() {
        val settings = AnthropicSettings(apiKey = apiKey, defaultModel = "claude-sonnet-4-6")
        val models = AnthropicModelFactory().availableModels(settings)

        assertTrue(models.isNotEmpty(), "Expected non-empty list of models when API key is provided")
    }

    @Test
    @DisplayName("AnthropicModelFactory returns default settings")
    fun defaultSettingsTest() {
        val factory = AnthropicModelFactory()
        val defaultSettings = factory.defaultSettings()

        assertTrue(defaultSettings.baseUrl.isNotBlank(), "Expected non-blank base URL in default settings")
    }

    companion object {
        @JvmStatic
        fun anthropicModelTiers(): List<String> = ANTHROPIC_MODEL_TIERS
    }
}
