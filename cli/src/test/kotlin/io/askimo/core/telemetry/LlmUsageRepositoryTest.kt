/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.telemetry

import io.askimo.core.db.DatabaseManager
import io.askimo.test.extensions.AskimoTestHome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ## LlmUsageRepository Spec
 *
 * ### insert
 * Every LLM call (success or error) is written as an individual row to `llm_usage_records`.
 *
 * ### queryGroupedByInstance
 * Returns per-instance aggregates within a half-open time window `[from, to)`.
 * Records are grouped by `COALESCE(instance_id, provider), model` and ordered by total
 * tokens descending so the highest-usage model appears first.
 */
@AskimoTestHome
class LlmUsageRepositoryTest {

    private lateinit var db: DatabaseManager
    private lateinit var repo: LlmUsageRepository

    @BeforeEach
    fun setup() {
        db = DatabaseManager.getInMemoryTestInstance(this)
        repo = db.getLlmUsageRepository()
    }

    @AfterEach
    fun tearDown() {
        db.close()
        DatabaseManager.reset()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun record(
        provider: String = "openai",
        model: String = "gpt-4o",
        instanceId: String? = null,
        totalTokens: Int = 100,
        durationMs: Long = 500,
        isError: Boolean = false,
        timestamp: Instant = Instant.now(),
    ) = LlmUsageRecord(
        provider = provider,
        model = model,
        instanceId = instanceId,
        totalTokens = totalTokens,
        durationMs = durationMs,
        isError = isError,
        timestamp = timestamp,
    )

    /** Inclusive lower bound that captures all records. */
    private val allTime: Instant = Instant.EPOCH

    /** Upper bound well beyond any test record. */
    private val farFuture: Instant = Instant.now().plusSeconds(3_600)

    // ── Insert ────────────────────────────────────────────────────────────────

    @Nested
    inner class Insert {

        @Test
        fun `insert does not throw for a successful call`() {
            repo.insert(record())
        }

        @Test
        fun `insert does not throw for an error call`() {
            repo.insert(record(isError = true, totalTokens = 0))
        }

        @Test
        fun `inserted record appears in queryGroupedByInstance`() {
            repo.insert(record(totalTokens = 200))

            val stats = repo.queryGroupedByInstance(allTime, farFuture)
            assertEquals(1, stats.size)
            assertEquals(200L, stats[0].tokens)
        }
    }

    // ── queryGroupedByInstance ────────────────────────────────────────────────

    @Nested
    inner class QueryGroupedByInstance {

        @Test
        fun `returns empty list when no records exist`() {
            val stats = repo.queryGroupedByInstance(allTime, farFuture)
            assertTrue(stats.isEmpty())
        }

        // ── Time-range filtering ──────────────────────────────────────────────

        @Test
        fun `records before the from boundary are excluded`() {
            val tooEarly = Instant.now().minus(2, ChronoUnit.HOURS)
            val from = Instant.now().minus(1, ChronoUnit.HOURS)
            repo.insert(record(timestamp = tooEarly, totalTokens = 999))

            val stats = repo.queryGroupedByInstance(from, farFuture)
            assertTrue(stats.isEmpty())
        }

        @Test
        fun `records inside the time window are included`() {
            repo.insert(record(timestamp = Instant.now(), totalTokens = 50))

            val stats = repo.queryGroupedByInstance(allTime, farFuture)
            assertEquals(1, stats.size)
        }

        @Test
        fun `from is inclusive and to is exclusive`() {
            val t0 = Instant.parse("2026-01-01T00:00:00Z")
            val t1 = Instant.parse("2026-01-01T01:00:00Z")
            val t2 = Instant.parse("2026-01-01T02:00:00Z")

            repo.insert(record(timestamp = t0, totalTokens = 10)) // at lower boundary — included
            repo.insert(record(timestamp = t1, totalTokens = 20)) // inside — included
            repo.insert(record(timestamp = t2, totalTokens = 30)) // at upper boundary — excluded

            val stats = repo.queryGroupedByInstance(t0, t2)
            assertEquals(1, stats.size)
            assertEquals(30L, stats[0].tokens) // 10 + 20
        }

        // ── Grouping ──────────────────────────────────────────────────────────

        @Test
        fun `calls for the same provider and model are merged into one group`() {
            repo.insert(record(provider = "openai", model = "gpt-4o", totalTokens = 100))
            repo.insert(record(provider = "openai", model = "gpt-4o", totalTokens = 200))

            val stats = repo.queryGroupedByInstance(allTime, farFuture)
            assertEquals(1, stats.size)
            assertEquals(300L, stats[0].tokens)
            assertEquals(2, stats[0].calls)
        }

        @Test
        fun `different models under the same provider form separate groups`() {
            repo.insert(record(provider = "openai", model = "gpt-4o", totalTokens = 100))
            repo.insert(record(provider = "openai", model = "gpt-3.5-turbo", totalTokens = 50))

            val stats = repo.queryGroupedByInstance(allTime, farFuture)
            assertEquals(2, stats.size)
        }

        @Test
        fun `instanceId takes precedence over provider for grouping key`() {
            repo.insert(record(provider = "openai", instanceId = "instance-a", model = "gpt-4o", totalTokens = 100))
            repo.insert(record(provider = "openai", instanceId = "instance-a", model = "gpt-4o", totalTokens = 150))
            repo.insert(record(provider = "anthropic", instanceId = "instance-b", model = "claude-3", totalTokens = 80))

            val stats = repo.queryGroupedByInstance(allTime, farFuture)
            assertEquals(2, stats.size)
            assertEquals(250L, stats[0].tokens) // instance-a
            assertEquals(80L, stats[1].tokens) // instance-b
        }

        // ── instanceKey field ─────────────────────────────────────────────────

        @Test
        fun `instanceKey is the provider when instanceId is null`() {
            repo.insert(record(provider = "openai", instanceId = null))

            val stats = repo.queryGroupedByInstance(allTime, farFuture)
            assertEquals("openai", stats[0].instanceKey)
        }

        @Test
        fun `instanceKey is the instanceId when present`() {
            repo.insert(record(provider = "openai", instanceId = "my-custom-instance"))

            val stats = repo.queryGroupedByInstance(allTime, farFuture)
            assertEquals("my-custom-instance", stats[0].instanceKey)
        }

        // ── Aggregation ───────────────────────────────────────────────────────

        @Test
        fun `tokens are summed across all calls in a group`() {
            repeat(5) { repo.insert(record(totalTokens = 100)) }

            val stats = repo.queryGroupedByInstance(allTime, farFuture)
            assertEquals(500L, stats[0].tokens)
        }

        @Test
        fun `avgDurationMs is the arithmetic mean of all calls in a group`() {
            repo.insert(record(durationMs = 200))
            repo.insert(record(durationMs = 400))

            val stats = repo.queryGroupedByInstance(allTime, farFuture)
            assertEquals(300L, stats[0].avgDurationMs)
        }

        @Test
        fun `errors counts only the rows with isError true`() {
            repo.insert(record(isError = false))
            repo.insert(record(isError = false))
            repo.insert(record(isError = true, totalTokens = 0))

            val stats = repo.queryGroupedByInstance(allTime, farFuture)
            assertEquals(3, stats[0].calls)
            assertEquals(1, stats[0].errors)
        }

        @Test
        fun `zero errors when all calls succeed`() {
            repo.insert(record(isError = false))
            repo.insert(record(isError = false))

            val stats = repo.queryGroupedByInstance(allTime, farFuture)
            assertEquals(0, stats[0].errors)
        }

        // ── Ordering ──────────────────────────────────────────────────────────

        @Test
        fun `results are ordered by total tokens descending`() {
            repo.insert(record(provider = "openai", model = "cheap", totalTokens = 10))
            repo.insert(record(provider = "anthropic", model = "expensive", totalTokens = 9_000))
            repo.insert(record(provider = "google", model = "medium", totalTokens = 500))

            val stats = repo.queryGroupedByInstance(allTime, farFuture)
            assertEquals(3, stats.size)
            assertEquals(9_000L, stats[0].tokens)
            assertEquals(500L, stats[1].tokens)
            assertEquals(10L, stats[2].tokens)
        }

        // ── provider / model fields ───────────────────────────────────────────

        @Test
        fun `provider and model fields are preserved on each stats row`() {
            repo.insert(record(provider = "anthropic", model = "claude-3-sonnet"))

            val stats = repo.queryGroupedByInstance(allTime, farFuture)
            assertEquals("anthropic", stats[0].provider)
            assertEquals("claude-3-sonnet", stats[0].model)
        }
    }
}
