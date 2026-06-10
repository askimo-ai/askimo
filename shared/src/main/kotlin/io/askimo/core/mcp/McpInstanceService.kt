/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.mcp

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.mcp.client.DefaultMcpClient
import io.askimo.core.intent.ToolCategory
import io.askimo.core.intent.ToolConfig
import io.askimo.core.intent.ToolSource
import io.askimo.core.intent.ToolVectorIndex
import io.askimo.core.logging.logger
import io.askimo.core.mcp.config.McpInstancesConfig
import io.askimo.core.mcp.config.McpServersConfig
import io.askimo.core.util.AskimoHome
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

private val log = logger<McpInstanceService>()

const val GLOBAL_MCP_SCOPE_ID: String = "__global__"

private data class ToolConfigData(
    val toolName: String,
    val instanceId: String,
    val category: String,
    val strategy: Int,
    val autoInferred: Boolean = true,
    val updatedAt: LocalDateTime = LocalDateTime.now(),
)

private data class GlobalToolsConfigWrapper(val tools: List<ToolConfigData>)

private val toolConfigMapper: ObjectMapper = ObjectMapper(YAMLFactory())
    .registerModule(KotlinModule.Builder().build())
    .registerModule(JavaTimeModule())
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

private fun loadToolConfigs(): Map<String, ToolConfigData> {
    val path = AskimoHome.base().resolve("global-mcp-tools-config.yml")
    if (!path.exists()) return emptyMap()
    return try {
        val wrapper = toolConfigMapper.readValue(Files.readString(path), GlobalToolsConfigWrapper::class.java)
        wrapper.tools.associateBy { "${it.instanceId}:${it.toolName}" }
    } catch (e: Exception) {
        log.warn("Failed to load global MCP tool configs: {}", e.message)
        emptyMap()
    }
}

private fun saveGlobalToolConfigs(configs: Map<String, ToolConfigData>) {
    val path = AskimoHome.base().resolve("global-mcp-tools-config.yml")
    try {
        path.parent.createDirectories()
        val yaml = toolConfigMapper.writerWithDefaultPrettyPrinter()
            .writeValueAsString(GlobalToolsConfigWrapper(configs.values.toList()))
        Files.writeString(path, yaml)
    } catch (e: Exception) {
        log.warn("Failed to save global MCP tool configs: {}", e.message)
    }
}

// ────────────────────────────────────────────────────────────────────────────

/**
 * Global MCP instances are available in Universal Chat (not tied to a specific project).
 *
 * This service manages the lifecycle and tool resolution for globally-scoped MCP instances.
 */
