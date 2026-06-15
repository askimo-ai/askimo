/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.googleai.GeneratedImageHelper
import io.askimo.core.context.AppContext
import io.askimo.core.context.ChatContext
import io.askimo.core.exception.ToolExecutionException
import io.askimo.core.intent.DetectAiResponseIntentCommand
import io.askimo.core.intent.FollowUpSuggestion
import io.askimo.core.intent.ToolRegistry
import io.askimo.core.logging.logger
import io.askimo.core.memory.SessionConversationSummary
import io.askimo.core.memory.UserMemorySummary
import io.askimo.core.util.JsonUtils.json
import io.askimo.core.util.RetryPresets
import io.askimo.core.util.RetryUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.CountDownLatch

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
 * @param userMessage The input text to send to the language model
 * @param onToken Optional callback function that is invoked for each token received from the model
 * @param onFollowUpSuggestion Optional callback for follow-up suggestions based on AI response
 * @return The complete response from the language model as a string
 */
fun ChatClient.sendStreamingMessageWithCallback(
    projectId: String? = null,
    userMessage: UserMessage,
    enabledServerIds: Set<String> = emptySet(),
    onToken: (String) -> Unit = {},
    onFollowUpSuggestion: ((FollowUpSuggestion) -> Unit)? = null,
    onTokenUsage: ((inputTokens: Int, outputTokens: Int, totalTokens: Int, durationMs: Long) -> Unit)? = null,
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
                    var isConfigurationError = false
                    var capturedError: Throwable? = null
                    val streamStartTime = System.currentTimeMillis()

                    sendMessageStreaming(userMessage)
                        .onPartialResponse { chunk ->
                            sb.append(chunk)
                            onToken(chunk)
                        }.onCompleteResponse { response ->
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
                        }.onToolExecuted { tool ->
                            log.debug("Tool executed: {}", tool)
                        }
                        .onError { e ->
                            errorOccurred = true
                            capturedError = e

                            val errorMessage = e.message ?: ""

                            // Check for context length errors first - let it bubble up for immediate retry
                            if (e?.isContextLengthError() == true) {
                                done.countDown()
                                val modelKey = ModelCapabilitiesCache.modelKey(provider, model)
                                val currentSize = ModelCapabilitiesCache.get(modelKey).contextSize
                                val newSize = ModelCapabilitiesCache.reduceContextSize(modelKey, currentSize)

                                log.warn("Context length exceeded for $modelKey (attempt $contextRetryCount/${maxContextRetries + 1}). Reducing context size: $currentSize → $newSize tokens. Retrying immediately...")
                                return@onError
                            }

                            // Check for insufficient context window - non-transient, show helpful message
                            if (e is InsufficientContextException) {
                                isConfigurationError = true
                                sb.append(e.message)
                                onToken(e.message ?: "Insufficient context window")
                                done.countDown()
                                contextRetryCount++
                                return@onError
                            }

                            // Check for unsupported sampling parameters (temperature, topP)
                            if (e.isUnsupportedSamplingError()) {
                                log.warn("Unsupported sampling parameters detected. Falling back to non sampling settings.")
                                ModelCapabilitiesCache.setSamplingSupport(provider, model, false)
                                done.countDown()
                                return@onError
                            }

                            if (e is ToolExecutionException) {
                                sb.append(e.errorDetails)
                                onToken(e.errorDetails ?: "Tool execution failed")
                                done.countDown()
                                return@onError
                            }

                            // Check if the underlying cause is a network connection issue
                            if (e.cause is UnresolvedAddressException) {
                                isConfigurationError = true
                                val connectionErrorMsg = """
                                ⚠️  Unable to connect to the server!

                                Cannot resolve the server address. Please check:
                                1. Your internet connection is working
                                2. The server URL/endpoint is correct
                                3. There are no firewall or proxy issues blocking the connection
                                """.trimIndent()
                                sb.append(connectionErrorMsg)
                                onToken(connectionErrorMsg)
                                done.countDown()
                                return@onError
                            }

                            val isModelError = errorMessage.contains("model is required") ||
                                errorMessage.contains("No model provided") ||
                                errorMessage.contains("model not found") ||
                                errorMessage.contains("invalid model")

                            val isApiKeyError = errorMessage.contains("api key") ||
                                errorMessage.contains("authentication") ||
                                errorMessage.contains("unauthorized") ||
                                errorMessage.contains("invalid API key") ||
                                errorMessage.contains("Incorrect API key provided") ||
                                errorMessage.contains("invalid_api_key") ||
                                e is dev.langchain4j.exception.AuthenticationException

                            if (isModelError || isApiKeyError) {
                                isConfigurationError = true
                                val helpMessage = when {
                                    isModelError -> """
                                    ⚠️  Model configuration required!

                                    It looks like you haven't selected a model yet. Please configure your setup:

                                    1. Set a provider: :set-provider openai
                                    2. Check available models: :models
                                    3. Select a model from the list
                                    """.trimIndent()

                                    else -> """
                                    ⚠️  API key configuration required!

                                    Your API key is missing or invalid. Please configure it:

                                    Interactive mode: :set-param api_key YOUR_API_KEY
                                    Command line: --set-param api_key YOUR_API_KEY
                                    """.trimIndent()
                                }

                                sb.append(helpMessage)
                                onToken(helpMessage)
                            } else {
                                val errorMsg = "\n[error] ${e.message ?: "unknown error"}\n"
                                sb.append(errorMsg)
                                onToken(errorMsg)
                            }

                            done.countDown()
                        }.start()

                    done.await()

                    val result = sb.toString()

                    if (isConfigurationError) {
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
                // Check if this is a context length error - immediate retry without backoff
                if ((e.isContextLengthError() || e.isUnsupportedSamplingError()) && contextRetryCount < maxContextRetries) {
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
 * Generates a structured summary of a conversation.
 *
 * This extension function analyzes conversation text and extracts:
 * - Key facts as name-value pairs
 * - Main topics discussed
 * - Recent context summary
 *
 * The AI model is instructed to respond with JSON only, which is then parsed
 * into a [SessionConversationSummary] object using structured output parsing.
 *
 * @param conversationText The conversation text to summarize
 * @return A SessionConversationSummary containing key facts, main topics, and recent context
 */
fun ChatClient.getSummary(conversationText: String): SessionConversationSummary {
    val log = logger<ChatClient>()

    val prompt = buildSummaryPrompt(conversationText)

    return try {
        val rawResponse = this.sendMessage(prompt)
        parseStructuredOutput<SessionConversationSummary>(
            rawResponse,
            arrayKeys = setOf("mainTopics"), // must be array
            stringKeys = setOf("recentContext"), // must be string
        )
    } catch (e: Exception) {
        log.error("Failed to generate conversation summary. Response was likely malformed.", e)
        SessionConversationSummary(
            keyFacts = emptyMap(),
            mainTopics = emptyList(),
            recentContext = conversationText.takeLast(500),
        )
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

        fun normalizeValue(key: String, value: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement = when {
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
