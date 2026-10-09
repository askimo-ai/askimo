/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.Content
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.memory.ChatMemory
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.request.ResponseFormat
import dev.langchain4j.model.chat.request.ResponseFormatType
import dev.langchain4j.model.chat.request.json.JsonArraySchema
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import dev.langchain4j.model.chat.request.json.JsonSchema
import dev.langchain4j.model.chat.request.json.JsonStringSchema
import dev.langchain4j.model.googleai.GeneratedImageHelper
import io.askimo.core.config.AppConfig
import io.askimo.core.context.AppContext
import io.askimo.core.context.ChatContext
import io.askimo.core.exception.AskimoException
import io.askimo.core.exception.ContextLengthException
import io.askimo.core.exception.ExceptionHandler
import io.askimo.core.exception.ExceptionMapper
import io.askimo.core.exception.MalformedToolCallException
import io.askimo.core.exception.RetryPolicy
import io.askimo.core.exception.ToolExecutionException
import io.askimo.core.exception.UnsupportedSamplingException
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.intent.DetectAiResponseIntentCommand
import io.askimo.core.intent.FollowUpSuggestion
import io.askimo.core.intent.ToolApprovalPolicy
import io.askimo.core.intent.ToolConfig
import io.askimo.core.intent.ToolRegistry
import io.askimo.core.intent.defaultApprovalPolicy
import io.askimo.core.logging.currentFileLogger
import io.askimo.core.logging.logger
import io.askimo.core.memory.SessionConversationSummary
import io.askimo.core.memory.UserMemorySummary
import io.askimo.core.util.JsonUtils.json
import io.askimo.core.util.RetryPresets
import io.askimo.core.util.RetryUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private val log = currentFileLogger()

/**
 * Checks whether any message in this exception's cause chain contains [needle]
 * (case-insensitive). Unlike a single `e.message` / `e.cause?.message` check, this
 * walks arbitrarily deep — needed because HTTP clients commonly wrap the originating
 * exception several levels deep (e.g. CompletionException -> RuntimeException -> IOException).
 */
private fun Throwable.causeChainContains(needle: String): Boolean {
    var current: Throwable? = this
    val seen = mutableSetOf<Throwable>()
    while (current != null && seen.add(current)) {
        if (current.message?.contains(needle, ignoreCase = true) == true) return true
        current = current.cause
    }
    return false
}

/**
 * Result of classifying a streaming error.
 * @see classifyStreamingError
 */
internal sealed class StreamingErrorResult {
    /**
     * Retry silently — no message shown to the user.
     *
     * @property exception The classified error, so the call site can apply the correct
     * side effect (shrink context cache, disable sampling, etc.) without re-deriving
     * the classification itself via a second round of string-matching.
     */
    data class Retryable(val exception: AskimoException) : StreamingErrorResult()

    /** Stop retrying — show [message] in the chat and mark the response as failed. */
    data class Terminal(val message: String) : StreamingErrorResult()
}

/**
 * Notifies tool completion on the approval-timeout/denial paths, awaiting it when possible
 * before the caller propagates its exception.
 *
 * Prefers [onToolFinishedAwaitable] when non-null, awaiting its [CompletableFuture] (bounded by
 * [timeoutMs]) so e.g. `SessionManager.markToolDone` applies before a later `catch` block reads
 * stale (still-RUNNING) state. Falls back to firing [onToolFinished] without waiting — preserving
 * its original behavior for callers that don't supply the awaitable variant.
 *
 * @param onToolFinished Legacy, non-awaitable callback — invoked without waiting if [onToolFinishedAwaitable] is null.
 * @param onToolFinishedAwaitable Optional awaitable variant — when present, takes precedence and is awaited.
 * @param id [ToolExecutionRequest.id] of the invocation this update belongs to
 * @param toolName Name of the tool
 * @param arguments Tool arguments
 * @param result Tool result
 * @param hasFailed Whether the tool failed
 * @param timeoutMs Timeout for waiting on the completion future
 */
private fun notifyToolFinishedAndAwaitIfPossible(
    onToolFinished: ((String, String, String?, String?, Boolean) -> Unit)?,
    onToolFinishedAwaitable: ((String, String, String?, String?, Boolean) -> CompletableFuture<Unit>?)?,
    id: String,
    toolName: String,
    arguments: String?,
    result: String?,
    hasFailed: Boolean,
    timeoutMs: Long,
) {
    try {
        if (onToolFinishedAwaitable != null) {
            onToolFinishedAwaitable.invoke(id, toolName, arguments, result, hasFailed)?.get(timeoutMs, TimeUnit.MILLISECONDS)
        } else {
            onToolFinished?.invoke(id, toolName, arguments, result, hasFailed)
        }
    } catch (e: Exception) {
        log.warn("Tool completion callback failed or timed out for $toolName", e)
    }
}

