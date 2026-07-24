/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.config

import io.askimo.core.context.AppContextParams
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ProviderInstance
import io.askimo.core.providers.openai.OpenAiSettings
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
    fun `DEFAULT_YAML snake_case fields are correctly deserialized for all providers`() {
        // @AskimoTestHome writes DEFAULT_YAML and resets AppConfig — so all fields
        // should reflect the YAML defaults, not the empty ProviderModelConfig() defaults.
        val models = AppConfig.models

        // Ollama — the field that was failing in EmbeddingModelFactoryOllamaTest
        assertEquals("", models[ModelProvider.OLLAMA].embeddingModel)
        assertEquals("", models[ModelProvider.OLLAMA].visionModel)
        assertEquals("", models[ModelProvider.OLLAMA].imageModel)

        // Anthropic
        assertEquals("", models[ModelProvider.ANTHROPIC].utilityModel)
        assertEquals("claude-sonnet-4-6", models[ModelProvider.ANTHROPIC].visionModel)
        assertEquals("claude-sonnet-4-6", models[ModelProvider.ANTHROPIC].imageModel)

        // Global timeouts
        assertEquals(45L, models.timeouts.utilityModelTimeoutSeconds)
        assertEquals(300L, models.timeouts.defaultModelTimeoutSeconds)

        // Gemini
        assertEquals("", models[ModelProvider.GEMINI].utilityModel)
        assertEquals("gemini-embedding-001", models[ModelProvider.GEMINI].embeddingModel)
        assertEquals("gemini-1.5-pro", models[ModelProvider.GEMINI].visionModel)
        assertEquals("gemini-2.0-flash-exp", models[ModelProvider.GEMINI].imageModel)

        // OpenAI
        assertEquals("", models[ModelProvider.OPENAI].utilityModel)
        assertEquals("text-embedding-3-small", models[ModelProvider.OPENAI].embeddingModel)
        assertEquals("gpt-4o", models[ModelProvider.OPENAI].visionModel)
        assertEquals("dall-e-3", models[ModelProvider.OPENAI].imageModel)

        // XAI
        assertEquals("", models[ModelProvider.XAI].utilityModel)
        assertEquals("grok-2-vision-latest", models[ModelProvider.XAI].visionModel)
        assertEquals("grok-2-vision-latest", models[ModelProvider.XAI].imageModel)
    }

    @Test
    fun `YAML round-trip preserves snake_case field values after updateField`() {
        // Mutate a field, then verify the in-memory value is correct
        AppConfig.updateField("models.ollama.embeddingModel", "mxbai-embed-large:latest")
        assertEquals("mxbai-embed-large:latest", AppConfig.models[ModelProvider.OLLAMA].embeddingModel)

        AppConfig.updateField("models.anthropic.utilityModel", "claude-haiku-4")
        assertEquals("claude-haiku-4", AppConfig.models[ModelProvider.ANTHROPIC].utilityModel)
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
    fun `updateModelsField should handle all provider vision models`() {
        val config = ModelsConfig()

        // Test OpenAI
        var updated = updateModelsFieldHelper(config, "openai.visionModel", "gpt-4-vision-preview")
        assertEquals("gpt-4-vision-preview", updated.openai.visionModel)

        // Test Anthropic
        updated = updateModelsFieldHelper(config, "anthropic.visionModel", "claude-3-opus-20240229")
        assertEquals("claude-3-opus-20240229", updated.anthropic.visionModel)

        // Test Gemini
        updated = updateModelsFieldHelper(config, "gemini.visionModel", "gemini-pro-vision")
        assertEquals("gemini-pro-vision", updated.gemini.visionModel)

        // Test XAI
        updated = updateModelsFieldHelper(config, "xai.visionModel", "grok-vision-beta")
        assertEquals("grok-vision-beta", updated.xai.visionModel)

        // Test Ollama
        updated = updateModelsFieldHelper(config, "ollama.visionModel", "llava:13b")
        assertEquals("llava:13b", updated.ollama.visionModel)

        // Test Docker
        updated = updateModelsFieldHelper(config, "docker.visionModel", "llava:latest")
        assertEquals("llava:latest", updated.docker.visionModel)

        // Test LocalAI
        updated = updateModelsFieldHelper(config, "localai.visionModel", "bakllava")
        assertEquals("bakllava", updated.localai.visionModel)

        // Test LMStudio
        updated = updateModelsFieldHelper(config, "lmstudio.visionModel", "llava-v1.6-mistral-7b")
        assertEquals("llava-v1.6-mistral-7b", updated.lmstudio.visionModel)
    }

    @Test
    fun `updateModelsField should handle embedding models`() {
        val config = ModelsConfig()

        // Test OpenAI
        var updated = updateModelsFieldHelper(config, "openai.embeddingModel", "text-embedding-3-large")
        assertEquals("text-embedding-3-large", updated.openai.embeddingModel)

        // Test Gemini
        updated = updateModelsFieldHelper(config, "gemini.embeddingModel", "text-embedding-004")
        assertEquals("text-embedding-004", updated.gemini.embeddingModel)

        // Test Ollama
        updated = updateModelsFieldHelper(config, "ollama.embeddingModel", "mxbai-embed-large")
        assertEquals("mxbai-embed-large", updated.ollama.embeddingModel)
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

        // Test top-level fields
        config = updateChatFieldHelper(config, "maxTokens", 10000)
        assertEquals(10000, config.maxTokens)

        config = updateChatFieldHelper(config, "summarizationThreshold", 0.8)
        assertEquals(0.8, config.summarizationThreshold, 0.001)

        config = updateChatFieldHelper(config, "enableAsyncSummarization", false)
        assertFalse(config.enableAsyncSummarization)

        // Verify all fields are correct after multiple updates
        assertEquals(10000, config.maxTokens)
        assertEquals(0.8, config.summarizationThreshold, 0.001)
        assertFalse(config.enableAsyncSummarization)
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
        val originalEmbeddingModel = AppConfig.models[ModelProvider.OLLAMA].embeddingModel

        val instance = ProviderInstance.create(
            displayName = "OpenAI",
            providerType = ModelProvider.OPENAI,
        )
        val params = AppContextParams(
            currentInstanceId = instance.id,
            providerInstances = mutableListOf(instance),
        )

        AppConfig.saveContext(params)

        assertEquals(originalEmbeddingModel, AppConfig.models[ModelProvider.OLLAMA].embeddingModel)
    }

    @Test
    fun `saveContext overwrites a previously saved context`() {
        val openAiInstance = ProviderInstance.create(
            displayName = "OpenAI",
            providerType = ModelProvider.OPENAI,
            settings = OpenAiSettings(defaultModel = "gpt-4o"),
        )
        val firstParams = AppContextParams(
            currentInstanceId = openAiInstance.id,
            providerInstances = mutableListOf(openAiInstance),
        )
        AppConfig.saveContext(firstParams)
        assertEquals(ModelProvider.OPENAI, AppConfig.context.activeProviderType)

        val geminiInstance = ProviderInstance.create(
            displayName = "Gemini",
            providerType = ModelProvider.GEMINI,
        )
        val secondParams = AppContextParams(
            currentInstanceId = geminiInstance.id,
            providerInstances = mutableListOf(geminiInstance),
        )
        AppConfig.saveContext(secondParams)

        assertEquals(ModelProvider.GEMINI, AppConfig.context.activeProviderType)
    }
}
