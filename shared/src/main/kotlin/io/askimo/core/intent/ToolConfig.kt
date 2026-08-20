/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.intent

import dev.langchain4j.agent.tool.ToolSpecification

/**
 * Configuration for a tool that can be used in follow-up actions.
 */
data class ToolConfig(
    val specification: ToolSpecification,
    val category: ToolCategory,
    val strategy: Int,
    val source: ToolSource,
    /** The MCP instance ID this tool belongs to. Null for built-in Askimo tools. */
    val serverId: String? = null,
    /**
     * Resolved approval policy for this tool.
     * [ToolApprovalPolicy.DEFAULT] means the runtime falls back to [ToolCategory.defaultApprovalPolicy].
     */
    val approvalPolicy: ToolApprovalPolicy = ToolApprovalPolicy.DEFAULT,
)

/**
 * Per-tool execution approval policy configured by the user.
 */
enum class ToolApprovalPolicy {
    /**
     * Derive behaviour from the tool's [ToolCategory]:
     * mutating categories require approval automatically; read-only categories run freely.
     */
    DEFAULT,

    /** Always pause and ask the user before the AI executes this tool. */
    REQUIRE_APPROVAL,
}

/**
 * Returns the approval policy implied by this category when the user has not set an explicit override.
 * Mutating / side-effectful categories default to [ToolApprovalPolicy.REQUIRE_APPROVAL];
 * safe read-only categories default to [ToolApprovalPolicy.DEFAULT] (auto-run).
 */
fun ToolCategory.defaultApprovalPolicy(): ToolApprovalPolicy = when (this) {
    ToolCategory.FILE_WRITE,
    ToolCategory.EXECUTE,
    -> ToolApprovalPolicy.REQUIRE_APPROVAL

    else -> ToolApprovalPolicy.DEFAULT
}

/**
 * Tool execution strategy using bitwise flags.
 * Tools can be available in one or both stages using OR operator.
 *
 * Examples:
 * - INTENT_BASED: User asks "search files" → tool attached (Stage 1 only)
 * - FOLLOW_UP_BASED: AI shows data → suggest "create chart?" (Stage 2 only)
 * - BOTH: User asks "chart this" OR AI suggests after data (Stage 1 AND 2)
 */
object ToolStrategy {
    /**
     * Stage 1: Tool attached when user intent detected.
     * Use: Safe read operations (file read, search)
     */
    const val INTENT_BASED = 1 shl 0 // 0b01 = 1

    /**
     * Stage 2: Tool suggested after AI response analysis.
     * Use: Operations requiring confirmation (writes, expensive ops)
     */
    const val FOLLOW_UP_BASED = 1 shl 1 // 0b10 = 2

    /**
     * Both stages: Available for intent detection AND follow-up.
     * Use: Tools users can explicitly request OR AI can suggest.
     * Example: Chart tools (user asks "chart this" OR AI suggests)
     */
    const val BOTH = INTENT_BASED or FOLLOW_UP_BASED // 0b11 = 3
}

/**
 * Source of the tool definition.
 */
enum class ToolSource {
    /**
     * Built-in Askimo tools - we classify these with appropriate strategies.
     */
    ASKIMO_BUILTIN,

    /**
     * External MCP (Model Context Protocol) tools - user must classify.
     * Defaults to FOLLOW_UP_ONLY for safety in v1.
     */
    MCP_EXTERNAL,
}

/**
 * Categories of tool functionality based on what the tool does.
 * Used for organizing and matching tools to user intent.
 */
enum class ToolCategory {
    /**
     * Database operations: queries, connections, schema management
     * Examples: query_database, connect_postgres, list_tables
     */
    DATABASE,

    /**
     * Network/API operations: HTTP requests, webhooks, API calls
     * Examples: http_request, call_api, webhook_trigger
     */
    NETWORK,

    /**
     * File system read operations
     * Examples: read_file, list_files, get_file_content
     */
    FILE_READ,

    /**
     * File system write operations
     * Examples: write_file, delete_file, create_directory
     */
    FILE_WRITE,

    /**
     * Data visualization and charting
     * Examples: create_chart, generate_graph, plot_data
     */
    VISUALIZE,

    /**
     * Code execution and running commands
     * Examples: run_command, execute_script, compile_code
     */
    EXECUTE,

    /**
     * Search and retrieval operations
     * Examples: search_github, find_files, query_docs
     */
    SEARCH,

    /**
     * Data transformation and processing
     * Examples: convert_format, transform_data, parse_json
     */
    TRANSFORM,

    /**
     * Version control operations
     * Examples: git_commit, create_branch, merge_pr
     */
    VERSION_CONTROL,

    /**
     * Communication and messaging
     * Examples: send_email, post_slack, notify_user
     */
    COMMUNICATION,

    /**
     * Monitoring and logging
     * Examples: log_event, track_metric, alert_on_error
     */
    MONITORING,

    /**
     * Weather data retrieval: current conditions, forecasts
     * Examples: get_weather, weather_forecast, check_temperature
     */
    WEATHER,

    /**
     * Web search and page reading: live internet searches, page content extraction
     * Examples: search_web, read_web_page, browse_url
     */
    WEB_SEARCH,

    /**
     * Date and time operations: current date/time, timezone conversion, date arithmetic
     * Examples: get_current_datetime, convert_timezone, days_until
     */
    DATETIME,

    /**
     * Other/unclassified tools that don't fit into above categories
     */
    OTHER,
}
