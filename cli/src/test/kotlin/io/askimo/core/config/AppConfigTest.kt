/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.config

import io.askimo.core.context.AppContextParams
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ProviderInstance
import io.askimo.core.providers.anthropic.AnthropicSettings
import io.askimo.core.providers.openai.OpenAiSettings
import io.askimo.core.providers.openaicompatible.OpenAiCompatibleSettings
import io.askimo.core.providers.openaicompatible.OpenAiCompatibleTemplate
import io.askimo.core.providers.xai.XAiSettings
import io.askimo.core.util.AskimoHome
import io.askimo.test.extensions.AskimoTestHome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Tests for AppConfig field update methods.
 * These tests verify that the domain object update methods work correctly.
 */
@AskimoTestHome
class AppConfigTest {

    @Test
    fun `DEFAULT_YAML snake_case fields are correctly deserialized`() {
        // @AskimoTestHome writes DEFAULT_YAML and resets AppConfig — so all fields
        // should reflect the YAML defaults.
        val models = AppConfig.models

        assertEquals(10, models.maxToolCallingRoundTrips)

        // Global timeouts
        assertEquals(45L, models.timeouts.utilityModelTimeoutSeconds)
        assertEquals(300L, models.timeouts.defaultModelTimeoutSeconds)
    }

    @Test
    fun `YAML round-trip preserves timeout values after updateField`() {
        AppConfig.updateField("models.timeouts.utilityModelTimeoutSeconds", "120")
        assertEquals(120L, AppConfig.models.timeouts.utilityModelTimeoutSeconds)
    }

    @Test
    fun `updateRagField should handle useAbsolutePathInCitations`() {
        val config = RagConfig(useAbsolutePathInCitations = true)

        val updated = updateRagFieldHelper(config, "useAbsolutePathInCitations", false)

        assertFalse(updated.useAbsolutePathInCitations)
    }

    @Test
    fun `updateRagField should handle all numeric fields`() {
        val config = RagConfig()

        var updated = updateRagFieldHelper(config, "vectorSearchMaxResults", 50)
        assertEquals(50, updated.vectorSearchMaxResults)

        updated = updateRagFieldHelper(config, "vectorSearchMinScore", 0.5)
        assertEquals(0.5, updated.vectorSearchMinScore, 0.001)

        updated = updateRagFieldHelper(config, "hybridMaxResults", 25)
        assertEquals(25, updated.hybridMaxResults)

        updated = updateRagFieldHelper(config, "rankFusionConstant", 100)
        assertEquals(100, updated.rankFusionConstant)
    }

    @Test
    fun `updateModelsField should handle maxToolCallingRoundTrips`() {
        val config = ModelsConfig()

        val withInt = updateModelsFieldHelper(config, "maxToolCallingRoundTrips", 25)
        assertEquals(25, withInt.maxToolCallingRoundTrips)

        val withString = updateModelsFieldHelper(config, "maxToolCallingRoundTrips", "5")
        assertEquals(5, withString.maxToolCallingRoundTrips)

        val withInvalid = updateModelsFieldHelper(config, "maxToolCallingRoundTrips", "invalid")
        assertEquals(config.maxToolCallingRoundTrips, withInvalid.maxToolCallingRoundTrips)
    }

    @Test
    fun `updateEmbeddingField should handle all fields`() {
        val config = EmbeddingConfig()

        var updated = updateEmbeddingFieldHelper(config, "maxCharsPerChunk", 5000)
        assertEquals(5000, updated.maxCharsPerChunk)

        updated = updateEmbeddingFieldHelper(config, "chunkOverlap", 300)
        assertEquals(300, updated.chunkOverlap)
    }

    @Test
    fun `updateChatField should handle all fields`() {
        var config = ChatConfig()

        config = updateChatFieldHelper(config, "maxTokens", 10000)
        assertEquals(10000, config.maxTokens)
    }

