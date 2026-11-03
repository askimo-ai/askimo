/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.mcp

/**
 * Built-in catalog of popular MCP server definitions.
 *
 * Each entry is a fully-specified [McpServerDefinition] whose `stdioConfig`/`httpConfig`
 * may contain `{{key}}` placeholders that correspond to entries in [McpServerDefinition.parameters].
 * The UI resolves those placeholders with user-supplied values before saving.
 *
 * ## Adding a new template
 * 1. Create a new [McpServerDefinition] value below.
 * 2. Add `"popular"` to [tags] if it should appear in the Popular filter.
 * 3. Add a category tag matching one of [CATEGORIES] (e.g. `"dev-tools"`, `"databases"`).
 * 4. Declare every user-configurable placeholder as a [Parameter].
 */
object McpServerTemplateRegistry {

    val CATEGORIES = listOf("All", "Popular", "Dev Tools", "Productivity", "Databases", "Search", "Travel", "Finance", "Utilities")

    val templates: List<McpServerDefinition> = listOf(

        // ── Dev Tools ─────────────────────────────────────────────────────────

        McpServerDefinition(
            id = "template-github",
            name = "GitHub",
            description = "Access repositories, issues, pull requests, and file contents via the official GitHub remote MCP server. No local tools required.",
            transportType = TransportType.HTTP,
            httpConfig = HttpConfig(
                urlTemplate = "https://api.githubcopilot.com/mcp/",
                headersTemplate = mapOf("Authorization" to "Bearer {{githubToken}}"),
            ),
            parameters = listOf(
                Parameter(
                    key = "githubToken",
                    label = "GitHub Personal Access Token",
                    type = ParameterType.SECRET,
                    description = "Create at github.com/settings/tokens. Requires repo scope.",
                    placeholder = "ghp_...",
                    location = ParameterLocation.HTTP_HEADER,
                ),
            ),
            tags = listOf("popular", "dev-tools"),
            author = "GitHub",
        ),

        McpServerDefinition(
            id = "template-github-enterprise",
            name = "GitHub Enterprise",
            description = "Access GitHub Enterprise Cloud (ghe.com) repositories and resources via the remote MCP server.",
            transportType = TransportType.HTTP,
            httpConfig = HttpConfig(
                urlTemplate = "https://copilot-api.{{gheHost}}/mcp",
                headersTemplate = mapOf("Authorization" to "Bearer {{githubToken}}"),
            ),
            parameters = listOf(
                Parameter(
                    key = "gheHost",
                    label = "GitHub Enterprise Host",
                    type = ParameterType.STRING,
                    description = "Your GHE host, e.g. octocorp.ghe.com",
                    placeholder = "octocorp.ghe.com",
                    location = ParameterLocation.HTTP_URL,
                ),
                Parameter(
                    key = "githubToken",
                    label = "GitHub Personal Access Token",
                    type = ParameterType.SECRET,
                    description = "PAT with appropriate scopes for your GitHub Enterprise organization.",
                    placeholder = "ghp_...",
                    location = ParameterLocation.HTTP_HEADER,
                ),
            ),
            tags = listOf("dev-tools"),
            author = "GitHub",
        ),

        // ── Utilities ─────────────────────────────────────────────────────────

        McpServerDefinition(
            id = "template-filesystem",
            name = "Filesystem",
            description = "Read and write files within a specific directory on your local machine.",
            transportType = TransportType.STDIO,
            stdioConfig = StdioConfig(
                commandTemplate = listOf("npx", "-y", "@modelcontextprotocol/server-filesystem", "{{rootPath}}"),
            ),
            parameters = listOf(
                Parameter(
                    key = "rootPath",
                    label = "Allowed Root Directory",
                    type = ParameterType.PATH,
                    description = "The AI will only be able to access files inside this directory.",
                    placeholder = "/Users/you/projects",
                    location = ParameterLocation.COMMAND,
                ),
            ),
            tags = listOf("popular", "utilities"),
            author = "Anthropic",
        ),

        McpServerDefinition(
            id = "template-time",
            name = "Time",
            description = "Query the current time and convert between timezones. No configuration required.",
            transportType = TransportType.STDIO,
            stdioConfig = StdioConfig(
                commandTemplate = listOf("uvx", "--system-certs", "mcp-server-time"),
            ),

            parameters = emptyList(),
            tags = listOf("popular", "utilities"),
            author = "Anthropic",
        ),

        McpServerDefinition(
            id = "template-sequential-thinking",
            name = "Sequential Thinking",
            description = "Structured scratchpad for step-by-step reasoning: break a problem into revisable " +
                "thoughts, branch alternatives, and track progress toward a solution. No configuration required.",
            transportType = TransportType.STDIO,
            stdioConfig = StdioConfig(
                commandTemplate = listOf("npx", "-y", "@modelcontextprotocol/server-sequential-thinking"),
            ),
            parameters = emptyList(),
            tags = listOf("popular", "utilities"),
            author = "Anthropic",
        ),

        McpServerDefinition(
            id = "template-memory",
            name = "Memory",
            description = "Persistent knowledge-graph scratchpad: store entities, relations, and observations " +
                "that survive across chat sessions. The model only reads/writes memory when explicitly asked " +
                "to (e.g. \"remember that...\" or \"what do you know about...\") — it is not injected " +
                "automatically into every conversation.",
            transportType = TransportType.STDIO,
            stdioConfig = StdioConfig(
                commandTemplate = listOf("npx", "-y", "@modelcontextprotocol/server-memory"),
                envTemplate = mapOf("MEMORY_FILE_PATH" to "{{memoryFilePath}}"),
            ),
            parameters = listOf(
                Parameter(
                    key = "memoryFilePath",
                    label = "Memory File Path",
                    type = ParameterType.PATH,
                    required = false,
                    defaultValue = "${System.getProperty("user.home")}/.askimo/mcp-memory/memory.json",
                    description = "Pinned to an absolute path so the same memory file is reused across every " +
                        "chat session and process restart. Without this, the server may fall back to a path " +
                        "relative to its install location, making stored memories appear to vanish.",
                    placeholder = "/Users/you/.askimo/mcp-memory/memory.json",
                    location = ParameterLocation.ENVIRONMENT,
                ),
            ),
            tags = listOf("popular", "utilities"),
            author = "Anthropic",
        ),

        McpServerDefinition(
            id = "template-fetch",
            name = "Fetch",
            description = "Fetch any URL and convert the response to Markdown for easy reading.",
            transportType = TransportType.STDIO,
            stdioConfig = StdioConfig(
                commandTemplate = listOf("uvx", "--system-certs", "mcp-server-fetch"),
            ),
            parameters = emptyList(),
            tags = listOf("popular", "utilities"),
            author = "Anthropic",
        ),

        McpServerDefinition(
            id = "template-playwright",
            name = "Playwright (Browser Automation)",
            description = "Automate and read content from web pages using a real browser, driven by Playwright. " +
                "Point it at your existing Chrome profile to reuse logged-in sessions (SSO, Confluence, etc.) " +
                "so protected pages can be navigated and extracted without re-authenticating.",
            transportType = TransportType.STDIO,
            stdioConfig = StdioConfig(
                commandTemplate = listOf("npx", "-y", "@playwright/mcp@latest"),
                envTemplate = mapOf(
                    "PLAYWRIGHT_MCP_BROWSER" to "{{browserChannel}}",
                    "PLAYWRIGHT_MCP_USER_DATA_DIR" to "{{userDataDir}}",
                    "PLAYWRIGHT_MCP_HEADLESS" to "{{headless}}",
                ),
            ),
            parameters = listOf(
                Parameter(
                    key = "browserChannel",
                    label = "Browser",
                    type = ParameterType.STRING,
                    required = false,
                    defaultValue = "chrome",
                    description = "Browser or Chrome channel to launch: chrome, msedge, firefox, or webkit.",
                    placeholder = "chrome",
                    location = ParameterLocation.ENVIRONMENT,
                ),
                Parameter(
                    key = "userDataDir",
                    label = "Chrome User Data Directory",
                    type = ParameterType.PATH,
                    required = false,
                    description = "Point this at your real Chrome profile directory to reuse its cookies/SSO " +
                        "session (e.g. so protected Confluence pages are already logged in). Leave blank to " +
                        "use a fresh, isolated profile. On macOS Chrome's default profile lives under " +
                        "'~/Library/Application Support/Google/Chrome'.",
                    placeholder = "/Users/you/Library/Application Support/Google/Chrome",
                    location = ParameterLocation.ENVIRONMENT,
                ),
                Parameter(
                    key = "headless",
                    label = "Headless",
                    type = ParameterType.BOOLEAN,
                    required = false,
                    defaultValue = "false",
                    description = "Run the browser without a visible window. Keep this off the first time so " +
                        "you can complete any interactive login prompts.",
                    location = ParameterLocation.ENVIRONMENT,
                ),
            ),
            tags = listOf("popular", "utilities"),
            author = "Microsoft",
        ),

        // ── Travel ────────────────────────────────────────────────────────────

        McpServerDefinition(
            id = "template-google-maps",
            name = "Google Maps",
            description = "Search places, geocode addresses, get directions, distances, timezones, and " +
                "elevation via the Google Maps Platform. Useful for travel planning and itineraries.",
            transportType = TransportType.STDIO,
            stdioConfig = StdioConfig(
                commandTemplate = listOf("npx", "-y", "@cablate/mcp-google-map", "--stdio"),
                envTemplate = mapOf("GOOGLE_MAPS_API_KEY" to "{{googleMapsApiKey}}"),
            ),
            parameters = listOf(
                Parameter(
                    key = "googleMapsApiKey",
                    label = "Google Maps API Key",
                    type = ParameterType.SECRET,
                    description = "Create at console.cloud.google.com. Enable Places API (New), Routes API, " +
                        "and Geocoding API for your project. Calls to the Google Maps Platform may be billable.",
                    placeholder = "AIza...",
                    location = ParameterLocation.ENVIRONMENT,
                ),
            ),
            tags = listOf("popular", "travel"),
            author = "cablate",
        ),

        // ── Finance ───────────────────────────────────────────────────────────

        McpServerDefinition(
            id = "template-yfinance",
            name = "Yahoo Finance",
            description = "Look up stock quotes, historical prices, financial statements, dividends, " +
                "earnings, and analyst data from Yahoo Finance. No API key required.",
            transportType = TransportType.STDIO,
            stdioConfig = StdioConfig(
                commandTemplate = listOf("uvx", "--system-certs", "--with", "mcp<2", "yfinance-mcp"),
            ),
            parameters = emptyList(),
            tags = listOf("popular", "finance"),
            author = "Community",
        ),

        McpServerDefinition(
            id = "template-markitdown",
            name = "MarkItDown",
            description = "Convert files and office documents (PDF, Word, Excel, PowerPoint, images, and more) to Markdown.",
            transportType = TransportType.STDIO,
            stdioConfig = StdioConfig(
                // `--with "pydantic<2.14"` works around markitdown-mcp@0.0.1a4's loose `mcp~=1.8.0`
                // pin: mcp 1.8.x reaches into a pydantic internal (`eval_type_backport`) that was
                // removed in pydantic 2.14, which `uv` otherwise resolves to by default.
                commandTemplate = listOf(
                    "uvx",
                    "--system-certs",
                    "--with",
                    "pydantic<2.14",
                    "markitdown-mcp@0.0.1a4",
                ),
                envTemplate = mapOf("UV_PYTHON" to "{{uvPythonVersion}}"),
            ),
            // mcp~=1.8.0 (used by markitdown-mcp@0.0.1a4) predates langchain4j's newer
            // protocol-detection probe and crashes the server process when it receives that
            // unrecognized request. Pinning the protocol version explicitly skips detection.
            protocolVersion = "2025-11-25",
            parameters = listOf(
                Parameter(
                    key = "uvPythonVersion",
                    label = "Python Version (for uv)",
                    type = ParameterType.STRING,
                    required = false,
                    defaultValue = "3.12",
                    description = "Pins the Python interpreter uv uses to run markitdown-mcp, independent of " +
                        "your system's default Python. markitdown's dependencies (onnxruntime/magika) may lag " +
                        "behind the newest Python releases, so a slightly older pinned version avoids " +
                        "'no wheels available' errors. uv downloads this version automatically if needed. " +
                        "Leave blank to let uv pick automatically.",
                    placeholder = "3.12",
                    location = ParameterLocation.ENVIRONMENT,
                ),
            ),
            tags = listOf("utilities"),
            author = "Microsoft",
        ),
    )