/**
 * Pure classification function: maps a streaming [Throwable] to a [StreamingErrorResult].
 *
 * No side-effects (no logging, no cache mutations) — those stay at the call site so this
 * stays fully unit-testable. Delegates to [ExceptionMapper] for classification and retry
 * policy, except two cases needing context the mapper doesn't have:
 * - [InsufficientContextException]'s message is already fully rendered.
 * - The empty-HTTP-response case needs `provider`/`model` to build its hint.
 */
internal fun classifyStreamingError(
    e: Throwable,
    provider: ModelProvider,
    model: String,
): StreamingErrorResult {
    // InsufficientContextException's message contains "context" + "too long", which would
    // otherwise be misclassified as a retryable ContextLengthException by ExceptionMapper —
    // so it must be checked first, and always terminal (retries are already exhausted by
    // the time this is thrown).
    if (e is InsufficientContextException) {
        return StreamingErrorResult.Terminal(e.message ?: "Insufficient context window")
    }

    // Needs provider/model to render its hint — not derivable from the exception alone.
    // Walks the full cause chain since HTTP clients often wrap the originating IOException
    // several levels deep (e.g. CompletionException -> RuntimeException -> IOException).
    if (e.causeChainContains("header parser received no bytes")) {
        val providerHint = LocalizationManager.getString("error.empty_http_response.hint.generic")
        return StreamingErrorResult.Terminal(
            LocalizationManager.getString(
                "error.empty_http_response",
                "${provider.providerKey()}:$model",
                providerHint,
            ),
        )
    }

    val askimoException = ExceptionMapper.map(e)

    // Only IMMEDIATE has an actual retry mechanism wired up at this call site (the
    // context-retry loop in `sendStreamingMessageWithCallback`, bounded at 20 attempts).
    // BACKOFF-classified errors (rate limits, transient 5xx, network blips) have no
    // backoff loop implemented here yet, so — for now — they're rendered as Terminal
    // just like NONE, rather than silently swallowed with no actual retry happening.
    return when (askimoException.retryPolicy) {
        RetryPolicy.IMMEDIATE -> StreamingErrorResult.Retryable(askimoException)
        RetryPolicy.BACKOFF, RetryPolicy.NONE -> StreamingErrorResult.Terminal(ExceptionHandler.handle(askimoException))
    }
}

/**
 * Provides a synchronous interface to chat with a language model.
 *
 * This extension function wraps the asynchronous streaming API of [ChatClient]
 * into a blocking call that returns the complete response as a string.
 *
 * Features:
 * - Automatic retry on transient errors
 * - Context length error detection and automatic context size reduction
 * - Memory clearing on context errors to reduce conversation history
 * - User-friendly error messages for configuration issues
 * - Two-stage intent detection:
 *   - Stage 1 (Pre-request): Detect user intent to attach relevant tools
 *   - Stage 2 (Post-response): Detect follow-up opportunities from AI response
 *
 * @param userContents the contents sent by user
 * @param onToken Optional callback function that is invoked for each token received from the model
 * @param onFollowUpSuggestion Optional callback for follow-up suggestions based on AI response
 * @param onToolFinishedAwaitable Awaitable variant of [onToolFinished], used instead of it on the
 * approval-timeout/denial paths: its [CompletableFuture] is awaited (bounded by [toolApprovalTimeoutMs])
 * before throwing, so callers like `SessionManager` can finish state updates (e.g. `markToolDone`)
 * before the tool is read as still RUNNING elsewhere.
 * @return The complete response from the language model as a string
 */
