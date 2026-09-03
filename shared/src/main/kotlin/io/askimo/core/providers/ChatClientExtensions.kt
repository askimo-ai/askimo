/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import dev.langchain4j.data.message.Content
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.exception.InternalServerException
import dev.langchain4j.exception.ModelNotFoundException
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.request.ResponseFormat
import dev.langchain4j.model.chat.request.ResponseFormatType
import dev.langchain4j.model.chat.request.json.JsonArraySchema
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import dev.langchain4j.model.chat.request.json.JsonSchema
import dev.langchain4j.model.chat.request.json.JsonStringSchema
import dev.langchain4j.model.googleai.GeneratedImageHelper
import io.askimo.core.context.AppContext
import io.askimo.core.context.ChatContext
import io.askimo.core.exception.AuthenticationException
import io.askimo.core.exception.ExceptionHandler
import io.askimo.core.exception.LocalServerException
import io.askimo.core.exception.ModelNotFoundChatException
import io.askimo.core.exception.ToolExecutionException
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.intent.DetectAiResponseIntentCommand
import io.askimo.core.intent.FollowUpSuggestion
import io.askimo.core.intent.ToolApprovalPolicy
import io.askimo.core.intent.ToolConfig
import io.askimo.core.intent.ToolRegistry
import io.askimo.core.intent.defaultApprovalPolicy
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
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Extension function to detect if an exception is due to unsupported sampling parameters.
 * Checks for common error messages related to temperature, topP, or other sampling parameters.
 */
private fun Throwable.isUnsupportedSamplingError(): Boolean {
    val message = this.message ?: ""
    return (message.contains("temperature") || message.contains("top_p") || message.contains("topP")) &&
        (
            message.contains("does not support") ||
                message.contains("not supported") ||
                message.contains("unsupported") ||
                message.contains("Unsupported value") ||
                message.contains("cannot both be specified")
            )
}

/**
 * Extension function to detect if an exception is due to the model streaming a malformed
 * tool call — e.g. a hallucinated `tool_calls` entry whose `name`/`arguments` never got
 * fully populated across the delta stream, which langchain4j's internal request builder
 * rejects with messages like `ToolExecutionRequest.arguments must be provided`.
 *
 * This is a transient AI/backend glitch (weaker or local models occasionally emit an
 * incomplete tool-call delta stream, especially with several tools enabled or parallel
 * tool calls) rather than a real configuration problem — so instead of failing the whole
 * turn with a raw internal error message, it's treated like a context-length error:
 * retried immediately with a fresh request (see [sendStreamingMessageWithCallback]).
 */
private fun Throwable.isMalformedToolCallError(): Boolean {
    val message = this.message ?: ""
    return message.contains("ToolExecutionRequest", ignoreCase = true) &&
        (
            message.contains("must be provided", ignoreCase = true) ||
                message.contains("must not be blank", ignoreCase = true)
            )
}

/**
 * Result of classifying a streaming error.
 * @see classifyStreamingError
 */
internal sealed class StreamingErrorResult {
    /** Retry silently — no message shown to the user. */
    object Retryable : StreamingErrorResult()

    /** Stop retrying — show [message] in the chat and mark the response as failed. */
    data class Terminal(val message: String) : StreamingErrorResult()
}

/**
 * Pure classification function: maps a streaming [Throwable] to a [StreamingErrorResult].
 *
 * Has no side-effects (no logging, no cache mutations). Side-effects such as cache
 * updates and logging remain in the call site so that this function is fully
 * unit-testable without any infrastructure.
 */
