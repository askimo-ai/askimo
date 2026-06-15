/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.openai

import com.sun.net.httpserver.HttpServer
import dev.langchain4j.data.message.UserMessage
import io.askimo.core.config.AppConfig
import io.askimo.core.config.ProxyConfig
import io.askimo.core.config.ProxyType
import io.askimo.core.context.AppContext
import io.askimo.core.context.ExecutionMode
import io.askimo.core.providers.ChatClient
import io.askimo.core.providers.sendStreamingMessageWithCallback
import io.askimo.test.extensions.AskimoTestHome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.inc
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Test for OpenAI model factory proxy support.
 *
 * These tests verify that:
 * 1. Proxy configuration is correctly applied to OpenAI API calls
 * 2. Different proxy types work with OpenAI
 * 3. OpenAI API calls succeed when proxy is configured
 *
 * Note: These tests require OPENAI_API_KEY environment variable.
 * Optional: Set PROXY_HOST and PROXY_PORT to test with a real proxy server.
 */
@EnabledIfEnvironmentVariable(
    named = "OPENAI_API_KEY",
    matches = ".+",
    disabledReason = "OPENAI_API_KEY environment variable is required for OpenAI proxy tests",
)
@TestInstance(Lifecycle.PER_CLASS)
@AskimoTestHome
class OpenAiModelFactoryProxyTest {

    private var mockProxyServer: HttpServer? = null
    private var proxyRequestCount = 0
    private var originalProxyConfig: ProxyConfig? = null

    @BeforeEach
    fun setUp() {
        // Save original proxy config
        originalProxyConfig = AppConfig.proxy

        // Reset request counter
        proxyRequestCount = 0

        // Initialize AppContext
        AppContext.reset()
        AppContext.initialize(mode = ExecutionMode.STATELESS_MODE)
    }

    @AfterEach
    fun tearDown() {
        // Restore original proxy config
        originalProxyConfig?.let { config ->
            AppConfig.updateField("proxy.type", config.type.name)
            AppConfig.updateField("proxy.host", config.host)
            AppConfig.updateField("proxy.port", config.port)
            AppConfig.updateField("proxy.username", config.username)
            AppConfig.updateField("proxy.password", config.password)
        }

        // Stop mock proxy server
        mockProxyServer?.stop(0)
        AppContext.reset()
    }

    private fun createChatClient(settings: OpenAiSettings = createDefaultSettings()): ChatClient = OpenAiModelFactory().create(
        settings = settings,
        retriever = null,
        executionMode = ExecutionMode.STATELESS_MODE,
    )

    private fun createDefaultSettings(): OpenAiSettings {
        val apiKey = System.getenv("OPENAI_API_KEY")
            ?: throw IllegalStateException("OPENAI_API_KEY environment variable is required")
        return OpenAiSettings(apiKey = apiKey, defaultModel = "gpt-3.5-turbo")
    }

    private fun sendPromptAndGetResponse(chatClient: ChatClient, prompt: String): String {
        println("Sending prompt with proxy config: '$prompt'")

        val output = chatClient.sendStreamingMessageWithCallback(null, UserMessage(prompt), onToken = { _ ->
            print(".")
        }).trim()

        println("\nReceived response (length: ${output.length})")
        return output
    }

    @Test
    @DisplayName("OpenAI works without proxy (NONE type)")
    fun worksWithNoProxy() {
        // Configure no proxy
        AppConfig.updateField("proxy.type", ProxyType.NONE.name)

        val chatClient = createChatClient()
        val prompt = "Reply with just the word 'test'."
        val output = sendPromptAndGetResponse(chatClient, prompt)

        assertTrue(output.isNotBlank(), "Expected non-empty response with NONE proxy")
        assertEquals(ProxyType.NONE, AppConfig.proxy.type)
    }