fun ChatClient.sendStreamingMessageWithCallback(
    projectId: String? = null,
    userContents: List<Content>,
    enabledServerIds: Set<String> = emptySet(),
    onToken: (String) -> Unit = {},
    onFollowUpSuggestion: ((FollowUpSuggestion) -> Unit)? = null,
    onTokenUsage: ((inputTokens: Int, outputTokens: Int, totalTokens: Int, durationMs: Long) -> Unit)? = null,
    /**
     * `id` is [ToolExecutionRequest.id] — the model's unique id for this specific invocation, not
     * just `toolName`. Needed because the model can call the same tool twice with identical
     * arguments in one parallel batch, which `toolName`+arguments alone can't disambiguate.
     */
    onToolStarted: ((id: String, toolName: String, arguments: String?) -> Unit)? = null,
    onToolFinished: ((id: String, toolName: String, arguments: String?, result: String?, hasFailed: Boolean) -> Unit)? = null,
    onThinkingToken: ((String) -> Unit)? = null,
    /**
     * Resolved [ToolConfig] list for the current session.
     * Used to look up each tool's [io.askimo.core.intent.ToolApprovalPolicy] before execution.
     * When empty, no approval checks are performed and all tools run automatically.
     */
    resolvedTools: List<ToolConfig> = emptyList(),
    /**
     * Called in `beforeToolExecution` when the resolved approval policy for a tool is
     * [ToolApprovalPolicy.REQUIRE_APPROVAL] (either explicitly set or implied by the tool's
     * [io.askimo.core.intent.ToolCategory.defaultApprovalPolicy]).
     *
     * The callback **must** eventually invoke either `approve` or `deny` — failing to do so
     * will stall the streaming thread until [toolApprovalTimeoutMs] elapses.
     *
     * - Invoke `approve` to let the tool proceed.
     * - Invoke `deny` to cancel the tool call (surfaces as a [ToolExecutionException]).
     */
    onToolApprovalRequired: ((toolName: String, arguments: String?, approve: () -> Unit, deny: () -> Unit) -> Unit)? = null,
    /**
     * Called when the approval wait in [onToolApprovalRequired] times out (neither `approve`
     * nor `deny` was invoked). Lets the caller clear pending-approval UI state that its own
     * `approve`/`deny` closures never got to run — e.g. `SessionManager.StreamingThread.clearApproval()`
     * — otherwise an approval banner stays visible forever.
     */
    onToolApprovalTimedOut: (() -> Unit)? = null,
    /**
     * How long to wait for [onToolApprovalRequired] to invoke `approve`/`deny` before treating
     * the request as timed out. Defaults to [io.askimo.core.config.ModelsConfig.toolApprovalTimeoutMs];
     * overridable here mainly so tests can inject a much smaller value instead of waiting out
     * the real (120s) default.
     */
    toolApprovalTimeoutMs: Long = AppConfig.models.toolApprovalTimeoutMs,
    /**
     * Session [ChatMemory]. On tool-approval denial/timeout, used to append a synthetic
     * [ToolExecutionResultMessage] before throwing — otherwise the already-persisted `tool_use`
     * is left without a matching `tool_result`, which Anthropic's API rejects on the next turn.
     */
    chatMemory: ChatMemory? = null,
    onToolFinishedAwaitable: ((id: String, toolName: String, arguments: String?, result: String?, hasFailed: Boolean) -> CompletableFuture<Unit>?)? = null,
): String {
    val log = logger<ChatClient>()

    try {
        // Set both projectId and enabledServers in ThreadLocal on this thread —
        // the same thread LangChain4j uses to call ToolProviderImpl.provideTools().
        ChatContext.setProjectId(projectId)
        ChatContext.setEnabledServers(enabledServerIds)

        // Get provider and model from AppContext
        val appContext = AppContext.getInstance()
        val provider = appContext.getActiveProvider()
        val model = appContext.params.model

        var contextRetryCount = 0
        val maxContextRetries = 20 // 20 immediate retries for context errors

        while (contextRetryCount <= maxContextRetries) {
            try {
                if (contextRetryCount > 0) {
                    log.debug("Retrying request with reduced context (attempt ${contextRetryCount + 1}/${maxContextRetries + 1})")
                }

                // Execute the streaming request with retry logic for transient errors
                return RetryUtils.retry(RetryPresets.STREAMING_ERRORS) {
                    val sb = StringBuilder()
                    val done = CountDownLatch(1)
                    var errorOccurred = false
                    // Non-null when the error is terminal: holds the rendered message already
                    // sent to the UI via onToken. Causes ConfigurationErrorException after done.await().
                    var terminalErrorMessage: String? = null
                    var capturedError: Throwable? = null
                    val streamStartTime = System.currentTimeMillis()

                    // Tool-call ids requested by the current AiMessage that don't have a
                    // ToolExecutionResultMessage yet. `onCompleteResponse` is the *terminal*
                    // AiServices callback — LangChain4j only invokes it once, with the final
                    // answer, *after* all tool rounds (beforeToolExecution → tool run →
                    // onToolExecuted) have already completed. So it fires too late to seed this
                    // map before an approval check. Instead it's lazily populated inside
                    // `beforeToolExecution` (see below) from the latest tool-calling AiMessage
                    // already persisted to [chatMemory] — LangChain4j appends that AiMessage
                    // before invoking beforeToolExecution for its requests. Drained in
                    // onToolExecuted. If an approval denial/timeout aborts the loop, whatever
                    // remains here still needs a synthetic result — otherwise sibling tool_use
                    // ids from the same parallel-tool-call turn are left dangling.
                    val pendingToolRequests = mutableMapOf<String, ToolExecutionRequest>()

                    sendMessageStreaming(userContents)
                        .onPartialResponse { chunk ->
                            sb.append(chunk)
                            try {
                                onToken(chunk)
                            } catch (e: Exception) {
                                log.warn("onToken callback threw", e)
                            }
                        }.onPartialThinking { thinking ->
                            val text = thinking.text()
                            if (!text.isNullOrEmpty()) {
                                try {
                                    onThinkingToken?.invoke(text)
                                } catch (e: Exception) {
                                    log.warn("onThinkingToken callback threw", e)
                                }
                            }
                        }
                        .onCompleteResponse { response ->
                            val aiMessage = response.aiMessage()
                            val tokenUsage = response.tokenUsage()

                            // Fire per-message token usage callback before counting down
                            if (onTokenUsage != null && tokenUsage != null) {
                                val duration = System.currentTimeMillis() - streamStartTime
                                try {
                                    onTokenUsage(
                                        tokenUsage.inputTokenCount() ?: 0,
                                        tokenUsage.outputTokenCount() ?: 0,
                                        tokenUsage.totalTokenCount() ?: 0,
                                        duration,
                                    )
                                } catch (e: Exception) {
                                    log.warn("onTokenUsage callback threw", e)
                                }
                            }

                            if (GeneratedImageHelper.hasGeneratedImages(aiMessage)) {
                                val generatedImages = GeneratedImageHelper.getGeneratedImages(aiMessage)
                                generatedImages?.forEach { image ->
                                    if (image != null) {
                                        val base64Data = image.base64Data()
                                        val mimeType = image.mimeType() ?: "image/png"
                                        val markdownImage = "\n![Generated Image](data:$mimeType;base64,$base64Data)\n"
                                        sb.append(markdownImage)
                                        try {
                                            onToken(markdownImage)
                                        } catch (e: Exception) {
                                            log.warn("onToken callback threw", e)
                                        }
                                    }
                                }
                            }
                            done.countDown()
                        }.beforeToolExecution { before ->
                            val id = before.request().id()
                            val toolName = before.request().name()
                            val arguments = before.request().arguments()
                            try {
                                onToolStarted?.invoke(id, toolName, arguments)
                            } catch (e: Exception) {
                                log.warn("onToolStarted callback threw for $toolName", e)
                            }

                            // Lazily seed `pendingToolRequests` from the latest tool-calling
                            // AiMessage already persisted to chatMemory — LangChain4j appends
                            // that AiMessage before invoking beforeToolExecution for any of its
                            // requests, so it's guaranteed to be present by the time we get here
                            // (unlike onCompleteResponse, which fires only after all tool rounds
                            // finish). Only runs once per turn: subsequent beforeToolExecution
                            // calls for sibling requests in the same parallel-tool-call batch
                            // find the map already populated.
                            if (pendingToolRequests.isEmpty()) {
                                chatMemory
                                    ?.messages()
                                    ?.asReversed()
                                    ?.filterIsInstance<AiMessage>()
                                    ?.firstOrNull { it.hasToolExecutionRequests() }
                                    ?.toolExecutionRequests()
                                    ?.forEach { req -> pendingToolRequests[req.id()] = req }
                            }

                            // ── Approval guardrail ─────────────────────────────────────────────
                            if (onToolApprovalRequired != null) {
                                val toolConfig = resolvedTools.find { it.specification.name() == toolName }
                                val effectivePolicy = when (toolConfig?.approvalPolicy ?: ToolApprovalPolicy.DEFAULT) {
                                    ToolApprovalPolicy.DEFAULT -> toolConfig?.category?.defaultApprovalPolicy()
                                        ?: ToolApprovalPolicy.DEFAULT

                                    ToolApprovalPolicy.REQUIRE_APPROVAL -> ToolApprovalPolicy.REQUIRE_APPROVAL
                                }

                                if (effectivePolicy == ToolApprovalPolicy.REQUIRE_APPROVAL) {
                                    val latch = CountDownLatch(1)
                                    var approved = false
                                    try {
                                        onToolApprovalRequired.invoke(
                                            toolName,
                                            arguments,
                                            {
                                                approved = true
                                                latch.countDown()
                                            },
                                            { latch.countDown() },
                                        )
                                    } catch (e: Exception) {
                                        // Treat a throwing callback as an implicit deny (fail-safe)
                                        // rather than hanging for the full approval timeout.
                                        log.warn("onToolApprovalRequired callback threw for $toolName — treating as denied", e)
                                        latch.countDown()
                                    }
                                    if (!latch.await(toolApprovalTimeoutMs, TimeUnit.MILLISECONDS)) {
                                        val msg = LocalizationManager.getString("chat.tool.approval.timed_out", toolName)
                                        // Snapshot before clearing — every pending sibling needs its own
                                        // completion notification, not just the current tool. Unioned with
                                        // the current request in case chatMemory is null (unseeded map).
                                        val terminatedRequests = (pendingToolRequests.values + before.request()).distinctBy { it.id() }
                                        terminatedRequests.forEach { req ->
                                            chatMemory?.add(ToolExecutionResultMessage.from(req, msg))
                                        }
                                        pendingToolRequests.clear()
                                        // Neither approve() nor deny() fired, so the caller's pending-approval
                                        // UI state was never cleared by either closure above. Fire this
                                        // immediately — not after awaiting onToolFinishedAwaitable below,
                                        // which can itself block for up to toolApprovalTimeoutMs and would
                                        // otherwise leave the approval banner visible for twice as long.
                                        try {
                                            onToolApprovalTimedOut?.invoke()
                                        } catch (e: Exception) {
                                            log.warn("onToolApprovalTimedOut callback threw", e)
                                        }
                                        terminatedRequests.forEach { req ->
                                            notifyToolFinishedAndAwaitIfPossible(onToolFinished, onToolFinishedAwaitable, req.id(), req.name(), req.arguments(), msg, true, toolApprovalTimeoutMs)
                                        }
                                        throw ToolExecutionException(toolName = toolName, errorDetails = msg)
                                    }
                                    if (!approved) {
                                        val msg = LocalizationManager.getString("chat.tool.approval.denied", toolName)
                                        // Same reasoning as the timeout branch above.
                                        val terminatedRequests = (pendingToolRequests.values + before.request()).distinctBy { it.id() }
                                        terminatedRequests.forEach { req ->
                                            chatMemory?.add(ToolExecutionResultMessage.from(req, msg))
                                        }
                                        pendingToolRequests.clear()
                                        terminatedRequests.forEach { req ->
                                            notifyToolFinishedAndAwaitIfPossible(onToolFinished, onToolFinishedAwaitable, req.id(), req.name(), req.arguments(), msg, true, toolApprovalTimeoutMs)
                                        }
                                        throw ToolExecutionException(toolName = toolName, errorDetails = msg)
                                    }
                                }
                            }
                        }.onToolExecuted { tool ->
                            pendingToolRequests.remove(tool.request().id())
                            val toolName = tool.request().name()
                            val arguments = tool.request().arguments()
                            val result = tool.result()
                            val hasFailed = tool.hasFailed()
                            try {
                                onToolFinished?.invoke(tool.request().id(), toolName, arguments, result, hasFailed)
                            } catch (e: Exception) {
                                log.warn("onToolFinished callback threw for $toolName", e)
                            }
                        }
                        .onError { e ->
                            errorOccurred = true
                            capturedError = e

                            val result = classifyStreamingError(e, provider, model)
                            // Only apply the retry side effect (and actually retry) if there's
                            // budget left — otherwise mutating the cache / disabling sampling
                            // buys nothing since the outer catch won't loop again anyway, and
                            // the user would be left without any terminal message on this attempt.
                            val retriesRemain = contextRetryCount < maxContextRetries

                            if (result is StreamingErrorResult.Retryable && retriesRemain) {
                                // Apply the side effect specific to this retryable
                                // classification, then countDown so the outer catch can loop.
                                when (result.exception) {
                                    is ContextLengthException -> {
                                        val modelKey = ModelCapabilitiesCache.modelKey(provider, model)
                                        val currentSize = ModelCapabilitiesCache.get(modelKey).contextSize
                                        val newSize = ModelCapabilitiesCache.reduceContextSize(modelKey, currentSize)
                                        log.warn("Context length exceeded for $modelKey (attempt $contextRetryCount/${maxContextRetries + 1}). Reducing context size: $currentSize → $newSize tokens. Retrying immediately...")
                                    }

                                    is UnsupportedSamplingException -> {
                                        log.warn("Unsupported sampling parameters detected. Falling back to non-sampling settings.")
                                        ModelCapabilitiesCache.setSamplingSupport(provider, model, false)
                                    }

                                    is MalformedToolCallException ->
                                        log.warn("Model streamed a malformed tool call (${e.message}) — retrying immediately without penalty.")

                                    else ->
                                        log.warn("Retrying after ${result.exception::class.simpleName}: ${e.message}")
                                }
                                done.countDown()
                            } else {
                                // Either genuinely Terminal, or Retryable but out of retry budget —
                                // render a proper terminal message either way so the caller/user
                                // sees feedback instead of a silent countDown followed by a bare
                                // IllegalStateException further down.
                                val message = when (result) {
                                    is StreamingErrorResult.Terminal -> result.message

                                    is StreamingErrorResult.Retryable -> {
                                        log.warn("Retry budget exhausted for ${result.exception::class.simpleName} (attempt $contextRetryCount/${maxContextRetries + 1}) — treating as terminal.")
                                        ExceptionHandler.handle(result.exception)
                                    }
                                }
                                terminalErrorMessage = message
                                sb.append(message)
                                try {
                                    onToken(message)
                                } catch (e2: Exception) {
                                    log.warn("onToken callback threw", e2)
                                }
                                done.countDown()
                            }
                        }.start()

                    done.await()

                    val result = sb.toString()

                    if (terminalErrorMessage != null) {
                        throw ConfigurationErrorException(result)
                    }

                    if (errorOccurred) {
                        val errorDetails = capturedError?.message ?: "Unknown streaming error"
                        throw IllegalStateException("Streaming error occurred: $errorDetails", capturedError)
                    }

                    // === STAGE 2: Post-response - Detect follow-up opportunities ===
                    if (onFollowUpSuggestion != null) {
                        val followUpSuggestion = DetectAiResponseIntentCommand.execute(
                            result,
                            availableTools = ToolRegistry.getFollowUpOnly(),
                        )

                        if (followUpSuggestion != null) {
                            log.debug("Detected follow-up opportunity (Stage 2): ${followUpSuggestion.question}")
                            try {
                                onFollowUpSuggestion(followUpSuggestion)
                            } catch (e: Exception) {
                                log.warn("onFollowUpSuggestion callback threw", e)
                            }
                        }
                    }

                    result
                }
            } catch (e: Exception) {
                // ConfigurationErrorException is always terminal — never retry regardless of message content
                if (e is ConfigurationErrorException) throw e

                // Same classification the streaming path uses (ExceptionMapper is the single
                // source of truth for retry policy) — covers the case where an error is thrown
                // synchronously (not via onError) or wrapped as IllegalStateException above.
                if (ExceptionMapper.map(e).retryPolicy == RetryPolicy.IMMEDIATE && contextRetryCount < maxContextRetries) {
                    contextRetryCount++

                    // Retry immediately with reduced context size (no backoff)
                    // ChatRequestTransformers.enforceTokenBudget() will automatically truncate
                    // messages to fit the new smaller budget on the next attempt
                    continue
                }

                // Not an immediately-retryable error, or out of retries - rethrow
                throw e
            }
        }

        // Should never reach here, but for completeness
        error("Failed to send message after ${maxContextRetries + 1} context retries")
    } finally {
        // Always clear ThreadLocal to prevent memory leaks
        ChatContext.clear()
    }
}

