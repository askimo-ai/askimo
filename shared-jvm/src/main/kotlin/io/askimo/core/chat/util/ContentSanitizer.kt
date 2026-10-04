/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.util

/**
 * Sanitizes content to prevent conflicts with template engines.
 * Escapes template variable syntax (e.g., {{variable}}) by adding spaces.
 */
object ContentSanitizer {

    /**
     * Sanitize template variables in content by escaping mustache syntax.
     * Replaces {{ with { { and }} with } } to prevent template engine errors.
     */
    fun sanitizeTemplateVariables(content: String): String = content
        .replace("{{", "{ {")
        .replace("}}", "} }")
}