    @Test
    fun `updateMemoryField should handle mode - swaps to full preset`() {
        var config = MemoryConfig()

        config = updateMemoryFieldHelper(config, "mode", MemoryMode.COMPACT)
        assertEquals(MemoryMode.COMPACT, config.mode)
        assertEquals(0.25, config.summarizationThreshold, 0.001)
        assertEquals(3, config.protectedRecentTurns)
        assertEquals(0.30, config.memoryBudgetFraction, 0.001)

        config = updateMemoryFieldHelper(config, "mode", "DETAIL")
        assertEquals(MemoryMode.DETAIL, config.mode)
        assertEquals(0.60, config.summarizationThreshold, 0.001)
        assertEquals(10, config.protectedRecentTurns)
        assertEquals(0.50, config.memoryBudgetFraction, 0.001)
    }

    @Test
    fun `updateMemoryField should handle all numeric fields`() {
        var config = MemoryConfig()

        config = updateMemoryFieldHelper(config, "summarizationThreshold", 0.5)
        assertEquals(0.5, config.summarizationThreshold, 0.001)

        config = updateMemoryFieldHelper(config, "protectedRecentTurns", 8)
        assertEquals(8, config.protectedRecentTurns)

        config = updateMemoryFieldHelper(config, "summarizationPruneFraction", 0.7)
        assertEquals(0.7, config.summarizationPruneFraction, 0.001)

        config = updateMemoryFieldHelper(config, "maxKeyFacts", 40)
        assertEquals(40, config.maxKeyFacts)

        config = updateMemoryFieldHelper(config, "maxMainTopics", 20)
        assertEquals(20, config.maxMainTopics)

        config = updateMemoryFieldHelper(config, "maxSummaryLength", 3000)
        assertEquals(3000, config.maxSummaryLength)

        config = updateMemoryFieldHelper(config, "memoryBudgetFraction", 0.45)
        assertEquals(0.45, config.memoryBudgetFraction, 0.001)
    }

    @Test
    fun `MemoryConfig preset values should be correct`() {
        val compact = MemoryConfig.COMPACT
        assertEquals(MemoryMode.COMPACT, compact.mode)
        assertEquals(0.25, compact.summarizationThreshold, 0.001)
        assertEquals(3, compact.protectedRecentTurns)
        assertEquals(0.30, compact.memoryBudgetFraction, 0.001)

        val balanced = MemoryConfig.BALANCED
        assertEquals(MemoryMode.BALANCED, balanced.mode)
        assertEquals(0.40, balanced.summarizationThreshold, 0.001)
        assertEquals(6, balanced.protectedRecentTurns)
        assertEquals(0.40, balanced.memoryBudgetFraction, 0.001)

        val detail = MemoryConfig.DETAIL
        assertEquals(MemoryMode.DETAIL, detail.mode)
        assertEquals(0.60, detail.summarizationThreshold, 0.001)
        assertEquals(10, detail.protectedRecentTurns)
        assertEquals(0.50, detail.memoryBudgetFraction, 0.001)
    }

    @Test
    fun `MemoryConfig preset factory should return correct preset`() {
        assertEquals(MemoryConfig.preset(MemoryMode.COMPACT), MemoryConfig.COMPACT)
        assertEquals(MemoryConfig.preset(MemoryMode.BALANCED), MemoryConfig.BALANCED)
        assertEquals(MemoryConfig.preset(MemoryMode.DETAIL), MemoryConfig.DETAIL)
    }

    @Test
    fun `updateDeveloperField should handle all fields`() {
        val config = DeveloperConfig()

        var updated = updateDeveloperFieldHelper(config, "enabled", true)
        assertTrue(updated.enabled)

        updated = updateDeveloperFieldHelper(config, "active", true)
        assertTrue(updated.active)
    }

    private fun updateRagFieldHelper(config: RagConfig, field: String, value: Any): RagConfig {
        val method = AppConfig::class.java.getDeclaredMethod(
            "updateRagField",
            RagConfig::class.java,
            String::class.java,
            Any::class.java,
        )
        method.isAccessible = true
        return method.invoke(AppConfig, config, field, value) as RagConfig
    }

