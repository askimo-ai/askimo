/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

import dev.langchain4j.service.IllegalConfigurationException
import io.askimo.core.logging.logger
import kotlin.reflect.KClass

/**
 * Retry configuration for the retry utility
 */
data class RetryConfig(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 1000,
    val delayIncrement: Long = 1000,
    val retryableExceptions: Set<KClass<out Throwable>> = setOf(),
    val retryCondition: ((Throwable) -> Boolean)? = null,
    val onRetry: ((attempt: Int, maxAttempts: Int, exception: Throwable, delayMs: Long) -> Unit)? = null,
)

/**
 * Generic retry utility with configurable parameters
 */
object RetryUtils {
    /**
     * Executes a regular (non-suspend) function with retry logic
     */
    fun <T> retry(
        config: RetryConfig,
        operation: () -> T,
    ): T {
        var lastException: Throwable? = null

        repeat(config.maxAttempts) { attempt ->
            try {
                return operation()
            } catch (e: Throwable) {
                lastException = e

                val shouldRetry =
                    when {
                        attempt == config.maxAttempts - 1 -> false
                        config.retryCondition != null -> config.retryCondition.invoke(e)
                        config.retryableExceptions.isNotEmpty() -> config.retryableExceptions.any { it.isInstance(e) }
                        else -> true
                    }

                if (!shouldRetry) {
                    throw e
                }

                val delayMs = config.initialDelayMs + (attempt * config.delayIncrement)
                config.onRetry?.invoke(attempt + 1, config.maxAttempts, e, delayMs)

                if (attempt < config.maxAttempts - 1) {
                    Thread.sleep(delayMs)
                }
            }
        }

        throw lastException ?: RuntimeException("Retry failed with unknown error")
    }
}

/**
 * Predefined retry configurations for common scenarios
 */
object RetryPresets {
    private val log = logger<RetryPresets>()

    /**
     * Retry configuration for recipe/LangChain4j transient errors
     */
    val RECIPE_EXECUTOR_TRANSIENT_ERRORS =
        RetryConfig(
            maxAttempts = 3,
            initialDelayMs = 1000,
            delayIncrement = 1000,
            retryCondition = { exception ->
                when {
                    exception is IllegalConfigurationException -> false

                    exception is IllegalArgumentException &&
                        exception.message?.contains("Model returned empty output") == true -> true

                    exception is NullPointerException &&
                        exception.stackTrace.any { it.className.contains("dev.langchain4j") } -> true

                    exception.javaClass.name.startsWith("dev.langchain4j") -> true

                    else -> false
                }
            },
            onRetry = { attempt, maxAttempts, exception, delayMs ->
                val message =
                    when {
                        exception.message?.contains("Model returned empty output") == true ->
                            "Model returned empty output"

                        exception is NullPointerException ->
                            "LangChain4j internal error"

                        else ->
                            "Transient error: ${exception.message}"
                    }
                log.debug("⚠️ $message (attempt $attempt/$maxAttempts). Retrying in ${delayMs}ms...")
            },
        )

    /**
     * Retry configuration for streaming operations
     */
    val STREAMING_ERRORS =
        RetryConfig(
            maxAttempts = 2,
            initialDelayMs = 1000,
            delayIncrement = 500,
            retryCondition = { exception ->
                if (exception is IllegalConfigurationException) {
                    false
                } else {
                    exception.javaClass.name.contains("streaming", ignoreCase = true) ||
                        exception.javaClass.name.startsWith("dev.langchain4j")
                }
            },
            onRetry = { attempt, maxAttempts, exception, delayMs ->
                log.debug("⚠️ Streaming error: ${exception.message} (attempt $attempt/$maxAttempts). Retrying in ${delayMs}ms...")
            },
        )
}
