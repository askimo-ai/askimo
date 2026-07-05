/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.telemetry

import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.model.chat.listener.ChatModelErrorContext
import dev.langchain4j.model.chat.listener.ChatModelListener
import dev.langchain4j.model.chat.listener.ChatModelRequestContext
import dev.langchain4j.model.chat.listener.ChatModelResponseContext
import io.askimo.core.analytics.Analytics
import io.askimo.core.analytics.AnalyticsEvent
import io.askimo.core.logging.logger
import io.askimo.core.memory.getTextContent
import io.askimo.core.util.MachineId
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * Configuration for syncing token usage back to the Askimo Team server.
 *
 * @param syncUrl   Full URL of the sync endpoint, e.g. `https://team.example.com/api/ai/usage/sync`
 * @param tokenSupplier Returns the current JWT access token (nullable — sync is skipped when null)
 */
data class UsageSyncConfig(
    val syncUrl: String,
    val tokenSupplier: () -> String?,
)

/**
 * LangChain4J listener that records LLM call metrics.
 * Integrates with TelemetryCollector to track:
 * - Request/response timing
 * - Token usage
 * - Errors
 */
class TelemetryChatModelListener(
    private val telemetry: TelemetryCollector,
    private val provider: String,
    private val usageSyncConfig: UsageSyncConfig? = null,
) : ChatModelListener {
    private val log = logger<TelemetryChatModelListener>()

    /**
     * Stable machine-bound identifier for the `X-Client-Id` request header.
     *
     * Resolved once at class-load time via [MachineId] (SHA-256 UUID of the hardware serial /
     * MAC address) and cached in the companion object so the underlying shell command runs only
     * once per JVM lifetime. Falls back to a random UUID if every hardware strategy fails.
     */
    val clientId: String get() = MachineId.resolve()

    /**
     * Per-request UUID for the `X-Correlation-Id` request header and the usage-sync payload.
     *
     * [ThreadLocal] so concurrent requests on different threads each carry their own ID.
     * Initialised eagerly with a random UUID so [requestHeaders] is always safe to call,
     * even before [onRequest] fires (e.g. during HTTP-client construction).
     *
     * Lifecycle:
     *  1. [onRequest] → generate a fresh UUID, write to ThreadLocal **and** to
     *     `context.attributes()` for the [onResponse] → sync leg.
     *  2. HTTP `send()` fires on the same thread immediately after [onRequest] →
     *     [requestHeaders] reads the ThreadLocal → header and sync payload always agree.
     */
    private val correlationId: ThreadLocal<String> =
        ThreadLocal.withInitial { UUID.randomUUID().toString() }

    /**
     * Returns the two tracking headers to inject into every outgoing HTTP request.
     *
     * - `X-Client-Id`: stable machine ID (never changes within a JVM run)
     * - `X-Correlation-Id`: per-request UUID (rotated in [onRequest] before each send)
     */
    fun requestHeaders(): Map<String, String> = mapOf(
        "X-Client-Id" to clientId,
        "X-Correlation-Id" to correlationId.get(),
    )

    override fun onRequest(context: ChatModelRequestContext) {
        val attrs = context.attributes()

        // Rotate to a fresh per-request UUID. onRequest fires before the HTTP send()
        // on the same thread, so requestHeaders() will read this new value.
        val newCorrelationId = if (usageSyncConfig != null) {
            UUID.randomUUID().toString().also { id ->
                correlationId.set(id)
                attrs[ATTR_CORRELATION_ID] = id
            }
        } else {
            correlationId.get()
        }

        attrs[ATTR_START_TIME] = System.currentTimeMillis()

        val request = context.chatRequest()
        if (log.isDebugEnabled) {
            val messages = request.messages()
            val systemMessages = messages.filterIsInstance<SystemMessage>()
            val nonSystemMessages = messages.filter { it !is SystemMessage }
            val lastMessages = nonSystemMessages.takeLast(3)
            val params = request.parameters()

            val debugMsg = buildString {
                append("LLM request to $provider: ${messages.size} messages, ")
                append("model=${request.modelName() ?: "default"}, ")
                appendLine("clientId=$clientId, correlationId=$newCorrelationId")

                if (systemMessages.isNotEmpty()) {
                    appendLine("  [System messages (${systemMessages.size})]")
                    systemMessages.forEach { msg ->
                        val preview = msg.getTextContent().take(300).replace('\n', ' ')
                        appendLine("    SYSTEM: $preview")
                    }
                }

                if (lastMessages.isNotEmpty()) {
                    appendLine("  [Last ${lastMessages.size} message(s)]")
                    lastMessages.forEach { msg ->
                        val role = msg.type().name
                        val preview = msg.getTextContent().take(300).replace('\n', ' ')
                        appendLine("    $role: $preview")
                    }
                }

                val paramParts = buildList {
                    params.modelName()?.let { add("modelName=$it") }
                    params.temperature()?.let { add("temperature=$it") }
                    params.topP()?.let { add("topP=$it") }
                    params.topK()?.let { add("topK=$it") }
                    params.frequencyPenalty()?.let { add("frequencyPenalty=$it") }
                    params.presencePenalty()?.let { add("presencePenalty=$it") }
                    params.maxOutputTokens()?.let { add("maxOutputTokens=$it") }
                    params.stopSequences()?.takeIf { it.isNotEmpty() }?.let { add("stopSequences=$it") }
                    params.responseFormat()?.let { add("responseFormat=$it") }
                    params.toolChoice()?.let { add("toolChoice=$it") }
                    params.toolSpecifications()?.takeIf { it.isNotEmpty() }?.let { tools -> add("tools(${tools.size})=${tools.map { it.name() }}") }
                }
                if (paramParts.isNotEmpty()) {
                    append("  [Parameters] ")
                    append(paramParts.joinToString(", "))
                }
            }
            log.debug(debugMsg.trimEnd())
        }
    }

    override fun onResponse(context: ChatModelResponseContext) {
        val attrs = context.attributes()
        val startTime = attrs[ATTR_START_TIME] as? Long ?: System.currentTimeMillis()
        val correlationId = attrs[ATTR_CORRELATION_ID] as? String
        val duration = System.currentTimeMillis() - startTime

        val request = context.chatRequest()
        val response = context.chatResponse()
        val model = request.modelName() ?: response.modelName() ?: "unknown"
        val tokenUsage = response.tokenUsage()

        telemetry.recordLLMCall(
            provider = provider,
            model = model,
            tokenUsage = tokenUsage,
            durationMs = duration,
        )

        Analytics.track(
            AnalyticsEvent.PROVIDER_USED,
            mapOf(
                "provider" to provider,
                "model_name" to model,
                "model_tier" to Analytics.modelTier(provider),
            ),
        )

        log.debug(
            "LLM response from {}:{} in {}ms, tokens={}, correlationId={}",
            provider,
            model,
            duration,
            tokenUsage?.totalTokenCount() ?: "unknown",
            correlationId,
        )

        // Sync token usage back to the team server (fire-and-forget)
        if (usageSyncConfig != null && correlationId != null && tokenUsage != null) {
            syncUsage(
                config = usageSyncConfig,
                correlationId = correlationId,
                promptTokens = tokenUsage.inputTokenCount() ?: 0,
                completionTokens = tokenUsage.outputTokenCount() ?: 0,
                totalTokens = tokenUsage.totalTokenCount() ?: 0,
                durationMs = duration,
            )
        }
    }

    override fun onError(context: ChatModelErrorContext) {
        val request = context.chatRequest()
        val error = context.error()
        val model = request.modelName() ?: "unknown"

        telemetry.recordLLMError(provider, model, error)

        // Categorise the error type without including the message (may contain PII/keys)
        val msg = error.message ?: error.cause?.message ?: ""
        val errorType = when {
            msg.contains("timeout", ignoreCase = true) ||
                msg.contains("timed out", ignoreCase = true) -> "provider_timeout"

            msg.contains("rate limit", ignoreCase = true) ||
                msg.contains("429") -> "rate_limit"

            msg.contains("401") ||
                msg.contains("403") ||
                msg.contains("unauthorized", ignoreCase = true) ||
                msg.contains("forbidden", ignoreCase = true) -> "auth_error"

            msg.contains("context length", ignoreCase = true) ||
                msg.contains("context window", ignoreCase = true) ||
                msg.contains("too long", ignoreCase = true) ||
                msg.contains("maximum context", ignoreCase = true) -> "context_length"

            msg.contains("connection refused", ignoreCase = true) ||
                error is java.net.ConnectException ||
                error.cause is java.net.ConnectException -> "connection_refused"

            msg.contains("unknown host", ignoreCase = true) ||
                error is java.net.UnknownHostException ||
                error.cause is java.net.UnknownHostException -> "connection_refused"

            (msg.contains("model", ignoreCase = true) && msg.contains("not found", ignoreCase = true)) ||
                msg.contains("no such file", ignoreCase = true) -> "model_not_found"

            msg.contains("500") ||
                msg.contains("internal server error", ignoreCase = true) -> "server_error"

            error is java.io.IOException ||
                error.cause is java.io.IOException -> "network_error"

            else -> "provider_error"
        }
        val sanitisedMessage = (error.message ?: error.cause?.message)
            ?.take(4000)
            ?.replace(Regex("[\\r\\n]+"), " ")
            ?: "unknown"
        Analytics.track(
            AnalyticsEvent.ERROR_OCCURRED,
            mapOf("error_type" to errorType, "provider" to provider, "error_message" to sanitisedMessage),
        )

        log.warn("LLM error from $provider:$model: ${error.message}", error)
    }

    private fun syncUsage(
        config: UsageSyncConfig,
        correlationId: String,
        promptTokens: Int,
        completionTokens: Int,
        totalTokens: Int,
        durationMs: Long,
    ) {
        val token = config.tokenSupplier() ?: run {
            log.debug("Usage sync skipped — no access token available (correlationId={})", correlationId)
            return
        }

        val json = """{"correlationId":"$correlationId","promptTokens":$promptTokens,"completionTokens":$completionTokens,"totalTokens":$totalTokens,"durationMs":$durationMs}"""

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(config.syncUrl))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $token")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()

        // Fire-and-forget on a daemon virtual thread (or fallback platform thread)
        Thread.ofVirtual().start {
            runCatching {
                syncHttpClient.send(httpRequest, HttpResponse.BodyHandlers.discarding())
                log.debug("Usage synced: correlationId={} tokens={}", correlationId, totalTokens)
            }.onFailure { e ->
                log.warn("Usage sync failed for correlationId={}: {}", correlationId, e.message)
            }
        }
    }

    companion object {
        /** Shared HTTP client for all sync calls — keeps connection pool alive across requests. */
        private val syncHttpClient: HttpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build()

        private const val ATTR_START_TIME = "askimo.startTime"
        private const val ATTR_CORRELATION_ID = "askimo.correlationId"
    }
}