    private fun updateModelsFieldHelper(config: ModelsConfig, field: String, value: Any): ModelsConfig {
        val method = AppConfig::class.java.getDeclaredMethod(
            "updateModelsField",
            ModelsConfig::class.java,
            String::class.java,
            Any::class.java,
        )
        method.isAccessible = true
        return method.invoke(AppConfig, config, field, value) as ModelsConfig
    }

    private fun updateEmbeddingFieldHelper(config: EmbeddingConfig, field: String, value: Any): EmbeddingConfig {
        val method = AppConfig::class.java.getDeclaredMethod(
            "updateEmbeddingField",
            EmbeddingConfig::class.java,
            String::class.java,
            Any::class.java,
        )
        method.isAccessible = true
        return method.invoke(AppConfig, config, field, value) as EmbeddingConfig
    }

    private fun updateChatFieldHelper(config: ChatConfig, field: String, value: Any): ChatConfig {
        val method = AppConfig::class.java.getDeclaredMethod(
            "updateChatField",
            ChatConfig::class.java,
            String::class.java,
            Any::class.java,
        )
        method.isAccessible = true
        return method.invoke(AppConfig, config, field, value) as ChatConfig
    }

    private fun updateMemoryFieldHelper(config: MemoryConfig, field: String, value: Any): MemoryConfig {
        val method = AppConfig::class.java.getDeclaredMethod(
            "updateMemoryField",
            MemoryConfig::class.java,
            String::class.java,
            Any::class.java,
        )
        method.isAccessible = true
        return method.invoke(AppConfig, config, field, value) as MemoryConfig
    }

    private fun updateDeveloperFieldHelper(config: DeveloperConfig, field: String, value: Any): DeveloperConfig {
        val method = AppConfig::class.java.getDeclaredMethod(
            "updateDeveloperField",
            DeveloperConfig::class.java,
            String::class.java,
            Any::class.java,
        )
        method.isAccessible = true
        return method.invoke(AppConfig, config, field, value) as DeveloperConfig
    }

    private fun updateProxyFieldHelper(config: ProxyConfig, field: String, value: Any): ProxyConfig {
        val method = AppConfig::class.java.getDeclaredMethod(
            "updateProxyField",
            ProxyConfig::class.java,
            String::class.java,
            Any::class.java,
        )
        method.isAccessible = true
        return method.invoke(AppConfig, config, field, value) as ProxyConfig
    }

    // Proxy Configuration Tests

    @Test
    fun `updateProxyField should handle all basic fields`() {
        var config = ProxyConfig()

        config = updateProxyFieldHelper(config, "type", ProxyType.HTTP)
        assertEquals(ProxyType.HTTP, config.type)

        config = updateProxyFieldHelper(config, "host", "proxy.example.com")
        assertEquals("proxy.example.com", config.host)

        config = updateProxyFieldHelper(config, "port", 8080)
        assertEquals(8080, config.port)

        config = updateProxyFieldHelper(config, "username", "john.doe")
        assertEquals("john.doe", config.username)
    }

    @Test
    fun `updateProxyField should store password as placeholder when actual password provided`() {
        val config = ProxyConfig(type = ProxyType.HTTP)

        // When actual password is provided, it should be stored as placeholder in config
        val updated = updateProxyFieldHelper(config, "password", "actual-password")

        // The config should have the placeholder, not the actual password
        assertEquals(ProxyConfig.getPasswordPlaceholder(), updated.password)
    }

    @Test
    fun `updateProxyField should preserve placeholder when placeholder provided`() {
        val config = ProxyConfig(type = ProxyType.HTTP)

        // When placeholder is provided, it should be preserved
        val updated = updateProxyFieldHelper(config, "password", ProxyConfig.getPasswordPlaceholder())

        assertEquals(ProxyConfig.getPasswordPlaceholder(), updated.password)
    }

    @Test
    fun `updateProxyField should preserve empty password`() {
        val config = ProxyConfig(type = ProxyType.HTTP)

        val updated = updateProxyFieldHelper(config, "password", "")

        assertEquals("", updated.password)
    }

