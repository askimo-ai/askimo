/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

/**
 * Thrown when a non-transient configuration error is detected during streaming
 * (e.g. invalid API key, missing model, insufficient context window, unreachable provider).
 *
 * Unlike a regular streaming error, this is surfaced as an exception so that the
 * response is saved to the database with [isFailed = true], which enables the user
 * to retry the message after fixing the underlying configuration issue.
 *
 * @property displayMessage The human-readable error message already sent to the UI via [onToken].
 */
class ConfigurationErrorException(
    val displayMessage: String,
) : RuntimeException(displayMessage)