/**
 * Parse a structured JSON response from the model into a typed object.
 *
 * Handles common formatting issues:
 * - Markdown code blocks (``` or ```json)
 * - Excess whitespace and newlines
 * - Arrays that should be strings (joins with comma-space)
 * - Strings that should be arrays (splits by comma)
 *
 * @param rawResponse The raw JSON string from the model
 * @param arrayKeys Keys whose values must be JSON arrays (converts comma-string → array)
 * @param stringKeys Keys whose values must be strings (converts array → comma-string)
 * @return Parsed object of type T
 * @throws Exception if JSON parsing fails after cleanup
 */
private inline fun <reified T> parseStructuredOutput(
    rawResponse: String,
    arrayKeys: Set<String> = emptySet(),
    stringKeys: Set<String> = emptySet(),
): T {
    var jsonText = rawResponse
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()

    jsonText = cleanJsonResponse(jsonText)
    jsonText = normalizeJsonFieldTypes(jsonText, arrayKeys, stringKeys)

    return json.decodeFromString<T>(jsonText)
}

/**
 * Generates a structured summary of a conversation using LangChain4j's native
 * [ResponseFormat] + JSON-schema enforcement so the model is forced to return
 * well-formed JSON without relying on prompt-only instructions.
 *
 * After summarization, the caller derives a title candidate from [SessionConversationSummary.recentContext]
 * and [SessionConversationSummary.mainTopics] — no extra AI call needed.
 *
 * Falls back to prompt-only parsing if schema enforcement fails (e.g. older local models).
 *
 * @param conversationText The conversation text to summarize
 * @return A [SessionConversationSummary] containing key facts, main topics, and recent context
 */
