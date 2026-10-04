/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.telemetry

import dev.langchain4j.model.output.TokenUsage
import io.askimo.core.logging.logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * Tracks LLM usage across the application lifetime.
 *
 * Every AI call — successful or failed — is recorded with its provider, model, token counts,
 * and latency. This data drives the token-usage dashboard in the Discover view and the
 * diagnostics export, giving users visibility into how they consume their AI quota.
 *
 * A **session** represents a continuous period of usage. Calling [reset] starts a new session,
 * narrowing the dashboard to calls made after that point. All historical data is always retained
 * and can be queried at any time through [usageRepository].
 *
 * UI components observe [refreshSignal] to know when new data is available and should re-query
 * the repository.
 */
class TelemetryCollector(
    val usageRepository: LlmUsageRepository,
) {
    private val log = logger<TelemetryCollector>()

    @Volatile
    private var _sessionStart: Instant = Instant.EPOCH

    /** Start of the current session window (updated by [reset]). */
    val sessionStart: Instant get() = _sessionStart

    private val _refreshSignal = MutableStateFlow(0L)

    /**
     * Increments each time a new [LlmUsageRecord] is written.
     * Collect this in UI composables and re-query [usageRepository] on each emission.
     */
    val refreshSignal: StateFlow<Long> = _refreshSignal.asStateFlow()

    /**
     * Records a successful LLM call.
     * Persists a [LlmUsageRecord] row and bumps [refreshSignal].
     */
    fun recordLLMCall(
        provider: String,
        model: String,
        tokenUsage: TokenUsage?,
        durationMs: Long,
        instanceId: String? = null,
    ) {
        usageRepository.insert(
            LlmUsageRecord(
                provider = provider,
                model = model,
                instanceId = instanceId,
                promptTokens = tokenUsage?.inputTokenCount() ?: 0,
                outputTokens = tokenUsage?.outputTokenCount() ?: 0,
                totalTokens = tokenUsage?.totalTokenCount() ?: 0,
                durationMs = durationMs,
                isError = false,
            ),
        )
        val key = "${instanceId ?: provider}:$model"
        log.debug("LLM call to $key: ${tokenUsage?.totalTokenCount() ?: 0} tokens in ${durationMs}ms")
        _refreshSignal.value++
    }

    /**
     * Records an LLM error.
     * Persists a [LlmUsageRecord] row with [LlmUsageRecord.isError] = true and bumps [refreshSignal].
     */
    fun recordLLMError(
        provider: String,
        model: String,
        error: Throwable,
        instanceId: String? = null,
    ) {
        usageRepository.insert(
            LlmUsageRecord(
                provider = provider,
                model = model,
                instanceId = instanceId,
                isError = true,
            ),
        )
        val key = "${instanceId ?: provider}:$model"
        log.warn("LLM error for $key: ${error.message}")
        _refreshSignal.value++
    }

    /**
     * Advances [sessionStart] to now, resetting the UI view to an empty session.
     * SQLite records are intentionally **kept** — query [usageRepository] directly for
     * historical data. [refreshSignal] is reset to 0.
     */
    fun reset() {
        _sessionStart = Instant.now()
        _refreshSignal.value = 0L
        log.info("Telemetry session reset — new sessionStart=$_sessionStart")
    }
}
