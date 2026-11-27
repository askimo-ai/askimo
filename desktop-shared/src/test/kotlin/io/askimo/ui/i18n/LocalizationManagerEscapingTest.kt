/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.i18n

import io.askimo.core.exception.ExceptionHandler
import io.askimo.core.exception.RateLimitException
import io.askimo.core.exception.RemoteServerException
import io.askimo.core.exception.TimeoutException
import io.askimo.core.i18n.LocalizationManager
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression test: [ExceptionHandler.localizedMessage] calls [LocalizationManager.getString]
 * with no args, so its `MessageFormat` branch never runs and placeholders are substituted via
 * plain `String.replace` instead. That means `''` in keys like `error.timeout`,
 * `error.remote_server`, and `error.rate_limit` leaks to the user as two apostrophes instead
 * of collapsing to one — these keys must use plain, un-doubled apostrophes.
 */
class LocalizationManagerEscapingTest {

    @Test
    fun `getString with no args returns the raw template without running it through MessageFormat`() {
        val key = "error.timeout"
        val rawTemplate = LocalizationManager.getString(key)
        assertTrue(
            rawTemplate.contains("{timeout}"),
            "getString(key) with no args must return the raw template, placeholders untouched. Got: $rawTemplate",
        )
    }

    @Test
    fun `TimeoutException message rendered via ExceptionHandler must not leak doubled apostrophes`() {
        val mapped = TimeoutException(timeoutSeconds = 600)
        val rendered = ExceptionHandler.localizedMessage(mapped)

        assertFalse(
            rendered.contains("''"),
            "Rendered user-facing message leaked a literal '' — ExceptionHandler.localizedMessage() " +
                "substitutes {placeholders} via plain String.replace, not MessageFormat, so message " +
                "templates for AskimoException keys must use plain un-doubled apostrophes. Got: $rendered",
        )
        assertTrue(rendered.contains("600"), "Expected the {timeout} placeholder to be substituted. Got: $rendered")
    }

    @Test
    fun `RemoteServerException message rendered via ExceptionHandler must not leak doubled apostrophes`() {
        val mapped = RemoteServerException(details = "503 UNAVAILABLE")
        val rendered = ExceptionHandler.localizedMessage(mapped)

        assertFalse(
            rendered.contains("''"),
            "Rendered user-facing message leaked a literal ''. Got: $rendered",
        )
        assertTrue(
            rendered.contains("503 UNAVAILABLE"),
            "Expected the {details} placeholder to be substituted. Got: $rendered",
        )
    }

    @Test
    fun `RateLimitException message rendered via ExceptionHandler must not leak doubled apostrophes`() {
        val mapped = RateLimitException(retryAfterSeconds = 30)
        val rendered = ExceptionHandler.localizedMessage(mapped)

        assertFalse(
            rendered.contains("''"),
            "Rendered user-facing message leaked a literal ''. Got: $rendered",
        )
    }

    @Test
    fun `TimeoutException with no provider renders without a leftover placeholder or provider name`() {
        val rendered = ExceptionHandler.localizedMessage(TimeoutException(timeoutSeconds = 600))

        assertFalse(rendered.contains("{provider}"), "Default provider arg must not leave the placeholder unexpanded. Got: $rendered")
        assertFalse(rendered.contains("null"), "Default provider arg must not render the literal word 'null'. Got: $rendered")
    }

    @Test
    fun `TimeoutException with a provider name includes it in the rendered message`() {
        val rendered = ExceptionHandler.localizedMessage(TimeoutException(timeoutSeconds = 600, provider = "Gemini"))

        assertTrue(rendered.contains("Gemini"), "Expected the provider name to appear in the rendered message. Got: $rendered")
        assertFalse(rendered.contains("{provider}"), "Placeholder must be fully substituted. Got: $rendered")
    }

    @Test
    fun `RemoteServerException with no provider renders without a leftover placeholder or provider name`() {
        val rendered = ExceptionHandler.localizedMessage(RemoteServerException(details = "503"))

        assertFalse(rendered.contains("{provider}"), "Default provider arg must not leave the placeholder unexpanded. Got: $rendered")
        assertFalse(rendered.contains("null"), "Default provider arg must not render the literal word 'null'. Got: $rendered")
    }

    @Test
    fun `RemoteServerException with a provider name includes it in the rendered message`() {
        val rendered = ExceptionHandler.localizedMessage(RemoteServerException(details = "503", provider = "Gemini"))

        assertTrue(rendered.contains("Gemini"), "Expected the provider name to appear in the rendered message. Got: $rendered")
        assertFalse(rendered.contains("{provider}"), "Placeholder must be fully substituted. Got: $rendered")
    }

    @Test
    fun `RateLimitException with no provider renders without a leftover placeholder or provider name`() {
        val rendered = ExceptionHandler.localizedMessage(RateLimitException(retryAfterSeconds = 30))

        assertFalse(rendered.contains("{provider}"), "Default provider arg must not leave the placeholder unexpanded. Got: $rendered")
        assertFalse(rendered.contains("null"), "Default provider arg must not render the literal word 'null'. Got: $rendered")
    }

    @Test
    fun `RateLimitException with a provider name includes it in the rendered message`() {
        val rendered = ExceptionHandler.localizedMessage(RateLimitException(retryAfterSeconds = 30, provider = "Gemini"))

        assertTrue(rendered.contains("Gemini"), "Expected the provider name to appear in the rendered message. Got: $rendered")
        assertFalse(rendered.contains("{provider}"), "Placeholder must be fully substituted. Got: $rendered")
    }
}
