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
 * Regression test for the bug this session fixed in `SessionManager.sendMessage`: the event
 * consumer is started unconditionally before branching on `CreationMode`, but only the chat
 * success/error branches explicitly closed + joined it — the image-generation branch never did,
 * leaking the consumer coroutine for `streamingScope`'s lifetime. The fix added a single
 * close+join in the enclosing `finally`, relying on both calls being safe to repeat. This test
 * proves that safety-net shape: a branch that "forgets" to drain is still drained by a later,
 * unconditional close+join, and an already-drained branch isn't broken by draining twice.
 */
class StreamingThreadDrainSafetyNetTest {

    private fun newThread(): SessionManager.StreamingThread = SessionManager.StreamingThread(
        threadId = "test",
        sessionId = "session",
        job = Job(),
        _timeline = MutableStateFlow(emptyList()),
        _isComplete = MutableStateFlow(false),
        _hasFailed = MutableStateFlow(false),
    )

    @Test
    fun `a branch that never closes or joins is still drained by the safety-net finally`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val thread = newThread()
        val processingJob = thread.startEventProcessing(scope)

        // Models the image-generation branch: events enqueued, but no close()/join() here —
        // that branch relied entirely on the (missing, pre-fix) safety net.
        val expected = (0 until 50).map { "tok$it" }
        for (token in expected) {
            thread.enqueue { thread.appendChunk(token) }
        }

        // The fix: an unconditional close+join after the branch, standing in for the
        // structural `finally` in SessionManager.
        thread.closeEventQueue()
        processingJob.join()

        val actualTokens = thread.timeline.value.filterIsInstance<TurnTimelineEntry.Token>().map { it.text }
        assertEquals(expected, actualTokens, "safety net must still drain every enqueued event")
    }

    @Test
    fun `closing and joining twice is a harmless no-op`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val thread = newThread()
        val processingJob = thread.startEventProcessing(scope)
        thread.enqueue { thread.appendChunk("only-token") }

        // Models the chat success path (drains early, for read-ordering) immediately followed
        // by the safety-net `finally` draining again — must not throw or duplicate entries.
        thread.closeEventQueue()
        processingJob.join()
        thread.closeEventQueue()
        processingJob.join()

        val actualTokens = thread.timeline.value.filterIsInstance<TurnTimelineEntry.Token>().map { it.text }
        assertEquals(listOf("only-token"), actualTokens, "repeated close+join must not duplicate or drop entries")
    }
}
