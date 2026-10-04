/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.plan

import dev.langchain4j.service.tool.DefaultToolExecutor
import dev.langchain4j.service.tool.ToolProvider
import dev.langchain4j.service.tool.ToolProviderRequest
import dev.langchain4j.service.tool.ToolProviderResult
import io.askimo.core.intent.ToolRegistry
import io.askimo.core.intent.ToolSource
import io.askimo.core.logging.logger

/**
 * A [ToolProvider] for plan steps that exposes only the tools explicitly allowed
 * for that step.
 *
 * Resolution order:
 * 1. [allowedToolNames] — the merged list of effective tools for this step
 *    (step-level overrides plan-level; plan-level is the fallback).
 * 2. [ToolRegistry] — looks up each name against registered built-in tools.
 *
 * Unknown tool names (not in the registry) are skipped with a warning so that
 * a single typo doesn't silently break the whole plan execution.
 *
 * @param allowedToolNames Tool names to expose. Empty means no tools.
 */
class PlanToolProvider(
    private val allowedToolNames: List<String>,
) : ToolProvider {

    private val log = logger<PlanToolProvider>()

    override fun provideTools(request: ToolProviderRequest?): ToolProviderResult? {
        if (request == null || allowedToolNames.isEmpty()) return null

        val builder = ToolProviderResult.builder()
        var added = 0

        for (name in allowedToolNames) {
            val tool = ToolRegistry.findByName(name)
            if (tool == null) {
                log.warn("Plan references unknown tool '{}' — skipping", name)
                continue
            }

            if (tool.source != ToolSource.ASKIMO_BUILTIN) {
                // MCP tools require a live McpClient; skip them here for now.
                log.debug("Skipping MCP tool '{}' — MCP tools are not yet supported in plan steps", name)
                continue
            }

            val className = tool.specification.metadata()["className"] as? String
            val methodName = tool.specification.metadata()["methodName"] as? String

            if (className == null || methodName == null) {
                log.warn(
                    "Built-in tool '{}' is missing className/methodName metadata — skipping",
                    name,
                )
                continue
            }

            try {
                val clazz = Class.forName(className)
                val objectInstance = clazz.kotlin.objectInstance
                    ?: error("Class '$className' is not a Kotlin object")
                val method = clazz.methods.find { it.name == methodName }
                    ?: throw NoSuchMethodException("Method '$methodName' not found in '$className'")

                builder.add(
                    tool.specification,
                    DefaultToolExecutor.builder()
                        .`object`(objectInstance)
                        .methodToInvoke(method)
                        .originalMethod(method)
                        .wrapToolArgumentsExceptions(true)
                        .propagateToolExecutionExceptions(true)
                        .build(),
                )
                added++
            } catch (e: ClassNotFoundException) {
                log.error("Class '{}' not found for tool '{}'", className, name, e)
            } catch (e: NoSuchMethodException) {
                log.error("Method '{}' not found in '{}' for tool '{}'", methodName, className, name, e)
            } catch (e: Exception) {
                log.error("Failed to register tool '{}'", name, e)
            }
        }

        return if (added > 0) builder.build() else null
    }
}
