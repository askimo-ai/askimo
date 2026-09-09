/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.exception

import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.logging.logger

/**
 * Handles exceptions by converting them to localized user-friendly messages.
 * This centralizes exception handling logic for reusability across CLI and Desktop.
 */
object ExceptionHandler {
    private val log = logger<ExceptionHandler>()

    /**
     * Handle an exception and return a localized user message.
     * Also logs the exception appropriately based on whether it's a user or system error.
     *
     * @param throwable The exception to handle
     * @param contextId Optional context ID (session ID, command name, etc.) for logging
     * @return Localized user-friendly error message
     */
    fun handle(
        throwable: Throwable,
        contextId: String? = null,
    ): String = render(ExceptionMapper.map(throwable), contextId)

    /**
     * Same as [handle], but for a [Throwable] the caller has already classified via
     * [ExceptionMapper.map] — e.g. because it needed the classification for something else
     * first (like deciding a retry policy). Avoids re-running classification a second time,
     * which for previously-unmapped errors would also re-log the "unmapped exception" warning.
     *
     * @param askimoException The already-classified exception to render
     * @param contextId Optional context ID (session ID, command name, etc.) for logging
     * @return Localized user-friendly error message
     */
    fun handle(
        askimoException: AskimoException,
        contextId: String? = null,
    ): String = render(askimoException, contextId)

    private fun render(
        askimoException: AskimoException,
        contextId: String?,
    ): String {
        // Get localized message (uses LocalizationManager.getCurrentLocale() internally)
        val localizedMessage = LocalizationManager.getString(
            askimoException.getMessageKey(),
        )

        // Replace placeholders with actual values
        var result = localizedMessage
        askimoException.getMessageArgs().forEach { (key, value) ->
            result = result.replace("{$key}", value)
        }

        // Log appropriately
        val logPrefix = contextId?.let { "[$it] " } ?: ""
        if (askimoException.isUserError()) {
            log.warn("${logPrefix}User error: ${askimoException.message}")
        } else {
            log.error("${logPrefix}System error: ${askimoException.message}", askimoException)
        }

        return result
    }

    /**
     * Handle an exception with partial content (useful for streaming responses).
     * Combines the partial content with the localized error message.
     *
     * @param throwable The exception to handle
     * @param partialContent Partial content that was generated before the error
     * @param contextId Optional context ID for logging
     * @return Partial content combined with localized error message
     */
    fun handleWithPartialContent(
        throwable: Throwable,
        partialContent: String,
        contextId: String? = null,
    ): String {
        val errorMessage = handle(throwable, contextId)

        return if (partialContent.isNotBlank()) {
            "$partialContent\n\n$errorMessage"
        } else {
            errorMessage
        }
    }
}
