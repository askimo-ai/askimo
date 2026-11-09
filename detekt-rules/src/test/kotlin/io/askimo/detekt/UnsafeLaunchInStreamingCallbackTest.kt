/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.detekt

import dev.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnsafeLaunchInStreamingCallbackTest {

    private val rule = UnsafeLaunchInStreamingCallback()

    // ── should flag ──────────────────────────────────────────────────────────

    @Test
    fun `flags launch directly inside onToken callback`() {
        val findings = rule.lint(
            """
            fun start(scope: CoroutineScope, thread: Thread) {
                sendStreamingMessageWithCallback(
                    onToken = { token ->
                        scope.launch { thread.appendChunk(token) }
                    },
                )
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags launch inside onToolStarted callback`() {
        val findings = rule.lint(
            """
            fun start(scope: CoroutineScope, thread: Thread) {
                sendStreamingMessageWithCallback(
                    onToolStarted = { id, toolName, arguments ->
                        scope.launch { thread.markToolRunning(id, toolName, arguments) }
                    },
                )
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags launch inside onStatus callback`() {
        val findings = rule.lint(
            """
            fun start(scope: CoroutineScope) {
                runAgent(
                    onStatus = { message ->
                        scope.launch { appendStatus(message) }
                    },
                )
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags plain launch (no receiver) inside onThinkingToken callback`() {
        val findings = rule.lint(
            """
            fun CoroutineScope.start() {
                sendStreamingMessageWithCallback(
                    onThinkingToken = { token ->
                        launch { appendThinkingChunk(token) }
                    },
                )
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }

    // ── should NOT flag ──────────────────────────────────────────────────────

    @Test
    fun `does not flag enqueue inside onToken callback`() {
        val findings = rule.lint(
            """
            fun start(thread: Thread) {
                sendStreamingMessageWithCallback(
                    onToken = { token ->
                        thread.enqueue { thread.appendChunk(token) }
                    },
                )
            }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag launch inside a non-streaming callback name`() {
        val findings = rule.lint(
            """
            fun start(scope: CoroutineScope) {
                Button(
                    onClick = {
                        scope.launch { doSomething() }
                    },
                )
            }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag launch outside of any named-argument callback`() {
        val findings = rule.lint(
            """
            fun start(scope: CoroutineScope) {
                scope.launch { doSomething() }
            }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty())
    }
}
