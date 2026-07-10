/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.config

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.context.AppContextParams
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.LanguageDirectiveChangedEvent
import io.askimo.core.logging.displayError
import io.askimo.core.logging.logger
import io.askimo.core.providers.ModelProvider
import io.askimo.core.security.SecureKeyManager
import io.askimo.core.security.SecureKeyManager.StorageMethod
import io.askimo.core.security.SecureSessionManager
import io.askimo.core.util.AskimoHome
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

private object AppConfigObject
private val log = logger<AppConfigObject>()

// TODO: Remove @JsonAlias camelCase aliases in v1.2.30 — kept for backward compatibility with pre-snake_case config files
data class EmbeddingConfig(
    @field:JsonAlias("maxCharsPerChunk") val maxCharsPerChunk: Int = 3000,
    @field:JsonAlias("chunkOverlap") val chunkOverlap: Int = 100,
)

// TODO: Remove @JsonAlias camelCase aliases in v1.2.30 — kept for backward compatibility with pre-snake_case config files
data class RetryConfig(
    val attempts: Int = 4,
    @field:JsonAlias("baseDelayMs") val baseDelayMs: Long = 150,
)

// TODO: Remove @JsonAlias camelCase aliases in v1.2.30 — kept for backward compatibility with pre-snake_case config files
data class ThrottleConfig(
    @field:JsonAlias("perRequestSleepMs") val perRequestSleepMs: Long = 30,
)

data class ProjectType(
    val name: String,
    val markers: Set<String>,
    val excludePaths: Set<String>,
)

data class FilterConfig(
    val gitignore: Boolean = true,
    val dockerignore: Boolean = false,
    val projecttype: Boolean = true,
    val binary: Boolean = true,
    val filesize: Boolean = true,
    val custom: Boolean = true,
)

/**
 * Splits a comma-separated YAML string or YAML sequence into a list of tokens.
 * Handles both `"java,kt,py"` and proper YAML sequences.
 */