    @Test
    fun `ProxyConfig isActualPassword should detect actual passwords`() {
        // Actual passwords
        assertTrue(ProxyConfig.isActualPassword("my-password"))
        assertTrue(ProxyConfig.isActualPassword("secret123"))

        // Not actual passwords (placeholders or empty)
        assertFalse(ProxyConfig.isActualPassword(""))
        assertFalse(ProxyConfig.isActualPassword("   "))
        assertFalse(ProxyConfig.isActualPassword(ProxyConfig.getPasswordPlaceholder()))
    }

    @Test
    fun `ProxyConfig should have placeholder constant`() {
        val placeholder = ProxyConfig.getPasswordPlaceholder()

        assertEquals("***keychain***", placeholder)
    }

    @Test
    fun `ProxyConfig getStorageKey should be unique per proxy type`() {
        // Use reflection to access private getStorageKey method
        val method = ProxyConfig::class.java.declaredClasses
            .first { it.simpleName == "Companion" }
            .getDeclaredMethod("getStorageKey", ProxyType::class.java)
        method.isAccessible = true
        val companion = ProxyConfig::class.java.getDeclaredField("Companion").get(null)

        val httpKey = method.invoke(companion, ProxyType.HTTP) as String
        val httpsKey = method.invoke(companion, ProxyType.HTTPS) as String
        val socks5Key = method.invoke(companion, ProxyType.SOCKS5) as String
        val systemKey = method.invoke(companion, ProxyType.SYSTEM) as String

        // Each proxy type should have a unique storage key
        assertEquals("proxy.http.password", httpKey)
        assertEquals("proxy.https.password", httpsKey)
        assertEquals("proxy.socks5.password", socks5Key)
        assertEquals("proxy.system.password", systemKey)

        // All keys should be different
        val keys = setOf(httpKey, httpsKey, socks5Key, systemKey)
        assertEquals(4, keys.size)
    }

    @Test
    fun `updateProxyField should handle type changes correctly`() {
        var config = ProxyConfig(type = ProxyType.HTTP)

        config = updateProxyFieldHelper(config, "type", ProxyType.SOCKS5)
        assertEquals(ProxyType.SOCKS5, config.type)

        config = updateProxyFieldHelper(config, "type", "HTTPS")
        assertEquals(ProxyType.HTTPS, config.type)
    }

    // saveContext tests

    @Test
    fun `saveContext updates in-memory cache with given params`() {
        val instance = ProviderInstance.create(
            displayName = "OpenAI",
            providerType = ModelProvider.OPENAI,
            settings = OpenAiSettings(apiKey = "", defaultModel = "gpt-4o"),
        )
        val params = AppContextParams(
            currentInstanceId = instance.id,
            providerInstances = mutableListOf(instance),
        )

        AppConfig.saveContext(params)

        assertEquals(ModelProvider.OPENAI, AppConfig.context.activeProviderType)
        assertEquals("gpt-4o", AppConfig.context.activeInstance?.settings?.defaultModel)
    }

    @Test
    fun `saveContext with no-op params stores UNKNOWN provider`() {
        val params = AppContextParams.noOp()

        AppConfig.saveContext(params)

        assertEquals(ModelProvider.UNKNOWN, AppConfig.context.activeProviderType)
        assertTrue(AppConfig.context.providerInstances.isEmpty())
    }

    @Test
    fun `saveContext sanitizes API key before persisting to disk`() {
        val instance = ProviderInstance.create(
            displayName = "OpenAI",
            providerType = ModelProvider.OPENAI,
            settings = OpenAiSettings(apiKey = "sk-super-secret-key"),
        )
        val params = AppContextParams(
            currentInstanceId = instance.id,
            providerInstances = mutableListOf(instance),
        )

        AppConfig.saveContext(params)

        // The in-memory context must NOT contain the raw API key — SecureSessionManager
        // replaces it with a placeholder or encrypted form before writing to disk.
        val storedInstance = AppConfig.context.providerInstances.firstOrNull { it.providerType == ModelProvider.OPENAI }
        val storedKey = (storedInstance?.settings as? OpenAiSettings)?.apiKey
        assertNotEquals("sk-super-secret-key", storedKey)
    }

