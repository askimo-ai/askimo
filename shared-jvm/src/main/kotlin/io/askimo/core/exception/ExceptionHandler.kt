/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.exception

import io.askimo.core.AppConstants
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

    /**
     * Same as [handle], but falls back to a caller-supplied [fallbackMessage] instead of the
     * generic "contact support" copy ([SystemException]'s `error.system` message) when
     * [throwable] can't be classified into a more specific [AskimoException] — e.g. an
     * operation-specific message like "Failed to load sessions. Please try again." that's
     * more useful to the user than a generic system-error template.
     *
     * Classified errors (network, auth, attachment, etc.) are still rendered normally —
     * [fallbackMessage] only applies to the truly-unclassified [SystemException] case, so
     * callers always get the most specific message available.
     *
     * @param throwable The exception to handle
     * @param contextId Optional context ID (session ID, command name, etc.) for logging
     * @param fallbackMessage Message to show when [throwable] maps to a generic [SystemException]
     * @return Localized user-friendly error message, or [fallbackMessage] if unclassified
     */
    fun handle(
        throwable: Throwable,
        contextId: String? = null,
        fallbackMessage: String,
    ): String {
        val mapped = ExceptionMapper.map(throwable)
        val logPrefix = contextId?.let { "[$it] " } ?: ""

        return if (mapped is SystemException) {
            log.error("${logPrefix}System error: ${mapped.message}", throwable)
            fallbackMessage
        } else {
            render(mapped, contextId)
        }
    }

    /**
     * Renders the localized, user-facing message for [askimoException] — same content as
     * [handle] — without the logging side effect.
     *
     * @param askimoException The already-classified exception to render
     * @return Localized user-friendly error message (same content [handle] would show)
     */
    fun renderMessage(askimoException: AskimoException): String = renderOnly(askimoException)

    /**
     * Localized template + substituted `{placeholder}` args for [askimoException] —
     * without the report-bug hint [renderOnly] appends. For callers (e.g.
     * `GlobalErrorHandler`) that append their own hint/forum link and would otherwise end up
     * with it duplicated.
     *
     * @param askimoException The already-classified exception to render
     * @return Localized message with args substituted, no trailing hint
     */
    fun localizedMessage(askimoException: AskimoException): String {
        val localizedMessage = LocalizationManager.getString(askimoException.getMessageKey())

        var result = localizedMessage
        askimoException.getMessageArgs().forEach { (key, value) ->
            result = result.replace("{$key}", value)
        }

        return result
    }

    private fun render(
        askimoException: AskimoException,
        contextId: String?,
    ): String {
        val result = renderOnly(askimoException)

        // Log appropriately
        val logPrefix = contextId?.let { "[$it] " } ?: ""
        if (askimoException.isUserError()) {
            log.warn("${logPrefix}User error: ${askimoException.message}")
        } else {
            log.error("${logPrefix}System error: ${askimoException.message}", askimoException)
        }

        return result
    }

    /** Pure rendering — localized template + substituted args + the report-bug hint. No logging. */
    private fun renderOnly(askimoException: AskimoException): String {
        val hint = LocalizationManager.getString(
            "error.report_bug_hint",
            "https://${AppConstants.DOMAIN}/forum/section/?s=help",
        )

        return "${localizedMessage(askimoException)}\n\n$hint"
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
