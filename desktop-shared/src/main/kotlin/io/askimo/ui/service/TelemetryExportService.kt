/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.service

import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.logging.logger
import io.askimo.core.telemetry.LlmInstanceStats
import io.askimo.core.telemetry.TelemetryCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports LLM usage metrics for the current session to a ZIP file containing two CSVs:
 *
 * - `session-metrics.csv`  — aggregated LLM summary metrics.
 * - `model-token-usage.csv` — per-provider/model LLM call breakdown.
 *
 * Data is queried from [TelemetryCollector.usageRepository] scoped to
 * [[TelemetryCollector.sessionStart], now).
 */
object TelemetryExportService {

    private val log = logger<TelemetryExportService>()
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneOffset.UTC)

    /**
     * Exports session metrics to [targetZipFile] as a ZIP with two CSV entries.
     *
     * @param telemetry     Current [TelemetryCollector] (provides sessionStart + repository).
     * @param targetZipFile  Destination file; parent directories are created if needed.
     * @return [Result.success] on completion, [Result.failure] on any error.
     */
    suspend fun export(telemetry: TelemetryCollector, targetZipFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            targetZipFile.parentFile?.mkdirs()
            val capturedAt = timestampFormatter.format(Instant.now())
            val stats = telemetry.usageRepository.queryGroupedByInstance(telemetry.sessionStart, Instant.now())

            ZipOutputStream(targetZipFile.outputStream().buffered()).use { zos ->
                // ── session-metrics.csv ──────────────────────────────────
                zos.putNextEntry(ZipEntry("session-metrics.csv"))
                zos.write(buildSessionMetricsCsv(stats, capturedAt).toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // ── model-token-usage.csv ────────────────────────────────
                zos.putNextEntry(ZipEntry("model-token-usage.csv"))
                zos.write(buildModelTokenUsageCsv(stats, capturedAt).toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            log.info("Telemetry exported to {}", targetZipFile.absolutePath)
        }.onFailure { e ->
            log.error("Failed to export telemetry", e)
        }
    }

    // ── CSV builders ─────────────────────────────────────────────────────────

    private fun buildSessionMetricsCsv(stats: List<LlmInstanceStats>, capturedAt: String): String {
        val sw = StringWriter()
        sw.appendCsvLine("captured_at", "metric_key", "metric_label", "unit", "value")

        if (stats.isNotEmpty()) {
            val totalCalls = stats.sumOf { it.calls }
            val totalTokens = stats.sumOf { it.tokens }
            val totalErrors = stats.sumOf { it.errors }
            sw.appendCsvLine(capturedAt, "llm_total_calls", "LLM Total Calls", "count", LocalizationManager.formatNumber(totalCalls))
            sw.appendCsvLine(capturedAt, "llm_total_tokens", "LLM Total Tokens", "tokens", LocalizationManager.formatNumber(totalTokens))
            sw.appendCsvLine(capturedAt, "llm_total_errors", "LLM Total Errors", "count", LocalizationManager.formatNumber(totalErrors))
        }

        return sw.toString()
    }

    private fun buildModelTokenUsageCsv(stats: List<LlmInstanceStats>, capturedAt: String): String {
        val sw = StringWriter()
        sw.appendCsvLine("captured_at", "instance_or_provider", "model", "calls", "tokens", "avg_duration_ms", "errors")

        stats.forEach { stat ->
            val instanceOrProvider = stat.instanceKey.split(":", limit = 2).getOrElse(0) { stat.instanceKey }
            sw.appendCsvLine(
                capturedAt,
                instanceOrProvider,
                stat.model,
                LocalizationManager.formatNumber(stat.calls),
                LocalizationManager.formatNumber(stat.tokens),
                LocalizationManager.formatNumber(stat.avgDurationMs),
                LocalizationManager.formatNumber(stat.errors),
            )
        }

        return sw.toString()
    }

    // ── CSV helpers ───────────────────────────────────────────────────────────

    private fun StringWriter.appendCsvLine(vararg fields: Any) {
        append(
            fields.joinToString(",") { field ->
                "\"${field.toString().replace("\"", "\"\"").replace("\n", "\\n").replace("\r", "")}\""
            },
        )
        append("\n")
    }
}