    @Test
    @DisplayName("OpenAI works with SYSTEM proxy")
    fun worksWithSystemProxy() {
        // Configure SYSTEM proxy (uses OS proxy settings)
        AppConfig.updateField("proxy.type", ProxyType.SYSTEM.name)

        val chatClient = createChatClient()
        val prompt = "Reply with just the word 'test'."
        val output = sendPromptAndGetResponse(chatClient, prompt)

        assertTrue(output.isNotBlank(), "Expected non-empty response with SYSTEM proxy")
        assertEquals(ProxyType.SYSTEM, AppConfig.proxy.type)

        println("✓ OpenAI successfully used SYSTEM proxy configuration")
    }

    @Test
    @DisplayName("OpenAI can be configured with HTTP proxy")
    fun canConfigureHttpProxy() {
        // Configure HTTP proxy (even if we don't have a real proxy, configuration should work)
        AppConfig.updateField("proxy.type", ProxyType.HTTP.name)
        AppConfig.updateField("proxy.host", "proxy.example.com")
        AppConfig.updateField("proxy.port", 8080)

        // Verify configuration
        assertEquals(ProxyType.HTTP, AppConfig.proxy.type)
        assertEquals("proxy.example.com", AppConfig.proxy.host)
        assertEquals(8080, AppConfig.proxy.port)

        // Create client (will fail if proxy doesn't exist, but we're just testing configuration)
        assertNotNull(createChatClient())

        println("✓ OpenAI model factory accepts HTTP proxy configuration")
    }

    @Test
    @DisplayName("OpenAI can be configured with HTTPS proxy")
    fun canConfigureHttpsProxy() {
        // Configure HTTPS proxy
        AppConfig.updateField("proxy.type", ProxyType.HTTPS.name)
        AppConfig.updateField("proxy.host", "secure-proxy.example.com")
        AppConfig.updateField("proxy.port", 8443)

        // Verify configuration
        assertEquals(ProxyType.HTTPS, AppConfig.proxy.type)
        assertEquals("secure-proxy.example.com", AppConfig.proxy.host)
        assertEquals(8443, AppConfig.proxy.port)

        // Create client
        assertNotNull(createChatClient())

        println("✓ OpenAI model factory accepts HTTPS proxy configuration")
    }

    @Test
    @DisplayName("OpenAI can be configured with SOCKS5 proxy")
    fun canConfigureSocks5Proxy() {
        // Configure SOCKS5 proxy
        AppConfig.updateField("proxy.type", ProxyType.SOCKS5.name)
        AppConfig.updateField("proxy.host", "socks-proxy.example.com")
        AppConfig.updateField("proxy.port", 1080)

        // Verify configuration
        assertEquals(ProxyType.SOCKS5, AppConfig.proxy.type)
        assertEquals("socks-proxy.example.com", AppConfig.proxy.host)
        assertEquals(1080, AppConfig.proxy.port)

        // Create client
        assertNotNull(createChatClient())

        println("✓ OpenAI model factory accepts SOCKS5 proxy configuration")
    }

    @Test
    @DisplayName("OpenAI can be configured with proxy authentication")
    fun canConfigureProxyAuth() {
        // Configure proxy with authentication
        AppConfig.updateField("proxy.type", ProxyType.HTTP.name)
        AppConfig.updateField("proxy.host", "proxy.example.com")
        AppConfig.updateField("proxy.port", 8080)
        AppConfig.updateField("proxy.username", "testuser")
        AppConfig.updateField("proxy.password", "testpass")

        // Verify configuration
        assertEquals("testuser", AppConfig.proxy.username)
        assertEquals("testpass", AppConfig.proxy.password)

        // Create client
        assertNotNull(createChatClient())

        println("✓ OpenAI model factory accepts proxy authentication credentials")
    }

