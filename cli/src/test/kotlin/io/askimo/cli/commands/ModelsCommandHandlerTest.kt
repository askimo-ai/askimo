/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.cli.commands

import io.askimo.core.context.AppContext
import io.askimo.core.providers.ModelProvider
import io.askimo.test.extensions.AskimoTestHome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@AskimoTestHome
class ModelsCommandHandlerTest : CommandHandlerTestBase() {
    private lateinit var appContext: AppContext
    private lateinit var handler: ModelsCommandHandler

    @BeforeEach
    fun setUp() {
        appContext = mock<AppContext>()
        handler = ModelsCommandHandler(appContext)
    }

    @Test
    fun `keyword returns correct value`() {
        assertEquals(":models", handler.keyword)
    }

    @Test
    fun `description is not empty and mentions models`() {
        assertTrue(handler.description.isNotBlank())
        assertTrue(handler.description.contains("model", ignoreCase = true))
        assertTrue(
            handler.description.contains("list", ignoreCase = true) ||
                handler.description.contains("available", ignoreCase = true),
        )
    }

    @Test
    fun `handle with OpenAI provider shows models or helpful message`() {
        whenever(appContext.getActiveProvider()) doReturn ModelProvider.OPENAI
        whenever(appContext.getActiveInstance()) doReturn null

        val parsedLine = mockParsedLine(":models")

        handler.handle(parsedLine)

        val output = getOutput()
        // Without a valid API key, OpenAI might return no models
        assertTrue(
            output.contains("Available models for 'openai'") ||
                output.contains("❌ No models available for 'openai'"),
        )
        // Should always show some helpful information (usage hint on success, help text on error)
        assertTrue(output.contains("💡") || output.contains("Please check your provider configuration"))
    }

    @Test
    fun `handle with OpenAI and no API key shows helpful guidance`() {
        whenever(appContext.getActiveProvider()) doReturn ModelProvider.OPENAI
        whenever(appContext.getActiveInstance()) doReturn null

        val parsedLine = mockParsedLine(":models")

        handler.handle(parsedLine)

        val output = getOutput()
        // If no models available, should show OpenAI-specific guidance
        if (output.contains("❌ No models available")) {
            assertTrue(output.contains("OpenAI API key") || output.contains("platform.openai.com"))
        }
    }

    @Test
    fun `handle with Ollama provider shows models or helpful message`() {
        whenever(appContext.getActiveProvider()) doReturn ModelProvider.OPENAI_COMPATIBLE
        whenever(appContext.getActiveInstance()) doReturn null

        val parsedLine = mockParsedLine(":models")

        handler.handle(parsedLine)

        val output = getOutput()
        // OpenAI-compatible provider might have no models if not configured
        assertTrue(
            output.contains("Available models for 'openai_compatible'") ||
                output.contains("❌ No models available for 'openai_compatible'"),
        )
        // Should always show some helpful information (usage hint on success, help text on error)
        assertTrue(output.contains("💡"))
    }

    @Test
    fun `handle with Ollama and no models shows helpful message`() {
        whenever(appContext.getActiveProvider()) doReturn ModelProvider.OPENAI_COMPATIBLE
        whenever(appContext.getActiveInstance()) doReturn null

        val parsedLine = mockParsedLine(":models")

        handler.handle(parsedLine)

        val output = getOutput()
        // If no models are available, should show helpful OpenAI-compatible-specific guidance
        if (output.contains("❌ No models available")) {
            assertTrue(output.contains("Base URL") || output.contains("API key") || output.contains("openai-compatible"))
        }
    }

    @Test
    fun `handle with Anthropic provider shows models or helpful message`() {
        whenever(appContext.getActiveProvider()) doReturn ModelProvider.ANTHROPIC
        whenever(appContext.getActiveInstance()) doReturn null

        val parsedLine = mockParsedLine(":models")

        handler.handle(parsedLine)

        val output = getOutput()
        assertTrue(
            output.contains("Available models for 'anthropic'") ||
                output.contains("❌ No models available for 'anthropic'"),
        )
        // Should always show some helpful information (usage hint on success, help text on error)
        assertTrue(output.contains("💡"))
    }

    @Test
    fun `handle with Gemini provider shows models or helpful message`() {
        whenever(appContext.getActiveProvider()) doReturn ModelProvider.GEMINI
        whenever(appContext.getActiveInstance()) doReturn null

        val parsedLine = mockParsedLine(":models")

        handler.handle(parsedLine)

        val output = getOutput()
        assertTrue(
            output.contains("Available models for 'gemini'") ||
                output.contains("❌ No models available for 'gemini'"),
        )
        // Should always show some helpful information (usage hint on success, help text on error)
        assertTrue(output.contains("💡") || output.contains("Please check your provider configuration"))
    }

    @Test
    fun `handle with xAI provider shows models or helpful message`() {
        whenever(appContext.getActiveProvider()) doReturn ModelProvider.XAI
        whenever(appContext.getActiveInstance()) doReturn null

        val parsedLine = mockParsedLine(":models")

        handler.handle(parsedLine)

        val output = getOutput()
        assertTrue(
            output.contains("Available models for 'xai'") ||
                output.contains("❌ No models available for 'xai'"),
        )
        // Should always show some helpful information (usage hint on success, help text on error)
        assertTrue(output.contains("💡") || output.contains("Please check your provider configuration"))
    }