fun ChatModel.getSummary(conversationText: String): SessionConversationSummary {
    val log = logger<ChatModel>()

    // Build JSON-schema that enforces the exact shape of SessionConversationSummary
    val responseFormat = ResponseFormat.builder()
        .type(ResponseFormatType.JSON)
        .jsonSchema(
            JsonSchema.builder()
                .name("SessionConversationSummary")
                .rootElement(
                    JsonObjectSchema.builder()
                        .addProperty(
                            "keyFacts",
                            JsonObjectSchema.builder()
                                .description("Key facts as name-value pairs extracted from the conversation")
                                .additionalProperties(true)
                                .build(),
                        )
                        .addProperty(
                            "mainTopics",
                            JsonArraySchema.builder()
                                .description("List of main topics discussed")
                                .items(JsonStringSchema.builder().build())
                                .build(),
                        )
                        .addStringProperty("recentContext", "1-3 sentence summary of the latest state and immediate goal")
                        .required("keyFacts", "mainTopics", "recentContext")
                        .build(),
                )
                .build(),
        )
        .build()

    val chatRequest = ChatRequest.builder()
        .messages(UserMessage.from(buildSummaryPrompt(conversationText)))
        .responseFormat(responseFormat)
        .build()

    return try {
        val rawJson = this.chat(chatRequest).aiMessage().text() ?: "{}"
        json.decodeFromString<SessionConversationSummary>(rawJson)
    } catch (e: Exception) {
        log.warn("Native-schema summary failed ({}), retrying with prompt-only fallback", e.message)
        // Fallback: send without ResponseFormat, parse manually
        try {
            val rawResponse = this.chat(ChatRequest.builder().messages(UserMessage.from(buildSummaryPrompt(conversationText))).build()).aiMessage().text() ?: "{}"
            parseStructuredOutput<SessionConversationSummary>(
                rawResponse,
                arrayKeys = setOf("mainTopics"),
                stringKeys = setOf("recentContext"),
            )
        } catch (fallback: Exception) {
            log.error("Summary fallback also failed. Returning minimal context summary.", fallback)
            SessionConversationSummary(
                keyFacts = emptyMap(),
                mainTopics = emptyList(),
                recentContext = conversationText.takeLast(500),
            )
        }
    }
}

