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
 * Context window exceeded after all automatic retries have been exhausted.
 * Triggered when the total input (history + message + attachments) is too large for the model.
 */
class ContextLengthException(
    cause: Throwable? = null,
) : UserException("Context window exceeded", cause) {
    override fun getMessageKey() = "error.context_length"
    override fun getMessageArgs() = emptyMap<String, String>()
}

/**
 * Local AI server crashed, failed to load the model, or returned an unrecoverable internal error.
 * Covers Ollama, Docker AI, LocalAI, LMStudio, and any other OpenAI-compatible local backend.
 *
 * This is non-retryable: the user must fix the underlying server/model issue first.
 *
 * @param details A short excerpt from the server error message for diagnostic display.
 */
class LocalServerException(
    val details: String = "",
    cause: Throwable? = null,
) : UserException("Local AI server error", cause) {
    override fun getMessageKey() = "error.local_server"
    override fun getMessageArgs() = mapOf("details" to details)
}

/**
 * Remote AI provider returned a server-side error (HTTP 5xx), such as 503 Service Unavailable
 * or 529 Service Overloaded. This is typically transient — the provider's infrastructure is
 * temporarily overwhelmed. The request may succeed after a brief wait and retry.
 *
 * @param details A short excerpt from the server error message for diagnostic display.
 */
class RemoteServerException(
    val details: String = "",
    cause: Throwable? = null,
) : UserException("Remote AI server error", cause) {
    override fun getMessageKey() = "error.remote_server"
    override fun getMessageArgs() = mapOf("details" to details)
}

/**
 * Model not found — the selected model has been deprecated or removed by the provider.
 */
class ModelNotFoundChatException(
    val model: String,
    cause: Throwable? = null,
) : UserException("Model not found: $model", cause) {
    override fun getMessageKey() = "error.model_not_found"
    override fun getMessageArgs() = mapOf("model" to model)
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
