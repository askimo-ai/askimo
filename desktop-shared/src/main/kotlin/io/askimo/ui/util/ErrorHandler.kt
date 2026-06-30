/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.util

import io.askimo.core.chat.util.FileSizeExceededException
import io.askimo.core.exception.ProviderNotConfiguredException
import io.askimo.core.logging.logger
import io.askimo.core.util.formatFileSize
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Utility for handling errors with user-friendly messages and proper logging.
 *
 * This object provides methods to convert technical exceptions into user-friendly
 * error messages while logging the full technical details for debugging.
 */
object ErrorHandler {
    private val log = logger<ErrorHandler>()

    /**
     * Get a user-friendly error message for an exception and log the technical details.
     *
     * @param exception The exception that occurred
     * @param context A context string describing what operation failed (e.g., "sending message", "loading session")
     * @return A user-friendly error message suitable for display to end users
     */
    fun getUserFriendlyError(exception: Throwable, context: String): String {
        log.error("Error during $context", exception)

        // Return user-friendly message based on exception type
        return when (exception) {
            is ProviderNotConfiguredException ->
                "⚠️ No AI provider configured.\n\nTo start chatting:\n1. Open Settings\n2. Select an AI provider (OpenAI, Ollama, Anthropic, etc.)\n3. Enter your API key or configure the connection"

            is FileSizeExceededException -> "File is too large (${formatFileSize(exception.fileSize)}). Maximum allowed size is ${formatFileSize(exception.maxAllowedSize)}."

            is UnknownHostException -> "Unable to connect to the server. Please check your internet connection."

            is ConnectException -> "Failed to connect to the AI service. Please check your connection and try again."

            is SocketTimeoutException -> "The request timed out. Please try again."

            is IOException -> "A network error occurred. Please check your connection and try again."

            is IllegalStateException -> "An unexpected state error occurred. Please try again."

            is IllegalArgumentException -> "Invalid input provided. Please check your settings."

            else -> {
                // Generic user-friendly message
                val errorType = when {
                    exception.message?.contains("401") == true -> "Authentication failed. Please check your API key."
                    exception.message?.contains("403") == true -> "Access denied. Please check your permissions."
                    exception.message?.contains("404") == true -> "Resource not found. Please check your configuration."
                    exception.message?.contains("429") == true -> "Rate limit exceeded. Please wait a moment and try again."
                    exception.message?.contains("500") == true -> "Server error. Please try again later."
                    exception.message?.contains("503") == true -> "Service temporarily unavailable. Please try again later."
                    else -> "An unexpected error occurred. Please try again."
                }
                errorType
            }
        }
    }

    /**
     * Get a user-friendly error message with a custom fallback message.
     *
     * @param exception The exception that occurred
     * @param context A context string describing what operation failed
     * @param fallbackMessage Custom fallback message if no specific message matches
     * @return A user-friendly error message
     */
    fun getUserFriendlyError(exception: Throwable, context: String, fallbackMessage: String): String {
        log.error("Error during $context", exception)

        // Try to get a specific message first
        val specificMessage = when (exception) {
            is ProviderNotConfiguredException ->
                "⚠️ No AI provider configured.\n\nTo start chatting:\n1. Open Settings\n2. Select an AI provider (OpenAI, Ollama, Anthropic, etc.)\n3. Enter your API key or configure the connection"

            is FileSizeExceededException -> "File is too large (${formatFileSize(exception.fileSize)}). Maximum allowed size is ${formatFileSize(exception.maxAllowedSize)}."

            is UnknownHostException -> "Unable to connect to the server. Please check your internet connection."

            is ConnectException -> "Failed to connect to the AI service. Please check your connection and try again."

            is SocketTimeoutException -> "The request timed out. Please try again."

            is IOException -> "A network error occurred. Please check your connection and try again."

            else -> null
        }

        return specificMessage ?: fallbackMessage
    }
}
