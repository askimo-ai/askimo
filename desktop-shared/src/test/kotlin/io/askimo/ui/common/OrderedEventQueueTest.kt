/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic-outcome stress tests for [OrderedEventQueue]: real `Dispatchers.Default`
 * (multi-threaded) scheduling is used deliberately, repeated many times, specifically to
 * surface the kind of race that a single assertion (or a mocked/virtual-time dispatcher)
 * would never catch — this is the class of bug that originally shipped as out-of-order
 * streamed response text (see [OrderedEventQueue]'s KDoc).
 *
 * All trials run as `suspend` functions driven by a single top-level `runBlocking` per test,
 * never a nested `runBlocking` inside work already dispatched onto `Dispatchers.Default` —
 * that would block one of that dispatcher's own limited worker threads waiting on work that
 * needs a free worker thread to proceed, which can starve/deadlock the pool under enough
 * concurrent trials.
 */
class OrderedEventQueueTest {

    /** A single run: enqueue [count] sequential actions, drain, and assert order held. */
    private suspend fun runOrderingTrial(scope: CoroutineScope, count: Int): List<Int> {
        val queue = OrderedEventQueue()
        val observed = CopyOnWriteArrayList<Int>()
        val job = queue.startProcessing(scope)

        // Enqueue synchronously and sequentially — mirrors how a real streaming callback fires
        // one event at a time from its reader thread. If ordering were only accidentally
        // correct (e.g. due to a race that happens to resolve fast enough), inserting a few
        // actions with a tiny suspend point before recording their index gives the scheduler
        // room to reorder them if the queue didn't actually serialize execution.
        for (i in 0 until count) {
            queue.enqueue {
                if (i % 7 == 0) delay(1)
                observed += i
            }
        }
        queue.close()
        job.join()
        return observed
    }

    @Test
    fun `enqueued actions apply in exact enqueue order under real thread-pool scheduling`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        // Many repetitions on a real multi-threaded dispatcher: a flaky ordering bug would
        // eventually show up here even if it doesn't on every single run.
        repeat(200) { trial ->
            val observed = runOrderingTrial(scope, count = 500)
            assertEquals(
                (0 until 500).toList(),
                observed,
                "Trial $trial: OrderedEventQueue must apply actions in exact enqueue order",
            )
        }
    }

    @Test
    fun `many concurrent queues do not reorder each other under scheduler pressure`() = runBlocking {
        // Run several independent OrderedEventQueue instances truly concurrently (maximizing
        // thread-pool contention/jitter) and verify every single one still drained in order —
        // guards against any shared-state bug that only appears under real concurrent load.
        val scope = CoroutineScope(Dispatchers.Default)
        val results = (0 until 20).map { queueIndex ->
            async(Dispatchers.Default) {
                queueIndex to runOrderingTrial(scope, count = 300)
            }
        }.awaitAll()

        results.forEach { (queueIndex, observed) ->
            assertEquals((0 until 300).toList(), observed, "Queue $queueIndex reordered its actions")
        }
    }

    @Test
    fun `close then join guarantees every enqueued action has already applied`() = runBlocking {
        // Regression test for the "drain-before-finalize" gap: a caller must never read state
        // mutated by the queue until it has closed the queue AND joined the processing job —
        // this proves that sequence is sufficient even when the consumer is deliberately slow.
        val queue = OrderedEventQueue()
        val applied = AtomicBoolean(false)
        val job = queue.startProcessing(CoroutineScope(Dispatchers.Default))

        queue.enqueue {
            delay(Random.nextLong(5, 30))
            applied.set(true)
        }
        queue.close()
        job.join()

        assertTrue(applied.get(), "Action must have fully applied by the time close()+join() returns")
    }

    @Test
    fun `enqueue after close is silently dropped instead of throwing`() {
        val queue = OrderedEventQueue()
        queue.close()
        // Must not throw — late/superseded callbacks (e.g. a stale turn after cancellation)
        // can legitimately still fire and call enqueue().
        queue.enqueue { error("should never run") }
    }
}
