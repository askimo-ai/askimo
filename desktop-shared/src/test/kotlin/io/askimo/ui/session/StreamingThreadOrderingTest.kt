/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.session

import io.askimo.core.chat.dto.TurnTimelineEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression test reproducing the exact production bug this session's fix addresses:
 * response text rendered out of order ("misplaced", not corrupted — see
 * [io.askimo.ui.common.OrderedEventQueue]'s KDoc). Exercises the real production call
 * shape — `thread.enqueue { thread.appendChunk(token) }`, as used by
 * [SessionManager]'s `onToken`/`onThinkingToken`/`onToolStarted`/`onToolFinished`
 * callbacks — end to end, repeated many times under real `Dispatchers.IO` thread-pool
 * scheduling (the same dispatcher `SessionManager.streamingScope` uses), to prove the
 * fix holds under real scheduler jitter, not just a single lucky run.
 */
class StreamingThreadOrderingTest {

    private fun newThread(): SessionManager.StreamingThread = SessionManager.StreamingThread(
        threadId = "test",
        sessionId = "session",
        job = Job(),
        _timeline = MutableStateFlow(emptyList()),
        _isComplete = MutableStateFlow(false),
        _hasFailed = MutableStateFlow(false),
    )

    @Test
    fun `tokens emitted from a background reader thread apply in exact arrival order`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO)
        repeat(100) { trial ->
            val thread = newThread()
            val processingJob = thread.startEventProcessing(scope)
            val expected = (0 until 200).map { "tok$it-" }

            // Simulate the real production shape: a single reader thread invoking onToken
            // synchronously, one at a time — exactly what SessionManager's onToken callback
            // does (`thread.enqueue { thread.appendChunk(token) }`), which is the line that
            // used to instead be `streamingScope.launch { thread.appendChunk(token) }` and
            // could silently reorder tokens under real thread-pool scheduling.
            for (token in expected) {
                thread.enqueue { thread.appendChunk(token) }
            }

            thread.closeEventQueue()
            processingJob.join()

            val actualTokens = thread.timeline.value.filterIsInstance<TurnTimelineEntry.Token>().map { it.text }
            assertEquals(expected, actualTokens, "Trial $trial: tokens must apply in arrival order")
        }
    }

    @Test
    fun `interleaved tokens and tool events preserve true chronological order`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO)
        repeat(50) { trial ->
            val thread = newThread()
            val processingJob = thread.startEventProcessing(scope)

            // A representative interleaving — text, tool call, more text — mirrors a real
            // tool-using turn, exercising the same enqueue() call shape used by
            // SessionManager's onToken/onToolStarted/onToolFinished/onThinkingToken callbacks.
            thread.enqueue { thread.appendChunk("intro-") }
            thread.enqueue { thread.markToolRunning("call-1", "search", "{}") }
            thread.enqueue { thread.markToolDone("call-1", "search", "{}", "result", false) }
            thread.enqueue { thread.appendChunk("outro-") }

            thread.closeEventQueue()
            processingJob.join()

            val kinds = thread.timeline.value.map {
                when (it) {
                    is TurnTimelineEntry.Token -> "token:${it.text}"

                    // markToolDone flips the matching RUNNING entry to DONE in place (same
                    // timeline slot) rather than appending a second entry — see
                    // StreamingThread.markToolDone.
                    is TurnTimelineEntry.Tool -> "tool:${it.toolCall.status}"

                    else -> "other"
                }
            }
            assertEquals(
                listOf("token:intro-", "tool:DONE", "token:outro-"),
                kinds,
                "Trial $trial: interleaved events must preserve chronological order",
            )
        }
    }
}
