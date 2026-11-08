/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.jupiter.api.Tag
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Canary: reconstructs the exact anti-pattern [OrderedEventQueue] exists to prevent — a
 * `scope.launch { }` fired per callback event, with a `Mutex` only guaranteeing each append is
 * atomic, never that launches apply in call order — and proves Lincheck actually detects it.
 * If this test ever stops failing, the Lincheck setup has silently lost its teeth and must be
 * investigated before trusting [OrderedEventQueueLincheckTest]'s green runs.
 *
 * `submit`/`appliedSoFar` are shaped identically to [OrderedEventQueueLincheckTest] (explicit
 * `value: Int` argument, not thread-derived identity — see that class's doc for why) so the
 * only difference is what's under test: an ordered queue vs. independent launches.
 */
@Tag("lincheck")
class UnsafeLaunchCanaryLincheckTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val applied = CopyOnWriteArrayList<Int>()

    @Operation
    fun submit(value: Int) {
        // The bug: an independently-scheduled coroutine per event. The Mutex makes the append
        // atomic but guarantees nothing about launch order — exactly the shipped
        // "misplaced response text" bug.
        scope.launch {
            mutex.withLock { applied += value }
        }
    }

    @Operation
    fun appliedSoFar(): List<Int> {
        val flushed = CountDownLatch(1)
        scope.launch {
            mutex.withLock { flushed.countDown() }
        }
        flushed.await()
        return applied.toList()
    }

    @Test
    fun `canary- unordered scope-launch-per-event is caught by Lincheck`() {
        val options =
            StressOptions()
                .threads(3)
                .actorsPerThread(4)
                .actorsBefore(0)
                .actorsAfter(2)
                .invocationsPerIteration(2000)
                .iterations(50)

        val failure =
            try {
                options.check(this::class)
                null
            } catch (e: LincheckAssertionError) {
                e
            }

        assertTrue(
            failure != null,
            "Expected Lincheck to catch the unordered scope.launch-per-event anti-pattern, " +
                "but it reported no failure — the Lincheck harness may have lost its ability " +
                "to detect this bug class; investigate before trusting OrderedEventQueueLincheckTest.",
        )
    }
}
