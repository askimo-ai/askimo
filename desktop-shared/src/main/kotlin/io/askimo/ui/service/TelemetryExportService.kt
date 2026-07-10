/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.service

import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.logging.logger
import io.askimo.core.telemetry.TelemetryMetrics
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
 * Exports a [TelemetryMetrics] snapshot to a ZIP file containing two CSVs:
 *
 * - `session-metrics.csv`  — aggregated RAG and LLM summary metrics.
 * - `model-token-usage.csv` — per-provider/model LLM call breakdown.
 *
 * CSV conventions:
 * - UTF-8 encoding, comma delimiter, RFC 4180 quoting (all fields quoted).
 * - English-only stable column headers for downstream tooling compatibility.
 * - Numeric values formatted via [LocalizationManager] using the current user locale.
 * - Decimal values use [LocalizationManager.formatDouble] with 2 fraction digits.
 * - Empty rows are written (header only) when a section has no data.
 * - Timestamps are ISO-8601 UTC.
 */
object TelemetryExportService {

    private val log = logger<TelemetryExportService>()
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneOffset.UTC)

    /**
     * Exports [metrics] to [targetZipFile] as a ZIP with two CSV entries.
     *
     * @param metrics   Current telemetry snapshot (may have all-zero values).
     * @param targetZipFile  Destination file; parent directories are created if needed.
     * @return [Result.success] on completion, [Result.failure] on any error.
     */
    suspend fun export(metrics: TelemetryMetrics, targetZipFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            targetZipFile.parentFile?.mkdirs()
            val capturedAt = timestampFormatter.format(Instant.now())

            ZipOutputStream(targetZipFile.outputStream().buffered()).use { zos ->
                // ── session-metrics.csv ──────────────────────────────────
                zos.putNextEntry(ZipEntry("session-metrics.csv"))
                zos.write(buildSessionMetricsCsv(metrics, capturedAt).toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // ── model-token-usage.csv ────────────────────────────────
                zos.putNextEntry(ZipEntry("model-token-usage.csv"))
                zos.write(buildModelTokenUsageCsv(metrics, capturedAt).toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            log.info("Telemetry exported to {}", targetZipFile.absolutePath)
        }.onFailure { e ->
            log.error("Failed to export telemetry", e)
        }
    }

    // ── CSV builders ─────────────────────────────────────────────────────────

    private fun buildSessionMetricsCsv(metrics: TelemetryMetrics, capturedAt: String): String {
        val sw = StringWriter()
        sw.appendCsvLine(
            "captured_at",
            "metric_key",
            "metric_label",
            "unit",
            "value",
        )

        // RAG metrics — only if any RAG data was collected
        if (metrics.ragClassificationTotal > 0) {
            sw.appendCsvLine(capturedAt, "rag_classification_total", "RAG Classification Total", "count", LocalizationManager.formatNumber(metrics.ragClassificationTotal))
            sw.appendCsvLine(capturedAt, "rag_triggered", "RAG Triggered", "count", LocalizationManager.formatNumber(metrics.ragTriggered))
            sw.appendCsvLine(capturedAt, "rag_skipped", "RAG Skipped", "count", LocalizationManager.formatNumber(metrics.ragSkipped))
            sw.appendCsvLine(capturedAt, "rag_triggered_percent", "RAG Triggered Percent", "%", LocalizationManager.formatDouble(metrics.ragTriggeredPercent, 2))
            sw.appendCsvLine(capturedAt, "rag_avg_classification_time_ms", "RAG Avg Classification Time", "ms", LocalizationManager.formatNumber(metrics.ragAvgClassificationTimeMs))
        }

        if (metrics.ragRetrievalTotal > 0) {
            sw.appendCsvLine(capturedAt, "rag_retrieval_total", "RAG Retrieval Total", "count", LocalizationManager.formatNumber(metrics.ragRetrievalTotal))
            sw.appendCsvLine(capturedAt, "rag_avg_retrieval_time_ms", "RAG Avg Retrieval Time", "ms", LocalizationManager.formatNumber(metrics.ragAvgRetrievalTimeMs))
            sw.appendCsvLine(capturedAt, "rag_avg_chunks_retrieved", "RAG Avg Chunks Retrieved", "count", LocalizationManager.formatDouble(metrics.ragAvgChunksRetrieved, 2))
        }

        // LLM summary metrics
        if (metrics.llmCallsByProvider.isNotEmpty()) {
            val totalCalls = metrics.llmCallsByProvider.values.sum()
            val totalTokens = metrics.llmTokensByProvider.values.sum()
            val totalErrors = metrics.llmErrorsByProvider.values.sum()
            sw.appendCsvLine(capturedAt, "llm_total_calls", "LLM Total Calls", "count", LocalizationManager.formatNumber(totalCalls))
            sw.appendCsvLine(capturedAt, "llm_total_tokens", "LLM Total Tokens", "tokens", LocalizationManager.formatNumber(totalTokens))
            sw.appendCsvLine(capturedAt, "llm_total_errors", "LLM Total Errors", "count", LocalizationManager.formatNumber(totalErrors))
        }

        return sw.toString()
    }

    private fun buildModelTokenUsageCsv(metrics: TelemetryMetrics, capturedAt: String): String {
        val sw = StringWriter()
        sw.appendCsvLine(
            "captured_at",
            "provider",
            "model",
            "calls",
            "tokens",
            "avg_duration_ms",
            "errors",
        )

        metrics.llmCallsByProvider.forEach { (providerModel, calls) ->
            val parts = providerModel.split(":", limit = 2)
            val provider = parts.getOrElse(0) { providerModel }
            val model = parts.getOrElse(1) { "" }
            val tokens = metrics.llmTokensByProvider[providerModel] ?: 0L
            val avgDurationMs = metrics.llmAvgDurationMsByProvider[providerModel] ?: 0L
            val errors = metrics.llmErrorsByProvider[providerModel] ?: 0

            sw.appendCsvLine(
                capturedAt,
                provider,
                model,
                LocalizationManager.formatNumber(calls),
                LocalizationManager.formatNumber(tokens),
                LocalizationManager.formatNumber(avgDurationMs),
                LocalizationManager.formatNumber(errors),
            )
        }

        return sw.toString()
    }

    // ── CSV helpers ───────────────────────────────────────────────────────────

    /**
     * Appends a RFC 4180 CSV row: all fields are quoted, internal quotes are doubled,
     * newlines within values are escaped as \n.
     */
    private fun StringWriter.appendCsvLine(vararg fields: Any) {
        append(
            fields.joinToString(",") { field ->
                "\"${field.toString().replace("\"", "\"\"").replace("\n", "\\n").replace("\r", "")}\""
            },
        )
        append("\n")
    }
}