    /** Returns templates matching the given category label (case-insensitive). "All" returns everything. */
    fun getByCategory(category: String): List<McpServerDefinition> = when (category.lowercase()) {
        "all" -> templates
        "popular" -> templates.filter { "popular" in it.tags }
        else -> templates.filter { category.lowercase().replace(" ", "-") in it.tags }
    }

    /**
     * Resolves all `{{key}}` placeholders in a template's command and env templates
     * with the supplied [paramValues], producing a ready-to-use [McpServerDefinition].
     */
    fun resolve(template: McpServerDefinition, instanceId: String, instanceName: String, paramValues: Map<String, String>): McpServerDefinition {
        fun String.resolve() = paramValues.entries.fold(this) { acc, (k, v) -> acc.replace("{{$k}}", v) }

        val resolvedStdio = template.stdioConfig?.let { cfg ->
            StdioConfig(
                commandTemplate = cfg.commandTemplate.map { it.resolve() }.filter { it.isNotBlank() },
                envTemplate = cfg.envTemplate.mapValues { (_, v) -> v.resolve() }.filterValues { it.isNotBlank() },
                workingDirectory = cfg.workingDirectory?.resolve(),
            )
        }
        val resolvedHttp = template.httpConfig?.let { cfg ->
            HttpConfig(
                urlTemplate = cfg.urlTemplate.resolve(),
                headersTemplate = cfg.headersTemplate.mapValues { (_, v) -> v.resolve() },
                timeoutMs = cfg.timeoutMs,
            )
        }
        return template.copy(
            id = instanceId,
            name = instanceName,
            stdioConfig = resolvedStdio,
            httpConfig = resolvedHttp,
            parameters = emptyList(), // resolved — no longer needed
            tags = template.tags + "global",
        )
    }
}
