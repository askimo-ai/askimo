/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.mcp

import io.askimo.core.mcp.config.McpServersConfig
import io.askimo.core.security.SecureKeyManager

/**
 * Manages secrets in MCP server definitions for global instances.
 *
 * When users create global MCP instances without template variables, sensitive
 * values (API keys, tokens) would otherwise be stored in plain text in mcp-servers.yml.
 *
 * This manager:
 * 1. Detects secrets in HTTP headers and STDIO env vars using [SecretDetector]
 * 2. Stores actual values in [SecureKeyManager] (keychain/encrypted)
 * 3. Replaces values in YAML with references: `{{secret:askimo.mcp.server.xxx}}`
 * 4. Resolves references when creating MCP clients
 *
 * Secret key format: `askimo.mcp.server.{serverId}.{type}.{name}`
 * - Example: `askimo.mcp.server.global-abc123.header.Authorization`
 * - Example: `askimo.mcp.server.global-abc123.env.GITHUB_TOKEN`
 *
 * This namespace allows us to:
 * - List all Askimo-managed secrets
 * - Clean up when server deleted
 * - Distinguish from other app secrets
 */
object ServerDefinitionSecretManager {

    private const val SECRET_REF_PREFIX = "{{secret:"
    private const val SECRET_REF_SUFFIX = "}}"
    private const val KEY_PREFIX = "askimo.mcp.server"

    /**
     * Protects secrets in a server definition before saving to YAML.
     *
     * @param definition The server definition to protect
     * @return A new definition with secrets replaced by references, and the count of secrets protected
     */
    fun protectSecrets(definition: McpServerDefinition): Pair<McpServerDefinition, Int> {
        var secretCount = 0

        val protectedDefinition = when (definition.transportType) {
            TransportType.HTTP -> {
                val originalHeaders = definition.httpConfig?.headersTemplate ?: emptyMap()
                val protectedHeaders = mutableMapOf<String, String>()

                originalHeaders.forEach { (key, value) ->
                    if (isSecretReference(value)) {
                        // Already a reference, keep as-is
                        protectedHeaders[key] = value
                    } else if (SecretDetector.isSecret(key, definition)) {
                        // Store in secure storage and replace with reference
                        val secretKey = buildSecretKey(definition.id, "header", key)
                        SecureKeyManager.storeSecuredKey(secretKey, value)
                        protectedHeaders[key] = buildSecretReference(secretKey)
                        secretCount++
                    } else {
                        // Not a secret, keep as-is
                        protectedHeaders[key] = value
                    }
                }

                definition.copy(
                    httpConfig = definition.httpConfig?.copy(headersTemplate = protectedHeaders),
                )
            }

            TransportType.STDIO -> {
                val originalEnv = definition.stdioConfig?.envTemplate ?: emptyMap()
                val protectedEnv = mutableMapOf<String, String>()

                originalEnv.forEach { (key, value) ->
                    if (isSecretReference(value)) {
                        // Already a reference, keep as-is
                        protectedEnv[key] = value
                    } else if (SecretDetector.isSecret(key, definition)) {
                        // Store in secure storage and replace with reference
                        val secretKey = buildSecretKey(definition.id, "env", key)
                        SecureKeyManager.storeSecuredKey(secretKey, value)
                        protectedEnv[key] = buildSecretReference(secretKey)
                        secretCount++
                    } else {
                        // Not a secret, keep as-is
                        protectedEnv[key] = value
                    }
                }

                definition.copy(
                    stdioConfig = definition.stdioConfig?.copy(envTemplate = protectedEnv),
                )
            }
        }

        return protectedDefinition to secretCount
    }

    /**
     * Resolves secret references in a server definition when creating MCP clients.
     *
     * @param definition The definition potentially containing secret references
     * @return A new definition with references replaced by actual values from secure storage
     */
    fun resolveSecrets(definition: McpServerDefinition): McpServerDefinition = when (definition.transportType) {
        TransportType.HTTP -> {
            val headers = definition.httpConfig?.headersTemplate ?: emptyMap()
            val resolvedHeaders = resolveMapValues(headers)

            definition.copy(
                httpConfig = definition.httpConfig?.copy(headersTemplate = resolvedHeaders),
            )
        }

        TransportType.STDIO -> {
            val env = definition.stdioConfig?.envTemplate ?: emptyMap()
            val resolvedEnv = resolveMapValues(env)

            definition.copy(
                stdioConfig = definition.stdioConfig?.copy(envTemplate = resolvedEnv),
            )
        }
    }

    /**
     * Resolves secret references in a map of key-value pairs.
     * If value is a secret reference, retrieves from secure storage.
     * Otherwise, returns value as-is.
     */
    private fun resolveMapValues(map: Map<String, String>): Map<String, String> = map.mapValues { (_, value) ->
        if (isSecretReference(value)) {
            val secretKey = extractSecretKey(value)
            SecureKeyManager.retrieveSecretKey(secretKey) ?: ""
        } else {
            value
        }
    }

    /**
     * Removes all secrets associated with a server definition from secure storage.
     *
     * This method cleans up secrets stored in the format:
     * - askimo.mcp.server.{serverId}.header.*
     * - askimo.mcp.server.{serverId}.env.*
     *
     * @param serverId The server ID whose secrets should be removed
     */
    fun cleanupSecrets(serverId: String) {
        // Load the server definition to see which secrets were stored
        val definition = McpServersConfig.get(serverId)
        if (definition != null) {
            when (definition.transportType) {
                TransportType.HTTP -> {
                    definition.httpConfig?.headersTemplate?.forEach { (key, value) ->
                        if (isSecretReference(value)) {
                            val secretKey = extractSecretKey(value)
                            SecureKeyManager.removeSecretKey(secretKey)
                        }
                    }
                }

                TransportType.STDIO -> {
                    definition.stdioConfig?.envTemplate?.forEach { (key, value) ->
                        if (isSecretReference(value)) {
                            val secretKey = extractSecretKey(value)
                            SecureKeyManager.removeSecretKey(secretKey)
                        }
                    }
                }
            }
        }
    }

    /**
     * Checks if a value is a secret reference.
     */
    fun isSecretReference(value: String): Boolean = value.startsWith(SECRET_REF_PREFIX) && value.endsWith(SECRET_REF_SUFFIX)

    /**
     * Builds a secret key for secure storage.
     * Format: askimo.mcp.server.{serverId}.{type}.{name}
     */
    private fun buildSecretKey(serverId: String, type: String, name: String): String = "$KEY_PREFIX.$serverId.$type.$name"

    /**
     * Builds a secret reference for YAML storage.
     * Format: {{secret:askimo.mcp.server.xxx}}
     */
    private fun buildSecretReference(secretKey: String): String = "$SECRET_REF_PREFIX$secretKey$SECRET_REF_SUFFIX"

    /**
     * Extracts the secret key from a reference.
     */
    private fun extractSecretKey(reference: String): String = reference
        .removePrefix(SECRET_REF_PREFIX)
        .removeSuffix(SECRET_REF_SUFFIX)
}