    @Test
    fun `saveContext persists context to YAML config file on disk`() {
        val configFile = AskimoHome.base().resolve("askimo.yml")

        val instance = ProviderInstance.create(
            displayName = "OpenAI",
            providerType = ModelProvider.OPENAI,
            settings = OpenAiSettings(apiKey = "", defaultModel = "gpt-4o-mini"),
        )
        val params = AppContextParams(
            currentInstanceId = instance.id,
            providerInstances = mutableListOf(instance),
        )

        AppConfig.saveContext(params)

        assertTrue(Files.exists(configFile), "Config file should exist after saveContext")
        val yaml = Files.readString(configFile)
        assertTrue(yaml.contains("OPENAI"), "Persisted YAML should reference OPENAI provider")
    }

    @Test
    fun `saveContext preserves existing config fields after save`() {
        // Verify that saving context does not wipe out unrelated config sections
        val originalMaxRoundTrips = AppConfig.models.maxToolCallingRoundTrips

        val instance = ProviderInstance.create(
            displayName = "OpenAI",
            providerType = ModelProvider.OPENAI,
        )
        val params = AppContextParams(
            currentInstanceId = instance.id,
            providerInstances = mutableListOf(instance),
        )

        AppConfig.saveContext(params)

        assertEquals(originalMaxRoundTrips, AppConfig.models.maxToolCallingRoundTrips)
    }

    @Test
    fun `saveContext overwrites a previously saved context`() {
        val xaiInstance = ProviderInstance.create(
            displayName = "xAI",
            providerType = ModelProvider.XAI,
            // Fill all constructor args to ensure agent captures the full reflective signature.
            settings = XAiSettings(
                baseUrl = "https://api.x.ai/v1",
                apiKey = "xai-test-key",
                defaultModel = "grok-4",
                utilityModel = "grok-4-fast",
                visionModel = "grok-vision-beta",
                imageModel = "grok-image-beta",
                embeddingModel = "grok-embed-beta",
            ),
        )
        val firstParams = AppContextParams(
            currentInstanceId = xaiInstance.id,
            providerInstances = mutableListOf(xaiInstance),
        )
        AppConfig.saveContext(firstParams)
        assertEquals(ModelProvider.XAI, AppConfig.context.activeProviderType)

        val anthropicInstance = ProviderInstance.create(
            displayName = "Anthropic",
            providerType = ModelProvider.ANTHROPIC,
            settings = AnthropicSettings(defaultModel = "claude-sonnet-4-20250514"),
        )
        val secondParams = AppContextParams(
            currentInstanceId = anthropicInstance.id,
            providerInstances = mutableListOf(anthropicInstance),
        )
        AppConfig.saveContext(secondParams)
        assertEquals(ModelProvider.ANTHROPIC, AppConfig.context.activeProviderType)

        val ollamaSettings = OpenAiCompatibleSettings(
            baseUrl = OpenAiCompatibleTemplate.OLLAMA.baseUrl,
            defaultModel = "llama3.1",
            apiMode = OpenAiCompatibleTemplate.OLLAMA.apiMode,
            httpVersion = OpenAiCompatibleTemplate.OLLAMA.httpVersion,
            isTemplate = true,
            templateName = OpenAiCompatibleTemplate.OLLAMA.name,
        )
        val ollamaInstance = ProviderInstance.create(
            displayName = "Ollama",
            providerType = ModelProvider.OPENAI_COMPATIBLE,
            settings = ollamaSettings,
        )
        val thirdParams = AppContextParams(
            currentInstanceId = ollamaInstance.id,
            providerInstances = mutableListOf(ollamaInstance),
        )
        AppConfig.saveContext(thirdParams)

        assertEquals(ModelProvider.OPENAI_COMPATIBLE, AppConfig.context.activeProviderType)
        assertEquals("llama3.1", AppConfig.context.activeInstance?.settings?.defaultModel)
    }
}
