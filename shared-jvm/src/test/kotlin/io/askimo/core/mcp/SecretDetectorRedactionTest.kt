/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.mcp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for [SecretDetector.redactValue]/[redactMap]/[redactList].
 *
 * These exist to prevent plaintext secrets (API keys, tokens) from leaking into debug logs
 * when [McpInstance] resolves STDIO/HTTP templates — see [McpInstance.createStdioConnector]
 * and [McpInstance.createHttpConnector].
 */
class SecretDetectorRedactionTest {

    private val googleMapsDefinition = McpServerDefinition(
        id = "template-google-maps",
        name = "Google Maps",
        description = "test",
        transportType = TransportType.STDIO,
        stdioConfig = StdioConfig(
            commandTemplate = listOf("npx", "-y", "@cablate/mcp-google-map", "--stdio"),
            envTemplate = mapOf("GOOGLE_MAPS_API_KEY" to "{{googleMapsApiKey}}"),
        ),
        parameters = listOf(
            Parameter(
                key = "googleMapsApiKey",
                label = "Google Maps API Key",
                type = ParameterType.SECRET,
                location = ParameterLocation.ENVIRONMENT,
            ),
        ),
    )

    @Test
    fun `redactValue masks a value whose template references a declared secret parameter`() {
        val result = SecretDetector.redactValue(
            rawTemplate = "{{googleMapsApiKey}}",
            resolvedValue = "AIzaSyD-real-secret-value-1234567890",
            definition = googleMapsDefinition,
        )

        assertFalse(result.contains("AIzaSyD-real-secret-value-1234567890"))
    }

    @Test
    fun `redactValue leaves non-secret values unchanged`() {
        val result = SecretDetector.redactValue(
            rawTemplate = "{{rootPath}}",
            resolvedValue = "/Users/alice/projects",
            definition = McpServerDefinition(
                id = "template-filesystem",
                name = "Filesystem",
                description = "test",
                transportType = TransportType.STDIO,
                stdioConfig = StdioConfig(
                    commandTemplate = listOf("npx", "-y", "server-filesystem", "{{rootPath}}"),
                ),
                parameters = listOf(
                    Parameter(
                        key = "rootPath",
                        label = "Allowed Root Directory",
                        type = ParameterType.PATH,
                        location = ParameterLocation.COMMAND,
                    ),
                ),
            ),
        )

        assertEquals("/Users/alice/projects", result)
    }

    @Test
    fun `redactValue falls back to naming convention when no definition is supplied`() {
        // No McpServerDefinition available (e.g. user-defined server) — convention-based
        // detection must still catch an obviously secret-shaped env var name.
        val result = SecretDetector.redactValue(
            rawTemplate = "{{apiKey}}",
            resolvedValue = "sk-abcdef1234567890",
            definition = null,
        )

        assertFalse(result.contains("sk-abcdef1234567890"))
    }

    @Test
    fun `redactMap only masks entries whose template references a secret, leaving others untouched`() {
        val rawTemplate = mapOf(
            "GOOGLE_MAPS_API_KEY" to "{{googleMapsApiKey}}",
            "NODE_ENV" to "production",
        )
        val resolved = mapOf(
            "GOOGLE_MAPS_API_KEY" to "AIzaSyD-real-secret-value-1234567890",
            "NODE_ENV" to "production",
        )

        val result = SecretDetector.redactMap(rawTemplate, resolved, googleMapsDefinition)

        assertFalse(result.getValue("GOOGLE_MAPS_API_KEY").contains("AIzaSyD-real-secret-value-1234567890"))
        assertEquals("production", result.getValue("NODE_ENV"))
    }

    @Test
    fun `redactList masks only the resolved arguments whose template slot is a secret`() {
        val rawTemplate = listOf("npx", "-y", "{{apiKey}}", "--stdio")
        val resolved = listOf("npx", "-y", "sk-abcdef1234567890", "--stdio")

        val result = SecretDetector.redactList(rawTemplate, resolved)

        assertEquals("npx", result[0])
        assertEquals("-y", result[1])
        assertFalse(result[2].contains("sk-abcdef1234567890"))
        assertEquals("--stdio", result[3])
    }

    @Test
    fun `redactList masks a secret whose index shifted after an earlier conditional flag was filtered out`() {
        // Regression: TemplateResolver.resolveList drops blank/unmet conditional entries, which
        // shifts later entries' indices. redactList must be given the index-aligned source
        // templates (via TemplateResolver.resolveListWithSources), not the original template
        // list, or the secret gets checked against the wrong template slot and leaks.
        val definition = McpServerDefinition(
            id = "template-conditional-secret",
            name = "Conditional Secret",
            description = "test",
            transportType = TransportType.STDIO,
            stdioConfig = StdioConfig(
                commandTemplate = listOf("{{?debug:--verbose}}", "{{apiKey}}"),
            ),
            parameters = listOf(
                Parameter(
                    key = "apiKey",
                    label = "API Key",
                    type = ParameterType.SECRET,
                    location = ParameterLocation.COMMAND,
                ),
            ),
        )
        val resolver = TemplateResolver(mapOf("debug" to "false", "apiKey" to "sk-real-secret-value-1234567890"))
        val (resolvedCommand, commandSources) = resolver.resolveListWithSources(definition.stdioConfig!!.commandTemplate)

        val result = SecretDetector.redactList(commandSources, resolvedCommand, definition)

        assertEquals(listOf("sk-real-secret-value-1234567890"), resolvedCommand)
        assertFalse(result.joinToString(" ").contains("sk-real-secret-value-1234567890"))
    }

    @Test
    fun `redactValue is deterministic and masking still flags the value as redacted`() {
        val value = "AIzaSyD-real-secret-value-1234567890"
        val first = SecretDetector.redactValue("{{googleMapsApiKey}}", value, googleMapsDefinition)
        val second = SecretDetector.redactValue("{{googleMapsApiKey}}", value, googleMapsDefinition)

        assertEquals(first, second)
        assertTrue(first != value)
    }

    @Test
    fun `redactMap masks a secret expanded from a comma-separated KEY=value template entry`() {
        // A single envTemplate entry ("ENV") expands into two output keys (API_KEY, MODE) whose
        // names don't exist in the original template map. redactMap must be given the
        // per-output-key source template (via TemplateResolver.resolveMapWithSources), not the
        // original template map, or the secret placeholder reference is lost and nothing is masked.
        val definition = McpServerDefinition(
            id = "template-comma-env",
            name = "Comma Env",
            description = "test",
            transportType = TransportType.STDIO,
            stdioConfig = StdioConfig(
                commandTemplate = listOf("npx", "-y", "some-server"),
                envTemplate = mapOf("ENV" to "API_KEY={{apiKey}},MODE=prod"),
            ),
            parameters = listOf(
                Parameter(
                    key = "apiKey",
                    label = "API Key",
                    type = ParameterType.SECRET,
                    location = ParameterLocation.ENVIRONMENT,
                ),
            ),
        )
        val resolver = TemplateResolver(mapOf("apiKey" to "sk-real-secret-value-1234567890"))
        val (resolvedEnv, envSources) = resolver.resolveMapWithSources(definition.stdioConfig!!.envTemplate)

        val result = SecretDetector.redactMap(envSources, resolvedEnv, definition)

        assertFalse(result.getValue("API_KEY").contains("sk-real-secret-value-1234567890"))
        // MODE is expanded from the same source template as API_KEY, so it is also redacted
        // (coarser but safe: redact every output produced by a secret-bearing template slot).
        assertFalse(result.getValue("MODE").contains("prod"))
    }
}
