/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.mcp.config

import com.fasterxml.jackson.module.kotlin.readValue
import io.askimo.core.logging.displayError
import io.askimo.core.logging.logger
import io.askimo.core.mcp.McpServerDefinition
import io.askimo.core.mcp.ServerDefinitionSecretManager
import io.askimo.core.mcp.TemplateResolver
import io.askimo.core.mcp.TransportType
import io.askimo.core.mcp.ValidationResult
import io.askimo.core.util.AskimoHome
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

private object McpServersConfigObject
private val log = logger<McpServersConfigObject>()

/**
 * MCP Servers configuration that persists to YAML file.
 * Similar to AppConfig pattern, but for MCP server definitions.
 */
object McpServersConfig {

    @Volatile
    private var cached: List<McpServerDefinition>? = null

    /** Ephemeral in-memory definitions (e.g. org-managed MCP servers from the team server).
     *  These are never persisted to disk and take precedence over disk-loaded definitions
     *  with the same ID. Replaced atomically on every sync. */
    @Volatile
    private var ephemeral: Map<String, McpServerDefinition> = emptyMap()

    private const val MCP_CONFIG_FILE = "mcp-servers.yml"

    /**
     * Register a set of ephemeral (in-memory only) server definitions.
     * Any previously registered ephemeral definition not present in [definitions] is removed.
     */
    fun setEphemeral(definitions: List<McpServerDefinition>) {
        ephemeral = definitions.associateBy { it.id }
        log.debug("Registered {} ephemeral MCP server definitions", definitions.size)
    }

    /** Remove all ephemeral server definitions (e.g. on logout). */
    fun clearEphemeral() {
        ephemeral = emptyMap()
        log.debug("Cleared ephemeral MCP server definitions")
    }

    /**
     * Get all registered MCP server definitions
     */
    fun getAll(): List<McpServerDefinition> {
        val disk = cached ?: loadFromDisk()
        val ep = ephemeral
        if (ep.isEmpty()) return disk
        // Merge: ephemeral wins on ID collision
        return disk.filter { it.id !in ep } + ep.values
    }

    /**
     * Get a specific MCP server definition by ID
     */
    fun get(id: String): McpServerDefinition? = ephemeral[id] ?: (cached ?: loadFromDisk()).find { it.id == id }

    /**
     * Add a new MCP server definition
     */
    fun add(definition: McpServerDefinition) {
        synchronized(this) {
            val current = getAll().toMutableList()

            // Remove if already exists (update)
            current.removeIf { it.id == definition.id }

            // Add new definition
            current.add(definition)

            // Update cache and persist
            cached = current
            persist(current)
        }
    }

    /**
     * Remove an MCP server definition
     */
    fun remove(id: String) {
        synchronized(this) {
            val current = getAll().toMutableList()
            val removed = current.find { it.id == id }
            if (current.removeIf { it.id == id }) {
                cached = current
                persist(current)

                // Clean up secrets for global instances
                if (removed?.tags?.contains("global") == true) {
                    ServerDefinitionSecretManager.cleanupSecrets(id)
                }
            }
        }
    }

    /**
     * Update an existing MCP server definition
     */
    fun update(definition: McpServerDefinition) {
        add(definition)
    }

    /**
     * Search server definitions by name or description
     */
    fun search(query: String): List<McpServerDefinition> {
        val lowerQuery = query.lowercase()
        return getAll().filter {
            it.name.lowercase().contains(lowerQuery) ||
                it.description.lowercase().contains(lowerQuery) ||
                it.tags.any { tag -> tag.lowercase().contains(lowerQuery) }
        }
    }

    /**
     * Validate a server definition
     */
    fun validate(definition: McpServerDefinition): ValidationResult {
        val errors = mutableListOf<String>()

        // Validate ID
        if (definition.id.isBlank()) {
            errors.add("Server definition ID cannot be blank")
        }

        // Validate name
        if (definition.name.isBlank()) {
            errors.add("Server definition name cannot be blank")
        }

        // Validate transport-specific config
        if (definition.transportType == TransportType.STDIO) {
            if (definition.stdioConfig == null) {
                errors.add("STDIO transport requires stdioConfig")
            } else {
                if (definition.stdioConfig.commandTemplate.isEmpty()) {
                    errors.add("STDIO commandTemplate cannot be empty")
                }
                // Validate command templates
                definition.stdioConfig.commandTemplate.forEach { template ->
                    val result = TemplateResolver.validate(template)
                    if (!result.isValid) {
                        errors.addAll(result.errors.map { "Command template error: $it" })
                    }
                }
            }
        }

        if (definition.transportType == TransportType.HTTP) {
            if (definition.httpConfig == null) {
                errors.add("HTTP transport requires httpConfig")
            } else {
                if (definition.httpConfig.urlTemplate.isBlank()) {
                    errors.add("HTTP url cannot be blank")
                } else {
                    val result = TemplateResolver.validate(definition.httpConfig.urlTemplate)
                    if (!result.isValid) {
                        errors.addAll(result.errors.map { "URL error: $it" })
                    }
                }
                definition.httpConfig.headersTemplate.forEach { (key, valueTemplate) ->
                    val result = TemplateResolver.validate(valueTemplate)
                    if (!result.isValid) {
                        errors.addAll(result.errors.map { "Header '$key' error: $it" })
                    }
                }
                if (definition.httpConfig.timeoutMs <= 0) {
                    errors.add("HTTP timeoutMs must be positive")
                }
            }
        }

        // Just basic validation - MCP server will validate the actual parameters
        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
        )
    }

    /**
     * Get count of registered definitions
     */
    fun count(): Int = getAll().size

    private fun loadFromDisk(): List<McpServerDefinition> {
        synchronized(this) {
            val path = resolveConfigPath()

            return if (path.exists()) {
                try {
                    val content = Files.readString(path)
                    val wrapper: McpServersWrapper = mcpObjectMapper.readValue(content)
                    cached = wrapper.servers
                    log.debug("Loaded {} MCP server definitions from {}", wrapper.servers.size, path)
                    wrapper.servers
                } catch (e: Exception) {
                    displayError("Failed to load MCP servers config, returning empty list", e)
                    cached = emptyList()
                    emptyList()
                }
            } else {
                log.debug("No MCP servers config found at {}", path)
                cached = emptyList()
                emptyList()
            }
        }
    }

    private fun persist(definitions: List<McpServerDefinition>) {
        val path = resolveConfigPath()

        try {
            path.parent?.createDirectories()
            val wrapper = McpServersWrapper(definitions)
            val yaml = mcpObjectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(wrapper)
            Files.writeString(path, yaml)
            log.debug("Persisted {} MCP server definitions to {}", definitions.size, path)
        } catch (e: Exception) {
            displayError("Failed to persist MCP servers config", e)
        }
    }

    private fun resolveConfigPath(): Path = AskimoHome.base().resolve(MCP_CONFIG_FILE)
}

/**
 * Wrapper for YAML serialization
 */
private data class McpServersWrapper(
    val servers: List<McpServerDefinition>,
)
