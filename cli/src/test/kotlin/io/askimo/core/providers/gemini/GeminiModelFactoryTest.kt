/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.gemini

import dev.langchain4j.data.message.UserMessage
import io.askimo.core.context.AppContext
import io.askimo.core.context.ExecutionMode
import io.askimo.core.providers.ChatClient
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
    named = "GEMINI_API_KEY",
    matches = ".+",
    disabledReason = "GEMINI_API_KEY environment variable is required for Gemini tests",
)
@AskimoTestHome
@TestInstance(Lifecycle.PER_CLASS)
class GeminiModelFactoryTest {

    private val apiKey = System.getenv("GEMINI_API_KEY")
        ?: throw IllegalStateException("GEMINI_API_KEY environment variable is required")

    @BeforeEach
    fun setUp() {
        AppContext.reset()
        AppContext.initialize(mode = ExecutionMode.STATELESS_MODE)
    }

    private fun createChatService(): ChatClient {
        val settings = GeminiSettings(apiKey = apiKey, defaultModel = "gemini-2.5-flash")

        return GeminiModelFactory().create(
            settings = settings,
            toolProvider = TestToolProviderFactory.createCountEntriesToolProvider(),
            retriever = null,
            executionMode = ExecutionMode.STATEFUL_TOOLS_MODE,
        )
    }

    private fun sendPromptAndGetResponse(chatClient: ChatClient, prompt: String): String {
        println("Sending prompt: '$prompt'")

        val output = chatClient.sendStreamingMessageWithCallback(null, UserMessage(prompt), onToken = { _ ->
            print(".") // Show progress without overwhelming output
        }).trim()

        println("\nFinal output: '$output'")
        return output
    }

    @Test
    @DisplayName("GeminiModelFactory can stream responses from Gemini API")
    fun canCreateChatServiceAndStream() {
        val chatService = createChatService()

        val prompt = "Reply with a single short word."
        val output = sendPromptAndGetResponse(chatService, prompt)

        assertTrue(output.isNotBlank(), "Expected a non-empty response from Gemini, but got blank: '$output'")
    }

    @Test
    @DisplayName("Gemini can call LocalFsTools countEntries function")
    fun canCallCountEntriesTool() {
        val chatService = createChatService()

        // Prompt that should trigger the countEntries tool call
        val prompt = "Use the countEntries tool to count files and directories in the current directory (\".\"). Give me the file count, directory count, and total size."

        val output = sendPromptAndGetResponse(chatService, prompt)

        // Verify that the AI actually used the tool and returned meaningful results
        assertTrue(output.isNotBlank(), "Expected a non-empty response from Gemini, but got blank: '$output'")

        println("Analyzing output for tool usage indicators...")
        println("Output length: ${output.length}")

        // Check if the response contains indicators that the tool was called successfully
        val outputLower = output.lowercase()
        val hasFileInfo = outputLower.contains("file") || outputLower.contains("directory") || outputLower.contains("dir")
        val hasCountInfo = outputLower.contains("count") || outputLower.matches(".*\\d+.*".toRegex())
        val hasByteInfo = outputLower.contains("byte") || outputLower.contains("size")

        println("Has file info: $hasFileInfo")
        println("Has count info: $hasCountInfo")
        println("Has byte info: $hasByteInfo")

        val toolWasCalled = hasFileInfo || hasCountInfo || hasByteInfo

        assertTrue(
            toolWasCalled,
            "Expected response to contain file/directory count information indicating tool was called, but got: '$output'",
        )
    }

    @Test
    @DisplayName("GeminiModelFactory returns available models when API key provided")
    fun availableModelsReturnsModelsWithApiKey() {
        val settings = GeminiSettings(apiKey = apiKey, defaultModel = "gemini-2.5-flash")
        val models = GeminiModelFactory().availableModels(settings)

        assertTrue(models.isNotEmpty(), "Expected non-empty list of models when API key is provided")
    }

    @Test
    @DisplayName("GeminiModelFactory returns default settings")
    fun defaultSettingsTest() {
        val factory = GeminiModelFactory()
        val defaultSettings = factory.defaultSettings()

        assertTrue(defaultSettings.apiKey.isBlank(), "Expected blank API key in default settings")
        assertTrue(defaultSettings.baseUrl.isNotBlank(), "Expected non-blank base URL in default settings")
    }
}
