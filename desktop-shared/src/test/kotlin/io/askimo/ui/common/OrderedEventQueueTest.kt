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
 * (multi-threaded) scheduling, repeated many times, to surface the race that originally
 * shipped as out-of-order streamed response text (see [OrderedEventQueue]'s KDoc) — a single
 * assertion or virtual-time dispatcher would never catch it.
 *
 * Each trial runs as a `suspend` function off one top-level `runBlocking`, never a nested
 * `runBlocking` inside work already on `Dispatchers.Default` — that would block one of its
 * limited worker threads waiting on another, risking starvation/deadlock under concurrency.
 */
class OrderedEventQueueTest {

    /** A single run: enqueue [count] sequential actions, drain, and assert order held. */
    private suspend fun runOrderingTrial(scope: CoroutineScope, count: Int): List<Int> {
        val queue = OrderedEventQueue()
        val observed = CopyOnWriteArrayList<Int>()
        val job = queue.startProcessing(scope)

        // Mirrors a real streaming callback firing one event at a time. A tiny `delay` before
        // recording some indices gives the scheduler room to reorder them if the queue didn't
        // actually serialize execution.
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
        // Many repetitions on a real multi-threaded dispatcher surface a flaky ordering bug
        // even if it doesn't reproduce on every single run.
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
        // Run several independent queues truly concurrently (maximizing thread-pool
        // contention) and verify every one still drained in order — guards against any
        // shared-state bug that only appears under real concurrent load.
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
        // Regression test for "drain-before-finalize": proves close()+join() is sufficient
        // before reading queue-mutated state, even with a deliberately slow consumer.
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
