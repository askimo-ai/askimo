/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.detekt

import dev.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RememberCoroutineScopeInConditionalComposableTest {

    private val rule = RememberCoroutineScopeInConditionalComposable()

    // ── should flag ──────────────────────────────────────────────────────────

    @Test
    fun `flags rememberCoroutineScope inside if-then branch of a Composable`() {
        val findings = rule.lint(
            """
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.rememberCoroutineScope

            @Composable
            fun DownloadButton(isVisible: Boolean) {
                if (isVisible) {
                    val scope = rememberCoroutineScope()
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags rememberCoroutineScope inside if-else branch of a Composable`() {
        val findings = rule.lint(
            """
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.rememberCoroutineScope

            @Composable
            fun MyComponent(show: Boolean) {
                if (show) {
                    // nothing
                } else {
                    val scope = rememberCoroutineScope()
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }

    // ── should NOT flag ──────────────────────────────────────────────────────

    @Test
    fun `does not flag rememberCoroutineScope at top level of Composable`() {
        val findings = rule.lint(
            """
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.rememberCoroutineScope

            @Composable
            fun MyComponent(isVisible: Boolean) {
                val scope = rememberCoroutineScope()
                if (isVisible) {
                    // scope hoisted correctly
                }
            }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag rememberCoroutineScope in non-Composable function`() {
        val findings = rule.lint(
            """
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.Dispatchers

            fun plainFunction(isVisible: Boolean) {
                if (isVisible) {
                    val scope = CoroutineScope(Dispatchers.IO)
                }
            }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag rememberCoroutineScope called inside if condition itself`() {
        val findings = rule.lint(
            """
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.rememberCoroutineScope

            @Composable
            fun MyComponent() {
                val scope = rememberCoroutineScope()
                if (scope.isActive) { }
            }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }
}
