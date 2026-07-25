/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

object SystemPrompts {
    val markdownDefaults =
        listOf(
            "By default, always respond in GitHub-Flavored Markdown.",
            "If you are uncertain or lack reliable information about something, acknowledge it openly and qualify your answer — do not present guesses as facts.",
        )

    fun systemMessage(vararg extras: String) = (markdownDefaults + extras).joinToString("\n\n")
}
