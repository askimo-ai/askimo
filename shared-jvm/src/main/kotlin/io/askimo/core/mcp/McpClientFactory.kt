/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.mcp

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.mcp.client.DefaultMcpClient
import dev.langchain4j.mcp.client.transport.McpTransport
import io.askimo.core.intent.ToolCategory
import io.askimo.core.intent.ToolStrategy
import io.askimo.core.logging.logger
import io.askimo.core.mcp.config.McpServersConfig
import kotlinx.coroutines.CancellationException

private val log = logger<McpClientFactory>()

/**
 * Low-level factory responsible for creating [DefaultMcpClient] instances and
 * listing/classifying tools from a [McpInstance].
 *
 * Registered as a Koin singleton so both services share the same instance.
 */
class McpClientFactory(
    private val serversConfig: McpServersConfig = McpServersConfig,
) {

    /**
     * Creates a [DefaultMcpClient] for the given [instance].
     *
     * Returns [Result.failure] with the root-cause exception intact so callers
     * can surface a meaningful message to the user instead of a generic error.
     */
    suspend fun createMcpClient(
        instance: McpInstance,
        clientKey: String,
    ): Result<DefaultMcpClient> {
        return try {
            val definition = serversConfig.get(instance.serverId)
                ?: return Result.failure(
                    IllegalStateException(
                        "Server definition not found for '${instance.name}' (serverId: ${instance.serverId}). " +
                            "The server may have been deleted.",
                    ),
                )

            val resolvedDefinition = ServerDefinitionSecretManager.resolveSecrets(definition)

            log.trace("Creating connector for instance '${instance.name}' (${instance.serverId})")
            val connector = instance.toConnector(resolvedDefinition)

            log.trace("Validating connector '${instance.name}'")
            val validationResult = connector.validate()
            if (!validationResult.isValid) {
                return Result.failure(
                    IllegalStateException(
                        "Invalid configuration for '${instance.name}': ${validationResult.errors.joinToString(", ")}",
                    ),
                )
            }

            log.trace("Creating transport for connector '${instance.name}'")
            var transport: McpTransport? = null
            val client = try {
                transport = connector.createTransport()
                val builder = DefaultMcpClient.builder()
                    .key(clientKey)
                    .transport(transport)
                resolvedDefinition.protocolVersion?.let {
                    log.debug("Using explicit MCP protocol version '{}' for '{}'", it, instance.name)
                    builder.protocolVersion(it)
                }
                builder.build()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug("Connect failed for '${instance.name}', probing for root cause", e)
                // DefaultMcpClient.builder().build() may have already started the transport's
                // child process (via transport.start(...)) before failing. Close it first so the
                // reactive probe below doesn't launch a second, competing copy of the same command.
                transport?.let { t ->
                    runCatching { t.close() }
                        .onFailure { ce -> log.debug("Failed to close transport for '${instance.name}'", ce) }
                }
                val diagnostic = try {
                    connector.probeStartup()
                } catch (pe: CancellationException) {
                    throw pe
                } catch (pe: Exception) {
                    null
                }
                val message = (diagnostic as? ProbeResult.Failed)
                    ?.let { "Cannot start '${instance.name}': ${it.message}" }
                    ?: "Cannot connect to '${instance.name}': ${e.message}"
                return Result.failure(IllegalStateException(message, e))
            }

            log.debug("Successfully created MCP client for '${instance.name}'")
            Result.success(client)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("Failed to create MCP client for '${instance.name}'", e)
            Result.failure(
                IllegalStateException(
                    "Failed to create MCP client for '${instance.name}': ${e.message}",
                    e,
                ),
            )
        }
    }

    // ── Classification ────────────────────────────────────────────────────────

    /**
     * Infers the [ToolCategory] of a tool using heuristics on its name and description.
     */
    fun inferToolCategory(toolSpec: ToolSpecification): ToolCategory {
        val name = toolSpec.name().lowercase()
        val description = toolSpec.description()?.lowercase() ?: ""

        // Database operations
        if (name.contains("database") || name.contains("db") || name.contains("sql") ||
            name.contains("query") || name.contains("postgres") || name.contains("mysql") ||
            name.contains("mongo") || name.contains("redis") ||
            description.contains("database") || description.contains("query") ||
            description.contains("table") || description.contains("schema")
        ) {
            return ToolCategory.DATABASE
        }

        // Network/API operations
        if (name.contains("http") || name.contains("api") || name.contains("webhook") ||
            name.contains("request") || name.contains("fetch") || name.contains("curl") ||
            description.contains("http") || description.contains("api") ||
            description.contains("request") || description.contains("endpoint")
        ) {
            return ToolCategory.NETWORK
        }

        // Visualization
        if (name.contains("chart") || name.contains("graph") || name.contains("plot") ||
            name.contains("visualiz") || name.contains("diagram") ||
            description.contains("chart") || description.contains("visualiz") ||
            description.contains("graph") || description.contains("plot")
        ) {
            return ToolCategory.VISUALIZE
        }

        // Search operations
        if (name.startsWith("search_") || name.startsWith("find_") || name.startsWith("query_") ||
            name.contains("lookup") ||
            description.contains("search") ||
            (description.contains("find") && !description.contains("file"))
        ) {
            return ToolCategory.SEARCH
        }

        // File read operations
        if (name.contains("read_file") || name.contains("get_file") || name.contains("list_file") ||
            name.contains("show_file") || name.contains("cat_") ||
            (description.contains("read") && description.contains("file")) ||
            (description.contains("get") && description.contains("file"))
        ) {
            return ToolCategory.FILE_READ
        }

        // File write operations
        if (name.contains("write_file") || name.contains("create_file") || name.contains("delete_file") ||
            name.contains("save_file") || name.contains("remove_file") || name.contains("mkdir") ||
            (description.contains("write") && description.contains("file")) ||
            (description.contains("create") && description.contains("file")) ||
            (description.contains("delete") && description.contains("file"))
        ) {
            return ToolCategory.FILE_WRITE
        }

        // Data transformation
        if (name.contains("convert") || name.contains("transform") || name.contains("parse") ||
            name.contains("format") || name.contains("encode") || name.contains("decode") ||
            description.contains("convert") || description.contains("transform") ||
            description.contains("parse") || description.contains("format")
        ) {
            return ToolCategory.TRANSFORM
        }

        // Version control
        if (name.contains("git") || name.contains("commit") || name.contains("branch") ||
            name.contains("merge") || name.contains("pull_request") || name.contains("pr_") ||
            description.contains("git") || description.contains("version control") ||
            description.contains("repository") || description.contains("commit")
        ) {
            return ToolCategory.VERSION_CONTROL
        }

        // Communication
        if (name.contains("email") || name.contains("slack") || name.contains("notify") ||
            name.contains("message") || name.contains("send") || name.contains("post_") ||
            description.contains("email") || description.contains("slack") ||
            description.contains("notification") || description.contains("message")
        ) {
            return ToolCategory.COMMUNICATION
        }

        // Monitoring/Logging
        if (name.contains("log") || name.contains("monitor") || name.contains("track") ||
            name.contains("metric") || name.contains("alert") ||
            description.contains("log") || description.contains("monitor") ||
            description.contains("track") || description.contains("metric")
        ) {
            return ToolCategory.MONITORING
        }

        // Execute/Command operations
        if (name.contains("execute") || name.contains("run") || name.contains("command") ||
            name.contains("shell") || name.contains("script") || name.contains("install") ||
            description.contains("execute") || description.contains("run") ||
            description.contains("command") || description.contains("shell")
        ) {
            return ToolCategory.EXECUTE
        }

        log.trace("Tool '${toolSpec.name()}' classified as OTHER (unrecognized category)")
        return ToolCategory.OTHER
    }

    /**
     * Infers the execution strategy of a tool using heuristics on its name and description.
     *
     * - [ToolStrategy.INTENT_BASED] — safe by default (reads, writes, single deletes)
     * - [ToolStrategy.FOLLOW_UP_BASED] — only truly dangerous/destructive operations
     */
    fun inferToolStrategy(toolSpec: ToolSpecification): Int {
        val name = toolSpec.name().lowercase()
        val description = toolSpec.description()?.lowercase() ?: ""

        // 1. Database/Schema destruction
        if ((name.contains("drop") || name.contains("truncate")) &&
            (
                name.contains("database") || name.contains("db") || name.contains("table") ||
                    name.contains("schema") || description.contains("drop database") ||
                    description.contains("drop table")
                )
        ) {
            log.trace("Tool '${toolSpec.name()}' classified as FOLLOW_UP_BASED (database destruction)")
            return ToolStrategy.FOLLOW_UP_BASED
        }

        // 2. Bulk/Mass deletion
        if ((
                name.contains("delete") || name.contains("remove") ||
                    name.contains("clear") || name.contains("wipe")
                ) &&
            (
                name.contains("all") || name.contains("everything") ||
                    description.contains("delete all") || description.contains("remove all") ||
                    description.contains("clear all") || description.contains("wipe all") ||
                    description.contains("bulk delete") || description.contains("mass delete")
                )
        ) {
            log.trace("Tool '${toolSpec.name()}' classified as FOLLOW_UP_BASED (bulk deletion)")
            return ToolStrategy.FOLLOW_UP_BASED
        }

        // 3. System-level dangerous commands
        if (name.contains("shutdown") || name.contains("restart") ||
            name.contains("reboot") || name.contains("format") ||
            description.contains("shutdown") || description.contains("restart") ||
            description.contains("irreversible") || description.contains("cannot be undone") ||
            description.contains("permanent deletion")
        ) {
            log.trace("Tool '${toolSpec.name()}' classified as FOLLOW_UP_BASED (system danger)")
            return ToolStrategy.FOLLOW_UP_BASED
        }

        // 4. Security-critical permission changes
        if ((name.contains("chmod") || name.contains("permission") || name.contains("grant")) &&
            (
                description.contains("777") || description.contains("full access") ||
                    description.contains("admin rights") || description.contains("root access") ||
                    description.contains("bypass security")
                )
        ) {
            log.trace("Tool '${toolSpec.name()}' classified as FOLLOW_UP_BASED (security risk)")
            return ToolStrategy.FOLLOW_UP_BASED
        }

        log.trace("Tool '${toolSpec.name()}' classified as INTENT_BASED (safe operation)")
        return ToolStrategy.INTENT_BASED
    }
}