class McpInstanceService(
    private val serversConfig: McpServersConfig = McpServersConfig,
    private val mcpClientFactory: McpClientFactory = McpClientFactory(),
) {

    private val instancesConfig = McpInstancesConfig

    /**
     * Ephemeral in-memory instances (e.g. org-managed MCP servers from the team server).
     * These are never persisted and are merged with disk-loaded instances at runtime.
     * Replaced atomically on every sync — older entries are dropped automatically.
     */
    @Volatile
    private var ephemeralInstances: List<McpInstance> = emptyList()

    /**
     * Replaces the current set of ephemeral instances.
     * Call this after fetching org-managed MCP servers from the team server.
     * Invalidates the tools cache so the new instances are picked up on the next request.
     */
    fun setEphemeralInstances(instances: List<McpInstance>) {
        ephemeralInstances = instances
        invalidateCache()
        log.debug("Registered {} ephemeral MCP instances", instances.size)
    }

    /** Remove all ephemeral instances (e.g. on logout). */
    fun clearEphemeralInstances() {
        ephemeralInstances = emptyList()
        invalidateCache()
        log.debug("Cleared ephemeral MCP instances")
    }

    private val globalToolsCache: Cache<String, List<ToolConfig>> = Caffeine.newBuilder()
        .maximumSize(200)
        .expireAfterWrite(30.minutes.toJavaDuration())
        .removalListener<String, List<ToolConfig>> { key, tools, cause ->
            if (tools != null && key != null) {
                log.debug("Evicting global tools cache (cause: {}, {} tools)", cause, tools.size)
            }
        }
        .build()

    private val toolVectorIndexCache: Cache<String, ToolVectorIndex> = Caffeine.newBuilder()
        .maximumSize(10)
        .expireAfterWrite(30.minutes.toJavaDuration())
        .build()

    private val mcpClientsByToolCache: Cache<String, DefaultMcpClient> = Caffeine.newBuilder()
        .maximumSize(200)
        .expireAfterWrite(30.minutes.toJavaDuration())
        .build()

    // ── Instance management ──────────────────────────────────────────────────

    fun getInstances(): List<McpInstance> = instancesConfig.load() + ephemeralInstances

    fun getInstance(instanceId: String): McpInstance? = instancesConfig.get(instanceId) ?: ephemeralInstances.find { it.id == instanceId }

    fun createInstance(
        serverId: String,
        name: String,
        parameterValues: Map<String, String>,
    ): Result<McpInstance> {
        return try {
            val definition = serversConfig.get(serverId)
                ?: return Result.failure(IllegalArgumentException("MCP server definition not found: $serverId"))

            val instance = McpInstance(
                id = UUID.randomUUID().toString(),
                serverId = serverId,
                name = name,
                parameterValues = parameterValues,
                enabled = true,
                createdAt = LocalDateTime.now(),
                updatedAt = LocalDateTime.now(),
            )

            instance.toConnector(definition) // validate

            instancesConfig.add(instance)
            invalidateCache()

            log.debug("Created global MCP instance '${instance.name}' (${instance.id})")
            Result.success(instance)
        } catch (e: Exception) {
            log.warn("Failed to create global MCP instance: ${e.message}")
            Result.failure(e)
        }
    }

    fun updateInstance(
        instanceId: String,
        name: String? = null,
        parameterValues: Map<String, String>? = null,
        enabled: Boolean? = null,
    ): Result<McpInstance> {
        return try {
            val existing = getInstance(instanceId)
                ?: return Result.failure(IllegalArgumentException("Instance not found: $instanceId"))

            val updated = existing.copy(
                name = name ?: existing.name,
                parameterValues = parameterValues ?: existing.parameterValues,
                enabled = enabled ?: existing.enabled,
                updatedAt = LocalDateTime.now(),
            )

            if (parameterValues != null) {
                val definition = serversConfig.get(updated.serverId)
                    ?: return Result.failure(IllegalStateException("Server definition not found: ${updated.serverId}"))
                updated.toConnector(definition) // validate
            }

            instancesConfig.add(updated)
            invalidateCache()

            log.debug("Updated global MCP instance '${updated.name}' (${updated.id})")
            Result.success(updated)
        } catch (e: Exception) {
            log.warn("Failed to update global MCP instance: ${e.message}")
            Result.failure(e)
        }
    }

    fun deleteInstance(instanceId: String): Result<Unit> {
        return try {
            val instance = getInstance(instanceId)
                ?: return Result.failure(IllegalArgumentException("Instance not found: $instanceId"))

            instancesConfig.remove(instanceId)

            // Also remove the associated server definition with "global" tag from mcp-servers.yml
            val serverDef = McpServersConfig.get(instance.serverId)
            if (serverDef != null && serverDef.tags.contains("global")) {
                McpServersConfig.remove(instance.serverId)
                log.debug("Removed global server definition '${instance.serverId}' for instance '${instance.name}'")
            }

            invalidateCache()

            log.debug("Deleted global MCP instance '${instance.name}' (${instance.id})")
            Result.success(Unit)
        } catch (e: Exception) {
            log.warn("Failed to delete global MCP instance: ${e.message}", e)
            Result.failure(e)
        }
    }

    // ── Tool resolution ──────────────────────────────────────────────────────

    private fun getEnabledInstances(): List<McpInstance> = getInstances().filter { it.enabled }

    private fun inferToolCategory(toolSpec: ToolSpecification): ToolCategory = mcpClientFactory.inferToolCategory(toolSpec)

    private fun inferToolStrategy(toolSpec: ToolSpecification): Int = mcpClientFactory.inferToolStrategy(toolSpec)

    private suspend fun fetchToolsFromInstance(
        instance: McpInstance,
        userConfigs: Map<String, ToolConfigData> = emptyMap(),
        newlyInferredConfigs: MutableList<ToolConfigData> = mutableListOf(),
    ): Result<List<ToolConfig>> {
        val clientKey = "global_tools_${instance.id}"
        val mcpClient = mcpClientFactory.createMcpClient(instance, clientKey)
            .getOrElse { return Result.failure(it) }

        log.debug("Fetching tools from global MCP instance '${instance.name}'")
        val toolSpecs = mcpClient.listTools()

        toolSpecs.forEach { toolSpec ->
            mcpClientsByToolCache.put(toolSpec.name(), mcpClient)
        }

        log.debug("Fetched ${toolSpecs.size} tools from global instance '${instance.name}'")

        return Result.success(
            toolSpecs.map { toolSpec ->
                val toolName = toolSpec.name()
                val compositeKey = "${instance.id}:$toolName"
                val userConfig = userConfigs[compositeKey]

                if (userConfig != null && !userConfig.autoInferred) {
                    log.trace("Using user-customized config for tool '{}': {}, {}", toolName, userConfig.category, userConfig.strategy)
                    ToolConfig(
                        specification = toolSpec,
                        category = ToolCategory.valueOf(userConfig.category),
                        strategy = userConfig.strategy,
                        source = ToolSource.MCP_EXTERNAL,
                        serverId = instance.id,
                    )
                } else {
                    val inferredCategory = inferToolCategory(toolSpec)
                    val inferredStrategy = inferToolStrategy(toolSpec)
                    log.trace("Auto-inferred global tool '{}': {}, {}", toolName, inferredCategory, inferredStrategy)

                    if (userConfig == null) {
                        newlyInferredConfigs.add(
                            ToolConfigData(
                                toolName = toolName,
                                instanceId = instance.id,
                                category = inferredCategory.name,
                                strategy = inferredStrategy,
                                autoInferred = true,
                            ),
                        )
                    }

                    ToolConfig(
                        specification = toolSpec,
                        category = inferredCategory,
                        strategy = inferredStrategy,
                        source = ToolSource.MCP_EXTERNAL,
                        serverId = instance.id,
                    )
                }
            },
        )
    }

    suspend fun getGlobalTools(): Result<List<ToolConfig>> = runCatching {
        val cached = globalToolsCache.getIfPresent(GLOBAL_MCP_SCOPE_ID)
        if (cached != null) {
            log.debug("Returning cached global tools ({} tools)", cached.size)
            return@runCatching cached
        }

        log.debug("Cache miss for global tools, fetching from MCP servers")

        val instances = getEnabledInstances()
        if (instances.isEmpty()) {
            log.debug("No active global MCP instances, returning empty list")
            return@runCatching emptyList()
        }

        val userConfigs = loadToolConfigs()
        val newlyInferredConfigs = mutableListOf<ToolConfigData>()
        val allTools = mutableListOf<ToolConfig>()

        instances.forEach { instance ->
            val tools = fetchToolsFromInstance(instance, userConfigs, newlyInferredConfigs)
                .getOrElse { e ->
                    log.warn("Skipping global instance '${instance.name}': ${e.message}")
                    return@forEach
                }
            allTools.addAll(tools)
        }

        if (newlyInferredConfigs.isNotEmpty()) {
            val updatedConfigs = userConfigs.toMutableMap()
            newlyInferredConfigs.forEach { config ->
                updatedConfigs["${config.instanceId}:${config.toolName}"] = config
            }
            saveGlobalToolConfigs(updatedConfigs)
            log.debug("Persisted {} newly auto-inferred global tool configs", newlyInferredConfigs.size)
        }

        globalToolsCache.put(GLOBAL_MCP_SCOPE_ID, allTools)
        log.debug("Cached {} global tools", allTools.size)

        allTools
    }

    suspend fun listTools(instanceId: String): Result<List<ToolConfig>> {
        val instance = getInstance(instanceId)
            ?: return Result.failure(IllegalArgumentException("Instance not found: $instanceId"))
        return fetchToolsFromInstance(instance)
    }

    fun getMcpClientForTool(toolName: String): DefaultMcpClient? = mcpClientsByToolCache.getIfPresent(toolName)

    fun invalidateCache() {
        globalToolsCache.invalidate(GLOBAL_MCP_SCOPE_ID)
        toolVectorIndexCache.invalidate(GLOBAL_MCP_SCOPE_ID)
        mcpClientsByToolCache.invalidateAll()
        log.debug("Invalidated global MCP tools, vector index, and client caches")
    }
}
