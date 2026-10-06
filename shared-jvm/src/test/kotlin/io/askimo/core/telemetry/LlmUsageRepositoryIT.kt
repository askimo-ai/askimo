/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.telemetry

import io.askimo.core.db.DatabaseManager
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * ## LlmUsageRepository Spec (SQLDelight)
 *
 * ### insert
 * Every LLM call (success or error) is written as an individual row to `llm_usage_records`.
 *
 * ### queryGroupedByInstance
 * Returns per-instance aggregates within a half-open time window `[from, to)`.
 * Records are grouped by `COALESCE(instance_id, provider), model` and ordered by total
 * tokens descending so the highest-usage model appears first.
 */
class LlmUsageRepositoryIT {

    companion object {
        private lateinit var testBaseScope: AskimoHome.TestBaseScope
        private lateinit var databaseManager: DatabaseManager
        private lateinit var repo: LlmUsageRepository

        @JvmStatic
        @BeforeAll
        fun setUpClass(@TempDir tempDir: Path) {
            testBaseScope = AskimoHome.withTestBase(tempDir)
            databaseManager = DatabaseManager.getInMemoryTestInstance(this)
            repo = databaseManager.getLlmUsageRepository()
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            if (::databaseManager.isInitialized) databaseManager.close()
            if (::testBaseScope.isInitialized) testBaseScope.close()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun record(
        provider: String = "openai",
        model: String = "gpt-4o",
        instanceId: String? = null,
        totalTokens: Int = 100,
        durationMs: Long = 500,
        isError: Boolean = false,
        timestamp: Instant = Clock.System.now(),
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
    private val allTime: Instant = Instant.DISTANT_PAST

    /** Upper bound well beyond any test record. */
    private val farFuture: Instant = Clock.System.now() + 3_600.seconds

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
            val t = Instant.parse("2026-02-01T00:00:00Z")
            repo.insert(record(timestamp = t, totalTokens = 200, provider = "insert-test"))

            val stats = repo.queryGroupedByInstance(t, t + 1.seconds)
            assertEquals(1, stats.size)
            assertEquals(200L, stats[0].tokens)
        }
    }

    // ── countByPeriod ─────────────────────────────────────────────────────────

    @Nested
    inner class CountByPeriod {

        @Test
        fun `counts records within the period`() {
            val t0 = Instant.parse("2026-03-01T00:00:00Z")
            val t1 = Instant.parse("2026-03-01T01:00:00Z")
            val t2 = Instant.parse("2026-03-01T02:00:00Z")

            repo.insert(record(timestamp = t0, provider = "count-test"))
            repo.insert(record(timestamp = t1, provider = "count-test"))
            repo.insert(record(timestamp = t2, provider = "count-test"))

            assertEquals(2, repo.countByPeriod(t0, t2))
        }

        @Test
        fun `returns zero when no records in range`() {
            val from = Instant.parse("2027-01-01T00:00:00Z")
            val to = Instant.parse("2027-01-02T00:00:00Z")

            assertEquals(0, repo.countByPeriod(from, to))
        }
    }

    // ── queryGroupedByInstance ────────────────────────────────────────────────

    @Nested
    inner class QueryGroupedByInstance {

        @Test
        fun `returns empty list when no records exist in range`() {
            val from = Instant.parse("2028-01-01T00:00:00Z")
            val to = Instant.parse("2028-01-02T00:00:00Z")
            val stats = repo.queryGroupedByInstance(from, to)
            assertTrue(stats.isEmpty())
        }

        // ── Time-range filtering ──────────────────────────────────────────────

        @Test
        fun `records before the from boundary are excluded`() {
            val tooEarly = Clock.System.now() - 2.hours
            val from = Clock.System.now() - 1.hours
            repo.insert(record(timestamp = tooEarly, totalTokens = 999, provider = "boundary-test"))

            val stats = repo.queryGroupedByInstance(from, farFuture)
            assertTrue(stats.none { it.provider == "boundary-test" })
        }

        @Test
        fun `records inside the time window are included`() {
            val t = Instant.parse("2026-04-01T00:00:00Z")
            repo.insert(record(timestamp = t, totalTokens = 50, provider = "inside-window-test"))

            val stats = repo.queryGroupedByInstance(t, t + 1.seconds)
            assertEquals(1, stats.size)
        }

        @Test
        fun `from is inclusive and to is exclusive`() {
            val t0 = Instant.parse("2026-01-01T00:00:00Z")
            val t1 = Instant.parse("2026-01-01T01:00:00Z")
            val t2 = Instant.parse("2026-01-01T02:00:00Z")

            repo.insert(record(timestamp = t0, totalTokens = 10, provider = "boundary-inclusive")) // at lower boundary — included
            repo.insert(record(timestamp = t1, totalTokens = 20, provider = "boundary-inclusive")) // inside — included
            repo.insert(record(timestamp = t2, totalTokens = 30, provider = "boundary-inclusive")) // at upper boundary — excluded

            val stats = repo.queryGroupedByInstance(t0, t2)
            val match = stats.single { it.provider == "boundary-inclusive" }
            assertEquals(30L, match.tokens) // 10 + 20
        }

        // ── Grouping ──────────────────────────────────────────────────────────

        @Test
        fun `calls for the same provider and model are merged into one group`() {
            val t = Instant.parse("2026-05-01T00:00:00Z")
            repo.insert(record(provider = "merge-test", model = "gpt-4o", totalTokens = 100, timestamp = t))
            repo.insert(record(provider = "merge-test", model = "gpt-4o", totalTokens = 200, timestamp = t))

            val stats = repo.queryGroupedByInstance(t, t + 1.seconds)
            assertEquals(1, stats.size)
            assertEquals(300L, stats[0].tokens)
            assertEquals(2, stats[0].calls)
        }

        @Test
        fun `different models under the same provider form separate groups`() {
            val t = Instant.parse("2026-05-02T00:00:00Z")
            repo.insert(record(provider = "separate-group-test", model = "gpt-4o", totalTokens = 100, timestamp = t))
            repo.insert(record(provider = "separate-group-test", model = "gpt-3.5-turbo", totalTokens = 50, timestamp = t))

            val stats = repo.queryGroupedByInstance(t, t + 1.seconds)
            assertEquals(2, stats.size)
        }

        @Test
        fun `instanceId takes precedence over provider for grouping key`() {
            val t = Instant.parse("2026-05-03T00:00:00Z")
            repo.insert(record(provider = "precedence-test", instanceId = "instance-a", model = "gpt-4o", totalTokens = 100, timestamp = t))
            repo.insert(record(provider = "precedence-test", instanceId = "instance-a", model = "gpt-4o", totalTokens = 150, timestamp = t))
            repo.insert(record(provider = "precedence-test-2", instanceId = "instance-b", model = "claude-3", totalTokens = 80, timestamp = t))

            val stats = repo.queryGroupedByInstance(t, t + 1.seconds)
            assertEquals(250L, stats.single { it.instanceKey == "instance-a" }.tokens)
            assertEquals(80L, stats.single { it.instanceKey == "instance-b" }.tokens)
        }

        // ── instanceKey field ─────────────────────────────────────────────────

        @Test
        fun `instanceKey is the provider when instanceId is null`() {
            val t = Instant.parse("2026-05-04T00:00:00Z")
            repo.insert(record(provider = "instance-key-null-test", instanceId = null, timestamp = t))

            val stats = repo.queryGroupedByInstance(t, t + 1.seconds)
            assertEquals("instance-key-null-test", stats[0].instanceKey)
        }

        @Test
        fun `instanceKey is the instanceId when present`() {
            val t = Instant.parse("2026-05-05T00:00:00Z")
            repo.insert(record(provider = "openai", instanceId = "my-custom-instance", timestamp = t))

            val stats = repo.queryGroupedByInstance(t, t + 1.seconds)
            assertEquals("my-custom-instance", stats[0].instanceKey)
        }

        // ── Aggregation ───────────────────────────────────────────────────────

        @Test
        fun `tokens are summed across all calls in a group`() {
            val t = Instant.parse("2026-05-06T00:00:00Z")
            repeat(5) { repo.insert(record(totalTokens = 100, provider = "sum-test", timestamp = t)) }

            val stats = repo.queryGroupedByInstance(t, t + 1.seconds)
            assertEquals(500L, stats[0].tokens)
        }

        @Test
        fun `avgDurationMs is the arithmetic mean of all calls in a group`() {
            val t = Instant.parse("2026-05-07T00:00:00Z")
            repo.insert(record(durationMs = 200, provider = "avg-test", timestamp = t))
            repo.insert(record(durationMs = 400, provider = "avg-test", timestamp = t))

            val stats = repo.queryGroupedByInstance(t, t + 1.seconds)
            assertEquals(300L, stats[0].avgDurationMs)
        }

        @Test
        fun `errors counts only the rows with isError true`() {
            val t = Instant.parse("2026-05-08T00:00:00Z")
            repo.insert(record(isError = false, provider = "errors-test", timestamp = t))
            repo.insert(record(isError = false, provider = "errors-test", timestamp = t))
            repo.insert(record(isError = true, totalTokens = 0, provider = "errors-test", timestamp = t))

            val stats = repo.queryGroupedByInstance(t, t + 1.seconds)
            assertEquals(3, stats[0].calls)
            assertEquals(1, stats[0].errors)
        }

        @Test
        fun `zero errors when all calls succeed`() {
            val t = Instant.parse("2026-05-09T00:00:00Z")
            repo.insert(record(isError = false, provider = "no-errors-test", timestamp = t))
            repo.insert(record(isError = false, provider = "no-errors-test", timestamp = t))

            val stats = repo.queryGroupedByInstance(t, t + 1.seconds)
            assertEquals(0, stats[0].errors)
        }

        // ── Ordering ──────────────────────────────────────────────────────────

        @Test
        fun `results are ordered by total tokens descending`() {
            val t = Instant.parse("2026-05-10T00:00:00Z")
            repo.insert(record(provider = "ordering-cheap", model = "cheap", totalTokens = 10, timestamp = t))
            repo.insert(record(provider = "ordering-expensive", model = "expensive", totalTokens = 9_000, timestamp = t))
            repo.insert(record(provider = "ordering-medium", model = "medium", totalTokens = 500, timestamp = t))

            val stats = repo.queryGroupedByInstance(t, t + 1.seconds)
            assertEquals(3, stats.size)
            assertEquals(9_000L, stats[0].tokens)
            assertEquals(500L, stats[1].tokens)
            assertEquals(10L, stats[2].tokens)
        }

        // ── provider / model fields ───────────────────────────────────────────

        @Test
        fun `provider and model fields are preserved on each stats row`() {
            val t = Instant.parse("2026-05-11T00:00:00Z")
            repo.insert(record(provider = "anthropic", model = "claude-3-sonnet", timestamp = t))

            val stats = repo.queryGroupedByInstance(t, t + 1.seconds)
            assertEquals("anthropic", stats[0].provider)
            assertEquals("claude-3-sonnet", stats[0].model)
        }
    }
}
