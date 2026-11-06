/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.jupiter.api.Tag
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.test.Test

/**
 * Lincheck harness for [OrderedEventQueue]: multiple threads call [submit] concurrently
 * (mirroring concurrent/racy callback arrival) with a value Lincheck itself generates and
 * tracks as the operation's explicit argument — never from hidden/thread-local state. That
 * matters: Lincheck's linearizability verifier re-checks a candidate call order by replaying
 * operations *sequentially on this very class*, using its own single verification thread, not
 * the original scenario threads — any "who called this" identity derived from `Thread.current`
 * or a `ThreadLocal` is silently unreproducible during that replay and produces false-positive
 * failures unrelated to [OrderedEventQueue] (this was tried and empirically confirmed broken
 * before landing on this design). An explicit method argument has no such problem: it's part
 * of the recorded call itself and replays identically regardless of which thread "says" it.
 *
 * [appliedSoFar] forces a deterministic flush (enqueues a barrier action and **blocks** until
 * the consumer coroutine reaches it) before reading back what's been applied so far, so the
 * result is never "still catching up". It's a plain blocking call, not a `suspend` function:
 * [OrderedEventQueue]'s consumer runs on its own `Dispatchers.Default` coroutine, a thread
 * Lincheck doesn't manage as part of the scenario — a `suspend` operation that resumes from
 * such a foreign/unmanaged thread confuses Lincheck's suspended-call bookkeeping (observed as
 * an internal `ResumedResult` failure). A synchronous block is just an ordinary (if slow)
 * method call from Lincheck's point of view, which it already knows how to schedule.
 *
 * Uses [StressOptions] (real-thread repeated stress) rather than Lincheck's bytecode-
 * instrumenting `ModelCheckingOptions`: the latter fails outright trying to transform
 * `kotlinx-coroutines` internals reached via the queue's background consumer, and — even if it
 * didn't — a managed/deterministically-scheduled operation thread blocking on a real,
 * unmanaged background thread risks deadlocking the model checker's own scheduler. The bug
 * class this guards against (concurrent *producer* threads racing on enqueue order) is fully
 * exercised by `StressOptions` regardless, since Lincheck's declared operation threads are the
 * producers here.
 *
 * Because [submit]/[appliedSoFar] have no declared sequential spec of their own, Lincheck uses
 * this very class, run single-threaded, as the reference — which is exactly right: called one
 * at a time, values obviously come back in call order. Lincheck's linearizability checker then
 * searches for *some* legal interleaving of the concurrently observed calls that reproduces
 * that same guarantee; [OrderedEventQueue] must make that true unconditionally, because it
 * linearizes every [enqueue][OrderedEventQueue.enqueue] at the real-time instant it's called,
 * regardless of which thread calls it.
 */
@Tag("lincheck")
class OrderedEventQueueLincheckTest {
    private val queue = OrderedEventQueue()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val consumerJob = queue.startProcessing(scope)

    // CopyOnWriteArrayList: the single consumer coroutine is the only writer, but appliedSoFar()
    // can be called concurrently from any actor thread while the consumer keeps applying later
    // actions — a plain ArrayList isn't safe for that concurrent read/write mix.
    private val applied = CopyOnWriteArrayList<Int>()

    @Operation
    fun submit(value: Int) {
        queue.enqueue { applied += value }
    }

    @Operation
    fun appliedSoFar(): List<Int> {
        val flushed = CountDownLatch(1)
        queue.enqueue { flushed.countDown() }
        flushed.await()
        return applied.toList()
    }

    @Test
    fun `OrderedEventQueue preserves real-time submission order under stress`() {
        StressOptions()
            .threads(3)
            .actorsPerThread(4)
            .actorsBefore(0)
            .actorsAfter(2)
            .invocationsPerIteration(2000)
            .iterations(50)
            .check(this::class)
    }
}
