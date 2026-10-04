/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.tools

import io.askimo.core.util.JsonUtils.json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Utility for creating standardized tool responses.
 * All tools should use this to ensure consistent response format for AI parsing.
 */
object ToolResponseBuilder {

    /**
     * Create a success response with output.
     *
     * @param output The result message or data description
     * @param metadata Optional additional data (e.g., actual content, counts, paths)
     * @return JSON string with standardized format
     */
    fun success(
        output: Any?,
        metadata: Map<String, Any>? = null,
    ): String {
        val jsonObject = buildJsonObject {
            put("success", true)
            put("output", output?.toString() ?: "")
            put("error", JsonNull)
            if (metadata != null) {
                putJsonObject("metadata") {
                    metadata.forEach { (key, value) ->
                        putValue(key, value)
                    }
                }
            } else {
                put("metadata", JsonNull)
            }
        }
        return json.encodeToString(JsonObject.serializer(), jsonObject)
    }

    /**
     * Create a failure response with error message.
     *
     * @param error Description of what went wrong
     * @param metadata Optional context about the failure
     * @return JSON string with standardized format
     */
    fun failure(
        error: String,
        metadata: Map<String, Any>? = null,
    ): String {
        val jsonObject = buildJsonObject {
            put("success", false)
            put("output", JsonNull)
            put("error", error)
            if (metadata != null) {
                putJsonObject("metadata") {
                    metadata.forEach { (key, value) ->
                        putValue(key, value)
                    }
                }
            } else {
                put("metadata", JsonNull)
            }
        }
        return json.encodeToString(JsonObject.serializer(), jsonObject)
    }

    /**
     * Create a success response with both output message and structured data.
     *
     * @param output Human-readable result message
     * @param data Structured data for AI to parse
     * @return JSON string with standardized format
     */
    fun successWithData(
        output: String,
        data: Map<String, Any>,
    ): String {
        val jsonObject = buildJsonObject {
            put("success", true)
            put("output", output)
            put("error", JsonNull)
            putJsonObject("metadata") {
                data.forEach { (key, value) ->
                    putValue(key, value)
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), jsonObject)
    }

    /**
     * Helper to create metadata map from vararg pairs, ensuring proper typing.
     * All values are converted to Any to avoid type inference issues.
     */
    fun metadata(vararg pairs: Pair<String, Any?>): Map<String, Any> = pairs.associate { (key, value) ->
        key to (value ?: "")
    }

    /**
     * Helper to put a value of any type into a JsonObjectBuilder
     */
    private fun kotlinx.serialization.json.JsonObjectBuilder.putValue(key: String, value: Any?) {
        when (value) {
            null -> put(key, JsonNull)

            is String -> put(key, value)

            is Number -> put(key, value)

            is Boolean -> put(key, value)

            is List<*> -> putJsonArray(key) {
                value.forEach { item ->
                    when (item) {
                        null -> add(JsonNull)

                        is String -> add(JsonPrimitive(item))

                        is Number -> add(JsonPrimitive(item))

                        is Boolean -> add(JsonPrimitive(item))

                        is Map<*, *> -> {
                            val jsonObj = buildJsonObject {
                                @Suppress("UNCHECKED_CAST")
                                (item as Map<String, Any?>).forEach { (k, v) ->
                                    putValue(k, v)
                                }
                            }
                            add(jsonObj)
                        }

                        else -> add(JsonPrimitive(item.toString()))
                    }
                }
            }

            is Map<*, *> -> putJsonObject(key) {
                @Suppress("UNCHECKED_CAST")
                (value as Map<String, Any?>).forEach { (k, v) ->
                    putValue(k, v)
                }
            }

            is Set<*> -> putJsonArray(key) {
                value.forEach { item ->
                    when (item) {
                        null -> add(JsonNull)
                        is String -> add(JsonPrimitive(item))
                        is Number -> add(JsonPrimitive(item))
                        is Boolean -> add(JsonPrimitive(item))
                        else -> add(JsonPrimitive(item.toString()))
                    }
                }
            }

            else -> put(key, value.toString())
        }
    }
}
