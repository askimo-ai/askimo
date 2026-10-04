/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.plan

import dev.langchain4j.agentic.scope.AgenticScope
import io.askimo.core.plan.domain.WorkflowNode

/**
 * Evaluates the simple condition expressions used in [WorkflowNode.Conditional].
 *
 * Supported syntax (all string-based, evaluated against the shared [AgenticScope] state):
 *
 * | Expression             | Meaning                                      |
 * |------------------------|----------------------------------------------|
 * | `key == value`         | Exact string match (case-insensitive)        |
 * | `key != value`         | Not-equal check                              |
 * | `key contains text`    | Substring check (case-insensitive)           |
 * | `key`                  | Truthy check — non-blank value in scope      |
 *
 * All values read from scope are coerced to strings via `.toString()`.
 */
internal object PlanConditionEvaluator {

    private val EQUALS_RE = Regex("""^\s*(\w+)\s*==\s*(.+?)\s*$""")
    private val NOTEQUAL_RE = Regex("""^\s*(\w+)\s*!=\s*(.+?)\s*$""")
    private val CONTAINS_RE = Regex("""^\s*(\w+)\s+contains\s+(.+?)\s*$""", RegexOption.IGNORE_CASE)

    /**
     * Returns `true` if [expression] evaluates to true against the values in [scope].
     * Unrecognised expressions default to `false` to avoid accidental execution.
     */
    fun evaluate(expression: String, scope: AgenticScope): Boolean {
        EQUALS_RE.matchEntire(expression)?.let { m ->
            val (key, expected) = m.destructured
            val actual = scope.readState(key)?.toString() ?: ""
            return actual.equals(expected.trim('"', '\''), ignoreCase = true)
        }

        NOTEQUAL_RE.matchEntire(expression)?.let { m ->
            val (key, expected) = m.destructured
            val actual = scope.readState(key)?.toString() ?: ""
            return !actual.equals(expected.trim('"', '\''), ignoreCase = true)
        }

        CONTAINS_RE.matchEntire(expression)?.let { m ->
            val (key, substring) = m.destructured
            val actual = scope.readState(key)?.toString() ?: ""
            return actual.contains(substring.trim('"', '\''), ignoreCase = true)
        }

        // Bare key — truthy if value is present and non-blank
        val bare = expression.trim()
        if (bare.matches(Regex("""\w+"""))) {
            val value = scope.readState(bare)?.toString() ?: ""
            return value.isNotBlank() && value != "false"
        }

        return false
    }
}