/**
 * Build the system prompt for conversation summarization.
 * Emphasizes quality, specificity, and actionable facts.
 */
private fun buildSummaryPrompt(conversationText: String) = """
    Analyze this conversation and extract a structured summary.

    FOCUS ON:
    • Meaningful, actionable facts (ignore greetings, confirmations, filler)
    • Specific topics with technical details (don't use generic labels)
    • User's goals, preferences, and constraints
    • Current state and next steps

    CONVERSATION:
    $conversationText

    RESPOND WITH VALID JSON ONLY (no markdown or explanation):
    {
      "keyFacts": { "key_name": "value", ... },
      "mainTopics": [ "topic1", "topic2", ... ],
      "recentContext": "1-3 sentence summary of latest state and immediate goal"
    }
""".trimIndent()

/**
 * Extract stable, long-term facts about the user from a conversation.
 *
 * Unlike [getSummary] which captures what was discussed, this focuses exclusively on
 * persistent facts about the person: their role, tech stack, preferences, constraints,
 * and working context. Trivial or session-specific information is ignored.
 *
 * Returns an empty map if nothing worth remembering is found — callers should skip
 * the merge in that case to avoid polluting the user memory store.
 *
 * @param conversationText The conversation text to analyse.
 * @return Map of fact-key to fact-value, or empty map if nothing stable was found.
 */
