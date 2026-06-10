/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import io.askimo.core.event.EventBus
import io.askimo.core.event.system.InvalidateCacheEvent
import io.askimo.core.logging.logger
import io.askimo.core.util.AskimoHome
import io.askimo.core.util.JsonUtils
import io.askimo.core.util.JsonUtils.prettyJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

/**
 * Tracks learned model capabilities with file-based persistence.
 * Model capabilities are learned at runtime and stored separately from user configuration.
 *
 * Cache file location: ~/.askimo/model-capabilities-cache.json
 */
object ModelCapabilitiesCache {
    private val cache = ConcurrentHashMap<String, ModelCapabilities>()
    private val cacheFile: Path = AskimoHome.base().resolve("model-capabilities-cache.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Binary search reduction - halve the context size each time for fast convergence
    private const val REDUCTION_FACTOR = 0.5

    // Provider-specific defaults
    private val PROVIDER_DEFAULTS: Map<ModelProvider, ModelCapabilities> = mapOf(
        ModelProvider.ANTHROPIC to ModelCapabilities(
            contextSize = 262_144,
            supportsTools = null, // Not tested yet
            supportsVision = null,
            supportsStreaming = true,
            reasoningLevel = ReasoningEffort.MEDIUM,
        ),
        ModelProvider.GEMINI to ModelCapabilities(
            contextSize = 1_048_576,
            supportsTools = null,
            supportsVision = null,
            supportsStreaming = true,
            reasoningLevel = ReasoningEffort.MEDIUM,
        ),
        ModelProvider.OPENAI to ModelCapabilities(
            contextSize = 262_144,
            supportsTools = null,
            supportsVision = null,
            supportsStreaming = true,
            reasoningLevel = ReasoningEffort.MEDIUM,
        ),
        ModelProvider.XAI to ModelCapabilities(
            contextSize = 262_144,
            supportsTools = null,
            supportsVision = null,
            supportsStreaming = true,
            reasoningLevel = ReasoningEffort.MEDIUM,
        ),
        ModelProvider.OLLAMA to ModelCapabilities(
            contextSize = 262_144,
            supportsTools = null,
            supportsVision = null,
            supportsStreaming = true,
            reasoningLevel = ReasoningEffort.MEDIUM,
        ),
        ModelProvider.DOCKER to ModelCapabilities(
            contextSize = 262_144,
            supportsTools = null,
            supportsVision = null,
            supportsStreaming = true,
            reasoningLevel = ReasoningEffort.MEDIUM,
        ),
        ModelProvider.LOCALAI to ModelCapabilities(
            contextSize = 262_144,
            supportsTools = null,
            supportsVision = null,
            supportsStreaming = true,
            reasoningLevel = ReasoningEffort.MEDIUM,
        ),
        ModelProvider.LMSTUDIO to ModelCapabilities(
            contextSize = 262_144,
            supportsTools = null,
            supportsVision = null,
            supportsStreaming = true,
            reasoningLevel = ReasoningEffort.MEDIUM,
        ),
    )

    // Fallback default for unknown providers
    private val FALLBACK_DEFAULT = ModelCapabilities(
        contextSize = 131_072,
        supportsTools = null,
        supportsVision = null,
        supportsStreaming = true,
        reasoningLevel = ReasoningEffort.MEDIUM,
    )

    private val log = logger<ModelCapabilitiesCache>()

    init {
        loadFromFile()

        // Listen for cache invalidation events
        scope.launch {
            EventBus.internalEvents.collect { event ->
                if (event is InvalidateCacheEvent) {
                    invalidateCache()
                }
            }
        }
    }

    /**
     * Get the capabilities for a given model key.
     * Returns cached capabilities if available, otherwise returns provider-specific default.
     *
     * @param modelKey The model key in format "provider:model"
     * @return The model capabilities
     */
    fun get(modelKey: String): ModelCapabilities {
        // Check cache first
        cache[modelKey]?.let { return it }

        // Extract provider from modelKey
        val provider = modelKey.substringBefore(":")
            .let { providerKey ->
                ModelProvider.entries.find { it.providerKey() == providerKey }
            }

        return PROVIDER_DEFAULTS[provider] ?: FALLBACK_DEFAULT
    }

    /**
     * Update specific capabilities for a model.
     *
     * @param modelKey The model key in format "provider:model"
     * @param update Lambda to modify capabilities
     */
    fun update(modelKey: String, update: (ModelCapabilities) -> ModelCapabilities) {
        val current = get(modelKey)
        val updated = update(current)
        cache[modelKey] = updated
        saveToFile()
    }

    /**
     * Reduce the context size using binary search (halve the size).
     *
     * @param modelKey The model key in format "provider:model"
     * @param currentSize The current context size that was exceeded
     * @return The new reduced context size
     */
    fun reduceContextSize(modelKey: String, currentSize: Int): Int {
        val newSize = (currentSize * REDUCTION_FACTOR).toInt().coerceAtLeast(4096)

        update(modelKey) { it.copy(contextSize = newSize) }

        log.info("Reduced context size for $modelKey: $currentSize → $newSize tokens (binary search)")
        return newSize
    }

    /**
     * Mark that a model supports tools.
     */
    fun markSupportsTools(modelKey: String, supports: Boolean = true) {
        update(modelKey) { it.copy(supportsTools = supports) }
        log.info("Updated tool support for $modelKey: $supports")
    }

    /**
     * Mark that a model supports vision.
     */
    fun markSupportsVision(modelKey: String, supports: Boolean = true) {
        update(modelKey) { it.copy(supportsVision = supports) }
        log.info("Updated vision support for $modelKey: $supports")
    }

    /**
     * Check if a model supports tool calling.
     * Returns false if not yet tested (null), indicating the client should test.
     *
     * @param provider The model provider
     * @param model The model name/identifier
     * @return true if tested and supports tools, false if tested and doesn't support OR not yet tested
     */
    fun supportsTools(provider: ModelProvider, model: String): Boolean {
        val modelKey = modelKey(provider, model)
        return get(modelKey).supportsTools ?: false // null means not tested yet, return false
    }

    /**
     * Check if a model supports reasoning (alias for thinking support).
     *
     * Reasoning effort applies only when the model supports thinking/reasoning capabilities.
     */
    fun supportsReasoning(provider: ModelProvider, model: String): Boolean = supportsThinking(provider, model)

    /**
     * Check if tool support has been tested for a model.
     *
     * @param provider The model provider
     * @param model The model name/identifier
     * @return true if tested (value is true or false), false if not yet tested (null)
     */
    fun hasTestedToolSupport(provider: ModelProvider, model: String): Boolean {
        val modelKey = modelKey(provider, model)
        return get(modelKey).supportsTools != null
    }

    /**
     * Check if reasoning support has been tested (alias for thinking support test state).
     */
    fun hasTestedReasoningSupport(provider: ModelProvider, model: String): Boolean = hasTestedThinkingSupport(provider, model)

    /**
     * Check if a model supports sampling parameters (temperature, topP).
     *
     * @param provider The model provider
     * @param model The model name/identifier
     * @return true if the model supports sampling parameters, false otherwise
     */
    fun supportsSampling(provider: ModelProvider, model: String): Boolean {
        val modelKey = modelKey(provider, model)
        return get(modelKey).supportsSampling
    }

    /**
     * Check if a model supports thinking (extended reasoning).
     * Returns false if not yet tested (null).
     *
     * @param provider The model provider
     * @param model The model name/identifier
     * @return true if tested and supports thinking, false if tested and doesn't support OR not yet tested
     */
    fun supportsThinking(provider: ModelProvider, model: String): Boolean {
        val modelKey = modelKey(provider, model)
        return get(modelKey).supportsThinking ?: false // null means not tested yet, return false
    }

    /**
     * Check if thinking support has been tested for a model.
     *
     * @param provider The model provider
     * @param model The model name/identifier
     * @return true if tested (value is true or false), false if not yet tested (null)
     */
    fun hasTestedThinkingSupport(provider: ModelProvider, model: String): Boolean {
        val modelKey = modelKey(provider, model)
        return get(modelKey).supportsThinking != null
    }

    /**
     * Update the cache with tool support information.
     * Typically called after:
     * - Successfully using tools with a model (supported = true)
     * - Getting API error about unsupported tools (supported = false)
     *
     * @param provider The model provider
     * @param model The model name/identifier
     * @param supported Whether the model supports tool calling
     */
    fun setToolSupport(provider: ModelProvider, model: String, supported: Boolean) {
        if (model.isBlank()) {
            log.warn("Cannot set tool support for empty model name (provider: ${provider.providerKey()})")
            return
        }
        val modelKey = modelKey(provider, model)
        update(modelKey) { it.copy(supportsTools = supported) }
        log.debug("Updated tool support for $modelKey: $supported")
    }

    /**
     * Update the cache with sampling support information.
     * Typically called after:
     * - Successfully using sampling params with a model (supported = true)
     * - Getting API error about unsupported params (supported = false)
     *
     * @param provider The model provider
     * @param model The model name/identifier
     * @param supported Whether the model supports sampling parameters
     */
    fun setSamplingSupport(provider: ModelProvider, model: String, supported: Boolean) {
        if (model.isBlank()) {
            log.warn("Cannot set sampling support for empty model name (provider: ${provider.providerKey()})")
            return
        }
        val modelKey = modelKey(provider, model)
        update(modelKey) { it.copy(supportsSampling = supported) }
        log.debug("Updated sampling support for $modelKey: $supported")
    }

    /**
     * Update the cache with thinking support information.
     * Typically called after:
     * - Successfully using thinking with a model (supported = true)
     * - Getting API error about unsupported thinking (supported = false)
     *
     * @param provider The model provider
     * @param model The model name/identifier
     * @param supported Whether the model supports thinking
     */
    fun setThinkingSupport(provider: ModelProvider, model: String, supported: Boolean) {
        if (model.isBlank()) {
            log.warn("Cannot set thinking support for empty model name (provider: ${provider.providerKey()})")
            return
        }
        val modelKey = modelKey(provider, model)
        update(modelKey) { it.copy(supportsThinking = supported) }
        log.debug("Updated thinking support for $modelKey: $supported")
    }

    /**
     * Get the reasoning effort level for a model.
     * Returns the cached reasoning level, or MEDIUM if not yet set.
     *
     * @param provider The model provider
     * @param model The model name/identifier
     * @return The reasoning effort level
     */
    fun getReasoningLevel(provider: ModelProvider, model: String): ReasoningEffort {
        val modelKey = modelKey(provider, model)
        return get(modelKey).reasoningLevel
    }

    /**
     * Set the reasoning effort level for a model.
     * Typically called after user configuration or model capability detection.
     *
     * @param provider The model provider
     * @param model The model name/identifier
     * @param level The reasoning effort level to set
     */
    fun setReasoningLevel(provider: ModelProvider, model: String, level: ReasoningEffort) {
        if (model.isBlank()) {
            log.warn("Cannot set reasoning level for empty model name (provider: ${provider.providerKey()})")
            return
        }
        val modelKey = modelKey(provider, model)
        update(modelKey) { it.copy(reasoningLevel = level) }
        log.debug("Updated reasoning level for {}: {}", modelKey, level)
    }

    /**
     * Create a model key from provider and model name.
     *
     * @param provider The ModelProvider enum value
     * @param model The model name (e.g., "gpt-4", "llama3")
     * @return The model key in format "provider:model"
     */
    fun modelKey(provider: ModelProvider, model: String): String = "${provider.providerKey()}:$model"

    /**
     * Clear all cached sizes (useful for testing or reset).
     */
    fun clear() {
        cache.clear()
        saveToFile()
    }

    /**
     * Invalidate the cache by clearing all entries and deleting the cache file.
     * This is typically triggered by user action to reset cached model capabilities.
     */
    private fun invalidateCache() {
        try {
            cache.clear()
            cacheFile.deleteIfExists()
            log.info("Model capabilities cache invalidated and file deleted")
        } catch (e: Exception) {
            log.warn("Failed to invalidate model capabilities cache", e)
        }
    }

    private fun loadFromFile() {
        try {
            if (cacheFile.exists()) {
                val json = Files.readString(cacheFile)
                val cacheData = JsonUtils.json.decodeFromString<CacheData>(json)
                cache.putAll(cacheData.capabilities)
                log.info("Loaded ${cache.size} cached model capabilities from $cacheFile")
            } else {
                log.debug("No cached model capabilities found, using defaults")
            }
        } catch (e: Exception) {
            log.warn("Failed to load model capabilities cache from $cacheFile, using defaults", e)
        }
    }

    private fun saveToFile() {
        try {
            cacheFile.parent?.let { Files.createDirectories(it) }
            val data = CacheData(capabilities = cache.toMap())
            val json = prettyJson.encodeToString(data)
            Files.writeString(cacheFile, json)
            if (log.isDebugEnabled) {
                log.debug("Saved ${cache.size} model capabilities to $cacheFile")
            }
        } catch (e: Exception) {
            log.warn("Failed to save model capabilities cache to $cacheFile", e)
        }
    }

    @Serializable
    private data class CacheData(
        val capabilities: Map<String, ModelCapabilities>,
    )
}

/**
 * Represents the capabilities of a model that can be learned at runtime.
 *
 * Nullable Boolean fields mean:
 * - null: Not tested yet
 * - true: Tested and supported
 * - false: Tested and NOT supported
 */
@Serializable
data class ModelCapabilities(
    val contextSize: Int,
    val supportsTools: Boolean? = null, // null = not tested yet
    val supportsVision: Boolean? = null, // null = not tested yet
    val supportsStreaming: Boolean = true, // Non-nullable - always known
    val supportsSampling: Boolean = true, // Non-nullable - always known
    val supportsThinking: Boolean? = null, // null = not tested yet
    val reasoningLevel: ReasoningEffort = ReasoningEffort.MEDIUM, // Default reasoning effort
    // For future extensibility
    val customAttributes: Map<String, String> = emptyMap(),
)

/**
 * Extension function to detect if an exception is due to context length issues.
 * Checks for common error messages from various AI providers.
 */
fun Throwable.isContextLengthError(): Boolean {
    val message = this.message?.lowercase() ?: ""
    return (
        message.contains("context") && (
            message.contains("length") ||
                message.contains("limit") ||
                message.contains("exceeded") ||
                message.contains("too long") ||
                message.contains("maximum context") ||
                message.contains("token limit") ||
                message.contains("exceed")
            )
        ) || message.contains("413") // HTTP 413 Payload Too Large
}
