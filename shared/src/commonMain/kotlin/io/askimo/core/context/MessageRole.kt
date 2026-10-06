/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.context

/**
 * Enum for chat message roles.
 */
enum class MessageRole(val value: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system"),
    TOOL_EXECUTION_RESULT_MESSAGE("tool_result_message"),
    ;

    override fun toString(): String = value
}
