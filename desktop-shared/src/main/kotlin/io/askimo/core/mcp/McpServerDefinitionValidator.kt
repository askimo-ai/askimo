/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.mcp

import io.konform.validation.Validation
import io.konform.validation.ValidationResult
import io.konform.validation.constraints.minItems
import io.konform.validation.constraints.minLength
import io.konform.validation.constraints.pattern
import java.util.ResourceBundle

/**
 * Loads validation messages from the i18n properties file.
 * This uses English locale by default.
 */
private object ValidationMessages {
    private val bundle: ResourceBundle by lazy {
        ResourceBundle.getBundle("i18n.messages")
    }

    val idEmpty: String
        get() = bundle.getString("mcp.instance.validation.error.empty.id")

    val idPattern: String
        get() = bundle.getString("mcp.instance.validation.error.invalid.id.pattern")

    val nameEmpty: String
        get() = bundle.getString("mcp.instance.validation.error.empty.name")

    val commandEmpty: String
        get() = bundle.getString("mcp.instance.validation.error.empty.command")

    val paramKeyEmpty: String
        get() = bundle.getString("mcp.instance.validation.error.empty.parameter.key")

    val paramLabelEmpty: String
        get() = bundle.getString("mcp.instance.validation.error.empty.parameter.label")

    val urlEmpty: String
        get() = bundle.getString("mcp.instance.validation.error.empty.url")

    val urlInvalid: String
        get() = bundle.getString("mcp.instance.validation.error.invalid.url")
}

/**
 * Validator for McpServerDefinition with localized English error messages.
 *
 * Usage:
 * ```kotlin
 * val result = mcpServerDefinitionValidator(serverDefinition)
 * if (result.errors.isNotEmpty()) {
 *     val errorMessage = result.getFirstError()
 *     // Handle error
 * }
 * ```
 */
val mcpServerDefinitionValidator = Validation {
    McpServerDefinition::id {
        minLength(1) hint ValidationMessages.idEmpty
        pattern("^[a-z0-9-]+$") hint ValidationMessages.idPattern
    }

    McpServerDefinition::name {
        minLength(1) hint ValidationMessages.nameEmpty
    }

    // STDIO: require a non-empty commandTemplate
    McpServerDefinition::stdioConfig ifPresent {
        StdioConfig::commandTemplate {
            minItems(1) hint ValidationMessages.commandEmpty
        }
    }

    // protocolVersion: if provided, must be a known MCP protocol version string
    McpServerDefinition::protocolVersion ifPresent {
        pattern("^\\d{4}-\\d{2}-\\d{2}$") hint "Protocol version must be a date string (e.g. \"2025-11-25\")"
    }

    // HTTP: require a non-blank, well-formed urlTemplate
    McpServerDefinition::httpConfig ifPresent {
        HttpConfig::urlTemplate {
            minLength(1) hint ValidationMessages.urlEmpty
            pattern("^https?://.*") hint ValidationMessages.urlInvalid
        }
    }
}

/**
 * Extension function to get the first error message from validation result.
 * Returns null if there are no errors.
 */
fun ValidationResult<McpServerDefinition>.getFirstError(): String? = this.errors.firstOrNull()?.message

/**
 * Extension function to get all error messages from validation result.
 * Returns an empty list if there are no errors.
 */
fun ValidationResult<McpServerDefinition>.getAllErrors(): List<String> = this.errors.map { it.message }
