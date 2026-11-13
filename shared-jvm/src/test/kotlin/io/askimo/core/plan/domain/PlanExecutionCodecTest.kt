/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.plan.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlanExecutionCodecTest {

    @Test
    fun `decodeInputs round-trips an empty map`() {
        assertTrue(PlanExecutionCodec.decodeInputs(PlanExecutionCodec.encodeInputs(emptyMap())).isEmpty())
    }

    @Test
    fun `decodeInputs round-trips values containing real newlines`() {
        val inputs = mapOf("note" to "line1\nline2")

        val decoded = PlanExecutionCodec.decodeInputs(PlanExecutionCodec.encodeInputs(inputs))

        assertEquals(inputs, decoded)
    }

    @Test
    fun `decodeInputs round-trips values containing a literal backslash-n sequence`() {
        // Regression test: the previous key=value encoding escaped real newlines as the two
        // literal characters backslash+n, which collided with values that already contained
        // that exact sequence and silently turned them into real newlines on decode.
        val inputs = mapOf("note" to "a\\nb")

        val decoded = PlanExecutionCodec.decodeInputs(PlanExecutionCodec.encodeInputs(inputs))

        assertEquals(inputs, decoded)
    }

    @Test
    fun `decodeStepOutputs round-trips structured output`() {
        val steps = listOf(
            PlanStepOutput(stepName = "step-a", output = "out-a", inputTokens = 10, outputTokens = 20, totalTokens = 30, durationMs = 500),
        )

        val decoded = PlanExecutionCodec.decodeStepOutputs(PlanExecutionCodec.encodeStepOutputs(steps))

        assertEquals(steps, decoded)
    }

    @Test
    fun `encodeStepOutputs returns null for an empty list`() {
        assertEquals(null, PlanExecutionCodec.encodeStepOutputs(emptyList()))
    }

    @Test
    fun `decodeStepOutputs tolerates the legacy stepName-output line format`() {
        val decoded = PlanExecutionCodec.decodeStepOutputs("step-a|out-a\nstep-b|out-b")

        assertEquals(2, decoded.size)
        assertEquals("step-a", decoded[0].stepName)
        assertEquals("out-a", decoded[0].output)
    }
}
