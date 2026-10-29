/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import dev.langchain4j.agent.tool.ToolSpecifications
import dev.langchain4j.service.tool.DefaultToolExecutor
import dev.langchain4j.service.tool.ToolProvider
import dev.langchain4j.service.tool.ToolProviderResult
import io.askimo.tools.datetime.DateTimeTools
import io.askimo.tools.fs.LocalFsTools
import java.lang.reflect.Method

/**
 * Factory for creating ToolProvider instances for testing purposes.
 */
object TestToolProviderFactory {

    /**
     * Creates a ToolProvider for the LocalFsTools.countEntries method.
     *
     * Schema shape: one required `String` plus two *optional boxed-`Boolean`* parameters
     * (defaulted). Exercises how each provider's JSON-schema generator renders optional/
     * nullable primitives — a common source of cross-provider (and cross-model-tier)
     * divergence, since some models reject `"type": ["boolean", "null"]`-style unions while
     * others require every property to be `required` regardless of a Kotlin default value.
     */
    fun createCountEntriesToolProvider(): ToolProvider {
        val toolMethod = LocalFsTools::class.java.getMethod(
            "countEntries",
            String::class.java,
            Boolean::class.javaObjectType,
            Boolean::class.javaObjectType,
        )

        return ToolProvider {
            ToolProviderResult.builder().add(
                ToolSpecifications.toolSpecificationFrom(toolMethod),
                DefaultToolExecutor.builder()
                    .`object`(LocalFsTools)
                    .methodToInvoke(toolMethod)
                    .originalMethod(toolMethod)
                    .wrapToolArgumentsExceptions(true)
                    .propagateToolExecutionExceptions(true)
                    .build(),
            ).build()
        }
    }

    /**
     * Creates a ToolProvider for [DateTimeTools.getCurrentDateTime].
     *
     * Schema shape: a *single* `String` parameter with a default value (`timezone = ""`),
     * callable with effectively no meaningful arguments. Exercises the "near-zero-arg tool"
     * case — some providers/models mishandle a tool whose only parameter is almost always
     * omitted or sent as an empty string.
     */
    fun createDateTimeToolProvider(): ToolProvider = singleMethodToolProvider(
        DateTimeTools,
        "getCurrentDateTime",
        String::class.java,
    )

    /**
     * Creates a ToolProvider for [DateTimeTools.calculateDateDiff].
     *
     * Schema shape: two *required* `String` parameters, no optional/defaulted arguments.
     * The simplest possible multi-parameter schema — useful as a baseline when diagnosing
     * whether a model-tier regression is specific to optional/nullable parameter handling
     * (see [createCountEntriesToolProvider]) or affects even the plain required-params case.
     */
    fun createCalculateDateDiffToolProvider(): ToolProvider = singleMethodToolProvider(
        DateTimeTools,
        "calculateDateDiff",
        String::class.java,
        String::class.java,
    )

    /**
     * Creates a ToolProvider exposing **three** tools with distinct schema shapes at once:
     * [LocalFsTools.countEntries] (optional booleans), [DateTimeTools.getCurrentDateTime]
     * (single defaulted string), and [DateTimeTools.calculateDateDiff] (two required strings).
     *
     * Use this (instead of a single-tool provider) when the scenario under test cares about
     * *cross-tool* schema interactions in one request — e.g. whether a model correctly
     * disambiguates between multiple candidate tools, or whether it issues a parallel
     * tool-call batch mixing more than one schema shape in a single turn.
     */
    fun createMultiToolProvider(): ToolProvider {
        val countEntries = LocalFsTools::class.java.getMethod(
            "countEntries",
            String::class.java,
            Boolean::class.javaObjectType,
            Boolean::class.javaObjectType,
        )
        val getCurrentDateTime = DateTimeTools::class.java.getMethod("getCurrentDateTime", String::class.java)
        val calculateDateDiff = DateTimeTools::class.java.getMethod("calculateDateDiff", String::class.java, String::class.java)

        return ToolProvider {
            val builder = ToolProviderResult.builder()
            listOf(
                LocalFsTools to countEntries,
                DateTimeTools to getCurrentDateTime,
                DateTimeTools to calculateDateDiff,
            ).forEach { (owner, method) ->
                builder.add(
                    ToolSpecifications.toolSpecificationFrom(method),
                    DefaultToolExecutor.builder()
                        .`object`(owner)
                        .methodToInvoke(method)
                        .originalMethod(method)
                        .wrapToolArgumentsExceptions(true)
                        .propagateToolExecutionExceptions(true)
                        .build(),
                )
            }
            builder.build()
        }
    }

    /** Shared helper behind [createDateTimeToolProvider] / [createCalculateDateDiffToolProvider]. */
    private fun singleMethodToolProvider(
        toolObject: Any,
        methodName: String,
        vararg parameterTypes: Class<*>,
    ): ToolProvider {
        val toolMethod: Method = toolObject::class.java.getMethod(methodName, *parameterTypes)

        return ToolProvider {
            ToolProviderResult.builder().add(
                ToolSpecifications.toolSpecificationFrom(toolMethod),
                DefaultToolExecutor.builder()
                    .`object`(toolObject)
                    .methodToInvoke(toolMethod)
                    .originalMethod(toolMethod)
                    .wrapToolArgumentsExceptions(true)
                    .propagateToolExecutionExceptions(true)
                    .build(),
            ).build()
        }
    }
}
