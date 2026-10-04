/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.mcp.config

import io.askimo.core.logging.displayError
import io.askimo.core.logging.logger
import io.askimo.core.mcp.McpInstance
import io.askimo.core.mcp.McpInstanceData
import io.askimo.core.security.SecureKeyManager
import io.askimo.core.util.AskimoHome
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

private object GlobalMcpInstancesConfigObject
private val log = logger<GlobalMcpInstancesConfigObject>()

/**
 * Global MCP instances (available to Universal Chat).
 *
 * Stored at: ~/.askimo/<profile>/mcp-instances.yml
 */
object McpInstancesConfig {

    private const val CONFIG_FILE_NAME = "mcp-instances.yml"

    private fun getConfigPath(): Path = AskimoHome.base().resolve(CONFIG_FILE_NAME)

    fun load(): List<McpInstance> {
        val path = getConfigPath()
        if (!path.exists()) return emptyList()

        return try {
            val content = Files.readString(path)
            val wrapper = mcpObjectMapper.readValue(content, InstancesWrapper::class.java)
            wrapper.instances.map { it.toDomain() }
        } catch (e: Exception) {
            displayError("Failed to load global MCP instances", e)
            emptyList()
        }
    }

    fun save(instances: List<McpInstance>) {
        val path = getConfigPath()

        try {
            path.parent.createDirectories()

            val data = instances.map { instance ->
                val definition = McpServersConfig.get(instance.serverId)
                McpInstanceData.from(instance, definition)
            }

            val wrapper = InstancesWrapper(data)
            val yaml = mcpObjectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(wrapper)
            Files.writeString(path, yaml)
            log.debug("Saved ${instances.size} global MCP instances")
        } catch (e: Exception) {
            displayError("Failed to save global MCP instances", e)
        }
    }

    fun add(instance: McpInstance) {
        val instances = load().toMutableList()
        instances.removeIf { it.id == instance.id }
        instances.add(instance)
        save(instances)
    }

    fun get(instanceId: String): McpInstance? = load().find { it.id == instanceId }

    fun remove(instanceId: String) {
        val path = getConfigPath()

        // Clean up secrets for this instance first
        if (path.exists()) {
            try {
                val content = Files.readString(path)
                val wrapper = mcpObjectMapper.readValue(content, InstancesWrapper::class.java)
                wrapper.instances
                    .find { it.id == instanceId }
                    ?.secretParameterKeys
                    ?.forEach { key ->
                        SecureKeyManager.removeSecretKey(McpInstanceData.secretKeyId(instanceId, key))
                    }
            } catch (e: Exception) {
                displayError("Failed to clean up secrets for global instance $instanceId", e)
            }
        }

        save(load().filterNot { it.id == instanceId })
    }
}
