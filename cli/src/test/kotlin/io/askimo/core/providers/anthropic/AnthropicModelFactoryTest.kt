/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.anthropic

import dev.langchain4j.data.message.UserMessage
import io.askimo.core.context.AppContext
import io.askimo.core.context.AppContextParams
import io.askimo.core.context.ExecutionMode
import io.askimo.core.providers.ChatClient
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.TestToolProviderFactory
import io.askimo.core.providers.sendStreamingMessageWithCallback
import io.askimo.test.extensions.AskimoTestHome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertTrue

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
        AppContext.initialize(
            mode = ExecutionMode.STATELESS_MODE,
            AppContextParams(
                currentProvider = ModelProvider.ANTHROPIC,
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
        val output = chatClient.sendStreamingMessageWithCallback(null, UserMessage(prompt), onToken = { _ -> }).trim()

        assertTrue(output.isNotBlank(), "Expected a non-empty response from Anthropic, but got blank: '$output'")
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
}
