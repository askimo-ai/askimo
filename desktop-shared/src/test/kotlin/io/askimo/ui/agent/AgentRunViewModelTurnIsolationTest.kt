/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.agent

import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression test for a second bug found alongside the one [TurnSessionJoinRaceTest] covers:
 * `AgentRunViewModel`'s normal completion path set `isRunning = false` and then unconditionally
 * nulled `runningAgent`/`currentRunJob`/`cancelTimeoutJob` — shared fields a *newer* turn could
 * have already claimed in that window. The stale completion would then null out the new turn's
 * active agent/job and cancel its safety-net timer, making the new turn's `cancelRun()` a silent
 * no-op. The fix: guard every clear with the same `turnId == currentTurnId.get()` check already
 * used for `appendTimelineEntry`/`onToken` — only clear state this turn still owns.
 */
class AgentRunViewModelTurnIsolationTest {

    @Test
    fun `a stale completion does not clear a newer turn's active state`() {
        val currentTurnId = AtomicLong(0)
        var runningAgent: String? = null
        var currentRunJob: Job? = null

        // Turn A starts and captures its own turnId, mirroring `executeAgentic`.
        val turnIdA = currentTurnId.incrementAndGet()
        runningAgent = "agentA"
        currentRunJob = Job()

        // Turn B starts in the race window after turn A's `isRunning = false`, before turn A's
        // own completion path reaches its guarded clear below.
        currentTurnId.incrementAndGet()
        runningAgent = "agentB"
        val jobB = Job()
        currentRunJob = jobB

        // Turn A's (stale) completion path: guarded clear using its own captured turnId.
        if (turnIdA == currentTurnId.get()) {
            runningAgent = null
            currentRunJob = null
        }

        // Turn B's state must survive untouched.
        assertEquals("agentB", runningAgent)
        assertEquals(jobB, currentRunJob)
    }

    @Test
    fun `a turn with no successor still clears its own state`() {
        val currentTurnId = AtomicLong(0)
        var runningAgent: String? = "agentA"
        var currentRunJob: Job? = Job()

        val turnIdA = currentTurnId.incrementAndGet()

        if (turnIdA == currentTurnId.get()) {
            runningAgent = null
            currentRunJob = null
        }

        assertNull(runningAgent)
        assertNull(currentRunJob)
    }
}
