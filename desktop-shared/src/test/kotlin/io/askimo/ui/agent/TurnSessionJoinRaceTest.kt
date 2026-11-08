/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.agent

import io.askimo.ui.common.OrderedEventQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Regression test for the `AgentRunViewModel` bug this session fixed: a turn's (queue, job)
 * pair lived in two separate mutable instance fields, read again (not from a local) after
 * `isRunning = false` unlocked the UI for a new turn. If the next turn started in that window,
 * it overwrote the fields first, so the original turn joined the *new* turn's job instead of
 * its own — returning before its own backlog had actually drained. The fix (`TurnTimelineSession`
 * + a local captured at creation time) is reproduced here directly against [OrderedEventQueue],
 * without needing to drive the full `AgentRunViewModel`/agent/DB stack.
 */
class TurnSessionJoinRaceTest {

    private data class Session(val queue: OrderedEventQueue, val job: Job)

    @Test
    fun `reading a shared field after unlock can join the next turn's job and miss entries`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        var sharedField: Session? = null
        val applied = mutableListOf<Int>()

        // Turn A: slow backlog (each entry takes 20ms to apply) — mirrors a turn still
        // draining real timeline mutations when the UI unlocks.
        val queueA = OrderedEventQueue()
        sharedField = Session(queueA, queueA.startProcessing(scope))
        repeat(5) { i ->
            queueA.enqueue {
                delay(20.milliseconds)
                applied += i
            }
        }

        // Turn B starts in the race window (simulating a new turn beginning right after
        // `isRunning = false`) and overwrites the shared field with its own, empty session.
        val queueB = OrderedEventQueue()
        sharedField = Session(queueB, queueB.startProcessing(scope))
        queueB.close()

        // BUG shape: turn A closes its own queue but joins via the FIELD, which now holds
        // turn B's (already-finished) job instead of its own.
        queueA.close()
        sharedField.job.join()

        // Joining the wrong (trivially fast) job returns long before turn A's 20ms-per-entry
        // backlog can finish draining.
        assertTrue(applied.size < 5, "expected the wrong join to race ahead of turn A's own drain")

        // THE FIX: capture the job as a local at creation time and join that local — never
        // re-read from a field a later turn may have already overwritten.
        val correctlyApplied = mutableListOf<Int>()
        val queueC = OrderedEventQueue()
        val jobC = queueC.startProcessing(scope)
        repeat(5) { i ->
            queueC.enqueue {
                delay(20.milliseconds)
                correctlyApplied += i
            }
        }
        queueC.close()
        jobC.join()
        assertEquals(5, correctlyApplied.size, "joining the local guarantees every enqueued entry is applied")
    }
}
