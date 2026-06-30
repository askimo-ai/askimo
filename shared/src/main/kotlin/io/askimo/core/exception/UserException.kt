/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.exception

/**
 * Base class for user-facing exceptions that can be fixed by the user.
 * Examples: network issues, authentication errors, configuration problems.
 */
sealed class UserException(
    message: String,
    cause: Throwable? = null,
) : AskimoException(message, cause) {
    override fun isUserError() = true
}

/**
 * Network connectivity issues (can't reach API server).
 */
class NetworkException(
    val endpoint: String? = null,
    cause: Throwable? = null,
) : UserException("Network connection failed", cause) {

    override fun getMessageKey() = "error.network"

    override fun getMessageArgs() = mapOf(
        "endpoint" to (endpoint?.let { " at $it" } ?: ""),
    )
}

/**
 * Authentication/API key issues.
 */
class AuthenticationException(
    val provider: String? = null,
    cause: Throwable? = null,
) : UserException("Authentication failed", cause) {

    override fun getMessageKey() = "error.authentication"

    override fun getMessageArgs() = mapOf(
        "provider" to (provider?.let { " for $it" } ?: ""),
    )
}

/**
 * Model configuration issues (no model selected, invalid model).
 */
class ModelConfigurationException(
    val issue: String,
    cause: Throwable? = null,
) : UserException("Model configuration error", cause) {

    override fun getMessageKey() = "error.model_configuration"

    override fun getMessageArgs() = mapOf("issue" to issue)
}

/**
 * Rate limit or quota exceeded.
 */
class RateLimitException(
    val retryAfterSeconds: Long? = null,
    cause: Throwable? = null,
) : UserException("Rate limit exceeded", cause) {

    override fun getMessageKey() = "error.rate_limit"

    override fun getMessageArgs() = mapOf(
        "retryAfter" to (retryAfterSeconds?.toString() ?: ""),
    )
}

/**
 * Timeout waiting for response.
 */
class TimeoutException(
    val timeoutSeconds: Int,
    cause: Throwable? = null,
) : UserException("Request timeout", cause) {

    override fun getMessageKey() = "error.timeout"

    override fun getMessageArgs() = mapOf("timeout" to timeoutSeconds.toString())
}

/**
 * Invalid request (malformed input, unsupported features).
 */
class InvalidRequestException(
    val details: String,
    cause: Throwable? = null,
) : UserException("Invalid request", cause) {

    override fun getMessageKey() = "error.invalid_request"

    override fun getMessageArgs() = mapOf("details" to details.take(200))
}

/**
 * Tool execution failed.
 */
class ToolExecutionException(
    val toolName: String,
    val errorDetails: String? = null,
    cause: Throwable? = null,
) : UserException("Tool execution failed", cause) {

    override fun getMessageKey() = "error.tool_execution"

    override fun getMessageArgs() = mapOf(
        "toolName" to toolName,
        "details" to (errorDetails ?: ""),
    )
}

/**
 * Insufficient API credits/balance.
 */
class InsufficientCreditsException(
    cause: Throwable? = null,
) : UserException("Insufficient API credits", cause) {

    override fun getMessageKey() = "error.insufficient_credits"

    override fun getMessageArgs() = emptyMap<String, String>()
}

/**
 * No AI provider has been configured yet (currentProvider == UNKNOWN).
 */
class ProviderNotConfiguredException :
    UserException(
        "No AI provider configured. Please set up a provider in Settings before chatting.",
    ) {
    override fun getMessageKey() = "error.provider_not_configured"

    override fun getMessageArgs() = emptyMap<String, String>()
}
