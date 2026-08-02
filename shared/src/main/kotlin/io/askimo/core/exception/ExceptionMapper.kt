/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.exception

import dev.langchain4j.exception.InternalServerException
import dev.langchain4j.exception.ModelNotFoundException
import io.askimo.core.logging.logger
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Maps generic exceptions to Askimo-specific exceptions with user-friendly messages.
 * This centralizes exception classification logic to distinguish between user errors
 * and system errors.
 *
 * The mapper traverses the entire exception chain to find the root cause, ensuring
 * that wrapped exceptions (like RuntimeException wrapping ConnectException) are
 * properly classified based on their underlying cause.
 */
object ExceptionMapper {
    private val log = logger<ExceptionMapper>()

    /**
     * Map a throwable to an AskimoException by traversing the entire exception chain.
     * If any exception in the chain is already an AskimoException, returns it as-is.
     * Otherwise, analyzes all exceptions in the chain and maps to the appropriate type.
     *
     * @param throwable The exception to map
     * @return An AskimoException (either user or system error)
     */
    fun map(throwable: Throwable): AskimoException {
        // Build the complete exception chain
        val exceptionChain = buildExceptionChain(throwable)

        // Check if ANY exception in the chain is already an AskimoException
        exceptionChain.forEach { exception ->
            if (exception is AskimoException) {
                return exception
            }
        }

        val rootCause = exceptionChain.last()

        // Try to match by exception type first (checking all in chain)
        exceptionChain.forEach { exception ->
            matchByType(exception)?.let { return it }
        }

        // Try to match by message pattern (checking all messages in chain)
        val allMessages = exceptionChain.mapNotNull { it.message }
        return matchByMessage(allMessages, rootCause)
    }

    /**
     * Build the complete exception chain from a throwable.
     * Traverses through all causes until reaching the root cause.
     *
     * @param throwable The starting exception
     * @return List of exceptions from the top-level to root cause
     */
    private fun buildExceptionChain(throwable: Throwable): List<Throwable> {
        val chain = mutableListOf<Throwable>()
        var current: Throwable? = throwable
        val seen = mutableSetOf<Throwable>()

        while (current != null && current !in seen) {
            chain.add(current)
            seen.add(current)
            current = current.cause
        }

        return chain
    }

    /**
     * Returns true when [message] contains patterns that indicate a local AI server crash
     * (Ollama, Docker AI, LocalAI, LMStudio process dying or failing to load a model).
     */
    private fun isLocalServerMessage(message: String): Boolean = message.contains("process has terminated", ignoreCase = true) ||
        message.contains("llama-server", ignoreCase = true) ||
        message.contains("llama_model_loader", ignoreCase = true) ||
        message.contains("error loading model", ignoreCase = true) ||
        (message.contains("api_error", ignoreCase = true) && message.contains("exit status", ignoreCase = true))

    /**
     * Try to match an exception by its concrete type.
     *
     * @param exception The exception to check
     * @return An AskimoException if matched, null otherwise
     */
    private fun matchByType(exception: Throwable): AskimoException? = when (exception) {
        // Network exceptions
        is ConnectException,
        is UnknownHostException,
        is NoRouteToHostException,
        -> NetworkException(cause = exception)

        // Timeout exceptions
        is SocketTimeoutException,
        is java.util.concurrent.TimeoutException,
        -> TimeoutException(timeoutSeconds = 30, cause = exception)

        is ModelNotFoundException -> ModelNotFoundChatException(
            model = exception.message?.substringAfterLast(" ") ?: "unknown",
            cause = exception,
        )

        // InternalServerException (HTTP 5xx) — sub-classify by message content:
        //   • local-server crash patterns (Ollama/LMStudio/etc.) → LocalServerException (non-retryable)
        //   • everything else (remote overload, 529, 503, etc.) → RemoteServerException (transient)
        is InternalServerException -> {
            val msg = exception.message ?: ""
            if (isLocalServerMessage(msg)) {
                LocalServerException(details = msg, cause = exception)
            } else {
                RemoteServerException(details = msg, cause = exception)
            }
        }

        // Add more type-based matches as needed
        else -> null
    }

