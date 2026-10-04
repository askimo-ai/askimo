/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.plan.repository

import io.askimo.core.db.DatabaseManager
import io.askimo.core.plan.domain.PlanExecution
import io.askimo.core.plan.domain.PlanExecutionStatus
import io.askimo.core.plan.domain.PlanStepOutput
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.collections.get
import kotlin.text.get

/**
 * Integration tests for the SQLDelight-backed [PlanExecutionRepository] /
 * [DatabaseManager]. No Exposed-based IT test existed to mirror, so this covers every
 * public method directly.
 */
class PlanExecutionRepositoryIT {

    @AfterEach
    fun tearDown() {
        repository.findAll().forEach { repository.delete(it.id) }
    }

    companion object {
        private lateinit var testBaseScope: AskimoHome.TestBaseScope
        private lateinit var databaseManager: DatabaseManager
        private lateinit var repository: PlanExecutionRepository

        @JvmStatic
        @BeforeAll
        fun setUpClass(@TempDir tempDir: Path) {
            testBaseScope = AskimoHome.withTestBase(tempDir)
            databaseManager = DatabaseManager.getInMemoryTestInstance(this)
            repository = databaseManager.getPlanExecutionRepository()
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            if (::databaseManager.isInitialized) databaseManager.close()
            if (::testBaseScope.isInitialized) testBaseScope.close()
        }
    }

    @Test
    fun `should create and find by id`() {
        val created = repository.create(
            PlanExecution(id = "", planId = "plan-1", planName = "My Plan", inputs = mapOf("k1" to "v1")),
        )

        assertTrue(created.id.isNotBlank())

        val found = repository.findById(created.id)
        assertNotNull(found)
        assertEquals("plan-1", found.planId)
        assertEquals("My Plan", found.planName)
        assertEquals(mapOf("k1" to "v1"), found.inputs)
        assertEquals(PlanExecutionStatus.IDLE, found.status)
    }

    @Test
    fun `should return null for non-existent id`() {
        assertNull(repository.findById("non-existent"))
    }

    @Test
    fun `should preserve multiline input values via escaping`() {
        val created = repository.create(
            PlanExecution(id = "", planId = "plan-1", planName = "P", inputs = mapOf("note" to "line1\nline2")),
        )

        val found = repository.findById(created.id)
        assertEquals("line1\nline2", found!!.inputs["note"])
    }

    @Test
    fun `should update mutable fields`() {
        val created = repository.create(PlanExecution(id = "", planId = "plan-1", planName = "P"))

        val updated = repository.update(
            created.copy(
                status = PlanExecutionStatus.COMPLETED,
                sessionId = "session-1",
                output = "final output",
                stepOutputs = listOf(PlanStepOutput(stepName = "step-a", output = "out-a", inputTokens = 10, outputTokens = 20, totalTokens = 30, durationMs = 500)),
                totalInputTokens = 10,
                totalOutputTokens = 20,
                totalTokens = 30,
                totalDurationMs = 500,
                runCount = 2,
            ),
        )

        assertEquals(PlanExecutionStatus.COMPLETED, updated.status)

        val found = repository.findById(created.id)!!
        assertEquals(PlanExecutionStatus.COMPLETED, found.status)
        assertEquals("session-1", found.sessionId)
        assertEquals("final output", found.output)
        assertEquals(1, found.stepOutputs.size)
        assertEquals("step-a", found.stepOutputs[0].stepName)
        assertEquals(10, found.stepOutputs[0].inputTokens)
        assertEquals(10, found.totalInputTokens)
        assertEquals(20, found.totalOutputTokens)
        assertEquals(30, found.totalTokens)
        assertEquals(500L, found.totalDurationMs)
        assertEquals(2, found.runCount)
    }

    @Test
    fun `should update status with error message`() {
        val created = repository.create(PlanExecution(id = "", planId = "plan-1", planName = "P"))

        repository.updateStatus(created.id, PlanExecutionStatus.FAILED, "boom")

        val found = repository.findById(created.id)!!
        assertEquals(PlanExecutionStatus.FAILED, found.status)
        assertEquals("boom", found.errorMessage)
    }

    @Test
    fun `should delete execution`() {
        val created = repository.create(PlanExecution(id = "", planId = "plan-1", planName = "P"))

        repository.delete(created.id)

        assertNull(repository.findById(created.id))
    }

    @Test
    fun `should find by plan id ordered newest first`() {
        val e1 = repository.create(PlanExecution(id = "", planId = "plan-a", planName = "P"))
        Thread.sleep(5)
        val e2 = repository.create(PlanExecution(id = "", planId = "plan-a", planName = "P"))
        repository.create(PlanExecution(id = "", planId = "plan-b", planName = "P"))

        val results = repository.findByPlanId("plan-a")

        assertEquals(2, results.size)
        assertEquals(e2.id, results[0].id)
        assertEquals(e1.id, results[1].id)
    }

    @Test
    fun `should find all executions ordered newest first`() {
        val e1 = repository.create(PlanExecution(id = "", planId = "plan-a", planName = "P"))
        Thread.sleep(5)
        val e2 = repository.create(PlanExecution(id = "", planId = "plan-b", planName = "P"))

        val results = repository.findAll()

        assertEquals(2, results.size)
        assertEquals(e2.id, results[0].id)
        assertEquals(e1.id, results[1].id)
    }

    @Test
    fun `should return empty inputs and step outputs when none provided`() {
        val created = repository.create(PlanExecution(id = "", planId = "plan-1", planName = "P"))

        val found = repository.findById(created.id)!!
        assertTrue(found.inputs.isEmpty())
        assertTrue(found.stepOutputs.isEmpty())
    }
}
