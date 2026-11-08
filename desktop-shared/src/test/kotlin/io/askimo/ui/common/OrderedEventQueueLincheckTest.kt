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
 * (mirroring racy callback arrival), passing `value` as an explicit method argument rather than
 * reading hidden/thread-local state. This matters because Lincheck's verifier replays a
 * candidate call order *sequentially on this class*, on its own thread — any identity derived
 * from `Thread.current`/`ThreadLocal` is unreproducible during replay and produces false
 * failures (confirmed by trying it). An explicit argument replays identically regardless of
 * which thread "said" it.
 *
 * [appliedSoFar] enqueues a barrier action and **blocks** until the consumer reaches it, so
 * reads are never "still catching up". It's a plain blocking call, not `suspend`: the queue's
 * consumer runs on its own `Dispatchers.Default` coroutine outside Lincheck's managed scenario
 * threads, and a `suspend` operation resuming from such a foreign thread breaks Lincheck's
 * suspended-call bookkeeping (`ResumedResult` failure). A synchronous call is just an ordinary
 * method call from Lincheck's point of view.
 *
 * Uses [StressOptions] (real-thread stress) instead of `ModelCheckingOptions`: the latter fails
 * transforming `kotlinx-coroutines` internals reached via the background consumer, and a
 * managed operation thread blocking on an unmanaged one risks deadlocking the model checker's
 * own scheduler. `StressOptions` still fully exercises the bug class this guards against
 * (producer threads racing on enqueue order), since the declared operation threads are exactly
 * those producers.
 *
 * [submit]/[appliedSoFar] have no declared sequential spec, so Lincheck uses this class run
 * single-threaded as the reference (correct, since one-at-a-time calls obviously return in call
 * order) and searches for a legal interleaving of concurrent calls reproducing that guarantee —
 * which [OrderedEventQueue] must satisfy unconditionally, since it linearizes every
 * [enqueue][OrderedEventQueue.enqueue] at the real-time instant it's called.
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