    /**
     * Try to match by message patterns across all messages in the exception chain.
     *
     * @param messages All messages from the exception chain
     * @param rootCause The root cause exception to use in the created AskimoException
     * @return An AskimoException based on message pattern matching
     */
    private fun matchByMessage(messages: List<String>, rootCause: Throwable): AskimoException {
        val combinedMessage = messages.joinToString(" | ")

        return when {
            // Local AI server internal error – process crashed, model failed to load, etc.
            // Covers Ollama, Docker AI, LocalAI, LMStudio, and any OpenAI-compatible local backend.
            combinedMessage.contains("process has terminated", ignoreCase = true) ||
                combinedMessage.contains("llama-server", ignoreCase = true) ||
                combinedMessage.contains("llama_model_loader", ignoreCase = true) ||
                combinedMessage.contains("error loading model", ignoreCase = true) ||
                (combinedMessage.contains("api_error", ignoreCase = true) && combinedMessage.contains("exit status", ignoreCase = true)) ->
                LocalServerException(details = combinedMessage.take(300), cause = rootCause)

            // Remote AI provider returned a transient server-side error (HTTP 5xx).
            // Covers 503 Service Unavailable, 529 Overloaded, and similar temporary outages.
            combinedMessage.contains("overloaded", ignoreCase = true) ||
                combinedMessage.contains("529", ignoreCase = true) ||
                combinedMessage.contains("503", ignoreCase = true) ||
                combinedMessage.contains("service unavailable", ignoreCase = true) ||
                combinedMessage.contains("temporarily unavailable", ignoreCase = true) ||
                combinedMessage.contains("server is busy", ignoreCase = true) ||
                combinedMessage.contains("server is currently processing", ignoreCase = true) ->
                RemoteServerException(details = combinedMessage.take(300), cause = rootCause)

            // Network connectivity issues
            combinedMessage.contains("Connection refused", ignoreCase = true) ||
                combinedMessage.contains("Network is unreachable", ignoreCase = true) ||
                combinedMessage.contains("Connection timeout", ignoreCase = true) ||
                combinedMessage.contains("Failed to connect", ignoreCase = true) ||
                combinedMessage.contains("ConnectException", ignoreCase = true) ->
                NetworkException(cause = rootCause)

            // Authentication issues
            combinedMessage.contains("api key", ignoreCase = true) ||
                combinedMessage.contains("authentication", ignoreCase = true) ||
                combinedMessage.contains("unauthorized", ignoreCase = true) ||
                combinedMessage.contains("invalid API key", ignoreCase = true) ||
                combinedMessage.contains("Incorrect API key", ignoreCase = true) ||
                combinedMessage.contains("invalid_api_key", ignoreCase = true) ||
                combinedMessage.contains("401", ignoreCase = true) ->
                AuthenticationException(cause = rootCause)

            // Model configuration issues
            combinedMessage.contains("model is required", ignoreCase = true) ||
                combinedMessage.contains("No model provided", ignoreCase = true) ||
                combinedMessage.contains("model not found", ignoreCase = true) ||
                combinedMessage.contains("invalid model", ignoreCase = true) ||
                combinedMessage.contains("does not exist", ignoreCase = true) ->
                ModelNotFoundChatException(
                    model = "",
                    cause = rootCause,
                )

            // Rate limiting
            combinedMessage.contains("rate limit", ignoreCase = true) ||
                combinedMessage.contains("quota exceeded", ignoreCase = true) ||
                combinedMessage.contains("too many requests", ignoreCase = true) ||
                combinedMessage.contains("429", ignoreCase = true) ->
                RateLimitException(cause = rootCause)

            // Timeout
            combinedMessage.contains("timeout", ignoreCase = true) ||
                combinedMessage.contains("timed out", ignoreCase = true) ->
                TimeoutException(timeoutSeconds = 30, cause = rootCause)

            // Insufficient credits
            combinedMessage.contains("credit balance is too low", ignoreCase = true) ||
                combinedMessage.contains("insufficient_funds", ignoreCase = true) ||
                combinedMessage.contains("billing", ignoreCase = true) ||
                combinedMessage.contains("upgrade or purchase credits", ignoreCase = true) ->
                InsufficientCreditsException(cause = rootCause)

            // Context window exceeded (checked before generic 400 to avoid misclassification)
            (
                combinedMessage.contains("context", ignoreCase = true) && (
                    combinedMessage.contains("length", ignoreCase = true) ||
                        combinedMessage.contains("limit", ignoreCase = true) ||
                        combinedMessage.contains("exceeded", ignoreCase = true) ||
                        combinedMessage.contains("too long", ignoreCase = true) ||
                        combinedMessage.contains("maximum context", ignoreCase = true) ||
                        combinedMessage.contains("token limit", ignoreCase = true) ||
                        combinedMessage.contains("exceed", ignoreCase = true)
                    )
                ) || combinedMessage.contains("413", ignoreCase = true) ->
                ContextLengthException(cause = rootCause)

            // Invalid request
            combinedMessage.contains("400", ignoreCase = true) ||
                combinedMessage.contains("bad request", ignoreCase = true) ||
                combinedMessage.contains("invalid request", ignoreCase = true) ||
                combinedMessage.contains("malformed", ignoreCase = true) ->
                InvalidRequestException(
                    details = combinedMessage.take(200),
                    cause = rootCause,
                )

            // Unknown error: System error (contact support)
            else -> {
                log.error("Unmapped exception chain: ${rootCause::class.simpleName}", rootCause)
                SystemException(
                    message = "${rootCause::class.simpleName}: ${rootCause.message ?: "Unknown"}",
                    cause = rootCause,
                )
            }
        }
    }
}