private fun parseCommaSeparated(p: JsonParser): List<String> = when (p.currentToken) {
    JsonToken.VALUE_STRING -> {
        val raw = p.text.trim()
        if (raw.isEmpty()) {
            emptyList()
        } else {
            raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    JsonToken.START_ARRAY -> {
        val items = mutableListOf<String>()
        while (p.nextToken() != JsonToken.END_ARRAY) items.add(p.text.trim())
        items
    }

    else -> emptyList()
}

private class CommaSeparatedSetDeserializer : StdDeserializer<Set<String>>(Set::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Set<String> = parseCommaSeparated(p).toSet()
}

// TODO: Remove @JsonAlias camelCase aliases in v1.2.30 — kept for backward compatibility with pre-snake_case config files
data class IndexingConfig(
    @field:JsonAlias("maxFileBytes") val maxFileBytes: Long = 5_000_000,
    @field:JsonAlias("concurrentIndexingThreads") val concurrentIndexingThreads: Int = 3,
    @field:JsonAlias("embeddingBatchSize") val embeddingBatchSize: Int = 50,
    val filters: FilterConfig = FilterConfig(),
    val customExcludes: Set<String> = emptySet(),
    @field:JsonDeserialize(using = CommaSeparatedSetDeserializer::class)
    @field:JsonAlias("supportedExtensions")
    val supportedExtensions: Set<String> = setOf(),
    @field:JsonDeserialize(using = CommaSeparatedSetDeserializer::class)
    @field:JsonAlias("binaryExtensions")
    val binaryExtensions: Set<String> = setOf(),
    @field:JsonDeserialize(using = CommaSeparatedSetDeserializer::class)
    @field:JsonAlias("excludeFileNames")
    val excludeFileNames: Set<String> = setOf(),
    val projectTypes: List<ProjectType> = listOf(
        ProjectType(
            name = "Gradle",
            markers = setOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradlew"),
            excludePaths = setOf("build/", ".gradle/", "out/", "bin/", ".kotlintest/", ".kotlin/"),
        ),
        ProjectType(
            name = "Maven",
            markers = setOf("pom.xml", "mvnw"),
            excludePaths = setOf("target/", ".mvn/", "out/", "bin/"),
        ),
        ProjectType(
            name = "Node.js",
            markers = setOf("package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml"),
            excludePaths = setOf(
                "node_modules/",
                "dist/",
                "build/",
                ".next/",
                ".nuxt/",
                "out/",
                "coverage/",
                ".cache/",
                ".parcel-cache/",
                ".turbo/",
                ".vite/",
            ),
        ),
        ProjectType(
            name = "Python",
            markers = setOf("requirements.txt", "setup.py", "pyproject.toml", "Pipfile", "poetry.lock"),
            excludePaths = setOf(
                "__pycache__/",
                "*.pyc",
                "*.pyo",
                "*.pyd",
                ".pytest_cache/",
                ".mypy_cache/",
                ".tox/",
                "venv/",
                "env/",
                ".venv/",
                ".env/",
                "dist/",
                "build/",
                "*.egg-info/",
                ".eggs/",
            ),
        ),
        ProjectType(
            name = "Go",
            markers = setOf("go.mod", "go.sum"),
            excludePaths = setOf("vendor/", "bin/", "pkg/"),
        ),
        ProjectType(
            name = "Rust",
            markers = setOf("Cargo.toml", "Cargo.lock"),
            excludePaths = setOf("target/", "Cargo.lock"),
        ),
        ProjectType(
            name = "Ruby",
            markers = setOf("Gemfile", "Gemfile.lock", "Rakefile"),
            excludePaths = setOf("vendor/", ".bundle/", "tmp/", "log/"),
        ),
        ProjectType(
            name = "PHP/Composer",
            markers = setOf("composer.json", "composer.lock"),
            excludePaths = setOf("vendor/", "var/cache/", "var/log/"),
        ),
        ProjectType(
            name = ".NET",
            markers = setOf("*.csproj", "*.sln", "*.fsproj", "*.vbproj"),
            excludePaths = setOf("bin/", "obj/", "packages/", ".vs/", "Debug/", "Release/"),
        ),
    ),
    @field:JsonDeserialize(using = CommaSeparatedSetDeserializer::class)
    val commonExcludes: Set<String> = setOf(
        ".git/", ".svn/", ".hg/", ".idea/", ".vscode/", ".DS_Store",
        "*.log", "*.tmp", "*.temp", "*.swp", "*.bak", ".history/",
    ),
)

data class DeveloperConfig(
    val enabled: Boolean = true,
    val active: Boolean = false,
)

enum class ProxyType {
    NONE,
    HTTP,
    HTTPS,
    SOCKS5,
    SYSTEM,
}

data class ProxyConfig(
    val type: ProxyType = ProxyType.NONE,
    val host: String = "",
    val port: Int = 8080,
    val username: String = "",
    val password: String = "",
) {
    companion object {
        private const val KEYCHAIN_PASSWORD_PLACEHOLDER = "***keychain***"

        private fun getStorageKey(proxyType: ProxyType): String = "proxy.${proxyType.name.lowercase()}.password"

        fun isActualPassword(password: String): Boolean = password.isNotBlank() && password != KEYCHAIN_PASSWORD_PLACEHOLDER

        fun getSecurePassword(proxyType: ProxyType): String? = SecureKeyManager.retrieveSecretKey(getStorageKey(proxyType))

        fun setSecurePassword(proxyType: ProxyType, password: String): SecureKeyManager.StorageResult = if (password.isEmpty()) {
            // Remove password if empty
            SecureKeyManager.removeSecretKey(getStorageKey(proxyType))
            SecureKeyManager.StorageResult(
                success = true,
                method = StorageMethod.KEYCHAIN,
            )
        } else {
            SecureKeyManager.storeSecuredKey(getStorageKey(proxyType), password)
        }

        /**
         * Get the placeholder to use in YAML for securely stored password.
         */
        fun getPasswordPlaceholder(): String = KEYCHAIN_PASSWORD_PLACEHOLDER
    }
}

// TODO: Remove @field:JsonAlias camelCase aliases in v1.2.30 - kept for backward compatibility with pre-snake_case config files
data class ChatConfig(
    @field:JsonAlias("maxTokens") val maxTokens: Int = 8000,
    @field:JsonAlias("summarizationThreshold") val summarizationThreshold: Double = 0.75,
    @field:JsonAlias("enableAsyncSummarization") val enableAsyncSummarization: Boolean = true,
    @field:JsonAlias("summarizationTimeoutSeconds") val summarizationTimeoutSeconds: Long = 300,
    @field:JsonAlias("defaultResponseAILocale") val defaultResponseAILocale: String? = null,
)

/**
 * RAG (Retrieval-Augmented Generation) configuration.
 * Controls how relevant documents are retrieved from the knowledge base.
 */
// TODO: Remove @field:JsonAlias camelCase aliases in v1.2.30 - kept for backward compatibility with pre-snake_case config files
data class RagConfig(
    /** Maximum number of documents to retrieve from vector search */
    @field:JsonAlias("vectorSearchMaxResults") val vectorSearchMaxResults: Int = 20,
    /** Minimum similarity score for vector search results (0.0 to 1.0) */
    @field:JsonAlias("vectorSearchMinScore") val vectorSearchMinScore: Double = 0.3,
    /** Maximum number of final documents to return after hybrid fusion */
    @field:JsonAlias("hybridMaxResults") val hybridMaxResults: Int = 15,
    /** RRF constant for rank fusion algorithm (standard value is 60) */
    @field:JsonAlias("rankFusionConstant") val rankFusionConstant: Int = 60,
    /** Use absolute file paths in citations (true) or relative filenames (false) */
    @field:JsonAlias("useAbsolutePathInCitations") val useAbsolutePathInCitations: Boolean = true,
)

// TODO: Remove @field:JsonAlias camelCase aliases in v1.2.30 - kept for backward compatibility with pre-snake_case config files
data class ProviderModelConfig(
    @field:JsonAlias("defaultModel") val defaultModel: String = "",
    @field:JsonAlias("utilityModel") val utilityModel: String = "",
    @field:JsonAlias("embeddingModel") val embeddingModel: String = "",
    @field:JsonAlias("visionModel") val visionModel: String = "",
    @field:JsonAlias("imageModel") val imageModel: String = "",
)

/**
 * Global AI model timeouts shared across all providers.
 *
 * - [utilityModelTimeoutSeconds]: Applied to the secondary/utility model used for short-lived
 *   structured tasks (title generation, RAG query compression, summarization). Keep tight.
 * - [defaultModelTimeoutSeconds]: Applied to the primary/streaming model. Set generously to
 *   accommodate slow local models and cloud reasoning models with extended thinking.
 */
data class ModelTimeoutsConfig(
    @field:JsonAlias("utilityModelTimeoutSeconds") val utilityModelTimeoutSeconds: Long = 600,
    @field:JsonAlias("defaultModelTimeoutSeconds") val defaultModelTimeoutSeconds: Long = 600,
)

data class ModelsConfig(
    val timeouts: ModelTimeoutsConfig = ModelTimeoutsConfig(),
    val anthropic: ProviderModelConfig = ProviderModelConfig(),
    val gemini: ProviderModelConfig = ProviderModelConfig(),
    val openai: ProviderModelConfig = ProviderModelConfig(),
    val ollama: ProviderModelConfig = ProviderModelConfig(),
    val docker: ProviderModelConfig = ProviderModelConfig(),
    val localai: ProviderModelConfig = ProviderModelConfig(),
    val lmstudio: ProviderModelConfig = ProviderModelConfig(),
    val xai: ProviderModelConfig = ProviderModelConfig(),
    val openai_compatible: ProviderModelConfig = ProviderModelConfig(),
    val askimo_pro: ProviderModelConfig = ProviderModelConfig(),
) {
    operator fun get(provider: ModelProvider): ProviderModelConfig = when (provider) {
        ModelProvider.OPENAI -> openai
        ModelProvider.ANTHROPIC -> anthropic
        ModelProvider.GEMINI -> gemini
        ModelProvider.XAI -> xai
        ModelProvider.OLLAMA -> ollama
        ModelProvider.DOCKER -> docker
        ModelProvider.LOCALAI -> localai
        ModelProvider.LMSTUDIO -> lmstudio
        ModelProvider.OPENAI_COMPATIBLE -> openai_compatible
        ModelProvider.ASKIMO_PRO -> askimo_pro
        ModelProvider.UNKNOWN -> ProviderModelConfig()
    }

    fun update(provider: ModelProvider, updated: ProviderModelConfig): ModelsConfig = when (provider) {
        ModelProvider.OPENAI -> copy(openai = updated)
        ModelProvider.ANTHROPIC -> copy(anthropic = updated)
        ModelProvider.GEMINI -> copy(gemini = updated)
        ModelProvider.XAI -> copy(xai = updated)
        ModelProvider.OLLAMA -> copy(ollama = updated)
        ModelProvider.DOCKER -> copy(docker = updated)
        ModelProvider.LOCALAI -> copy(localai = updated)
        ModelProvider.LMSTUDIO -> copy(lmstudio = updated)
        ModelProvider.OPENAI_COMPATIBLE -> copy(openai_compatible = updated)
        ModelProvider.ASKIMO_PRO -> copy(askimo_pro = updated)
        ModelProvider.UNKNOWN -> this
    }
}

/**
 * Configuration for the Askimo business analytics system.
 * Lives under the `analytics:` key in askimo.yml.
 */
data class AnalyticsConfig(
    /** True only when the user has explicitly opted in via the consent dialog. Default false. */
    val optedIn: Boolean = false,
    val endpoint: String = "https://analytics.$DOMAIN/ingest",
)

/**
 * Supported web search backends.
 * Resolution order: user-configured backend → DuckDuckGo fallback (zero-config).
 */
enum class WebSearchBackend {
    DUCKDUCKGO,
    SEARXNG,
    BRAVE,
    TAVILY,
}

private const val WEB_SEARCH_KEY_BRAVE = "websearch.brave.key"
private const val WEB_SEARCH_KEY_TAVILY = "websearch.tavily.key"
private const val WEB_SEARCH_KEY_PLACEHOLDER = "***keychain***"

/**
 * Configuration for the built-in web search feature.
 * Lives under the `web_search:` key in askimo.yml.
 *
 * API keys are stored securely via [SecureKeyManager] — the YAML holds a
 * `***keychain***` placeholder, never the raw key value.
 */
data class WebSearchConfig(
    val backend: WebSearchBackend = WebSearchBackend.DUCKDUCKGO,
    val searxngEndpoint: String = "https://searx.be",
    val enabled: Boolean = true,
    /** Raw field — may be blank or `***keychain***`. Use [AppConfig.webSearch] resolved accessors. */
    val braveApiKey: String = "",
    /** Raw field — may be blank or `***keychain***`. Use [AppConfig.webSearch] resolved accessors. */
    val tavilyApiKey: String = "",
) {
    companion object {
        fun isKeyPlaceholder(value: String): Boolean = value == WEB_SEARCH_KEY_PLACEHOLDER
        fun isActualKey(value: String): Boolean = value.isNotBlank() && !isKeyPlaceholder(value)
        fun getSecureBraveKey(): String? = SecureKeyManager.retrieveSecretKey(WEB_SEARCH_KEY_BRAVE)
        fun getSecureTavilyKey(): String? = SecureKeyManager.retrieveSecretKey(WEB_SEARCH_KEY_TAVILY)
        fun setSecureBraveKey(key: String): SecureKeyManager.StorageResult = if (key.isEmpty()) {
            SecureKeyManager.removeSecretKey(WEB_SEARCH_KEY_BRAVE)
            SecureKeyManager.StorageResult(success = true, method = SecureKeyManager.StorageMethod.KEYCHAIN)
        } else {
            SecureKeyManager.storeSecuredKey(WEB_SEARCH_KEY_BRAVE, key)
        }
        fun setSecureTavilyKey(key: String): SecureKeyManager.StorageResult = if (key.isEmpty()) {
            SecureKeyManager.removeSecretKey(WEB_SEARCH_KEY_TAVILY)
            SecureKeyManager.StorageResult(success = true, method = SecureKeyManager.StorageMethod.KEYCHAIN)
        } else {
            SecureKeyManager.storeSecuredKey(WEB_SEARCH_KEY_TAVILY, key)
        }
        fun getKeyPlaceholder(): String = WEB_SEARCH_KEY_PLACEHOLDER
    }
}

data class AppConfigData(
    val embedding: EmbeddingConfig = EmbeddingConfig(),
    val retry: RetryConfig = RetryConfig(),
    val throttle: ThrottleConfig = ThrottleConfig(),
    val indexing: IndexingConfig = IndexingConfig(),
    val developer: DeveloperConfig = DeveloperConfig(),
    val chat: ChatConfig = ChatConfig(),
    val rag: RagConfig = RagConfig(),
    val models: ModelsConfig = ModelsConfig(),
    val proxy: ProxyConfig = ProxyConfig(),
    val analytics: AnalyticsConfig = AnalyticsConfig(),
    val webSearch: WebSearchConfig = WebSearchConfig(),
    val context: AppContextParams = AppContextParams.noOp(),
    @field:JsonAlias("current_locale") val currentLocale: String? = null,
)

object AppConfig {
    val embedding: EmbeddingConfig get() = delegate.embedding
    val retry: RetryConfig get() = delegate.retry
    val indexing: IndexingConfig get() = delegate.indexing
    val developer: DeveloperConfig get() = delegate.developer
    val chat: ChatConfig get() = delegate.chat
    val rag: RagConfig get() = delegate.rag
    val models: ModelsConfig get() = delegate.models
    val context: AppContextParams get() = delegate.context
    val analytics: AnalyticsConfig get() = delegate.analytics

    /**
     * Raw web search configuration **without** keychain lookup.
     * API key fields may be blank or `***keychain***`.
     */
    val rawWebSearch: WebSearchConfig get() = delegate.webSearch

    /**
     * Web search configuration with API keys resolved from secure storage.
     * Safe to pass directly to backends.
     */
    val webSearch: WebSearchConfig
        get() {
            val config = delegate.webSearch
            val braveKey = if (!WebSearchConfig.isActualKey(config.braveApiKey)) {
                WebSearchConfig.getSecureBraveKey() ?: ""
            } else {
                config.braveApiKey
            }
            val tavilyKey = if (!WebSearchConfig.isActualKey(config.tavilyApiKey)) {
                WebSearchConfig.getSecureTavilyKey() ?: ""
            } else {
                config.tavilyApiKey
            }
            return if (braveKey != config.braveApiKey || tavilyKey != config.tavilyApiKey) {
                config.copy(braveApiKey = braveKey, tavilyApiKey = tavilyKey)
            } else {
                config
            }
        }

    /**
     * BCP-47 language tag of the user's selected UI locale (e.g. "ja-JP", "zh-CN").
     * Null means no preference has been saved yet — callers should treat null as English.
     * Stored in [AppConfigData.currentLocale] under the `current_locale:` YAML key.
     */
    val currentLocale: String? get() = delegate.currentLocale

    /**
     * Raw proxy configuration **without** keychain/secure-storage lookup.
     * All fields except password are accurate; password may be a placeholder (`***keychain***`).
     */
    val rawProxy: ProxyConfig get() = delegate.proxy

    /**
     * Proxy configuration with password loaded from secure storage.
     * If password is a placeholder (***keychain***), loads actual password from keychain/encrypted storage.
     */
    val proxy: ProxyConfig
        get() {
            val config = delegate.proxy
            val currentPassword = config.password

            // If password is a placeholder, load from secure storage
            if (!ProxyConfig.isActualPassword(currentPassword)) {
                val securePassword = ProxyConfig.getSecurePassword(config.type)
                if (securePassword != null) {
                    return config.copy(password = securePassword)
                }
            }

            return config
        }

    @Volatile private var cached: AppConfigData? = null

    @Volatile private var secureSessionManager: SecureSessionManager = SecureSessionManager()

    /**
     * Replaces the [SecureSessionManager] used by [saveContext].
     * **For testing only** — call from [AppConfig.initForTest] to prevent keychain collisions.
     */
    @Synchronized
    fun setSecureSessionManagerForTest(manager: SecureSessionManager) {
        secureSessionManager = manager
    }

    /**
     * Clears the cached configuration, forcing it to be reloaded on next access.
     * Useful for testing to ensure clean state between tests.
     */
    @Synchronized
    fun reset() {
        cached = null
        secureSessionManager = SecureSessionManager()
    }

    /**
     * Initialises AppConfig for tests by writing the DEFAULT_YAML to
     * [configDir]/askimo.yml and resetting the cache so the next access
     * reads from that file with all default values populated.
     *
     * Called automatically by @AskimoTestHome — you do not need to call this manually.
     */
    @Synchronized
    fun initForTest(configDir: Path) {
        val configFile = configDir.resolve("askimo.yml")
        writeDefaultConfig(configFile)
        cached = null
    }

    private val mapper: ObjectMapper =
        ObjectMapper(YAMLFactory().disable(YAMLGenerator.Feature.USE_NATIVE_TYPE_ID))
            .registerModule(
                KotlinModule.Builder()
                    .withReflectionCacheSize(512)
                    .configure(KotlinFeature.NullIsSameAsDefault, true)
                    .configure(KotlinFeature.NullToEmptyCollection, true)
                    .configure(KotlinFeature.NullToEmptyMap, true)
                    .build(),
            )
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

    // Default YAML written on first run if no config exists
    private val DEFAULT_YAML =
        """
        # Askimo application configuration
        # This file was auto-generated because none was found.
        # You can override any value via environment variables using ${'$'}{ENV:default} placeholders.


        embedding:
          max_chars_per_chunk: ${'$'}{ASKIMO_EMBED_MAX_CHARS_PER_CHUNK:4000}
          chunk_overlap:       ${'$'}{ASKIMO_EMBED_CHUNK_OVERLAP:200}


        retry:
          attempts:      ${'$'}{ASKIMO_EMBED_RETRY_ATTEMPTS:4}
          base_delay_ms: ${'$'}{ASKIMO_EMBED_RETRY_BASE_MS:150}

        throttle:
          per_request_sleep_ms: ${'$'}{ASKIMO_EMBED_SLEEP_MS:30}

        indexing:
          max_file_bytes:              ${'$'}{ASKIMO_EMBED_MAX_FILE_BYTES:2000000}
          concurrent_indexing_threads: ${'$'}{ASKIMO_INDEXING_CONCURRENT_THREADS:10}
          supported_extensions: ${'$'}{ASKIMO_INDEXING_SUPPORTED_EXTENSIONS:java,kt,kts,py,js,ts,jsx,tsx,go,rs,c,cpp,h,hpp,cs,rb,php,swift,scala,groovy,sh,bash,yaml,yml,json,xml,md,txt,gradle,properties,toml,pdf}
          binary_extensions: ${'$'}{ASKIMO_INDEXING_BINARY_EXTENSIONS:png,jpg,jpeg,gif,svg,ico,webp,bmp,mp4,avi,mov,mkv,mp3,wav,ogg,flac,zip,tar,gz,7z,rar,exe,dll,so,dylib,bin,db,sqlite,doc,docx,xls,xlsx,ppt,pptx,ttf,otf,woff,woff2,class,jar,pyc,icns}
          exclude_file_names: ${'$'}{ASKIMO_INDEXING_EXCLUDE_FILE_NAMES:.DS_Store,Thumbs.db,desktop.ini,package-lock.json,yarn.lock,pnpm-lock.yaml,poetry.lock,Gemfile.lock,.project,.classpath,.factorypath}
          common_excludes: ${'$'}{ASKIMO_INDEXING_COMMON_EXCLUDES:.git/,.svn/,.hg/,.idea/,.vscode/,.DS_Store,*.log,*.tmp,*.temp,*.swp,*.bak,.history/}
          filters:
            gitignore:    ${'$'}{ASKIMO_INDEXING_FILTER_GITIGNORE:true}
            dockerignore: ${'$'}{ASKIMO_INDEXING_FILTER_DOCKERIGNORE:false}
            projecttype:  ${'$'}{ASKIMO_INDEXING_FILTER_PROJECTTYPE:true}
            binary:       ${'$'}{ASKIMO_INDEXING_FILTER_BINARY:true}
            filesize:     ${'$'}{ASKIMO_INDEXING_FILTER_FILESIZE:true}
            custom:       ${'$'}{ASKIMO_INDEXING_FILTER_CUSTOM:true}
          # Project types are configured with default values and can be customized via environment variables
          # ASKIMO_INDEXING_PROJECT_TYPES_<TYPE>_MARKERS and ASKIMO_INDEXING_PROJECT_TYPES_<TYPE>_EXCLUDES

        chat:
          max_tokens:                    ${'$'}{ASKIMO_CHAT_MAX_TOKENS:8000}
          summarization_threshold:       ${'$'}{ASKIMO_CHAT_SUMMARIZATION_THRESHOLD:0.75}
          summarization_timeout_seconds: ${'$'}{ASKIMO_CHAT_SUMMARIZATION_TIMEOUT:60}
          enable_async_summarization:    ${'$'}{ASKIMO_CHAT_ENABLE_ASYNC_SUMMARIZATION:true}
          default_response_ai_locale:    ${'$'}{ASKIMO_CHAT_DEFAULT_RESPONSE_LOCALE:}

        rag:
          vector_search_max_results:      ${'$'}{ASKIMO_RAG_VECTOR_SEARCH_MAX_RESULTS:20}
          vector_search_min_score:        ${'$'}{ASKIMO_RAG_VECTOR_SEARCH_MIN_SCORE:0.3}
          hybrid_max_results:             ${'$'}{ASKIMO_RAG_HYBRID_MAX_RESULTS:15}
          rank_fusion_constant:           ${'$'}{ASKIMO_RAG_RANK_FUSION_CONSTANT:60}
          use_absolute_path_in_citations: ${'$'}{ASKIMO_RAG_USE_ABSOLUTE_PATH:true}

        models:
          timeouts:
            utility_model_timeout_seconds: ${'$'}{ASKIMO_UTILITY_MODEL_TIMEOUT:45}
            default_model_timeout_seconds: ${'$'}{ASKIMO_DEFAULT_MODEL_TIMEOUT:300}
          anthropic:
            utility_model: ${'$'}{ASKIMO_ANTHROPIC_UTILITY_MODEL:}
            embedding_model: ${'$'}{ASKIMO_ANTHROPIC_EMBEDDING_MODEL:}
            vision_model: ${'$'}{ASKIMO_ANTHROPIC_VISION_MODEL:claude-sonnet-4-6}
            image_model: ${'$'}{ASKIMO_ANTHROPIC_IMAGE_MODEL:claude-sonnet-4-6}
            # Extended thinking settings — only applied when the selected model supports thinking.
            # Increase thinking_budget_tokens for deeper reasoning (max varies by model, up to 64000 for claude-sonnet-4-6+).
            # thinking_max_tokens must always be greater than thinking_budget_tokens.
            thinking_budget_tokens: ${'$'}{ASKIMO_ANTHROPIC_THINKING_BUDGET_TOKENS:16000}
            thinking_max_tokens: ${'$'}{ASKIMO_ANTHROPIC_THINKING_MAX_TOKENS:32000}
          gemini:
            utility_model: ${'$'}{ASKIMO_GEMINI_UTILITY_MODEL:}
            embedding_model: ${'$'}{ASKIMO_GEMINI_EMBEDDING_MODEL:gemini-embedding-001}
            vision_model: ${'$'}{ASKIMO_GEMINI_VISION_MODEL:gemini-1.5-pro}
            image_model: ${'$'}{ASKIMO_GEMINI_IMAGE_MODEL:gemini-2.0-flash-exp}
          openai:
            utility_model: ${'$'}{ASKIMO_OPENAI_UTILITY_MODEL:}
            embedding_model: ${'$'}{ASKIMO_OPENAI_EMBEDDING_MODEL:text-embedding-3-small}
            vision_model: ${'$'}{ASKIMO_OPENAI_VISION_MODEL:gpt-4o}
            image_model: ${'$'}{ASKIMO_OPENAI_IMAGE_MODEL:dall-e-3}
          ollama:
            utility_model: ${'$'}{ASKIMO_OLLAMA_UTILITY_MODEL:}
            embedding_model: ${'$'}{ASKIMO_OLLAMA_EMBEDDING_MODEL:}
            vision_model: ${'$'}{ASKIMO_OLLAMA_VISION_MODEL:}
            image_model: ${'$'}{ASKIMO_OLLAMA_IMAGE_MODEL:}
          docker:
            utility_model: ${'$'}{ASKIMO_DOCKER_UTILITY_MODEL:}
            embedding_model: ${'$'}{ASKIMO_DOCKER_EMBEDDING_MODEL:}
            vision_model: ${'$'}{ASKIMO_DOCKER_VISION_MODEL:}
            image_model: ${'$'}{ASKIMO_DOCKER_IMAGE_MODEL:}
          localai:
            utility_model: ${'$'}{ASKIMO_LOCALAI_UTILITY_MODEL:}
            embedding_model: ${'$'}{ASKIMO_LOCALAI_EMBEDDING_MODEL:}
            vision_model: ${'$'}{ASKIMO_LOCALAI_VISION_MODEL:}
            image_model: ${'$'}{ASKIMO_LOCALAI_IMAGE_MODEL:}
          lmstudio:
            utility_model: ${'$'}{ASKIMO_LMSTUDIO_UTILITY_MODEL:}
            embedding_model: ${'$'}{ASKIMO_LMSTUDIO_EMBEDDING_MODEL:}
            vision_model: ${'$'}{ASKIMO_LMSTUDIO_VISION_MODEL:}
            image_model: ${'$'}{ASKIMO_LMSTUDIO_IMAGE_MODEL:stable-diffusion}
          xai:
            utility_model: ${'$'}{ASKIMO_XAI_UTILITY_MODEL:}
            embedding_model: ${'$'}{ASKIMO_XAI_EMBEDDING_MODEL:}
            vision_model: ${'$'}{ASKIMO_XAI_VISION_MODEL:grok-2-vision-latest}
            image_model: ${'$'}{ASKIMO_XAI_IMAGE_MODEL:grok-2-vision-latest}
          openai_compatible:
            utility_model:
            embedding_model:
            vision_model:
            image_model:

        proxy:
          type: ${'$'}{ASKIMO_PROXY_TYPE:NONE}
          host: ${'$'}{ASKIMO_PROXY_HOST:}
          port: ${'$'}{ASKIMO_PROXY_PORT:8080}
          username: ${'$'}{ASKIMO_PROXY_USERNAME:}
          password: ${'$'}{ASKIMO_PROXY_PASSWORD:}

        developer:
          enabled: ${'$'}{ASKIMO_DEVELOPER_ENABLED:true}
          active:  ${'$'}{ASKIMO_DEVELOPER_ACTIVE:false}

        analytics:
          opted_in: ${'$'}{ASKIMO_ANALYTICS_OPTED_IN:false}
          endpoint: ${'$'}{ASKIMO_ANALYTICS_ENDPOINT:https://analytics.askimo.chat/ingest}

        web_search:
          backend:          ${'$'}{ASKIMO_WEB_SEARCH_BACKEND:DUCKDUCKGO}
          searxng_endpoint: ${'$'}{ASKIMO_SEARXNG_ENDPOINT:https://searx.be}
          brave_api_key:    ${'$'}{BRAVE_SEARCH_API_KEY:}
          tavily_api_key:   ${'$'}{TAVILY_API_KEY:}
          enabled:          ${'$'}{ASKIMO_WEB_SEARCH_ENABLED:true}

        context:
          current_provider: ${'$'}{ASKIMO_CONTEXT_CURRENT_PROVIDER:UNKNOWN}
          models: {}
          provider_settings: {}

        current_locale: ${'$'}{ASKIMO_UI_LOCALE:}
        """.trimIndent()

    // Lazy, thread-safe init
    private val delegate: AppConfigData
        get() =
            cached ?: synchronized(this) {
                cached ?: loadOnce().also { cached = it }
            }

    private fun loadOnce(): AppConfigData {
        val path = resolveOrCreateConfigPath()
        return if (path != null && path.isRegularFile()) {
            val raw = Files.readString(path)
            // TODO: Remove migration call in v1.2.30 — only needed for users upgrading from pre-snake_case config files
            val migrated = migrateCamelToSnake(raw)
            if (migrated != raw) {
                try {
                    Files.writeString(path, migrated)
                    log.info("Migrated $path from camelCase to snake_case keys")
                } catch (e: Exception) {
                    log.displayError("Failed to write migrated config at $path", e)
                }
            }
            // Migrate session JSON -> context: block in YAML (one-time, only if context: is absent)
            val withContext = migrateSessionJsonToYaml(migrated, path)
            val interpolated = interpolateEnv(withContext)
            try {
                mapper.readValue<AppConfigData>(interpolated)
            } catch (e: Exception) {
                log.displayError("Config parse failed at $path ", e)
                envFallback()
            }
        } else {
            envFallback()
        }
    }

    /**
     * One-time migration: rewrites camelCase YAML keys to snake_case in-place.
     * This handles users upgrading from versions prior to the snake_case config format.
     *
     * TODO: Remove this method in v1.2.30 along with all @field:JsonAlias camelCase annotations.
     */
    private fun migrateCamelToSnake(yaml: String): String {
        val replacements = mapOf(
            "utilityModel:" to "utility_model:",
            "utilityModelTimeoutSeconds:" to "utility_model_timeout_seconds:",
            "embeddingModel:" to "embedding_model:",
            "visionModel:" to "vision_model:",
            "imageModel:" to "image_model:",
            "maxCharsPerChunk:" to "max_chars_per_chunk:",
            "chunkOverlap:" to "chunk_overlap:",
            "baseDelayMs:" to "base_delay_ms:",
            "perRequestSleepMs:" to "per_request_sleep_ms:",
            "maxFileBytes:" to "max_file_bytes:",
            "concurrentIndexingThreads:" to "concurrent_indexing_threads:",
            "supportedExtensions:" to "supported_extensions:",
            "binaryExtensions:" to "binary_extensions:",
            "excludeFileNames:" to "exclude_file_names:",
            "commonExcludes:" to "common_excludes:",
            "projectTypes:" to "project_types:",
            "excludePaths:" to "exclude_paths:",
            "maxTokens:" to "max_tokens:",
            "summarizationThreshold:" to "summarization_threshold:",
            "enableAsyncSummarization:" to "enable_async_summarization:",
            "summarizationTimeoutSeconds:" to "summarization_timeout_seconds:",
            "defaultResponseAILocale:" to "default_response_ai_locale:",
            "vectorSearchMaxResults:" to "vector_search_max_results:",
            "vectorSearchMinScore:" to "vector_search_min_score:",
            "hybridMaxResults:" to "hybrid_max_results:",
            "rankFusionConstant:" to "rank_fusion_constant:",
            "useAbsolutePathInCitations:" to "use_absolute_path_in_citations:",
        )
        var result = yaml
        for ((camel, snake) in replacements) {
            result = result.replace(camel, snake)
        }
        return result
    }

    /**
     * One-time migration: reads the legacy JSON session file and injects its content
     * as a `context:` block into the YAML string, then persists the updated YAML.
     *
     * Only runs when:
     *  - The YAML does not already contain a non-empty `context:` section, AND
     *  - The JSON session file exists and is parseable.
     *
     * The JSON uses `__type` with full class names; the YAML uses `type` with short names.
     */
    private fun migrateSessionJsonToYaml(yaml: String, yamlPath: Path): String {
        // Skip if context block already has real content beyond the defaults
        if (hasContextSection(yaml)) return yaml

        val sessionFile = AskimoHome.sessionFile()
        if (!sessionFile.toFile().exists()) return yaml

        return try {
            val jsonMapper = ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            val root = jsonMapper.readTree(Files.readString(sessionFile)) as? ObjectNode
                ?: return yaml

            val contextYaml = buildContextYaml(root)
            val merged = mergeContextIntoYaml(yaml, contextYaml)

            Files.writeString(yamlPath, merged)
            log.info("Migrated session JSON from $sessionFile into context: block in $yamlPath")
            merged
        } catch (e: Exception) {
            log.displayError("Failed to migrate session JSON to YAML context block", e)
            yaml
        }
    }

    /** Returns true if the YAML already has a populated context section (not just empty maps/defaults). */
    private fun hasContextSection(yaml: String): Boolean {
        val contextIdx = yaml.indexOf("\ncontext:")
        if (contextIdx < 0) return false
        // Check if any meaningful sub-key follows beyond empty maps
        val afterContext = yaml.substring(contextIdx).lines().drop(1).take(10)
        return afterContext.any { line ->
            val trimmed = line.trimStart()
            trimmed.isNotEmpty() && !trimmed.startsWith("#") &&
                // Not just the empty-map defaults we wrote in DEFAULT_YAML
                trimmed != "models: {}" && trimmed != "provider_settings: {}" &&
                trimmed != "current_provider: UNKNOWN" && trimmed != "current_provider: \${ASKIMO_CONTEXT_CURRENT_PROVIDER:UNKNOWN}"
        }
    }

    /**
     * Maps the kotlinx.serialization full class name (used as `__type` in JSON)
     * to the short Jackson type name (used as `type` in YAML).
     */
    private val jsonTypeToYamlType = mapOf(
        "io.askimo.core.providers.openai.OpenAiSettings" to "openai",
        "io.askimo.core.providers.anthropic.AnthropicSettings" to "anthropic",
        "io.askimo.core.providers.gemini.GeminiSettings" to "gemini",
        "io.askimo.core.providers.xai.XAiSettings" to "xai",
        "io.askimo.core.providers.ollama.OllamaSettings" to "ollama",
        "io.askimo.core.providers.docker.DockerAiSettings" to "docker",
        "io.askimo.core.providers.localai.LocalAiSettings" to "localai",
        "io.askimo.core.providers.lmstudio.LmStudioSettings" to "lmstudio",
        "io.askimo.app.team.AskimoProSettings" to "openai_compatible",
    )

    /** Builds an indented YAML `context:` block from the parsed JSON session root. */
    private fun buildContextYaml(root: ObjectNode): String {
        val sb = StringBuilder()
        sb.appendLine("context:")

        // current_provider
        val currentProvider = root.get("currentProvider")?.asText("UNKNOWN") ?: "UNKNOWN"
        sb.appendLine("  current_provider: $currentProvider")

        // Build a lookup of provider -> selected model from the JSON models map.
        // These values are folded into default_model inside each provider's settings block
        // rather than kept as a separate models: section.
        val modelsMap: Map<String, String> = root.get("models")
            ?.takeIf { it.isObject }
            ?.properties()
            ?.associate { (provider, modelNode) -> provider to modelNode.asText() }
            ?: emptyMap()

        // provider_settings — default_model is written from modelsMap, overriding the
        // (usually empty) defaultModel field that was stored in the JSON settings object
        val settingsNode = root.get("providerSettings")
        if (settingsNode != null && settingsNode.isObject && settingsNode.size() > 0) {
            sb.appendLine("  provider_settings:")
            settingsNode.properties().forEach { (provider, settingNode) ->
                if (settingNode !is ObjectNode) return@forEach
                sb.appendLine("    $provider:")
                // Resolve short type name from __type
                val fullType = settingNode.get("__type")?.asText()
                val shortType = jsonTypeToYamlType[fullType] ?: fullType?.substringAfterLast('.') ?: "unknown"
                sb.appendLine("      type: $shortType")
                // Write remaining fields, skipping __type.
                // default_model is overridden by the value from the models map if present.
                settingNode.properties().forEach { (key, valueNode) ->
                    if (key == "__type") return@forEach
                    val snakeKey = camelToSnakeKey(key)
                    val yamlValue = if (key == "defaultModel") {
                        yamlScalar(modelsMap[provider] ?: valueNode.asText())
                    } else {
                        yamlScalar(valueNode)
                    }
                    sb.appendLine("      $snakeKey: $yamlValue")
                }
            }
        } else {
            sb.appendLine("  provider_settings: {}")
        }

        return sb.toString().trimEnd()
    }

    /** Replaces or appends the `context:` block in the YAML string. */
    private fun mergeContextIntoYaml(yaml: String, contextYaml: String): String {
        val contextIdx = yaml.indexOf("\ncontext:")
        return if (contextIdx >= 0) {
            // Find end of existing context block (next top-level key or EOF)
            val afterContext = yaml.indexOf("\n", contextIdx + 1)
            val nextTopLevel = if (afterContext >= 0) {
                val rest = yaml.substring(afterContext + 1)
                val nextIdx = rest.indexOfFirst { it.isLetter() || it == '#' }
                if (nextIdx >= 0) afterContext + 1 + nextIdx else -1
            } else {
                -1
            }

            if (nextTopLevel >= 0) {
                yaml.substring(0, contextIdx + 1) + contextYaml + "\n\n" + yaml.substring(nextTopLevel)
            } else {
                yaml.substring(0, contextIdx + 1) + contextYaml
            }
        } else {
            yaml.trimEnd() + "\n\n" + contextYaml
        }
    }

    /** Converts a camelCase key to snake_case for YAML output. */
    private fun camelToSnakeKey(key: String): String = key.replace(Regex("([A-Z])")) { "_${it.value.lowercase()}" }

    /** Renders a plain string as a YAML scalar (quoted if it contains special chars or is empty). */
    private fun yamlScalar(text: String): String = if (text.isEmpty() || text.any { it in ":#{}[]|>&*!,'\"" } || text == "true" || text == "false") {
        "\"${text.replace("\"", "\\\"")}\""
    } else {
        text
    }

    /** Renders a Jackson JsonNode as a plain YAML scalar (strings quoted if needed). */
    private fun yamlScalar(node: JsonNode): String = when {
        node.isNull -> "~"
        node.isBoolean -> node.asText()
        node.isNumber -> node.asText()
        else -> yamlScalar(node.asText())
    }

    /**
     * Resolution order:
     *  1) system property: askimo.config
     *  2) env var: ASKIMO_CONFIG
     *  3) ~/.askimo/askimo.yml (will be created if missing)
     *  4) ./askimo.yml (used only if already exists; we don’t auto-create in CWD)
     *
     * If an explicit path (1 or 2) is provided and missing, we create it.
     * Otherwise, if home path is missing, we create ~/.askimo/askimo.yml.
     */
    private fun resolveOrCreateConfigPath(): Path? {
        val homeBase = AskimoHome.base()
        val homePath = homeBase.resolve("askimo.yml")
        if (!homePath.exists()) writeDefaultConfig(homePath)
        if (homePath.isRegularFile()) return homePath
        val cwdPath = Paths.get("askimo.yml")
        if (cwdPath.isRegularFile()) return cwdPath
        return null
    }

    private fun writeDefaultConfig(target: Path) {
        try {
            target.parent?.createDirectories()
            val supportsPosix =
                try {
                    FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
                } catch (_: Exception) {
                    false
                }

            if (supportsPosix) {
                val attrs =
                    PosixFilePermissions.asFileAttribute(
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    )
                Files.createFile(target, attrs)
            } else {
                Files.createFile(target)
            }
            Files.writeString(target, DEFAULT_YAML)
            log.info("📝 Created default config at $target")
        } catch (e: Exception) {
            log.displayError("Failed to create default config at $target ", e)
        }
    }

    /** Supports ${ENV} or ${ENV:default} inside YAML. */
    private val placeholder = "\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?}".toRegex()

    private fun interpolateEnv(text: String): String = placeholder.replace(text) { m ->
        val key = m.groupValues[1]
        val def = m.groupValues.getOrNull(2)
        propOrEnv(key) ?: def.orEmpty()
    }

    private fun propOrEnv(key: String): String? = System.getProperty(key) ?: System.getenv(key)

    /** Env-only fallback (works even without YAML). */
    private fun envFallback(): AppConfigData {
        fun env(
            k: String,
            def: String,
        ) = System.getenv(k) ?: def

        fun envInt(
            k: String,
            def: Int,
        ) = System.getenv(k)?.toIntOrNull() ?: def

        fun envLong(
            k: String,
            def: Long,
        ) = System.getenv(k)?.toLongOrNull() ?: def

        fun envList(k: String, def: String): Set<String> = System.getenv(k)?.split(",")?.map { it.trim() }?.toSet() ?: def.split(",").map { it.trim() }.toSet()

        fun envNullableInt(k: String) = System.getenv(k)?.toIntOrNull()

        val emb =
            EmbeddingConfig(
                maxCharsPerChunk = envInt("ASKIMO_EMBED_MAX_CHARS_PER_CHUNK", 4000),
                chunkOverlap = envInt("ASKIMO_EMBED_CHUNK_OVERLAP", 200),
            )
        val r =
            RetryConfig(
                attempts = envInt("ASKIMO_EMBED_RETRY_ATTEMPTS", 4),
                baseDelayMs = envLong("ASKIMO_EMBED_RETRY_BASE_MS", 150L),
            )
        val t =
            ThrottleConfig(
                perRequestSleepMs = envLong("ASKIMO_EMBED_SLEEP_MS", 30L),
            )
        val idx =
            IndexingConfig(
                maxFileBytes = envLong("ASKIMO_EMBED_MAX_FILE_BYTES", 5_000_000L),
                concurrentIndexingThreads = envInt("ASKIMO_INDEXING_CONCURRENT_THREADS", 10),
                supportedExtensions = envList("ASKIMO_INDEXING_SUPPORTED_EXTENSIONS", "java,kt,kts,py,js,ts,jsx,tsx,go,rs,c,cpp,h,hpp,cs,rb,php,swift,scala,groovy,sh,bash,yaml,yml,json,xml,md,txt,gradle,properties,toml,pdf"),
                binaryExtensions = envList("ASKIMO_INDEXING_BINARY_EXTENSIONS", "png,jpg,jpeg,gif,svg,ico,webp,bmp,mp4,avi,mov,mkv,mp3,wav,ogg,flac,zip,tar,gz,7z,rar,exe,dll,so,dylib,bin,db,sqlite,doc,docx,xls,xlsx,ppt,pptx,ttf,otf,woff,woff2,class,jar,pyc,icns"),
                excludeFileNames = envList("ASKIMO_INDEXING_EXCLUDE_FILE_NAMES", ".DS_Store,Thumbs.db,desktop.ini,package-lock.json,yarn.lock,pnpm-lock.yaml,poetry.lock,Gemfile.lock"),
                commonExcludes = envList("ASKIMO_INDEXING_COMMON_EXCLUDES", ".git/,.svn/,.hg/,.idea/,.vscode/,.DS_Store,*.log,*.tmp,*.temp,*.swp,*.bak,.history/"),
                filters = FilterConfig(
                    gitignore = System.getenv("ASKIMO_INDEXING_FILTER_GITIGNORE")?.toBoolean() ?: true,
                    dockerignore = System.getenv("ASKIMO_INDEXING_FILTER_DOCKERIGNORE")?.toBoolean() ?: false,
                    projecttype = System.getenv("ASKIMO_INDEXING_FILTER_PROJECTTYPE")?.toBoolean() ?: true,
                    binary = System.getenv("ASKIMO_INDEXING_FILTER_BINARY")?.toBoolean() ?: true,
                    filesize = System.getenv("ASKIMO_INDEXING_FILTER_FILESIZE")?.toBoolean() ?: true,
                    custom = System.getenv("ASKIMO_INDEXING_FILTER_CUSTOM")?.toBoolean() ?: true,
                ),
            )
        val dev =
            DeveloperConfig(
                enabled = System.getenv("ASKIMO_DEVELOPER_ENABLED")?.toBoolean() ?: false,
                active = System.getenv("ASKIMO_DEVELOPER_ACTIVE")?.toBoolean() ?: false,
            )

        fun envDouble(k: String, def: Double) = System.getenv(k)?.toDoubleOrNull() ?: def

        val chat =
            ChatConfig(
                maxTokens = envInt("ASKIMO_CHAT_MAX_TOKENS", 8000),
                summarizationThreshold = envDouble("ASKIMO_CHAT_SUMMARIZATION_THRESHOLD", 0.75),
                summarizationTimeoutSeconds = envLong("ASKIMO_CHAT_SUMMARIZATION_TIMEOUT", 300L),
                enableAsyncSummarization = System.getenv("ASKIMO_CHAT_ENABLE_ASYNC_SUMMARIZATION")?.toBoolean() ?: true,
                defaultResponseAILocale = System.getenv("ASKIMO_CHAT_DEFAULT_RESPONSE_LOCALE")?.takeIf { it.isNotBlank() },
            )

        val rag =
            RagConfig(
                vectorSearchMaxResults = envInt("ASKIMO_RAG_VECTOR_SEARCH_MAX_RESULTS", 20),
                vectorSearchMinScore = envDouble("ASKIMO_RAG_VECTOR_SEARCH_MIN_SCORE", 0.3),
                hybridMaxResults = envInt("ASKIMO_RAG_HYBRID_MAX_RESULTS", 15),
                rankFusionConstant = envInt("ASKIMO_RAG_RANK_FUSION_CONSTANT", 60),
                useAbsolutePathInCitations = System.getenv("ASKIMO_RAG_USE_ABSOLUTE_PATH")?.toBoolean() ?: true,
            )

        val models = ModelsConfig(
            timeouts = ModelTimeoutsConfig(
                utilityModelTimeoutSeconds = envLong("ASKIMO_UTILITY_MODEL_TIMEOUT", 600L),
                defaultModelTimeoutSeconds = envLong("ASKIMO_DEFAULT_MODEL_TIMEOUT", 600L),
            ),
            anthropic = ProviderModelConfig(
                utilityModel = env("ASKIMO_ANTHROPIC_UTILITY_MODEL", ""),
                embeddingModel = env("ASKIMO_ANTHROPIC_EMBEDDING_MODEL", ""),
                visionModel = env("ASKIMO_ANTHROPIC_VISION_MODEL", "claude-sonnet-4-6"),
                imageModel = env("ASKIMO_ANTHROPIC_IMAGE_MODEL", "claude-sonnet-4-6"),
            ),
            gemini = ProviderModelConfig(
                utilityModel = env("ASKIMO_GEMINI_UTILITY_MODEL", "gemini-2.5-flash-lite"),
                embeddingModel = env("ASKIMO_GEMINI_EMBEDDING_MODEL", "gemini-embedding-001"),
                visionModel = env("ASKIMO_GEMINI_VISION_MODEL", "gemini-1.5-pro"),
                imageModel = env("ASKIMO_GEMINI_IMAGE_MODEL", "gemini-2.0-flash-exp"),
            ),
            openai = ProviderModelConfig(
                utilityModel = env("ASKIMO_OPENAI_UTILITY_MODEL", "gpt-3.5-turbo"),
                embeddingModel = env("ASKIMO_OPENAI_EMBEDDING_MODEL", "text-embedding-3-small"),
                visionModel = env("ASKIMO_OPENAI_VISION_MODEL", "gpt-4o"),
                imageModel = env("ASKIMO_OPENAI_IMAGE_MODEL", "dall-e-3"),
            ),
            ollama = ProviderModelConfig(
                utilityModel = env("ASKIMO_OLLAMA_UTILITY_MODEL", ""),
                embeddingModel = env("ASKIMO_OLLAMA_EMBEDDING_MODEL", ""),
                visionModel = env("ASKIMO_OLLAMA_VISION_MODEL", ""),
                imageModel = env("ASKIMO_OLLAMA_IMAGE_MODEL", ""),
            ),
            docker = ProviderModelConfig(
                utilityModel = env("ASKIMO_DOCKER_UTILITY_MODEL", ""),
                embeddingModel = env("ASKIMO_DOCKER_EMBEDDING_MODEL", ""),
                visionModel = env("ASKIMO_DOCKER_VISION_MODEL", ""),
                imageModel = env("ASKIMO_DOCKER_IMAGE_MODEL", ""),
            ),
            localai = ProviderModelConfig(
                utilityModel = env("ASKIMO_LOCALAI_UTILITY_MODEL", ""),
                embeddingModel = env("ASKIMO_LOCALAI_EMBEDDING_MODEL", ""),
                visionModel = env("ASKIMO_LOCALAI_VISION_MODEL", ""),
                imageModel = env("ASKIMO_LOCALAI_IMAGE_MODEL", ""),
            ),
            lmstudio = ProviderModelConfig(
                utilityModel = env("ASKIMO_LMSTUDIO_UTILITY_MODEL", ""),
                embeddingModel = env("ASKIMO_LMSTUDIO_EMBEDDING_MODEL", ""),
                visionModel = env("ASKIMO_LMSTUDIO_VISION_MODEL", ""),
                imageModel = env("ASKIMO_LMSTUDIO_IMAGE_MODEL", ""),
            ),
            xai = ProviderModelConfig(
                utilityModel = env("ASKIMO_XAI_UTILITY_MODEL", "grok-3-mini"),
                embeddingModel = env("ASKIMO_XAI_EMBEDDING_MODEL", ""),
                visionModel = env("ASKIMO_XAI_VISION_MODEL", "grok-2-vision-latest"),
                imageModel = env("ASKIMO_XAI_IMAGE_MODEL", "grok-2-vision-latest"),
            ),
            openai_compatible = ProviderModelConfig(
                utilityModel = "",
                embeddingModel = "",
                visionModel = "",
                imageModel = "",
            ),
        )

        val proxy =
            ProxyConfig(
                type = System.getenv("ASKIMO_PROXY_TYPE")?.let { ProxyType.valueOf(it) } ?: ProxyType.NONE,
                host = env("ASKIMO_PROXY_HOST", ""),
                port = envInt("ASKIMO_PROXY_PORT", 8080),
                username = env("ASKIMO_PROXY_USERNAME", ""),
                password = env("ASKIMO_PROXY_PASSWORD", ""),
            )

        val webSearch = WebSearchConfig(
            backend = System.getenv("ASKIMO_WEB_SEARCH_BACKEND")
                ?.let { runCatching { WebSearchBackend.valueOf(it) }.getOrNull() }
                ?: WebSearchBackend.DUCKDUCKGO,
            searxngEndpoint = env("ASKIMO_SEARXNG_ENDPOINT", "https://searx.be"),
            braveApiKey = env("BRAVE_SEARCH_API_KEY", ""),
            tavilyApiKey = env("TAVILY_API_KEY", ""),
            enabled = System.getenv("ASKIMO_WEB_SEARCH_ENABLED")?.toBoolean() ?: true,
        )

        return AppConfigData(emb, r, t, idx, dev, chat, rag, models, proxy, webSearch = webSearch)
    }

    /**
     * Persists the given [AppContextParams] into the in-memory cache and YAML file,
     * replacing the current `context:` section.
     * API keys are sanitised via [SecureSessionManager] before writing to disk.
     */
    fun saveContext(params: AppContextParams) {
        synchronized(this) {
            val sanitized = secureSessionManager.saveSecureSession(params)
            // ASKIMO_PRO settings contain a transient accessToken and use a type id
            // not registered in shared's @JsonSubTypes — strip before persisting.
            val persistable = sanitized.copy(
                providerSettings = sanitized.providerSettings.filterKeys {
                    it.name != ModelProvider.ASKIMO_PRO.name
                }.toMutableMap(),
            )
            val current = cached ?: loadOnce()

            // If ASKIMO_PRO settings carry a defaultModel, persist it into
            // models.askimo_pro.default_model so the model selection survives restarts.
            val askimoProSettings = params.providerSettings[ModelProvider.ASKIMO_PRO]
            val askimoProDefaultModel = askimoProSettings?.defaultModel?.takeIf { it.isNotBlank() }
            val updatedModels = if (askimoProDefaultModel != null) {
                current.models.update(
                    ModelProvider.ASKIMO_PRO,
                    current.models[ModelProvider.ASKIMO_PRO].copy(defaultModel = askimoProDefaultModel),
                )
            } else {
                current.models
            }

            cached = current.copy(context = sanitized, models = updatedModels)

            val configPath = resolveOrCreateConfigPath()
            if (configPath != null && configPath.exists()) {
                try {
                    val updatedYaml = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        current.copy(context = persistable, models = updatedModels),
                    )
                    Files.writeString(configPath, updatedYaml)
                    log.info("Saved context to $configPath")
                } catch (e: Exception) {
                    log.displayError("Failed to persist context to config file", e)
                }
            }
        }
    }

    /**
     * Generic method to update any config field and persist to YAML file.
     *
     * @param path Dot-separated path to the field (e.g., "developer.active", "chat.maxRecentMessages")
     * @param value The new value to set
     *
     * Example: AppConfig.updateField("developer.active", true)
     */
    fun updateField(path: String, value: Any) {
        synchronized(this) {
            // Handle top-level scalar fields that don't follow the section.field pattern
            if (path == "currentLocale") {
                val tag = (value as? String)?.takeIf { it.isNotBlank() }
                val current = cached ?: loadOnce()
                cached = current.copy(currentLocale = tag)
                val configPath = resolveOrCreateConfigPath()
                if (configPath != null && configPath.exists()) {
                    try {
                        val updatedYaml = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(cached)
                        Files.writeString(configPath, updatedYaml)
                        log.debug("Updated currentLocale=$tag in $configPath")
                    } catch (e: Exception) {
                        log.displayError("Failed to persist currentLocale to config file", e)
                    }
                }
                return
            }

            val parts = path.split(".")

            if (parts.size !in 2..3) {
                log.displayError("Invalid config path: $path. Must be in format 'section.field' or 'models.provider.field'", null)
                return
            }

            val section = parts[0]
            val field = if (parts.size == 2) parts[1] else "${parts[1]}.${parts[2]}"

            // Update in-memory cache
            val current = cached ?: loadOnce()
            cached = when (section) {
                "developer" -> current.copy(developer = updateDeveloperField(current.developer, field, value))

                "retry" -> current.copy(retry = updateRetryField(current.retry, field, value))

                "throttle" -> current.copy(throttle = updateThrottleField(current.throttle, field, value))

                "embedding" -> current.copy(embedding = updateEmbeddingField(current.embedding, field, value))

                "chat" -> current.copy(chat = updateChatField(current.chat, field, value))

                "rag" -> current.copy(rag = updateRagField(current.rag, field, value))

                "models" -> current.copy(models = updateModelsField(current.models, field, value))

                "proxy" -> current.copy(proxy = updateProxyField(current.proxy, field, value))

                "analytics" -> current.copy(analytics = updateAnalyticsField(current.analytics, field, value))

                "indexing" -> current.copy(indexing = updateIndexingField(current.indexing, field, value))

                "webSearch" -> current.copy(webSearch = updateWebSearchField(current.webSearch, field, value))

                else -> {
                    log.displayError("Unknown config section: $section", null)
                    return
                }
            }

            val configPath = resolveOrCreateConfigPath()
            if (configPath != null && configPath.exists()) {
                try {
                    val updatedYaml = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(cached)
                    Files.writeString(configPath, updatedYaml)

                    log.debug("Updated $path=$value in $configPath")
                } catch (e: Exception) {
                    log.displayError("Failed to persist $path to config file", e)
                }
            }
        }
    }

    private fun updateAnalyticsField(config: AnalyticsConfig, field: String, value: Any): AnalyticsConfig = when (field) {
        "opted_in" -> config.copy(optedIn = value as Boolean)
        "endpoint" -> config.copy(endpoint = value as String)
        else -> config
    }

    private fun updateDeveloperField(config: DeveloperConfig, field: String, value: Any): DeveloperConfig = when (field) {
        "enabled" -> config.copy(enabled = value as Boolean)
        "active" -> config.copy(active = value as Boolean)
        else -> config
    }

    private fun updateRetryField(config: RetryConfig, field: String, value: Any): RetryConfig = when (field) {
        "attempts" -> config.copy(attempts = value as Int)
        "baseDelayMs" -> config.copy(baseDelayMs = value as Long)
        else -> config
    }

    private fun updateThrottleField(config: ThrottleConfig, field: String, value: Any): ThrottleConfig = when (field) {
        "perRequestSleepMs" -> config.copy(perRequestSleepMs = value as Long)
        else -> config
    }

    private fun updateEmbeddingField(config: EmbeddingConfig, field: String, value: Any): EmbeddingConfig = when (field) {
        "maxCharsPerChunk" -> config.copy(maxCharsPerChunk = value as Int)
        "chunkOverlap" -> config.copy(chunkOverlap = value as Int)
        else -> config
    }

    private fun updateChatField(config: ChatConfig, field: String, value: Any): ChatConfig = when (field) {
        "maxTokens" -> config.copy(maxTokens = value as Int)

        "summarizationThreshold" -> config.copy(summarizationThreshold = (value as Number).toDouble())

        "enableAsyncSummarization" -> config.copy(enableAsyncSummarization = value as Boolean)

        "defaultResponseAILocale" -> {
            val newLocale = if (value is String && value.isBlank()) null else value as? String
            EventBus.post(
                LanguageDirectiveChangedEvent(localeString = newLocale),
            )
            config.copy(defaultResponseAILocale = newLocale)
        }

        else -> config
    }

    private fun updateRagField(config: RagConfig, field: String, value: Any): RagConfig = when (field) {
        "vectorSearchMaxResults" -> config.copy(vectorSearchMaxResults = value as Int)
        "vectorSearchMinScore" -> config.copy(vectorSearchMinScore = (value as Number).toDouble())
        "hybridMaxResults" -> config.copy(hybridMaxResults = value as Int)
        "rankFusionConstant" -> config.copy(rankFusionConstant = value as Int)
        "useAbsolutePathInCitations" -> config.copy(useAbsolutePathInCitations = value as Boolean)
        else -> config
    }

    private fun updateModelsField(config: ModelsConfig, field: String, value: Any): ModelsConfig {
        val parts = field.split(".")
        if (parts.size != 2) {
            log.displayError("Models config requires nested path format: provider.field or timeouts.field", null)
            return config
        }

        val providerKey = parts[0]
        val modelField = parts[1]
        val stringValue = value as? String ?: value.toString()

        // Handle global timeouts: models.timeouts.utilityModelTimeoutSeconds / defaultModelTimeoutSeconds
        if (providerKey == "timeouts") {
            val current = config.timeouts
            val updated = when (modelField) {
                "utilityModelTimeoutSeconds" -> current.copy(utilityModelTimeoutSeconds = stringValue.toLongOrNull() ?: current.utilityModelTimeoutSeconds)

                "defaultModelTimeoutSeconds" -> current.copy(defaultModelTimeoutSeconds = stringValue.toLongOrNull() ?: current.defaultModelTimeoutSeconds)

                else -> {
                    log.displayError("Unknown timeouts field '$modelField'", null)
                    return config
                }
            }
            return config.copy(timeouts = updated)
        }

        val provider = ModelProvider.entries.firstOrNull { it.name.lowercase() == providerKey }
            ?: run {
                log.displayError("Unknown provider: $providerKey", null)
                return config
            }

        val current = config[provider]
        val updated = when (modelField) {
            "defaultModel" -> current.copy(defaultModel = stringValue)

            "utilityModel" -> current.copy(utilityModel = stringValue)

            "embeddingModel" -> current.copy(embeddingModel = stringValue)

            "visionModel" -> current.copy(visionModel = stringValue)

            "imageModel" -> current.copy(imageModel = stringValue)

            else -> {
                log.displayError("Unknown model field '$modelField' for provider $providerKey", null)
                return config
            }
        }
        return config.update(provider, updated)
    }

    private fun updateProxyField(config: ProxyConfig, field: String, value: Any): ProxyConfig = when (field) {
        "type" -> config.copy(type = if (value is String) ProxyType.valueOf(value) else value as ProxyType)

        "host" -> config.copy(host = value as String)

        "port" -> config.copy(port = value as Int)

        "username" -> config.copy(username = value as String)

        "password" -> {
            val password = value as String

            // Only store if it's an actual password (not a placeholder)
            if (ProxyConfig.isActualPassword(password)) {
                val result = ProxyConfig.setSecurePassword(config.type, password)

                when (result.method) {
                    StorageMethod.KEYCHAIN -> {
                        log.debug("Proxy password stored securely in system keychain")
                    }

                    StorageMethod.ENCRYPTED -> {
                        log.warn("Proxy password stored with encryption (${result.warningMessage})")
                    }

                    StorageMethod.INSECURE_FALLBACK -> {
                        log.warn("⚠️ Proxy password storage: ${result.warningMessage}")
                    }
                }

                config.copy(password = ProxyConfig.getPasswordPlaceholder())
            } else {
                // Keep placeholder or empty as-is
                config.copy(password = password)
            }
        }

        else -> {
            log.displayError("Unknown proxy field: $field", null)
            config
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun updateIndexingField(config: IndexingConfig, field: String, value: Any): IndexingConfig = when (field) {
        "maxFileBytes" -> config.copy(maxFileBytes = (value as Number).toLong())
        "embeddingBatchSize" -> config.copy(embeddingBatchSize = (value as Number).toInt().coerceAtLeast(1))
        "supportedExtensions" -> config.copy(supportedExtensions = value as Set<String>)
        "excludeFileNames" -> config.copy(excludeFileNames = value as Set<String>)
        "binaryExtensions" -> config.copy(binaryExtensions = value as Set<String>)
        else -> config
    }

    private fun updateWebSearchField(config: WebSearchConfig, field: String, value: Any): WebSearchConfig = when (field) {
        "backend" -> config.copy(
            backend = if (value is WebSearchBackend) {
                value
            } else {
                runCatching { WebSearchBackend.valueOf(value.toString()) }.getOrElse { config.backend }
            },
        )

        "searxngEndpoint" -> config.copy(searxngEndpoint = value as String)

        "enabled" -> config.copy(enabled = value as Boolean)

        "braveApiKey" -> {
            val key = value as String
            if (WebSearchConfig.isActualKey(key)) {
                val result = WebSearchConfig.setSecureBraveKey(key)
                when (result.method) {
                    SecureKeyManager.StorageMethod.KEYCHAIN ->
                        log.debug("Brave Search API key stored securely in keychain")

                    SecureKeyManager.StorageMethod.ENCRYPTED ->
                        log.warn("Brave Search API key stored with encryption ({})", result.warningMessage)

                    SecureKeyManager.StorageMethod.INSECURE_FALLBACK ->
                        log.warn("⚠️ Brave Search API key storage: {}", result.warningMessage)
                }
                config.copy(braveApiKey = WebSearchConfig.getKeyPlaceholder())
            } else {
                config.copy(braveApiKey = key)
            }
        }

        "tavilyApiKey" -> {
            val key = value as String
            if (WebSearchConfig.isActualKey(key)) {
                val result = WebSearchConfig.setSecureTavilyKey(key)
                when (result.method) {
                    SecureKeyManager.StorageMethod.KEYCHAIN ->
                        log.debug("Tavily API key stored securely in keychain")

                    SecureKeyManager.StorageMethod.ENCRYPTED ->
                        log.warn("Tavily API key stored with encryption ({})", result.warningMessage)

                    SecureKeyManager.StorageMethod.INSECURE_FALLBACK ->
                        log.warn("⚠️ Tavily API key storage: {}", result.warningMessage)
                }
                config.copy(tavilyApiKey = WebSearchConfig.getKeyPlaceholder())
            } else {
                config.copy(tavilyApiKey = key)
            }
        }

        else -> {
            log.displayError("Unknown webSearch field: $field", null)
            config
        }
    }
}
