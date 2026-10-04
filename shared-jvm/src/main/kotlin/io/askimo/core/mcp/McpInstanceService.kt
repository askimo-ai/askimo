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
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.mcp.client.DefaultMcpClient
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.McpInstancesChangedEvent
import io.askimo.core.event.system.ShellErrorEvent
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.intent.ToolApprovalPolicy
import io.askimo.core.intent.ToolCategory
import io.askimo.core.intent.ToolConfig
import io.askimo.core.intent.ToolSource
import io.askimo.core.intent.defaultApprovalPolicy
import io.askimo.core.logging.logger
import io.askimo.core.mcp.config.McpInstancesConfig
import io.askimo.core.mcp.config.McpServersConfig
import io.askimo.core.util.AskimoHome
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

private val log = logger<McpInstanceService>()

private data class ToolConfigData(
    val toolName: String,
    val instanceId: String,
    val category: String,
    val strategy: Int,
    val autoInferred: Boolean = true,
    val updatedAt: LocalDateTime = LocalDateTime.now(),
    /**
     * Explicit user-set approval policy. Null means "use the category default"
     * ([ToolCategory.defaultApprovalPolicy]).
     */
    val approvalPolicy: ToolApprovalPolicy? = null,
    /**
     * Whether this tool is included in the tool context forwarded to the AI.
     * Defaults to true so existing behavior is preserved for tools without an explicit setting.
     */
    val enabled: Boolean = true,
    /**
     * Whether this tool is selected for AI execution from chat input tool selection UI.
     * Defaults to true so enabled tools are selected unless the user explicitly unselects them.
     */
    val selected: Boolean = true,
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
     * Caches the result of [getGlobalTools] — the full tool-context list sent to the model.
     */
    @Volatile
    private var globalToolsCache: List<ToolConfig>? = null

    /**
     * Caches the result of [listActiveMcpServers] — the server/tool list shown in the chat
     * input "tools" popup. Built once and reused across every chat session's UI, instead of
     * each session's ChatInputField refetching tools from every MCP server on mount.
     */
    @Volatile
    private var activeServersCache: List<McpServerInfo>? = null

    /**
     * Maps tool name → the MCP client that serves it. A plain map suffices since the total
     * number of MCP tools is always small. Its lifetime must stay aligned with
     * [globalToolsCache] / [activeServersCache]: all three are only ever cleared together via
     * [invalidateCache], so a tool's client never disappears while it's still advertised.
     */
    private val mcpClientsByToolCache: MutableMap<String, DefaultMcpClient> = ConcurrentHashMap()

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

    // ── Tool resolution ──────────────────────────────────────────────────────

    private fun getEnabledInstances(): List<McpInstance> = getInstances().filter { it.enabled }

    private fun inferToolCategory(toolSpec: ToolSpecification): ToolCategory = mcpClientFactory.inferToolCategory(toolSpec)

    private fun inferToolStrategy(toolSpec: ToolSpecification): Int = mcpClientFactory.inferToolStrategy(toolSpec)

    private suspend fun fetchToolsFromInstance(
        instance: McpInstance,
        userConfigs: Map<String, ToolConfigData> = emptyMap(),
        newlyInferredConfigs: MutableList<ToolConfigData> = mutableListOf(),
        filterDisabled: Boolean = false,
    ): Result<List<ToolConfig>> {
        val clientKey = "global_tools_${instance.id}"
        val mcpClient = mcpClientFactory.createMcpClient(instance, clientKey)
            .getOrElse { return Result.failure(it) }

        log.debug("Fetching tools from global MCP instance '${instance.name}'")
        val toolSpecs = mcpClient.listTools()

        toolSpecs.forEach { toolSpec ->
            mcpClientsByToolCache[toolSpec.name()] = mcpClient
        }

        log.debug("Fetched ${toolSpecs.size} tools from global instance '${instance.name}'")

        val allTools = toolSpecs.map { toolSpec ->
            val toolName = toolSpec.name()
            val compositeKey = "${instance.id}:$toolName"
            val userConfig = userConfigs[compositeKey]

            if (userConfig != null && !userConfig.autoInferred) {
                log.trace("Using user-customized config for tool '{}': {}, {}", toolName, userConfig.category, userConfig.strategy)
                val category = ToolCategory.valueOf(userConfig.category)
                ToolConfig(
                    specification = toolSpec,
                    category = category,
                    strategy = userConfig.strategy,
                    source = ToolSource.MCP_EXTERNAL,
                    serverId = instance.id,
                    approvalPolicy = userConfig.approvalPolicy ?: category.defaultApprovalPolicy(),
                    enabled = userConfig.enabled,
                    selected = userConfig.selected,
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
                    approvalPolicy = userConfig?.approvalPolicy ?: inferredCategory.defaultApprovalPolicy(),
                    enabled = userConfig?.enabled ?: true,
                    selected = userConfig?.selected ?: true,
                )
            }
        }

        return Result.success(if (filterDisabled) allTools.filter { it.enabled } else allTools)
    }

    suspend fun getGlobalTools(): Result<List<ToolConfig>> = runCatching {
        globalToolsCache?.let { cached ->
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
            val tools = fetchToolsFromInstance(instance, userConfigs, newlyInferredConfigs, filterDisabled = true)
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

        globalToolsCache = allTools
        log.debug("Cached {} global tools", allTools.size)

        allTools
    }

    /**
     * Returns the list of enabled global MCP servers with their active tools, for display in
     * the chat input "tools" popup. Cached (see [activeServersCache]) so repeated calls across
     * chat sessions are instant; the cache is only rebuilt after [invalidateCache] runs (i.e.
     * when instances or tool configs actually change).
     */
    suspend fun listActiveMcpServers(): Result<List<McpServerInfo>> = runCatching {
        activeServersCache?.let { cached ->
            log.debug("Returning cached active MCP servers ({} servers)", cached.size)
            return@runCatching cached
        }

        log.debug("Cache miss for active MCP servers, fetching from MCP servers")

        val userConfigs = loadToolConfigs()
        val servers = getEnabledInstances().map { instance ->
            val tools = fetchToolsFromInstance(instance, userConfigs, filterDisabled = true)
                .getOrElse { e ->
                    log.error("Error loading tools for global server ${instance.name}", e)
                    EventBus.emit(
                        ShellErrorEvent(
                            title = "MCP Tool Error",
                            errorMessage = LocalizationManager.getString(
                                "error.app.message",
                                e.message ?: instance.name,
                            ),
                            cause = e,
                        ),
                    )
                    emptyList()
                }
            McpServerInfo(name = instance.name, id = instance.id, isGlobal = true, tools = tools)
        }

        activeServersCache = servers
        log.debug("Cached {} active MCP servers", servers.size)

        servers
    }

    suspend fun listTools(instanceId: String): Result<List<ToolConfig>> {
        val instance = getInstance(instanceId)
            ?: return Result.failure(IllegalArgumentException("Instance not found: $instanceId"))
        val userConfigs = loadToolConfigs()
        return fetchToolsFromInstance(instance, userConfigs)
    }

    /**
     * Returns only the tools on this instance that are enabled for AI use — i.e. excludes
     * any tool the user has explicitly disabled via [setToolEnabled]. This is the source of
     * truth for "what tools does the AI actually see for this instance", used both when
     * assembling the tool context sent to the model ([getGlobalTools]) and by any UI surface
     * (e.g. the chat input tools popup) that needs to reflect the same active set.
     */
    suspend fun listActiveTools(instanceId: String): Result<List<ToolConfig>> {
        val instance = getInstance(instanceId)
            ?: return Result.failure(IllegalArgumentException("Instance not found: $instanceId"))
        val userConfigs = loadToolConfigs()
        return fetchToolsFromInstance(instance, userConfigs, filterDisabled = true)
    }

    /**
     * Persists a user-defined [ToolApprovalPolicy] for a specific tool on an instance.
     * Invalidates the global tools cache so the change takes effect immediately.
     */
    fun setToolApproval(instanceId: String, toolName: String, policy: ToolApprovalPolicy) {
        upsertToolConfig(instanceId, toolName) {
            copy(
                approvalPolicy = policy,
                autoInferred = false,
                updatedAt = LocalDateTime.now(),
            )
        }
        log.debug("Set approval policy for tool '{}' on instance '{}': {}", toolName, instanceId, policy)
    }

    /**
     * Persists whether a specific tool on an instance is included in the tool context
     * forwarded to the AI. Disabled tools remain visible in the UI but are excluded from
     * model requests. Invalidates the global tools cache so the change takes effect immediately.
     */
    fun setToolEnabled(instanceId: String, toolName: String, enabled: Boolean) {
        upsertToolConfig(instanceId, toolName) {
            copy(
                enabled = enabled,
                // Enabling a tool also makes it selected by default in chat tool picker.
                selected = if (enabled) true else selected,
                autoInferred = false,
                updatedAt = LocalDateTime.now(),
            )
        }
        log.debug("Set enabled={} for tool '{}' on instance '{}'", enabled, toolName, instanceId)
    }

    /**
     * Persists whether a specific enabled tool is selected by the user for chat execution.
     * Disabled tools are excluded regardless of this value.
     */
    fun setToolSelected(instanceId: String, toolName: String, selected: Boolean) {
        upsertToolConfig(instanceId, toolName) {
            copy(
                selected = selected,
                autoInferred = false,
                updatedAt = LocalDateTime.now(),
            )
        }
        log.debug("Set selected={} for tool '{}' on instance '{}'", selected, toolName, instanceId)
    }

    /**
     * Loads the current tool config map, applies [update] to the existing entry for
     * `instanceId:toolName` (or a freshly-inferred default if none exists yet), persists the
     * result, and invalidates the tools cache. Shared by [setToolApproval] and [setToolEnabled]
     * to avoid duplicating the load/default/copy/save/invalidate sequence.
     */
    private fun upsertToolConfig(
        instanceId: String,
        toolName: String,
        update: ToolConfigData.() -> ToolConfigData,
    ) {
        val configs = loadToolConfigs().toMutableMap()
        val key = "$instanceId:$toolName"
        val existing = configs[key] ?: ToolConfigData(
            toolName = toolName,
            instanceId = instanceId,
            category = ToolCategory.OTHER.name,
            strategy = io.askimo.core.intent.ToolStrategy.INTENT_BASED,
            autoInferred = true,
        )
        configs[key] = existing.update()
        saveGlobalToolConfigs(configs)
        invalidateCache()
    }

    fun getMcpClientForTool(toolName: String): DefaultMcpClient? = mcpClientsByToolCache[toolName]

    fun invalidateCache() {
        globalToolsCache = null
        activeServersCache = null
        mcpClientsByToolCache.clear()
        log.debug("Invalidated global MCP tools, vector index, active servers, and client caches")
        EventBus.post(McpInstancesChangedEvent())
    }
}