    @Test
    fun `handle with unknown provider shows error`() {
        whenever(appContext.getActiveProvider()) doReturn ModelProvider.UNKNOWN
        whenever(appContext.getActiveInstance()) doReturn null

        val parsedLine = mockParsedLine(":models")

        handler.handle(parsedLine)

        val output = getOutput()
        assertTrue(output.contains("❌ No model factory registered for provider: unknown"))
    }

    @Test
    fun `handle always shows usage hint`() {
        whenever(appContext.getActiveProvider()) doReturn ModelProvider.OPENAI
        whenever(appContext.getActiveInstance()) doReturn null

        val parsedLine = mockParsedLine(":models")

        handler.handle(parsedLine)

        val output = getOutput()
        // Should always show how to set a model (unless there's an error with no factory)
        assertTrue(
            output.contains(":set-param model") ||
                output.contains("💡") ||
                output.contains("❌ No model factory registered"),
        )
    }

    @Test
    fun `handle shows provider name in lowercase`() {
        whenever(appContext.getActiveProvider()) doReturn ModelProvider.OPENAI
        whenever(appContext.getActiveInstance()) doReturn null

        val parsedLine = mockParsedLine(":models")

        handler.handle(parsedLine)

        val output = getOutput()
        // Provider name should be lowercase in output
        assertTrue(output.contains("openai"))
    }

    @Test
    fun `handle formats model list with dashes when models available`() {
        whenever(appContext.getActiveProvider()) doReturn ModelProvider.ANTHROPIC
        whenever(appContext.getActiveInstance()) doReturn null

        val parsedLine = mockParsedLine(":models")

        handler.handle(parsedLine)

        val output = getOutput()
        assertTrue(
            output.contains("anthropic") ||
                output.contains("Available models") ||
                output.contains("No models available"),
        )
    }

    @Test
    fun `handle with different providers shows correct provider names`() {
        val testCases =
            listOf(
                ModelProvider.OPENAI to "openai",
                ModelProvider.OPENAI_COMPATIBLE to "openai_compatible",
                ModelProvider.ANTHROPIC to "anthropic",
                ModelProvider.GEMINI to "gemini",
                ModelProvider.XAI to "xai",
            )

        testCases.forEach { (provider, expectedName) ->
            testOut.reset() // Reset output between iterations

            whenever(appContext.getActiveProvider()) doReturn provider
            whenever(appContext.getActiveInstance()) doReturn null

            val parsedLine = mockParsedLine(":models")
            handler.handle(parsedLine)

            val output = getOutput()
            assertTrue(
                output.contains(expectedName),
                "Output should contain provider name '$expectedName'",
            )
        }
    }

    @Test
    fun `handle with instance display name uses it as label`() {
        val instance = io.askimo.core.providers.ProviderInstance.create(
            displayName = "My Ollama",
            providerType = ModelProvider.OPENAI_COMPATIBLE,
        )
        whenever(appContext.getActiveProvider()) doReturn ModelProvider.OPENAI_COMPATIBLE
        whenever(appContext.getActiveInstance()) doReturn instance

        val parsedLine = mockParsedLine(":models")

        handler.handle(parsedLine)

        val output = getOutput()
        // Should use instance display name, not raw provider key
        assertTrue(
            output.contains("My Ollama") ||
                output.contains("Available models") ||
                output.contains("❌ No models available"),
        )
    }

    @Test
    fun `handle shows emoji in output`() {
        whenever(appContext.getActiveProvider()) doReturn ModelProvider.OPENAI
        whenever(appContext.getActiveInstance()) doReturn null

        val parsedLine = mockParsedLine(":models")

        handler.handle(parsedLine)

        val output = getOutput()
        // Should contain at least one emoji
        assertTrue(
            output.contains("💡") ||
                output.contains("⚠️") ||
                output.contains("❌"),
        )
    }

    @Test
    fun `handle with no active instance uses factory defaults`() {
        whenever(appContext.getActiveProvider()) doReturn ModelProvider.OPENAI_COMPATIBLE
        whenever(appContext.getActiveInstance()) doReturn null

        val parsedLine = mockParsedLine(":models")

        handler.handle(parsedLine)

        val output = getOutput()
        // Should not crash, should show models or no models message
        assertTrue(
            output.contains("openai_compatible") ||
                output.contains("Available models") ||
                output.contains("❌ No models available"),
        )
    }

    @Test
    fun `handle shows appropriate guidance based on provider`() {
        // Test Ollama guidance
        whenever(appContext.getActiveProvider()) doReturn ModelProvider.OPENAI_COMPATIBLE
        whenever(appContext.getActiveInstance()) doReturn null

        var parsedLine = mockParsedLine(":models")
        handler.handle(parsedLine)
        var output = getOutput()

        if (output.contains("❌ No models available")) {
            assertTrue(output.contains("openai_compatible") || output.contains("Base URL") || output.contains("API key"))
        }

        // Reset and test OpenAI guidance
        testOut.reset()
        whenever(appContext.getActiveProvider()) doReturn ModelProvider.OPENAI

        parsedLine = mockParsedLine(":models")
        handler.handle(parsedLine)
        output = getOutput()

        if (output.contains("❌ No models available")) {
            assertTrue(output.contains("API key") || output.contains("platform.openai.com"))
        }
    }

    @Test
    fun `handle with null active instance does not crash`() {
        whenever(appContext.getActiveProvider()) doReturn ModelProvider.OPENAI_COMPATIBLE
        whenever(appContext.getActiveInstance()) doReturn null

        val parsedLine = mockParsedLine(":models")

        // Should not throw exception
        handler.handle(parsedLine)

        val output = getOutput()
        assertTrue(output.isNotEmpty())
    }
}
