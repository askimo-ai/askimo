/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.mcp

/**
 * Extracts variables from MCP server configuration templates.
 * Variables can appear in command templates, environment templates, and working directories.
 */
object VariableExtractor {

    /**
     * Represents a variable extracted from templates
     */
    data class ExtractedVariable(
        val key: String,
        val locations: Set<VariableLocation>,
        val isConditional: Boolean = false,
    )

    /**
     * Where a variable is used in the configuration
     */
    enum class VariableLocation {
        COMMAND, // Used in commandTemplate
        ENVIRONMENT, // Used in envTemplate
        WORKING_DIR, // Used in workingDirectory
    }

    /**
     * Extract all variables from a StdioConfig.
     *
     * Example:
     * ```
     * val config = StdioConfig(
     *     commandTemplate = listOf("node", "{{script}}", "{{?debug:--verbose}}"),
     *     envTemplate = mapOf("API_KEY" to "{{apiKey}}")
     * )
     * val vars = VariableExtractor.extract(config)
     * // Result: [
     * //   ExtractedVariable(key="script", locations=[COMMAND]),
     * //   ExtractedVariable(key="debug", locations=[COMMAND], isConditional=true),
     * //   ExtractedVariable(key="apiKey", locations=[ENVIRONMENT])
     * // ]
     * ```
     */
    fun extract(config: StdioConfig): List<ExtractedVariable> {
        val variables = mutableMapOf<String, MutableSet<VariableLocation>>()
        val conditionals = mutableSetOf<String>()

        // Extract from command template
        config.commandTemplate.forEach { arg ->
            val varsInArg = extractFromString(arg)
            varsInArg.forEach { varKey ->
                variables.getOrPut(varKey) { mutableSetOf() }.add(VariableLocation.COMMAND)

                // Check if it's a conditional variable {{?key:value}}
                if (arg.contains("{{?$varKey:") || arg.contains("{{?$varKey }}")) {
                    conditionals.add(varKey)
                }
            }
        }

        // Extract from environment template
        config.envTemplate.values.forEach { envValue ->
            extractFromString(envValue).forEach { varKey ->
                variables.getOrPut(varKey) { mutableSetOf() }.add(VariableLocation.ENVIRONMENT)
            }
        }

        // Extract from working directory
        config.workingDirectory?.let { workDir ->
            extractFromString(workDir).forEach { varKey ->
                variables.getOrPut(varKey) { mutableSetOf() }.add(VariableLocation.WORKING_DIR)
            }
        }

        return variables.map { (key, locations) ->
            ExtractedVariable(
                key = key,
                locations = locations,
                isConditional = key in conditionals,
            )
        }.sortedBy { it.key }
    }

    /**
     * Extract variable keys from a template string.
     *
     * Supports both simple and conditional placeholders:
     * - Simple: {{key}} -> ["key"]
     * - Conditional: {{?flag:value}} -> ["flag"]
     * - Multiple: "{{host}}:{{port}}/{{db}}" -> ["host", "port", "db"]
     *
     * Example: "mongodb://{{host}}:{{port}}/{{db}}" -> ["host", "port", "db"]
     */
    fun extractFromString(template: String): List<String> {
        val simplePattern = Regex("""\{\{([^}?:]+)\}\}""")
        val conditionalPattern = Regex("""\{\{\?([^}:]+):.*?\}\}""")

        val simpleVars = simplePattern.findAll(template).map { it.groupValues[1].trim() }
        val conditionalVars = conditionalPattern.findAll(template).map { it.groupValues[1].trim() }

        return (simpleVars + conditionalVars).distinct().toList()
    }
}

/**
 * Resolved configuration ready for execution
 */
data class ResolvedStdioConfig(
    val command: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: String?,
)

/**
 * Resolve a StdioConfig with user-provided variable values.
 *
 * Example:
 * ```
 * val config = StdioConfig(
 *     commandTemplate = listOf("node", "{{script}}", "{{?debug:--verbose}}"),
 *     envTemplate = mapOf("API_KEY" to "{{apiKey}}")
 * )
 * val resolved = config.resolve(mapOf(
 *     "script" to "server.js",
 *     "debug" to "true",
 *     "apiKey" to "secret123"
 * ))
 * // Result: ResolvedStdioConfig(
 * //   command = ["node", "server.js", "--verbose"],
 * //   environment = {"API_KEY": "secret123"},
 * //   workingDirectory = null
 * // )
 * ```
 */
fun StdioConfig.resolve(variables: Map<String, String>): ResolvedStdioConfig {
    val resolver = TemplateResolver(variables)

    return ResolvedStdioConfig(
        command = resolver.resolveList(commandTemplate),
        environment = resolver.resolveMap(envTemplate),
        workingDirectory = workingDirectory?.let { resolver.resolve(it) }?.takeIf { it.isNotBlank() },
    )
}

/**
 * Extract all variables from this StdioConfig
 */
fun StdioConfig.extractVariables(): List<VariableExtractor.ExtractedVariable> = VariableExtractor.extract(this)

/**
 * Extract all variables from this HttpConfig (URL template + header templates)
 */
fun HttpConfig.extractVariables(): List<VariableExtractor.ExtractedVariable> {
    val variables = mutableMapOf<String, MutableSet<VariableExtractor.VariableLocation>>()

    // Extract from URL template
    VariableExtractor.extractFromString(urlTemplate).forEach { varKey ->
        variables.getOrPut(varKey) { mutableSetOf() }.add(VariableExtractor.VariableLocation.COMMAND)
    }

    // Extract from header templates
    headersTemplate.values.forEach { headerValue ->
        VariableExtractor.extractFromString(headerValue).forEach { varKey ->
            variables.getOrPut(varKey) { mutableSetOf() }.add(VariableExtractor.VariableLocation.ENVIRONMENT)
        }
    }

    return variables.map { (key, locations) ->
        VariableExtractor.ExtractedVariable(
            key = key,
            locations = locations,
            isConditional = false,
        )
    }.sortedBy { it.key }
}
