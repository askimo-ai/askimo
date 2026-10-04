/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.mcp

/**
 * Resolves template strings by replacing placeholders with actual values.
 *
 * Supports two types of placeholders:
 * - Simple: {{key}} - replaced with the value of parameterValues[key]
 * - Conditional: {{?key:value}} - includes 'value' only if parameterValues[key] == "true"
 *
 * Example:
 * ```
 * val values = mapOf("mongoUri" to "mongodb://localhost", "readOnly" to "true")
 * resolveTemplate("{{mongoUri}}", values) // -> "mongodb://localhost"
 * resolveTemplate("{{?readOnly:--readOnly}}", values) // -> "--readOnly"
 * resolveTemplate("{{?readOnly:--readOnly}}", mapOf("readOnly" to "false")) // -> ""
 * ```
 */
class TemplateResolver(private val parameterValues: Map<String, String>) {

    /**
     * Resolves a single template string
     */
    fun resolve(template: String): String {
        var result = template

        // Handle conditional flags: {{?key:value}}
        // If parameterValues[key] == "true", replace with value, otherwise with empty string
        val conditionalRegex = """\{\{\?(\w+):([^}]+)\}\}""".toRegex()
        result = conditionalRegex.replace(result) { match ->
            val paramKey = match.groupValues[1]
            val flagValue = match.groupValues[2]
            val paramValue = parameterValues[paramKey]

            if (paramValue?.lowercase() == "true") flagValue else ""
        }

        // Handle simple placeholders: {{key}}
        val simpleRegex = """\{\{(\w+)\}\}""".toRegex()
        result = simpleRegex.replace(result) { match ->
            val key = match.groupValues[1]
            parameterValues[key] ?: ""
        }

        return result
    }

    /**
     * Resolves a list of template strings, filtering out empty results
     */
    fun resolveList(templates: List<String>): List<String> = templates.mapNotNull { template ->
        val resolved = resolve(template)
        // Skip empty strings (useful for optional conditional flags)
        resolved.ifBlank { null }
    }

    /**
     * Resolves a map of template strings.
     * Special handling for comma-separated KEY=value pairs:
     * If a resolved value contains "KEY=value,KEY2=value2", it will be split
     * into multiple environment variables.
     */
    fun resolveMap(templates: Map<String, String>): Map<String, String> {
        val result = mutableMapOf<String, String>()

        templates.forEach { (templateKey, template) ->
            val resolved = resolve(template)
            if (resolved.isBlank()) return@forEach

            // Check if this is a comma-separated KEY=value format
            if (resolved.contains('=')) {
                // Split by comma and process each KEY=value pair
                resolved.split(',').forEach { pair ->
                    val trimmed = pair.trim()
                    if (trimmed.contains('=')) {
                        val (key, value) = trimmed.split('=', limit = 2)
                        result[key.trim()] = value.trim()
                    } else {
                        // Not a KEY=value format, use as-is with template key
                        result[templateKey] = resolved
                    }
                }
            } else {
                // Simple value, use template key
                result[templateKey] = resolved
            }
        }

        return result
    }

    companion object {
        /**
         * Validates a template string for syntax errors
         */
        fun validate(template: String): ValidationResult {
            val errors = mutableListOf<String>()

            // Check for unmatched braces
            var braceDepth = 0
            var i = 0
            while (i < template.length) {
                when {
                    i < template.length - 1 && template[i] == '{' && template[i + 1] == '{' -> {
                        braceDepth++
                        i += 2
                    }

                    i < template.length - 1 && template[i] == '}' && template[i + 1] == '}' -> {
                        braceDepth--
                        i += 2
                        if (braceDepth < 0) {
                            errors.add("Unmatched closing braces at position $i")
                        }
                    }

                    else -> i++
                }
            }

            if (braceDepth > 0) {
                errors.add("Unmatched opening braces")
            }

            // Check for empty placeholders
            if (template.contains("{{}}")) {
                errors.add("Empty placeholder found")
            }

            return ValidationResult(
                isValid = errors.isEmpty(),
                errors = errors,
            )
        }

        /**
         * Extracts all parameter keys used in a template
         */
        fun extractParameters(template: String): Set<String> {
            val parameters = mutableSetOf<String>()

            // Extract from simple placeholders
            val simpleRegex = """\{\{(\w+)\}\}""".toRegex()
            simpleRegex.findAll(template).forEach {
                parameters.add(it.groupValues[1])
            }

            // Extract from conditional placeholders
            val conditionalRegex = """\{\{\?(\w+):([^}]+)\}\}""".toRegex()
            conditionalRegex.findAll(template).forEach {
                parameters.add(it.groupValues[1])
            }

            return parameters
        }
    }
}