fun ChatClient.getUserMemoryFacts(conversationText: String): Map<String, String> {
    val log = logger<ChatClient>()

    val prompt = buildUserMemoryPrompt(conversationText)

    return try {
        val rawResponse = this.sendMessage(prompt)
        // Wrap flat {"key":"value"} into {"facts": {...}} expected by UserMemorySummary
        val wrapped = """{"facts": ${cleanJsonResponse(rawResponse.removePrefix("```json").removePrefix("```").removeSuffix("```").trim())}}"""
        val sanitized = normalizeJsonFieldTypes(wrapped, emptySet(), emptySet())
        json.decodeFromString<UserMemorySummary>(sanitized).facts
    } catch (e: Exception) {
        log.debug("getUserMemoryFacts: could not parse response, returning empty — {}", e.message)
        emptyMap()
    }
}

/**
 * Build the system prompt for extracting user memory facts.
 * Uses intention-qualified key names to avoid ambiguous extractions (e.g. bare "location").
 */
private fun buildUserMemoryPrompt(conversationText: String) = """
    Extract stable, long-term personalization facts about the USER that will help provide relevant,
    contextual, and personalized responses across all future conversations.

    Include EVERYTHING that defines who they are and how they live:

    LIFESTYLE & LIVING:
    • Where the USER *lives* (only if they explicitly say so — "I live in...", "I'm based in...")
    • Timezone, work schedule, lifestyle patterns
    • Family situation, household composition
    • Health, fitness, dietary preferences

    PROFESSIONAL & EXPERTISE:
    • Role, title, industry, company type
    • Skills, expertise areas, tech stack
    • Work style, work-life balance preferences
    • Career goals, ambitions

    PERSONAL INTERESTS & PASSIONS:
    • Hobbies and activities they enjoy (e.g. "I love aquariums", "I enjoy gaming")
    • Interests inferred from topics they *personally care about*
    • Learning goals, sports, entertainment preferences

    PREFERENCES & PERSONALITY:
    • Communication style: formal/casual, detailed/concise, humor preference
    • Decision-making and values: what matters to them
    • Pet peeves or strong preferences

    TECHNICAL & PRACTICAL:
    • Operating system, devices, tools they use daily
    • Programming languages, frameworks, tools

    KEY NAMING RULES — USE INTENTION-QUALIFIED KEYS:
    • ALWAYS qualify ambiguous keys with their meaning. Never use bare "location".
    • "home_location" → where the user actually lives
    • "travel_interest" → places they want to visit or are curious about
    • "hobby" → activities they actively do
    Bad:  { "location": "Los Angeles" }          ← meaningless, unclear intent
    Good: { "travel_interest": "Los Angeles" }    ← user wants to visit
    Good: { "home_location": "Ho Chi Minh City" } ← user lives here

    CRITICAL RULES:
    • Distinguish "user IS [something]" vs "user is ASKING ABOUT [something]"
    • A city/place mentioned in a question is NOT the user's location unless explicitly stated
    • Only store facts that reflect the user's own identity, preferences, or life — not topics discussed

    IGNORE:
    • Temporary context (current task, one-off questions)
    • Topics discussed that don't reflect the user's identity
    • Greetings, pleasantries, confirmations
    • Weak or uncertain claims

    CONVERSATION:
    $conversationText

    RESPOND WITH VALID JSON ONLY (no markdown, no explanation):
    { "hobby": "travel", "family_status": "has young children", "travel_interest": "US national parks", ... }

    Return empty {} if no stable personalizable facts found.
""".trimIndent()

