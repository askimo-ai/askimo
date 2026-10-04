/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.intent

import dev.langchain4j.agent.tool.ToolSpecifications
import io.askimo.core.logging.logger
import io.askimo.tools.chart.ChartTools
import io.askimo.tools.datetime.DateTimeTools
import io.askimo.tools.weather.WeatherTools
import io.askimo.tools.web.WebSearchTools
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for managing tool configurations and their strategies.
 *
 * Askimo built-in tools are pre-configured with appropriate strategies.
 * MCP external tools default to FOLLOW_UP_ONLY for safety (v1).
 */
object ToolRegistry {
    /** Server ID used for all Askimo built-in tools. */
    const val BUILTIN_SERVER_ID = "builtin"

    private val tools = ConcurrentHashMap<String, ToolConfig>()
    private val log = logger<ToolRegistry>()

    init {
        // Askimo built-in tools with pre-classified strategies
        val chartTools = ToolSpecifications.toolSpecificationsFrom(ChartTools)

        // Register each chart tool
        chartTools.forEach { toolSpec ->
            register(
                ToolConfig(
                    specification = toolSpec,
                    category = ToolCategory.VISUALIZE,
                    strategy = ToolStrategy.BOTH,
                    source = ToolSource.ASKIMO_BUILTIN,
                    serverId = BUILTIN_SERVER_ID,
                ),
            )
        }

        // Register weather tools — intent-based (user asks) + follow-up (AI suggests after a trip plan)
        val weatherTools = ToolSpecifications.toolSpecificationsFrom(WeatherTools)
        weatherTools.forEach { toolSpec ->
            register(
                ToolConfig(
                    specification = toolSpec,
                    category = ToolCategory.WEATHER,
                    strategy = ToolStrategy.BOTH,
                    source = ToolSource.ASKIMO_BUILTIN,
                    serverId = BUILTIN_SERVER_ID,
                ),
            )
        }

        // Register web search tools — intent-based (user asks) + follow-up (AI uses when researching)
        val webSearchTools = ToolSpecifications.toolSpecificationsFrom(WebSearchTools)
        webSearchTools.forEach { toolSpec ->
            register(
                ToolConfig(
                    specification = toolSpec,
                    category = ToolCategory.WEB_SEARCH,
                    strategy = ToolStrategy.BOTH,
                    source = ToolSource.ASKIMO_BUILTIN,
                    serverId = BUILTIN_SERVER_ID,
                ),
            )
        }

        // Register date/time tools — intent-based (user asks about current date/time, conversions)
        val dateTimeTools = ToolSpecifications.toolSpecificationsFrom(DateTimeTools)
        dateTimeTools.forEach { toolSpec ->
            register(
                ToolConfig(
                    specification = toolSpec,
                    category = ToolCategory.DATETIME,
                    strategy = ToolStrategy.INTENT_BASED,
                    source = ToolSource.ASKIMO_BUILTIN,
                    serverId = BUILTIN_SERVER_ID,
                ),
            )
        }

        log.debug("Initialized tool registry with ${tools.size} built-in tools")
    }

    /**
     * Register a tool configuration.
     */
    private fun register(config: ToolConfig) {
        tools[config.specification.name()] = config
        log.debug("Registered tool: ${config.specification.name()} [${config.strategy}]")
    }

    /**
     * Get all tools with INTENT_BASED flag.
     * These tools are attached to requests when user intent is detected.
     */
    fun getIntentBased(): List<ToolConfig> = tools.values.filter { (it.strategy and ToolStrategy.INTENT_BASED) != 0 }

    /**
     * Get all tools with FOLLOW_UP_BASED flag.
     * These tools require user confirmation before use.
     */
    fun getFollowUpOnly(): List<ToolConfig> = tools.values.filter { (it.strategy and ToolStrategy.FOLLOW_UP_BASED) != 0 }

    /**
     * Look up a single tool by its [ToolSpecification] name.
     * Returns `null` if no tool with that name has been registered.
     */
    fun findByName(name: String): ToolConfig? = tools[name]
}
