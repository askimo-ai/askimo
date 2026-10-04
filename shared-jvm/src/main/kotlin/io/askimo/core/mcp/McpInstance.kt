/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.mcp

import io.askimo.core.logging.logger
import io.askimo.core.mcp.connectors.HttpMcpConnector
import io.askimo.core.mcp.connectors.StdioMcpConnector
import io.askimo.core.security.SecureKeyManager
import kotlinx.serialization.Serializable
import java.time.LocalDateTime

/**
 * An instance of an MCP server with actual values for the server's parameters.
 *
 * Example:
 *   - Instance: serverId="mongodb-mcp-server", name="Production MongoDB"
 *               parameterValues={mongoUri: "mongodb://prod/analytics", readOnly: "true"}
 */
data class McpInstance(
    val id: String,
    val serverId: String, // References McpServerDefinition.id
    val name: String,
    val parameterValues: Map<String, String>, // User-provided values
    val enabled: Boolean = true,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {

    private val log = logger<McpInstance>()

    /**
     * Creates an MCP connector from this instance + its definition.
     * Resolves all template placeholders with actual parameter values.
     */
    fun toConnector(definition: McpServerDefinition): McpConnector {
        val resolver = TemplateResolver(parameterValues)
        return when (definition.transportType) {
            TransportType.STDIO -> createStdioConnector(definition, resolver)
            TransportType.HTTP -> createHttpConnector(definition, resolver)
        }
    }

    private fun createStdioConnector(
        definition: McpServerDefinition,
        resolver: TemplateResolver,
    ): StdioMcpConnector {
        val stdioConfig = definition.stdioConfig
            ?: error("STDIO config missing for ${definition.id}")

        val resolvedCommand = resolver.resolveList(stdioConfig.commandTemplate)
        val resolvedEnv = resolver.resolveMap(stdioConfig.envTemplate)
        val resolvedWorkingDir = stdioConfig.workingDirectory?.let { resolver.resolve(it) }
        log.debug(
            "Create the MCP connector with command: {}, env: {}, workingDir: {}",
            resolvedCommand,
            resolvedEnv,
            resolvedWorkingDir,
        )

        return StdioMcpConnector(
            StdioMcpTransportConfig(
                id = id,
                name = name,
                description = "Instance of ${definition.name}",
                command = resolvedCommand,
                env = resolvedEnv,
                workingDirectory = resolvedWorkingDir,
            ),
        )
    }

    private fun createHttpConnector(
        definition: McpServerDefinition,
        resolver: TemplateResolver,
    ): HttpMcpConnector {
        val httpConfig = definition.httpConfig
            ?: error("HTTP config missing for ${definition.id}")

        val resolvedUrl = resolver.resolve(httpConfig.urlTemplate)
        val resolvedHeaders = resolver.resolveMap(httpConfig.headersTemplate)
        log.debug(
            "Create the HTTP MCP connector with url: {}",
            resolvedUrl,
        )

        return HttpMcpConnector(
            HttpMcpTransportConfig(
                id = id,
                name = name,
                description = "Instance of ${definition.name}",
                url = resolvedUrl,
                headers = resolvedHeaders,
                timeoutMs = httpConfig.timeoutMs,
            ),
        )
    }
}

/**
 * Serializable version of McpInstance for JSON storage.
 *
 * Secret parameter values are never persisted in this data class.
 * Instead, their keys are listed in [secretParameterKeys] and the values
 * are stored/retrieved via [SecureKeyManager] (keychain or encrypted file).
 */
@Serializable
data class McpInstanceData(
    val id: String,
    val serverId: String,
    val name: String,
    /** Non-secret parameter values only — safe to persist in plain YAML. */
    val parameterValues: Map<String, String>,
    /** Keys whose values are stored in [SecureKeyManager], not in this file. */
    val secretParameterKeys: Set<String> = emptySet(),
    val enabled: Boolean = true,
    // ISO-8601 format
    val createdAt: String,
    // ISO-8601 format
    val updatedAt: String,
) {
    /**
     * Reconstruct the domain object by merging plain values with secrets
     * retrieved from [SecureKeyManager].
     */
    fun toDomain(): McpInstance {
        val resolvedSecrets = secretParameterKeys.associateWith { key ->
            SecureKeyManager.retrieveSecretKey(secretKeyId(id, key)) ?: ""
        }
        return McpInstance(
            id = id,
            serverId = serverId,
            name = name,
            parameterValues = parameterValues + resolvedSecrets,
            enabled = enabled,
            createdAt = LocalDateTime.parse(createdAt),
            updatedAt = LocalDateTime.parse(updatedAt),
        )
    }

    companion object {
        /**
         * Converts a domain instance to its serializable form.
         *
         * @param instance   The domain object to convert
         * @param definition Optional server definition used by [SecretDetector] for
         *                   authoritative secret detection. Falls back to convention-based
         *                   detection when null.
         */
        fun from(
            instance: McpInstance,
            definition: McpServerDefinition? = null,
        ): McpInstanceData {
            val plain = mutableMapOf<String, String>()
            val secretKeys = mutableSetOf<String>()

            instance.parameterValues.forEach { (key, value) ->
                if (SecretDetector.isSecret(key, definition)) {
                    SecureKeyManager.storeSecuredKey(secretKeyId(instance.id, key), value)
                    secretKeys.add(key)
                } else {
                    plain[key] = value
                }
            }

            return McpInstanceData(
                id = instance.id,
                serverId = instance.serverId,
                name = instance.name,
                parameterValues = plain,
                secretParameterKeys = secretKeys,
                enabled = instance.enabled,
                createdAt = instance.createdAt.toString(),
                updatedAt = instance.updatedAt.toString(),
            )
        }

        /**
         * Namespaced key for [SecureKeyManager] — prevents collisions between
         * different instances that may share the same parameter name.
         *
         * Format: `mcp.<instanceId>.<paramKey>`
         */
        fun secretKeyId(instanceId: String, paramKey: String) = "mcp.$instanceId.$paramKey"
    }
}