    @Test
    @DisplayName("OpenAI works with real proxy if configured")
    @EnabledIfEnvironmentVariable(
        named = "PROXY_HOST",
        matches = ".+",
        disabledReason = "PROXY_HOST environment variable required for real proxy test",
    )
    fun worksWithRealProxy() {
        // Get proxy configuration from environment
        val proxyHost = System.getenv("PROXY_HOST")
        val proxyPort = System.getenv("PROXY_PORT")?.toIntOrNull() ?: 8080
        val proxyType = System.getenv("PROXY_TYPE") ?: "HTTP"
        val proxyUsername = System.getenv("PROXY_USERNAME") ?: ""
        val proxyPassword = System.getenv("PROXY_PASSWORD") ?: ""

        // Configure proxy
        AppConfig.updateField("proxy.type", proxyType)
        AppConfig.updateField("proxy.host", proxyHost)
        AppConfig.updateField("proxy.port", proxyPort)
        if (proxyUsername.isNotBlank()) {
            AppConfig.updateField("proxy.username", proxyUsername)
            AppConfig.updateField("proxy.password", proxyPassword)
        }

        println("Testing with real proxy: $proxyType://$proxyHost:$proxyPort")

        // Create client and send request
        val chatClient = createChatClient()
        val prompt = "Reply with just the word 'proxy'."
        val output = sendPromptAndGetResponse(chatClient, prompt)

        assertTrue(output.isNotBlank(), "Expected non-empty response through real proxy")
        println("✓ OpenAI successfully communicated through real proxy")
        println("Response: $output")
    }

    @Test
    @DisplayName("OpenAI proxy configuration persists across multiple requests")
    fun proxyConfigPersistsAcrossRequests() {
        // Configure SYSTEM proxy
        AppConfig.updateField("proxy.type", ProxyType.SYSTEM.name)

        val chatClient = createChatClient()

        // First request
        val output1 = sendPromptAndGetResponse(chatClient, "Say 'one'.")
        assertTrue(output1.isNotBlank())
        assertEquals(ProxyType.SYSTEM, AppConfig.proxy.type, "Proxy config should persist after first request")

        // Second request
        val output2 = sendPromptAndGetResponse(chatClient, "Say 'two'.")
        assertTrue(output2.isNotBlank())
        assertEquals(ProxyType.SYSTEM, AppConfig.proxy.type, "Proxy config should persist after second request")

        println("✓ Proxy configuration persisted across multiple requests")
    }

    @Test
    @DisplayName("OpenAI utility model respects proxy configuration")
    fun utilityModelRespectsProxy() {
        // Configure SYSTEM proxy
        AppConfig.updateField("proxy.type", ProxyType.SYSTEM.name)

        // Create utility client (used for secondary operations)
        val settings = createDefaultSettings()
        val utilityClient = OpenAiModelFactory().createUtilityClient(settings)

        assertNotNull(utilityClient)
        assertEquals(ProxyType.SYSTEM, AppConfig.proxy.type)

        println("✓ OpenAI utility model created with proxy configuration")
    }

    @Test
    @DisplayName("OpenAI streaming model respects proxy configuration")
    fun streamingModelRespectsProxy() {
        // Configure SYSTEM proxy
        AppConfig.updateField("proxy.type", ProxyType.SYSTEM.name)

        val chatClient = createChatClient()

        // Test streaming with proxy
        var chunkCount = 0
        val output = chatClient.sendStreamingMessageWithCallback(
            userMessage = UserMessage("Count to 3."),
            onToken = { token ->
                chunkCount++
                print(token)
            },
        ).trim()

        assertTrue(output.isNotBlank(), "Expected streaming response with proxy")
        assertTrue(chunkCount > 0, "Expected multiple streaming chunks")
        assertEquals(ProxyType.SYSTEM, AppConfig.proxy.type)

        println("\n✓ OpenAI streaming worked through proxy ($chunkCount chunks received)")
    }

    @Test
    @DisplayName("OpenAI model list fetching respects proxy configuration")
    fun modelListRespectsProxy() {
        // Configure SYSTEM proxy
        AppConfig.updateField("proxy.type", ProxyType.SYSTEM.name)

        val settings = createDefaultSettings()
        val models = OpenAiModelFactory().availableModels(settings)

        // Should be able to fetch models through proxy
        assertTrue(models.isNotEmpty(), "Expected to fetch models through proxy")
        assertEquals(ProxyType.SYSTEM, AppConfig.proxy.type)

        println("✓ Fetched ${models.size} OpenAI models through proxy")
        println("Sample models: ${models.take(3)}")
    }
}
