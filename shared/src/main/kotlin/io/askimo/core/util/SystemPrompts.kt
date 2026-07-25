/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

object SystemPrompts {
    val markdownDefaults =
        listOf(
            "By default, always respond in GitHub-Flavored Markdown.",
        )

    fun systemMessage(vararg extras: String) = (markdownDefaults + extras).joinToString("\n\n")
}