internal fun classifyStreamingError(
    e: Throwable,
    provider: ModelProvider,
    model: String,
): StreamingErrorResult {
    val errorMessage = e.message ?: ""

    // ── RETRYABLE ─────────────────────────────────────────────────────────────

    // InsufficientContextException must be checked BEFORE isContextLengthError because
    // its rendered message contains "context" + "too long" which would falsely match.
    if (e is InsufficientContextException) {
        return StreamingErrorResult.Terminal(
            e.message ?: "Insufficient context window",
        )
    }

    if (e.isContextLengthError()) return StreamingErrorResult.Retryable
    if (e.isUnsupportedSamplingError()) return StreamingErrorResult.Retryable
    if (e.isMalformedToolCallError()) return StreamingErrorResult.Retryable

    // ── TERMINAL ──────────────────────────────────────────────────────────────

    val msg: String = when {
        e is InsufficientContextException ->
            e.message ?: "Insufficient context window"

        // unreachable, handled above

        e is ToolExecutionException ->
            e.errorDetails ?: "Tool execution failed"

        e is InternalServerException ->
            ExceptionHandler.handle(e)

        errorMessage.contains("process has terminated", ignoreCase = true) ||
            errorMessage.contains("llama-server", ignoreCase = true) ||
            errorMessage.contains("llama_model_loader", ignoreCase = true) ||
            errorMessage.contains("error loading model", ignoreCase = true) ||
            (
                errorMessage.contains("api_error", ignoreCase = true) &&
                    errorMessage.contains("exit status", ignoreCase = true)
                ) ->
            ExceptionHandler.handle(LocalServerException(details = errorMessage, cause = e))

        e.cause is UnresolvedAddressException ->
            """
            ⚠️  Unable to connect to the server!

            Cannot resolve the server address. Please check:
            1. Your internet connection is working
            2. The server URL/endpoint is correct
            3. There are no firewall or proxy issues blocking the connection
            """.trimIndent()

        run {
            val causeMsg = e.cause?.message ?: ""
            errorMessage.contains("header parser received no bytes", ignoreCase = true) ||
                causeMsg.contains("header parser received no bytes", ignoreCase = true)
        } -> {
            val providerHint = LocalizationManager.getString("error.empty_http_response.hint.generic")
            LocalizationManager.getString(
                "error.empty_http_response",
                "${provider.providerKey()}:$model",
                providerHint,
            )
        }

        e is ModelNotFoundException ->
            ExceptionHandler.handle(ModelNotFoundChatException(model = model, cause = e))

        errorMessage.contains("model is required", ignoreCase = true) ||
            errorMessage.contains("No model provided", ignoreCase = true) ||
            errorMessage.contains("model not found", ignoreCase = true) ||
            errorMessage.contains("invalid model", ignoreCase = true) ->
            ExceptionHandler.handle(ModelNotFoundChatException(model = model, cause = e))

        errorMessage.contains("api key") ||
            errorMessage.contains("authentication") ||
            errorMessage.contains("unauthorized") ||
            errorMessage.contains("invalid API key") ||
            errorMessage.contains("Incorrect API key provided") ||
            errorMessage.contains("invalid_api_key") ||
            e is dev.langchain4j.exception.AuthenticationException ->
            ExceptionHandler.handle(AuthenticationException(cause = e))

        else -> "\n[error] ${e.message ?: "unknown error"}\n"
    }

    return StreamingErrorResult.Terminal(msg)
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
 * @return The complete response from the language model as a string
 */
fun ChatClient.sendStreamingMessageWithCallback(
    projectId: String? = null,
    userContents: List<Content>,
    enabledServerIds: Set<String> = emptySet(),
    onToken: (String) -> Unit = {},
    onFollowUpSuggestion: ((FollowUpSuggestion) -> Unit)? = null,
    onTokenUsage: ((inputTokens: Int, outputTokens: Int, totalTokens: Int, durationMs: Long) -> Unit)? = null,
    onToolStarted: ((toolName: String, arguments: String?) -> Unit)? = null,
    onToolFinished: ((toolName: String, arguments: String?, result: String?, hasFailed: Boolean) -> Unit)? = null,
    onThinkingToken: ((String) -> Unit)? = null,
    /**
     * Resolved [ToolConfig] list for the current session.
     * Used to look up each tool's [io.askimo.core.intent.ToolApprovalPolicy] before execution.
     * When empty, no approval checks are performed and all tools run automatically.
     */
    resolvedTools: List<ToolConfig> = emptyList(),
    /**
     * Called in [beforeToolExecution] when the resolved approval policy for a tool is
     * [ToolApprovalPolicy.REQUIRE_APPROVAL] (either explicitly set or implied by the tool's
     * [io.askimo.core.intent.ToolCategory.defaultApprovalPolicy]).
     *
     * The callback **must** eventually invoke either [approve] or [deny] — failing to do so
     * will stall the streaming thread until the 120-second timeout fires.
     *
     * - Invoke [approve] to let the tool proceed.
     * - Invoke [deny] to cancel the tool call (surfaces as a [ToolExecutionException]).
     */
    onToolApprovalRequired: ((toolName: String, arguments: String?, approve: () -> Unit, deny: () -> Unit) -> Unit)? = null,
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

                    sendMessageStreaming(userContents)
                        .onPartialResponse { chunk ->
                            sb.append(chunk)
                            onToken(chunk)
                        }.onPartialThinking { thinking ->
                            val text = thinking.text()
                            if (!text.isNullOrEmpty()) {
                                onThinkingToken?.invoke(text)
                            }
                        }
                        .onCompleteResponse { response ->
                            val aiMessage = response.aiMessage()
                            val tokenUsage = response.tokenUsage()

                            // Fire per-message token usage callback before counting down
                            if (onTokenUsage != null && tokenUsage != null) {
                                val duration = System.currentTimeMillis() - streamStartTime
                                onTokenUsage(
                                    tokenUsage.inputTokenCount() ?: 0,
                                    tokenUsage.outputTokenCount() ?: 0,
                                    tokenUsage.totalTokenCount() ?: 0,
                                    duration,
                                )
                            }

                            if (GeneratedImageHelper.hasGeneratedImages(aiMessage)) {
                                val generatedImages = GeneratedImageHelper.getGeneratedImages(aiMessage)
                                generatedImages?.forEach { image ->
                                    if (image != null) {
                                        val base64Data = image.base64Data()
                                        val mimeType = image.mimeType() ?: "image/png"
                                        val markdownImage = "\n![Generated Image](data:$mimeType;base64,$base64Data)\n"
                                        sb.append(markdownImage)
                                        onToken(markdownImage)
                                    }
                                }
                            }
                            done.countDown()
                        }.beforeToolExecution { before ->
                            val toolName = before.request().name()
                            val arguments = before.request().arguments()
                            log.debug("Tool starting: {}", toolName)
                            onToolStarted?.invoke(toolName, arguments)

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
                                    onToolApprovalRequired.invoke(
                                        toolName,
                                        arguments,
                                        {
                                            approved = true
                                            latch.countDown()
                                        },
                                        { latch.countDown() },
                                    )
                                    if (!latch.await(120, TimeUnit.SECONDS)) {
                                        throw ToolExecutionException(toolName = toolName, errorDetails = LocalizationManager.getString("chat.tool.approval.timed_out", toolName))
                                    }
                                    if (!approved) {
                                        throw ToolExecutionException(toolName = toolName, errorDetails = LocalizationManager.getString("chat.tool.approval.denied", toolName))
                                    }
                                }
                            }
                        }.onToolExecuted { tool ->
                            val toolName = tool.request().name()
                            val arguments = tool.request().arguments()
                            val result = tool.result()
                            val hasFailed = tool.hasFailed()
                            log.debug("Tool executed: {}", toolName)
                            onToolFinished?.invoke(toolName, arguments, result, hasFailed)
                        }
                        .onError { e ->
                            errorOccurred = true
                            capturedError = e

                            when (val result = classifyStreamingError(e, provider, model)) {
                                is StreamingErrorResult.Retryable -> {
                                    // Apply side-effects for the specific retryable type,
                                    // then countDown so the outer catch can loop.
                                    if (e.isContextLengthError()) {
                                        val modelKey = ModelCapabilitiesCache.modelKey(provider, model)
                                        val currentSize = ModelCapabilitiesCache.get(modelKey).contextSize
                                        val newSize = ModelCapabilitiesCache.reduceContextSize(modelKey, currentSize)
                                        log.warn("Context length exceeded for $modelKey (attempt $contextRetryCount/${maxContextRetries + 1}). Reducing context size: $currentSize → $newSize tokens. Retrying immediately...")
                                    } else if (e.isUnsupportedSamplingError()) {
                                        log.warn("Unsupported sampling parameters detected. Falling back to non-sampling settings.")
                                        ModelCapabilitiesCache.setSamplingSupport(provider, model, false)
                                    } else if (e.isMalformedToolCallError()) {
                                        log.warn("Model streamed a malformed tool call (${e.message}) — retrying immediately without penalty.")
                                    }
                                    done.countDown()
                                }

                                is StreamingErrorResult.Terminal -> {
                                    terminalErrorMessage = result.message
                                    sb.append(result.message)
                                    onToken(result.message)
                                    done.countDown()
                                }
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
                            onFollowUpSuggestion(followUpSuggestion)
                        }
                    }

                    result
                }
            } catch (e: Exception) {
                // ConfigurationErrorException is always terminal — never retry regardless of message content
                if (e is ConfigurationErrorException) throw e

                // Check if this is a context length error - immediate retry without backoff
                if ((e.isContextLengthError() || e.isUnsupportedSamplingError() || e.isMalformedToolCallError()) && contextRetryCount < maxContextRetries) {
                    contextRetryCount++

                    // Retry immediately with reduced context size (no backoff)
                    // ChatRequestTransformers.enforceTokenBudget() will automatically truncate
                    // messages to fit the new smaller budget on the next attempt
                    continue
                }

                // Not a context error or out of retries - rethrow
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
