/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.context

/**
 * Defines the execution mode of the application using bit flags.
 *
 * This class is used to track how the user is running the application,
 * which can be useful for applying different business rules based on the context.
 */
@JvmInline
value class ExecutionMode(val flags: Int) {

    fun isToolEnabled(): Boolean = (flags and TOOL_ENABLED) != 0

    operator fun plus(other: ExecutionMode): ExecutionMode = ExecutionMode(flags or other.flags)

    operator fun minus(other: ExecutionMode): ExecutionMode = ExecutionMode(flags and other.flags.inv())

    companion object {
        const val STATELESS = 1
        const val STATEFUL = 2
        const val TOOL_ENABLED = 4

        val STATELESS_MODE = ExecutionMode(STATELESS)
        val STATEFUL_MODE = ExecutionMode(STATEFUL)
        val STATEFUL_TOOLS_MODE = ExecutionMode(STATEFUL xor TOOL_ENABLED)
    }
}