internal fun cleanJsonResponse(jsonText: String): String {
    // First, try to find the actual JSON object
    val jsonStart = jsonText.indexOf('{')
    val jsonEnd = jsonText.lastIndexOf('}')

    if (jsonStart == -1 || jsonEnd == -1 || jsonStart >= jsonEnd) {
        return jsonText // Return as-is if no valid JSON structure found
    }

    val jsonOnly = jsonText.substring(jsonStart, jsonEnd + 1)

    // Remove newlines and excessive whitespace while preserving JSON structure
    return jsonOnly
        .replace("\n", " ") // Remove all newlines
        .replace("\r", " ") // Remove carriage returns
        .replace("\\s+".toRegex(), " ") // Collapse multiple spaces
        .trim()
}

/**
 * Normalize JSON field types to match the expected schema:
 * - [arrayKeys]: fields that must be JSON arrays.
 *   If the model returns a string, it is split by comma into an array.
 * - [stringKeys]: fields that must be strings.
 *   If the model returns an array, it is joined with ", ".
 * - All other array values (including inside nested objects such as keyFacts) are converted
 *   to comma-strings — safe default for Map<String,String> schemas.
 */
internal fun normalizeJsonFieldTypes(
    jsonText: String,
    arrayKeys: Set<String>,
    stringKeys: Set<String>,
): String {
    val log = logger<ChatClient>()
    return try {
        val jsonParser = Json { ignoreUnknownKeys = true }
        val root = jsonParser.parseToJsonElement(jsonText)
        if (root !is JsonObject) return jsonText

        fun normalizeValue(key: String, value: JsonElement): JsonElement = when {
            key in arrayKeys -> when (value) {
                is JsonArray -> value

                is JsonPrimitive -> {
                    val parts = value.content.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    JsonArray(parts.map { JsonPrimitive(it) })
                }

                else -> value
            }

            key in stringKeys || value is JsonArray -> when (value) {
                is JsonArray -> JsonPrimitive(
                    value.jsonArray.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(", "),
                )

                else -> value
            }

            // Recurse into nested objects so e.g. keyFacts values are also normalized
            value is JsonObject -> JsonObject(value.entries.associate { (k, v) -> k to normalizeValue(k, v) })

            else -> value
        }

        val fixed = root.entries.associate { (key, value) -> key to normalizeValue(key, value) }
        JsonObject(fixed).toString()
    } catch (e: Exception) {
        log.debug("normalizeJsonFieldTypes: failed to normalize, returning original — {}", e.message)
        jsonText
    }
}
