/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.tools

import dev.langchain4j.agent.tool.ReturnBehavior
import dev.langchain4j.mcp.McpToolExecutor
import dev.langchain4j.service.tool.DefaultToolExecutor
import dev.langchain4j.service.tool.ToolProvider
import dev.langchain4j.service.tool.ToolProviderRequest
import dev.langchain4j.service.tool.ToolProviderResult
import io.askimo.core.context.ChatContext
import io.askimo.core.intent.ToolConfig
import io.askimo.core.intent.ToolSource
import io.askimo.core.logging.logger
import io.askimo.core.mcp.McpInstanceService
import kotlinx.coroutines.runBlocking

/**
 * Implementation of ToolProvider that dynamically provides tools based on context.
 * Uses ThreadLocal to access the current project ID and retrieve MCP tools.
 *
 * Tool detection runs two layers in sequence:
 *  1. Keyword classifier  (IntentDetectionChain)      — fast & deterministic
 *  2. Vector similarity   (ToolVectorIndex / JVector)  — semantic, catches misses
 */
class ToolProviderImpl(
    private val mcpInstanceService: McpInstanceService,
) : ToolProvider {

    private val log = logger<ToolProviderImpl>()

    override fun provideTools(request: ToolProviderRequest?): ToolProviderResult? {
        if (request == null) return null

        log.debug("Providing tools for request: {}", request)

        // Global tools — always available in every chat
        val mcpTools: List<ToolConfig> = runBlocking { mcpInstanceService.getGlobalTools() }
            .getOrElse { e ->
                log.warn("Failed to load global MCP tools: {}", e.message)
                emptyList()
            }

        val enabledServers = ChatContext.getEnabledServers()

        val builder = ToolProviderResult.builder()

        mcpTools
            .filter { tool -> tool.serverId in enabledServers }
            .forEach { tool ->
                if (tool.source == ToolSource.ASKIMO_BUILTIN) {
                    val className = tool.specification.metadata()["className"]
                    val methodName = tool.specification.metadata()["methodName"]
                    if (className != null && methodName != null) {
                        try {
                            val clazz = Class.forName(className as String)
                            val kotlinClass = clazz.kotlin
                            val objectInstance = kotlinClass.objectInstance
                                ?: error("Class '$className' is not a Kotlin object")
                            val toolMethod = clazz.methods.find { it.name == methodName }
                                ?: throw NoSuchMethodException("Method '$methodName' not found in class '$className'")

                            builder.add(
                                tool.specification,
                                DefaultToolExecutor.builder()
                                    .`object`(objectInstance)
                                    .methodToInvoke(toolMethod)
                                    .originalMethod(toolMethod)
                                    .wrapToolArgumentsExceptions(true)
                                    .propagateToolExecutionExceptions(true)
                                    .build(),
                            )
                        } catch (e: ClassNotFoundException) {
                            log.error("Class '{}' not found", className, e)
                        } catch (e: NoSuchMethodException) {
                            log.error("Method '{}' not found in class '{}'", methodName, className, e)
                        } catch (e: Exception) {
                            log.error("Error creating tool provider for {}.{}", className, methodName, e)
                        }
                    } else {
                        log.warn(
                            "Missing className or methodName metadata for askimo tool: {}. " +
                                "Please check the tool again since all askimo must have both these attributes",
                            tool.specification.name(),
                        )
                    }
                } else {
                    val mcpClient = mcpInstanceService.getMcpClientForTool(tool.specification.name())
                    if (mcpClient != null) {
                        builder.add(tool.specification, McpToolExecutor(mcpClient), ReturnBehavior.TO_LLM)
                    } else {
                        log.error(
                            "Could not find MCP client for tool '{}'",
                            tool.specification.name(),
                        )
                    }
                }
            }

        return builder.build()
    }
}
