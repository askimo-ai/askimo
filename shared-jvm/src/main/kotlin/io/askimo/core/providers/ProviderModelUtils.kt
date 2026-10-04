/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.ToolExecutionResultMessage
import io.askimo.core.logging.logger
import io.askimo.core.util.appJson
import io.askimo.core.util.httpGet
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpClient

object ProviderModelUtils {
    private val log = logger<ProviderModelUtils>()

    fun hallucinatedToolHandler(request: ToolExecutionRequest): ToolExecutionResultMessage {
        val toolName = request.name()
        log.warn("LLM hallucinated tool: '$toolName'")

        return ToolExecutionResultMessage.from(
            request,
            """
            Error: Tool '$toolName' does not exist.

            Please use only the tools that have been explicitly provided to you.
            Do not invent or assume the existence of tools.
            """.trimIndent(),
        )
    }

    fun fetchModels(
        apiKey: String,
        url: String,
        providerName: ModelProvider,
        httpVersion: HttpClient.Version = HttpClient.Version.HTTP_2,
    ): List<String> {
        val (_, body) = httpGet(url, headers = mapOf("Authorization" to "Bearer $apiKey"), httpVersion = httpVersion)
        val jsonElement = appJson.parseToJsonElement(body)
        val data = jsonElement.jsonObject["data"]?.jsonArray.orEmpty()
        return data
            .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
            .distinct()
            .sorted()
    }
}
