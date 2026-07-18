/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.detekt

import dev.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NestedScrollInScrollingWrapperTest {

    private val rule = NestedScrollInScrollingWrapper()

    // ── should flag ──────────────────────────────────────────────────────────

    @Test
    fun `flags verticalScroll used as trailing lambda inside scaffoldDialog`() {
        val findings = rule.lint(
            """
            import androidx.compose.foundation.verticalScroll
            import androidx.compose.foundation.rememberScrollState
            import androidx.compose.ui.Modifier

            fun caller() {
                scaffoldDialog(onDismissRequest = {}, actions = {}) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) { }
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
        assertEquals("NestedScrollInScrollingWrapper", findings.first().ruleName.value)
    }

    @Test
    fun `flags horizontalScroll inside scaffoldDialog`() {
        val findings = rule.lint(
            """
            import androidx.compose.foundation.horizontalScroll
            import androidx.compose.foundation.rememberScrollState
            import androidx.compose.ui.Modifier

            fun caller() {
                scaffoldDialog(onDismissRequest = {}, actions = {}) {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) { }
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
        assertEquals("NestedScrollInScrollingWrapper", findings.first().ruleName.value)
    }

    @Test
    fun `flags verticalScroll nested inside scaffoldDialogLazyColumn`() {
        val findings = rule.lint(
            """
            import androidx.compose.foundation.verticalScroll
            import androidx.compose.foundation.rememberScrollState
            import androidx.compose.ui.Modifier

            fun caller() {
                scaffoldDialogLazyColumn(onDismissRequest = {}, actions = {}) {
                    item {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) { }
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags verticalScroll passed as named content argument to scaffoldDialog`() {
        val findings = rule.lint(
            """
            import androidx.compose.foundation.verticalScroll
            import androidx.compose.foundation.rememberScrollState
            import androidx.compose.ui.Modifier

            fun caller() {
                scaffoldDialog(
                    onDismissRequest = {},
                    actions = {},
                    content = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) { }
                    },
                )
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags verticalScroll with fully qualified Column and Composable annotation`() {
        val findings = rule.lint(
            """
            import androidx.compose.foundation.verticalScroll
            import androidx.compose.foundation.rememberScrollState
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier

            @Composable
            fun smokeTestViolation() {
                scaffoldDialog(onDismissRequest = {}, actions = {}) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    ) { }
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size, "Expected 1 finding but got ${findings.size}: $findings")
    }

    @Test
    fun `flags verticalScroll assigned to val inside scaffoldDialog lambda in object`() {
        // Mirrors exactly the smokeTestViolation pattern in AppComponents.kt:
        // scaffoldDialog called as a member of an object, with verticalScroll
        // stored in a local val rather than passed directly as a modifier arg.
        val findings = rule.lint(
            """
            import androidx.compose.foundation.verticalScroll
            import androidx.compose.foundation.rememberScrollState
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier

            object AppComponents {
                @Composable
                fun scaffoldDialog(
                    onDismissRequest: () -> Unit,
                    actions: () -> Unit,
                    content: () -> Unit,
                ) { content() }

                @Composable
                fun smokeTestViolation() {
                    scaffoldDialog(onDismissRequest = {}, actions = {}) {
                        val m = Modifier.verticalScroll(rememberScrollState())
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size, "Expected 1 finding but got ${findings.size}: $findings")
    }

    // ── should NOT flag ──────────────────────────────────────────────────────

    @Test
    fun `does not flag verticalScroll outside any scrolling wrapper`() {
        val findings = rule.lint(
            """
            import androidx.compose.foundation.verticalScroll
            import androidx.compose.foundation.rememberScrollState
            import androidx.compose.ui.Modifier

            fun plainScreen() {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) { }
            }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `verticalScroll with heightIn inside scaffoldDialog is still flagged`() {
        val findings = rule.lint(
            """
            import androidx.compose.foundation.verticalScroll
            import androidx.compose.foundation.rememberScrollState
            import androidx.compose.foundation.layout.heightIn
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.unit.dp

            fun caller() {
                scaffoldDialog(onDismissRequest = {}, actions = {}) {
                    Column(modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) { }
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `does not flag verticalScroll defined inside a separately named composable`() {
        val findings = rule.lint(
            """
            import androidx.compose.foundation.verticalScroll
            import androidx.compose.foundation.rememberScrollState
            import androidx.compose.ui.Modifier

            fun innerContent() {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) { }
            }

            fun caller() {
                scaffoldDialog(onDismissRequest = {}, actions = {}) {
                    innerContent()
                }
            }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag verticalScroll inside an unknown wrapper`() {
        val findings = rule.lint(
            """
            import androidx.compose.foundation.verticalScroll
            import androidx.compose.foundation.rememberScrollState
            import androidx.compose.ui.Modifier

            fun caller() {
                someOtherDialog {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) { }
                }
            }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }
}
