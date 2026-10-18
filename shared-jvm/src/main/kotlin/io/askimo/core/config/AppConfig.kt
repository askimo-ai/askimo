/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.config

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
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.context.AppContextParams
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.LanguageDirectiveChangedEvent
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

data class EmbeddingConfig(
    val maxCharsPerChunk: Int = 3000,
    val chunkOverlap: Int = 100,
)

data class RetryConfig(
    val attempts: Int = 4,
    val baseDelayMs: Long = 150,
)

data class ThrottleConfig(
    val perRequestSleepMs: Long = 30,
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

data class IndexingConfig(
    val maxFileBytes: Long = 5_000_000,
    val concurrentIndexingThreads: Int = 3,
    val embeddingBatchSize: Int = 50,
    val filters: FilterConfig = FilterConfig(),
    val customExcludes: Set<String> = emptySet(),
    @field:JsonDeserialize(using = CommaSeparatedSetDeserializer::class)
    val supportedExtensions: Set<String> = setOf(),
    @field:JsonDeserialize(using = CommaSeparatedSetDeserializer::class)
    val binaryExtensions: Set<String> = setOf(),
    @field:JsonDeserialize(using = CommaSeparatedSetDeserializer::class)
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

data class ChatConfig(
    val maxTokens: Int = 8000,
    val defaultResponseAILocale: String? = null,
)

/**
 * Memory quality/cost preset modes.
 *
 * - [COMPACT]  – Aggressive summarization. Fires early, prunes hard, keeps small summaries.
 *               Best for long sessions or cost-sensitive models.
 * - [BALANCED] – Current defaults. Good quality/cost trade-off for general use.
 * - [DETAIL]   – Minimal summarization. Keeps more verbatim turns and richer summaries.
 *               Best for short sessions, coding, or precise recall tasks.
 */
enum class MemoryMode { COMPACT, BALANCED, DETAIL }

/**
 * Memory configuration. All fields are required and non-null.
 * Select a [mode] to load the full preset via [MemoryConfig.preset]; the mode field is
 * kept alongside the values so it can be persisted and displayed in settings.
 *
 * Preset values by mode:
 * ```
 * Field                      | COMPACT | BALANCED | DETAIL
 * ---------------------------|---------|----------|-------
 * summarizationThreshold     |  0.25   |   0.40   |  0.60
 * protectedRecentTurns       |    3    |     6    |   10
 * summarizationPruneFraction |  0.80   |   0.65   |  0.50
 * maxKeyFacts                |   15    |    30    |   50
 * maxMainTopics              |    8    |    15    |   25
 * maxSummaryLength (chars)   |  1000   |  2000    | 4000
 * memoryBudgetFraction       |  0.30   |   0.40   |  0.50
 * ```
 *
 * Preset trade-offs:
 * ```
 * Mode     | Token cost | Context quality | Best for
 * ---------|------------|-----------------|---------------------------
 * COMPACT  | Low        | Less detail     | Long sessions, cheap models
 * BALANCED | Medium     | Medium          | General use (default)
 * DETAIL   | High       | High fidelity   | Short sessions, coding
 * ```
 */
data class MemoryConfig(
    val mode: MemoryMode = MemoryMode.BALANCED,
    /**
     * Fraction of [memoryBudgetFraction] × context-window tokens at which summarization
     * is triggered. Lower values fire earlier. Range: 0.0–1.0.
     */
    val summarizationThreshold: Double = 0.40,
    /**
     * Number of the most-recent conversation messages always kept verbatim and
     * never included in a summarization batch.
     */
    val protectedRecentTurns: Int = 6,
    /**
     * Fraction of eligible (non-protected) messages pruned in each summarization cycle,
     * oldest first. Range: 0.0–1.0.
     */
    val summarizationPruneFraction: Double = 0.65,
    /**
     * Maximum number of distinct key facts retained in the structured conversation summary.
     */
    val maxKeyFacts: Int = 30,
    /**
     * Maximum number of distinct topic labels tracked across summary merges.
     */
    val maxMainTopics: Int = 15,
    /**
     * Character cap for the fallback extractive summary when AI summarization is unavailable.
     */
    val maxSummaryLength: Int = 2000,
    /**
     * Fraction of the model's total context window reserved for conversation history.
     * Range: 0.0–1.0.
     */
    val memoryBudgetFraction: Double = 0.40,
) {
    companion object {
        /** Returns the full preset [MemoryConfig] for the given [mode]. */
        fun preset(mode: MemoryMode): MemoryConfig = when (mode) {
            MemoryMode.COMPACT -> COMPACT
            MemoryMode.BALANCED -> BALANCED
            MemoryMode.DETAIL -> DETAIL
        }

        /** Aggressive summarization — lower token cost, less detail. */
        val COMPACT = MemoryConfig(
            mode = MemoryMode.COMPACT,
            summarizationThreshold = 0.25,
            protectedRecentTurns = 3,
            summarizationPruneFraction = 0.80,
            maxKeyFacts = 15,
            maxMainTopics = 8,
            maxSummaryLength = 1000,
            memoryBudgetFraction = 0.30,
        )

        /** Current defaults — good quality/cost trade-off. */
        val BALANCED = MemoryConfig(
            mode = MemoryMode.BALANCED,
            summarizationThreshold = 0.40,
            protectedRecentTurns = 6,
            summarizationPruneFraction = 0.65,
            maxKeyFacts = 30,
            maxMainTopics = 15,
            maxSummaryLength = 2000,
            memoryBudgetFraction = 0.40,
        )

        /** Minimal summarization — high fidelity, more tokens consumed. */
        val DETAIL = MemoryConfig(
            mode = MemoryMode.DETAIL,
            summarizationThreshold = 0.60,
            protectedRecentTurns = 10,
            summarizationPruneFraction = 0.50,
            maxKeyFacts = 50,
            maxMainTopics = 25,
            maxSummaryLength = 4000,
            memoryBudgetFraction = 0.50,
        )
    }
}

/**
 * RAG (Retrieval-Augmented Generation) configuration.
 * Controls how relevant documents are retrieved from the knowledge base.
 */
data class RagConfig(
    /** Maximum number of documents to retrieve from vector search */
    val vectorSearchMaxResults: Int = 20,
    /** Minimum similarity score for vector search results (0.0 to 1.0) */
    val vectorSearchMinScore: Double = 0.3,
    /** Maximum number of final documents to return after hybrid fusion */
    val hybridMaxResults: Int = 15,
    /** RRF constant for rank fusion algorithm (standard value is 60) */
    val rankFusionConstant: Int = 60,
    /** Use absolute file paths in citations (true) or relative filenames (false) */
    val useAbsolutePathInCitations: Boolean = true,
)

/**
 * Minimum allowed value (in seconds) for [ModelsConfig.requestTimeoutInSeconds].
 * Prevents users from configuring a timeout so low that every AI request fails immediately.
 */
const val MIN_MODEL_TIMEOUT_SECONDS: Long = 5

/**
 * Global model execution settings.
 *
 * Per-provider model names (utility, embedding, image, vision) are now stored directly on each
 * [ProviderInstance]'s [ProviderSettings]. This class intentionally no longer contains
 * per-provider fields — they were removed in favour of instance-level configuration so that
 * multiple instances of the same provider type can each have independent model assignments.
 *
 * Existing `askimo.yml` files that still contain per-provider sub-keys (e.g.
 * `models.ollama.embedding_model`) will deserialize silently — the unknown fields are ignored
 * by Jackson (`FAIL_ON_UNKNOWN_PROPERTIES = false`). No migration or data loss occurs.
 */
data class ModelsConfig(
    val maxToolCallingRoundTrips: Int = 50,
    /**
     * Single, unified request timeout (in seconds) applied to both the secondary/utility model
     * (title generation, RAG query compression, summarization) and the primary/streaming model.
     * Set generously to accommodate slow local models and cloud reasoning models with extended
     * thinking. Clamped to a minimum of [MIN_MODEL_TIMEOUT_SECONDS].
     */
    val requestTimeoutInSeconds: Long = 600,
    /**
     * How long (in milliseconds) [io.askimo.core.providers.sendStreamingMessageWithCallback]
     * waits for a tool-approval decision (`onToolApprovalRequired`'s `approve`/`deny`) before
     * treating the request as timed out and denying it automatically.
     */
    val toolApprovalTimeoutMs: Long = 120_000,
)

/**
 * Persisted state for Askimo's built-in local LLM engine (see
 * [io.askimo.core.providers.llama.LlamaServerProcessManager]), so the model that was actually
 * resident in the previous session is automatically reloaded the next time the app starts
 */
data class LocalLlmConfig(
    /**
     * [io.askimo.core.providers.llama.LlamaModelEntry.fileName] of the last model that was
     * actually loaded into the `llama-server` child process. Null if no model has ever been loaded.
     */
    val lastSelectedModelFileName: String? = null,
    /** [io.askimo.core.providers.llama.LlamaModelEntry.isEmbedding] for [lastSelectedModelFileName]. */
    val lastSelectedModelIsEmbedding: Boolean = false,
    /**
     * Explicit context-window size (`--ctx-size`) passed to every `llama-server` launch
     */
    val ctxSize: Int = 8192,
    /**
     * Number of transformer layers offloaded to GPU (`--n-gpu-layers`). `0` = fully CPU (the
     * default, matching [io.askimo.core.providers.llama.LlamaServerCatalog]'s CPU-only release
     * builds). Dormant today since no catalog build currently ships GPU acceleration, but kept
     * configurable so it's ready the moment a GPU-enabled build variant is added — the single
     * biggest lever on inference speed once available.
     */
    val nGpuLayers: Int = 0,
    /**
     * KV-cache quantization type for the K matrix (`--cache-type-k`), e.g. `f16`, `q8_0`, `q4_0`.
     * Quantizing the KV cache shrinks its memory footprint (roughly linear in [ctxSize] ×
     * layers × heads) — directly relevant on CPU-only hardware where KV cache, not compute, is
     * often the real limiter on how large [ctxSize] can safely go. `f16` (no quantization) is the
     * safe default; llama-server requires `--flash-attn` whenever either cache type is
     * non-`f16` — see [io.askimo.core.providers.llama.LlamaServerProcessManager] for where that's
     * applied automatically.
     */
    val cacheTypeK: String = "f16",
    /** KV-cache quantization type for the V matrix (`--cache-type-v`) — see [cacheTypeK]'s doc. */
    val cacheTypeV: String = "f16",
    /**
     * Prompt-processing (prefill) batch size (`--batch-size` / `-b`). Controls how many prompt
     * tokens llama-server processes per logical batch — the main knob on "time to first token"
     * for large prompts, which matters here since Askimo feeds RAG context + system prompt +
     * conversation history on every request (see
     * [io.askimo.core.memory.TokenAwareSummarizingMemory]). Larger values process prompts faster
     * at the cost of a bigger one-time memory/compute spike; llama-server's own default (2048)
     * is a safe starting point.
     */
    val batchSize: Int = 2048,
    /**
     * Physical micro-batch size (`--ubatch-size` / `-ub`) — the actual batch size used internally
     * per compute step; must be `<= batchSize`. llama-server's own default (512) works well
     * alongside the default [batchSize]; lowering it reduces peak memory during prompt processing
     * at some further throughput cost, useful on very memory-constrained machines.
     */
    val ubatchSize: Int = 512,
    /**
     * Enables Flash Attention kernels (`--flash-attn`) — reduces both memory and latency on
     * supported builds/hardware. Default `true` since it's effectively a free speedup when
     * supported; llama-server silently falls back to standard attention if unsupported. **Always**
     * forced on regardless of this value whenever [cacheTypeK] or [cacheTypeV] is non-`f16`
     * (hard llama-server requirement — see [io.askimo.core.providers.llama.LlamaServerProcessManager]).
     */
    val flashAttn: Boolean = true,
    /**
     * Disables memory-mapped model loading (`--no-mmap`) — forces the whole model into RAM up
     * front instead of lazily paging it in from disk. Slower initial load, but avoids mid-generation
     * stalls from page faults on memory-constrained or slow-disk machines, where the default mmap
     * behavior can look like a random hang. Default `false` (use mmap) since it's faster to load
     * and fine on most hardware.
     */
    val noMmap: Boolean = false,
    /**
     * Locks the model into physical RAM (`--mlock`), preventing the OS from swapping it to disk
     * under memory pressure. Reduces the same class of "random hang" symptom as [noMmap] from a
     * different angle (preventing eviction rather than avoiding lazy paging); costs that much
     * RAM permanently for as long as the model is loaded. Default `false`.
     */
    val mlock: Boolean = false,
    /**
     * Number of parallel request slots within a single `llama-server` child process
     * (`--parallel` / `-np`). Only relevant if more than one chat session ever hits the *same*
     * chat-slot process concurrently — see [io.askimo.core.providers.llama.LlamaServerProcessManager]'s
     * class doc for why chat vs. embedding contention is already eliminated by splitting into two
     * processes; this controls contention *within* one of those two processes. Default `1`
     * (serialize concurrent requests) since most desktop usage is single-session.
     */
    val numParallel: Int = 1,
    /**
     * Enables continuous batching (`--cont-batching`), letting llama-server interleave multiple
     * in-flight generations instead of fully serializing them. Only has an effect when
     * [numParallel] > 1. Default `true` (llama-server's own default) — negligible cost when
     * [numParallel] is 1.
     */
    val contBatching: Boolean = true,
    /**
     * Skips llama-server's internal warmup inference pass (`--no-warmup`), shaving a few seconds
     * off model load time at the cost of a slightly slower *first* real request (which then pays
     * the warmup cost instead). Default `false` (warm up during load, not during the user's first
     * message).
     */
    val noWarmup: Boolean = false,
    /**
     * NUMA optimization strategy (`--numa <mode>`) — one of `""` (not passed / disabled),
     * `distribute`, `isolate`, or `numactl`. Only matters on multi-socket/NUMA workstations;
     * harmless no-op elsewhere. Default `""` (disabled).
     */
    val numaMode: String = "",
    /**
     * Server-side cap on generated tokens per response (`--n-predict`). `-1` (the default) means
     * unlimited / not passed — generation length is instead controlled by the client request
     * (langchain4j's own max-tokens parameter) and natural stop tokens. A belt-and-suspenders
     * safety net more than a day-to-day tuning knob; most users should leave this at `-1`.
     */
    val nPredict: Int = -1,
    /**
     * RoPE frequency base override (`--rope-freq-base`) for extending a model's effective context
     * beyond its native trained length ("self-extend"). `0.0` (the default) means not passed —
     * use the model's own trained value. Generally reduces output quality the further beyond
     * native length you push, so leave at `0.0` unless you specifically need a larger [ctxSize]
     * than the model was trained for and accept the quality tradeoff.
     */
    val ropeFreqBase: Double = 0.0,
    /** RoPE frequency scale override (`--rope-freq-scale`) — paired with [ropeFreqBase] for the same self-extend use case; `0.0` means not passed. */
    val ropeFreqScale: Double = 0.0,
    /**
     * llama.cpp GitHub release tag the default download URLs below were mirrored from. **Not**
     * exposed in Settings UI — this (and every `*DownloadUrl` field below) is here purely so an
     * advanced user can see/hand-edit it directly in `askimo.yml` (`local_llm.release_tag`) if
     * they need to point at a different release or a mirror, without digging through source.
     * [io.askimo.core.providers.llama.LlamaServerDownloader.installedVersion] records whichever
     * tag was actually extracted, independent of this field.
     */
    val releaseTag: String = "b11433",
    /**
     * Base URL the default `*DownloadUrl` fields below are built from — the
     * `askimo-ai/askimo-thirdparties` repo, which mirrors upstream `ggml-org/llama.cpp` release
     * assets as a dedicated release per [releaseTag] (see `tools/bin/llama-release.sh`). Assets
     * are download exactly as-is (byte-for-byte, hence [macosSha256] etc. staying unchanged from
     * the upstream originals) — mirroring only protects against upstream GitHub release pruning
     * or asset-layout churn, not against needing re-verification of what's actually hosted.
     */
    val llamaMirrorBaseUrl: String = "https://github.com/askimo-ai/askimo-thirdparties/releases/download/llama-$releaseTag",
    /** Download URL for the macOS (arm64) `llama-server` release archive. Hand-editable in `askimo.yml` only — see [releaseTag]. */
    val macosDownloadUrl: String = "$llamaMirrorBaseUrl/llama-$releaseTag-bin-macos-arm64.tar.gz",
    /** Download URL for the Linux x86_64 `llama-server` release archive. Hand-editable in `askimo.yml` only — see [releaseTag]. */
    val linuxX86DownloadUrl: String = "$llamaMirrorBaseUrl/llama-$releaseTag-bin-ubuntu-x64.tar.gz",
    /** Download URL for the Linux aarch64 `llama-server` release archive. Hand-editable in `askimo.yml` only — see [releaseTag]. */
    val linuxArmDownloadUrl: String = "$llamaMirrorBaseUrl/llama-$releaseTag-bin-ubuntu-arm64.tar.gz",
    /** Download URL for the Windows x86_64 `llama-server` release archive. Hand-editable in `askimo.yml` only — see [releaseTag]. */
    val windowsX86DownloadUrl: String = "$llamaMirrorBaseUrl/llama-$releaseTag-bin-win-cpu-x64.zip",
    /** Download URL for the Windows aarch64 `llama-server` release archive. Hand-editable in `askimo.yml` only — see [releaseTag]. */
    val windowsArmDownloadUrl: String = "$llamaMirrorBaseUrl/llama-$releaseTag-bin-win-cpu-arm64.zip",
    /**
     * SHA-256 of the macOS (arm64) `llama-server` release archive for [releaseTag], pinned so
     * [io.askimo.core.providers.llama.LlamaServerDownloader.download] can verify archive
     * integrity before extraction — a `Content-Length` match alone only rules out truncation, not
     * a mirror/CDN serving substitute bytes of the exact expected length. Hand-editable in
     * `askimo.yml` only, alongside [macosDownloadUrl] — both must be updated together whenever
     * [releaseTag] (or a mirror URL) changes.
     */
    val macosSha256: String = "5e7b2383009facb31404f308cdb9edd1fb15131330801408fb582bd05e188f97",
    /** SHA-256 of the Linux x86_64 `llama-server` release archive for [releaseTag] — see [macosSha256]. */
    val linuxX86Sha256: String = "d6ff12a557793e302ddbba9660a0f045c403e2f67ab5dcdc1254b343c560c015",
    /** SHA-256 of the Linux aarch64 `llama-server` release archive for [releaseTag] — see [macosSha256]. */
    val linuxArmSha256: String = "9261c811ece17f236508edaa861042b40d8e673874f2eb3c3f524649dd4269fd",
    /** SHA-256 of the Windows x86_64 `llama-server` release archive for [releaseTag] — see [macosSha256]. */
    val windowsX86Sha256: String = "301943380e45deae3991d42dddd03fae65c6a4963a5d882328a60208b43aa9e0",
    /** SHA-256 of the Windows aarch64 `llama-server` release archive for [releaseTag] — see [macosSha256]. */
    val windowsArmSha256: String = "c415c3576dd673d31da874c57ef8863f9592b8f0e1f092290271cbaf619b2ade",
)

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
    SERPLY,
}

private const val WEB_SEARCH_KEY_BRAVE = "websearch.brave.key"
private const val WEB_SEARCH_KEY_TAVILY = "websearch.tavily.key"
private const val WEB_SEARCH_KEY_SERPLY = "websearch.serply.key"
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
    /** Raw field — may be blank or `***keychain***`. Use [AppConfig.webSearch] resolved accessors. */
    val serplyApiKey: String = "",
) {
    companion object {
        fun isKeyPlaceholder(value: String): Boolean = value == WEB_SEARCH_KEY_PLACEHOLDER
        fun isActualKey(value: String): Boolean = value.isNotBlank() && !isKeyPlaceholder(value)
        fun getSecureBraveKey(): String? = SecureKeyManager.retrieveSecretKey(WEB_SEARCH_KEY_BRAVE)
        fun getSecureTavilyKey(): String? = SecureKeyManager.retrieveSecretKey(WEB_SEARCH_KEY_TAVILY)
        fun getSecureSerplyKey(): String? = SecureKeyManager.retrieveSecretKey(WEB_SEARCH_KEY_SERPLY)
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
        fun setSecureSerplyKey(key: String): SecureKeyManager.StorageResult = if (key.isEmpty()) {
            SecureKeyManager.removeSecretKey(WEB_SEARCH_KEY_SERPLY)
            SecureKeyManager.StorageResult(success = true, method = SecureKeyManager.StorageMethod.KEYCHAIN)
        } else {
            SecureKeyManager.storeSecuredKey(WEB_SEARCH_KEY_SERPLY, key)
        }
        fun getKeyPlaceholder(): String = WEB_SEARCH_KEY_PLACEHOLDER
    }
}

/**
 * Supported speech-to-text / text-to-speech backends.
 *
 * Voice is a convenience layer on top of the existing text chat pipeline (see
 * [io.askimo.core.providers.sendStreamingMessageWithCallback]) and is resolved
 * **independently** of the active chat [io.askimo.core.providers.ModelProvider] —
 * e.g. a user can chat with Gemini while using OpenAI Whisper for transcription,
 * or a fully local/free STT+TTS stack.
 *
 * - [NONE]: Explicitly disables this direction only — e.g. dictation (STT) with no
 *   auto-played responses, or auto-play (TTS) without a microphone. Distinct from
 *   [VoiceConfig.enabled] = false, which turns off voice entirely (both directions, no UI).
 * - [OPENAI]: Cloud API (Whisper for STT, OpenAI TTS for TTS). Requires an API key.
 * - [LOCAL_WHISPER_CPP]: STT-only. Talks to a user-hosted whisper.cpp/faster-whisper
 *   server exposing an OpenAI-compatible `/v1/audio/transcriptions` endpoint. Free, offline-capable.
 * - [LOCAL_WHISPER_FFM]: STT-only. Runs whisper.cpp **in-process** via the Java Foreign Function
 *   & Memory API (no separate server process) — see `io.askimo.ui.voice.impl.whispercpp` in
 *   `desktop-shared`. Requires a downloaded `ggml-*.bin` model file ([VoiceConfig.localWhisperModelPath]).
 *   Free, fully offline, lowest latency of the local options (no HTTP hop).
 * - [LOCAL_PIPER]: TTS-only. Talks to a user-hosted Piper HTTP server. Free, offline-capable.
 * - [SYSTEM]: TTS-only. Shells out to the operating system's built-in TTS engine (macOS `say`,
 *   Windows SAPI via PowerShell, Linux `espeak-ng`). Free, fully offline, zero configuration —
 *   but voice quality is more robotic than OpenAI/Piper's neural voices, and requires `espeak-ng`
 *   to be installed on Linux (macOS/Windows ship a TTS engine out of the box).
 */
enum class VoiceProvider {
    NONE,
    OPENAI,
    LOCAL_WHISPER_CPP,
    LOCAL_WHISPER_FFM,
    LOCAL_PIPER,
    SYSTEM,
}

private const val VOICE_OPENAI_KEY = "voice.openai.key"
private const val VOICE_KEY_PLACEHOLDER = "***keychain***"

/**
 * Configuration for the optional voice input/output feature.
 * Lives under the `voice:` key in askimo.yml. Disabled by default — zero impact on existing users.
 *
 * The OpenAI API key used for voice is intentionally **separate** from any `OPENAI`
 * [io.askimo.core.providers.ProviderInstance] key — a user may chat exclusively with
 * another provider (Anthropic, Gemini, Ollama...) yet still want OpenAI Whisper/TTS for voice.
 * [useProviderKeyForVoice] offers a convenience toggle so the Settings UI can optionally
 * reuse an existing OpenAI provider instance's key instead of requiring the user to paste it again.
 *
 * API keys are stored securely via [SecureKeyManager] — the YAML holds a
 * `***keychain***` placeholder, never the raw key value.
 */
data class VoiceConfig(
    val enabled: Boolean = false,
    val sttProvider: VoiceProvider = VoiceProvider.LOCAL_WHISPER_FFM,
    val ttsProvider: VoiceProvider = VoiceProvider.OPENAI,
    val sttModel: String = "whisper-1",
    val ttsModel: String = "tts-1",
    val ttsVoice: String = "alloy",
    /**
     * Playback speed multiplier passed to the TTS provider's `speed` parameter.
     * OpenAI's `/v1/audio/speech` accepts 0.25–4.0 (1.0 = normal speed). Applied the same way
     * for [VoiceProvider.LOCAL_PIPER] since its OpenAI-compatible endpoint accepts the same field.
     */
    val ttsSpeed: Double = 1.0,
    /**
     * When true and [openAiApiKey] is blank, Settings UI may offer to reuse the key from an
     * existing `OPENAI` provider instance instead of requiring a separate paste. Purely a UX
     * convenience flag — resolution of the actual key happens at the call site, not here.
     */
    val useProviderKeyForVoice: Boolean = true,
    /** Raw field — may be blank or `***keychain***`. Use [AppConfig.voice] for the resolved value. */
    val openAiApiKey: String = "",
    /** Base URL of a user-hosted whisper.cpp/faster-whisper server (OpenAI-compatible API). */
    val localSttEndpoint: String = "http://localhost:8081",
    /**
     * Absolute path to a downloaded `ggml-*.bin` whisper.cpp model file, used only when
     * [sttProvider] is [VoiceProvider.LOCAL_WHISPER_FFM]. Blank means no model has been
     * downloaded yet — the Settings UI shows a "Download model" prompt in that case instead
     * of allowing the provider to be used. Downloaded models live under
     * `io.askimo.core.util.AskimoHome.whisperModelsDir()` so they survive app updates/reinstalls.
     */
    val localWhisperModelPath: String = "",
    /**
     * Base URL (no trailing slash) the Settings UI downloads [io.askimo.ui.voice.impl.whispercpp.WhisperModelCatalog]
     * `ggml-*.bin` files from. Defaults to the official `ggerganov/whisper.cpp` Hugging Face repo;
     * configurable so users behind a mirror/proxy or in a region where huggingface.co is blocked
     * can point this at an alternate host serving the same filenames.
     */
    val whisperModelBaseUrl: String = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main",
    /** Base URL of a user-hosted Piper HTTP server. */
    val localTtsEndpoint: String = "http://localhost:5000",
    /**
     * When true, stopping a voice dictation recording automatically sends the transcribed
     * text as a chat message instead of just inserting it into the input field for review —
     * makes the whole record → transcribe → send flow fully hands-free. Off by default so
     * users can proofread/edit the transcript before sending.
     */
    val autoSendTranscript: Boolean = false,
    /**
     * When true, a completed AI response is automatically spoken aloud (via [ttsProvider])
     * as soon as it finishes streaming — no need to click the 🔊 play button on each message.
     * Off by default; the per-message 🔊 button remains available either way.
     */
    val autoPlayResponses: Boolean = false,
    /**
     * When true, a voice-dictation recording automatically stops (and transcribes) once the
     * user has paused speaking for [autoStopSilenceDurationMs], instead of requiring a manual
     * click/shortcut to stop — mirrors Siri/Alexa-style "speak, then it just knows you're done."
     * Defaults on: at the default 1.5s pause threshold, false positives from normal mid-sentence
     * pauses are unlikely, and manual click-to-stop remains available at any time as an override.
     */
    val autoStopOnSilence: Boolean = true,
    /**
     * How long (ms) the mic must stay below the RMS silence threshold before an in-progress
     * recording is auto-stopped, when [autoStopOnSilence] is enabled. Only takes effect after
     * at least one burst of actual speech was detected, so a recording that opens into silence
     * (e.g. a slow mic permission prompt) doesn't immediately auto-finalize into an empty/no-op
     * transcription.
     */
    val autoStopSilenceDurationMs: Long = 1_500L,
    /**
     * Voice names accepted by OpenAI's `/v1/audio/speech` `voice` parameter — shown as a
     * dropdown (not free text) in Settings > Voice since any other value is rejected by the
     * API. Kept here (rather than hardcoded in the UI) so that when OpenAI ships a new voice,
     * users can add it to `askimo.yml` (`voice.open_ai_tts_voices`) themselves and pick it up
     * immediately, without waiting for an Askimo release.
     */
    val openAiTtsVoices: List<String> = listOf(
        "alloy", "ash", "coral", "echo", "fable", "onyx", "nova", "sage", "shimmer", "verse",
    ),
    /**
     * Max number of distinct AI messages whose synthesized TTS audio is kept in the in-memory
     * playback cache (see `VoicePlaybackController`), so replaying/stopping a recently-played
     * response (e.g. via the "toggle last response playback" shortcut) doesn't re-hit the TTS
     * API. Purely a runtime, app-lifetime cache — never persisted to disk. Oldest entry is
     * evicted first once this cap (or [ttsCacheMaxBytes]) is exceeded.
     */
    val ttsCacheMaxMessages: Int = 5,
    /** Max total bytes of synthesized audio kept across all cached messages. See [ttsCacheMaxMessages]. */
    val ttsCacheMaxBytes: Long = 4L * 1024 * 1024,
) {
    /**
     * Whether STT (microphone/dictation) is actually usable, not just "configured".
     *
     * [VoiceProvider.LOCAL_WHISPER_FFM] (the default [sttProvider]) also needs a downloaded
     * `ggml-*.bin` model file ([localWhisperModelPath]) — without this check the mic would be
     * enabled out of the box and only fail after recording.
     */
    fun isSttAvailable(): Boolean {
        if (!enabled || sttProvider == VoiceProvider.NONE) return false
        if (sttProvider == VoiceProvider.LOCAL_WHISPER_FFM) {
            return localWhisperModelPath.isNotBlank() &&
                runCatching { Files.exists(Path.of(localWhisperModelPath)) }.getOrDefault(false)
        }
        return true
    }

    companion object {
        fun isKeyPlaceholder(value: String): Boolean = value == VOICE_KEY_PLACEHOLDER
        fun isActualKey(value: String): Boolean = value.isNotBlank() && !isKeyPlaceholder(value)
        fun getSecureOpenAiKey(): String? = SecureKeyManager.retrieveSecretKey(VOICE_OPENAI_KEY)
        fun setSecureOpenAiKey(key: String): SecureKeyManager.StorageResult = if (key.isEmpty()) {
            SecureKeyManager.removeSecretKey(VOICE_OPENAI_KEY)
            SecureKeyManager.StorageResult(success = true, method = SecureKeyManager.StorageMethod.KEYCHAIN)
        } else {
            SecureKeyManager.storeSecuredKey(VOICE_OPENAI_KEY, key)
        }
        fun getKeyPlaceholder(): String = VOICE_KEY_PLACEHOLDER
    }
}

/**
 * Configuration for the optional OS-level "chat response completed" notification feature.
 * Lives under the `notifications:` key in askimo.yml.
 *
 * Enabled by default (opt-out) since it's a low-friction quality-of-life feature — unlike
 * [VoiceConfig] it requires no API keys/setup and has no meaningful side effects when on.
 */
data class NotificationsConfig(
    val enabled: Boolean = true,
    /**
     * When true (default), the OS notification is only shown while the Askimo window does not
     * have OS focus — i.e. the user has switched away to another app. When false, a notification
     * is shown every time a chat response completes, regardless of focus.
     */
    val onlyWhenUnfocused: Boolean = true,
    /**
     * When true, the notification body includes a short plain-text preview of the completed
     * response (see `ChatCompletedEvent.preview`). Off by default for privacy — e.g. so a
     * response isn't shown on a locked/shared screen — leaving only a generic
     * "Response ready"/"Response failed" message.
     */
    val showDetails: Boolean = false,
)

/**
 * Configuration for the inline chat-input autocomplete ("ghost text") feature.
 * Lives under the `suggestions:` key in askimo.yml.
 *
 * History-based suggestions ([io.askimo.core.chat.service.HistoryBackedSuggestionService])
 * are always on — free, local-only, no AI call — so there's nothing to gate there.
 *
 * [aiExtractionEnabled] gates the opt-in AI enhancement: when true, `PhraseHabitRefreshService`
 * periodically sends a sample of recent messages to the active AI provider — which may be a
 * cloud service — to learn generalized "typing habit" phrases, served by
 * [io.askimo.core.chat.service.PersistedPhraseSuggestionService]. Off by default — the only
 * part of this feature that sends chat content off-device.
 */
data class SuggestionsConfig(
    val aiExtractionEnabled: Boolean = false,
)

data class AppConfigData(
    val embedding: EmbeddingConfig = EmbeddingConfig(),
    val retry: RetryConfig = RetryConfig(),
    val throttle: ThrottleConfig = ThrottleConfig(),
    val indexing: IndexingConfig = IndexingConfig(),
    val developer: DeveloperConfig = DeveloperConfig(),
    val chat: ChatConfig = ChatConfig(),
    val memory: MemoryConfig = MemoryConfig(),
    val rag: RagConfig = RagConfig(),
    val models: ModelsConfig = ModelsConfig(),
    val localLlm: LocalLlmConfig = LocalLlmConfig(),
    val proxy: ProxyConfig = ProxyConfig(),
    val analytics: AnalyticsConfig = AnalyticsConfig(),
    val webSearch: WebSearchConfig = WebSearchConfig(),
    val voice: VoiceConfig = VoiceConfig(),
    val notifications: NotificationsConfig = NotificationsConfig(),
    val suggestions: SuggestionsConfig = SuggestionsConfig(),
    val context: AppContextParams = AppContextParams.noOp(),
    val currentLocale: String? = null,
)

object AppConfig {
    val embedding: EmbeddingConfig get() = delegate.embedding
    val retry: RetryConfig get() = delegate.retry
    val indexing: IndexingConfig get() = delegate.indexing
    val developer: DeveloperConfig get() = delegate.developer
    val chat: ChatConfig get() = delegate.chat
    val memory: MemoryConfig get() = delegate.memory
    val rag: RagConfig get() = delegate.rag
    val models: ModelsConfig get() = delegate.models
    val localLlm: LocalLlmConfig get() = delegate.localLlm
    val context: AppContextParams get() = delegate.context
    val analytics: AnalyticsConfig get() = delegate.analytics

    /**
     * Raw web search configuration **without** keychain lookup.
     * API key fields may be blank or `***keychain***`.
     */
    val rawWebSearch: WebSearchConfig get() = delegate.webSearch

    /**
     * Web search configuration with the API key for the **currently selected** [WebSearchConfig.backend]
     * resolved from secure storage. Safe to pass directly to backends.
     *
     * Only the active backend's key is resolved — keys for other (unused) backends are left as-is
     * (blank or a `***keychain***` placeholder) to avoid unnecessary keychain/secure-storage lookups.
     */
    val webSearch: WebSearchConfig
        get() {
            val config = delegate.webSearch
            return when (config.backend) {
                WebSearchBackend.BRAVE -> {
                    if (!WebSearchConfig.isActualKey(config.braveApiKey)) {
                        val secureKey = WebSearchConfig.getSecureBraveKey()
                        if (secureKey != null) config.copy(braveApiKey = secureKey) else config
                    } else {
                        config
                    }
                }

                WebSearchBackend.TAVILY -> {
                    if (!WebSearchConfig.isActualKey(config.tavilyApiKey)) {
                        val secureKey = WebSearchConfig.getSecureTavilyKey()
                        if (secureKey != null) config.copy(tavilyApiKey = secureKey) else config
                    } else {
                        config
                    }
                }

                WebSearchBackend.SERPLY -> {
                    if (!WebSearchConfig.isActualKey(config.serplyApiKey)) {
                        val secureKey = WebSearchConfig.getSecureSerplyKey()
                        if (secureKey != null) config.copy(serplyApiKey = secureKey) else config
                    } else {
                        config
                    }
                }

                WebSearchBackend.DUCKDUCKGO, WebSearchBackend.SEARXNG -> config
            }
        }

    /**
     * Raw voice configuration **without** keychain lookup.
     * [VoiceConfig.openAiApiKey] may be blank or `***keychain***`.
     */
    val rawVoice: VoiceConfig get() = delegate.voice

    /**
     * Voice configuration with the OpenAI API key resolved from secure storage.
     * Safe to pass directly to voice STT/TTS services.
     */
    val voice: VoiceConfig
        get() {
            val config = delegate.voice
            val apiKey = if (!VoiceConfig.isActualKey(config.openAiApiKey)) {
                VoiceConfig.getSecureOpenAiKey() ?: ""
            } else {
                config.openAiApiKey
            }
            return if (apiKey != config.openAiApiKey) config.copy(openAiApiKey = apiKey) else config
        }

    /**
     * BCP-47 language tag of the user's selected UI locale (e.g. "ja-JP", "zh-CN").
     * Null means no preference has been saved yet — callers should treat null as English.
     * Stored in [AppConfigData.currentLocale] under the `current_locale:` YAML key.
     */
    val currentLocale: String? get() = delegate.currentLocale

    /**
     * Configuration for the optional OS-level "chat response completed" notification feature.
     * See [NotificationsConfig] for field semantics.
     */
    val notifications: NotificationsConfig get() = delegate.notifications

    /**
     * Configuration for the inline chat-input autocomplete ("ghost text") feature.
     * See [SuggestionsConfig] for field semantics.
     */
    val suggestions: SuggestionsConfig get() = delegate.suggestions

    /**
     * Raw proxy configuration **without** keychain/secure-storage lookup.
     * All fields except password are accurate; password may be a placeholder (`***keychain***`).
     */
    val rawProxy: ProxyConfig get() = delegate.proxy

    /**
     * Proxy configuration with password loaded from secure storage.
     * If password is a placeholder (***keychain***), loads actual password from keychain/encrypted storage.
     * No-op (skips the keychain lookup) when the proxy is disabled ([ProxyType.NONE]), since the
     * password is irrelevant in that case.
     */
    val proxy: ProxyConfig
        get() {
            val config = delegate.proxy

            // Proxy disabled — no need to resolve a password that will never be used.
            if (config.type == ProxyType.NONE) {
                return config
            }

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
    // NOTE: sparse (overrides-only) writing is implemented manually via sparseDiff() below rather
    // than Jackson's built-in JsonInclude.Include.NON_DEFAULT — that inclusion mode needs a plain
    // no-arg Java constructor to build its internal "default instance" for comparison, which
    // Kotlin data classes with all-default constructor params don't expose (Kotlin instead
    // generates a synthetic bitmask-based constructor only usable via jackson-module-kotlin's own
    // KotlinValueInstantiator) — so NON_DEFAULT silently degrades to a null-check and doesn't
    // actually diff values, which was verified to leave entire untouched sections in the output.

    // Default YAML written on first run if no config exists
    private val DEFAULT_YAML =
        """
        # Askimo application configuration
        # This file was auto-generated because none was found.

        embedding:
          max_chars_per_chunk: 4000
          chunk_overlap: 200

        retry:
          attempts: 4
          base_delay_ms: 150

        throttle:
          per_request_sleep_ms: 30

        indexing:
          max_file_bytes: 2000000
          concurrent_indexing_threads: 10
          supported_extensions: java,kt,kts,py,js,ts,jsx,tsx,go,rs,c,cpp,h,hpp,cs,rb,php,swift,scala,groovy,sh,bash,yaml,yml,json,xml,md,txt,gradle,properties,toml,pdf
          binary_extensions: png,jpg,jpeg,gif,svg,ico,webp,bmp,mp4,avi,mov,mkv,mp3,wav,ogg,flac,zip,tar,gz,7z,rar,exe,dll,so,dylib,bin,db,sqlite,doc,docx,xls,xlsx,ppt,pptx,ttf,otf,woff,woff2,class,jar,pyc,icns
          exclude_file_names: .DS_Store,Thumbs.db,desktop.ini,package-lock.json,yarn.lock,pnpm-lock.yaml,poetry.lock,Gemfile.lock,.project,.classpath,.factorypath
          common_excludes: .git/,.svn/,.hg/,.idea/,.vscode/,.DS_Store,*.log,*.tmp,*.temp,*.swp,*.bak,.history/
          filters:
            gitignore: true
            dockerignore: false
            projecttype: true
            binary: true
            filesize: true
            custom: true

        chat:
          max_tokens: 8000
          default_response_ai_locale:

        memory:
          mode: BALANCED
          summarization_threshold: 0.40
          protected_recent_turns: 6
          summarization_prune_fraction: 0.65
          max_key_facts: 30
          max_main_topics: 15
          max_summary_length: 2000
          memory_budget_fraction: 0.40

        rag:
          vector_search_max_results: 20
          vector_search_min_score: 0.3
          hybrid_max_results: 15
          rank_fusion_constant: 60
          use_absolute_path_in_citations: true

        models:
          max_tool_calling_round_trips: 10
          request_timeout_in_seconds: 600
          tool_approval_timeout_ms: 120000

        proxy:
          type: NONE
          host:
          port: 8080
          username:
          password:

        developer:
          enabled: true
          active: false

        analytics:
          opted_in: false
          endpoint: https://analytics.askimo.chat/ingest

        web_search:
          backend: DUCKDUCKGO
          searxng_endpoint: https://searx.be
          brave_api_key:
          tavily_api_key:
          serply_api_key:
          enabled: true

        voice:
          enabled: false
          stt_provider: OPENAI
          tts_provider: OPENAI
          stt_model: whisper-1
          tts_model: tts-1
          tts_voice: alloy
          tts_speed: 1.0
          use_provider_key_for_voice: true
          open_ai_api_key:
          local_stt_endpoint: http://localhost:8081
          local_tts_endpoint: http://localhost:5000
          open_ai_tts_voices:
            - alloy
            - ash
            - coral
            - echo
            - fable
            - onyx
            - nova
            - sage
            - shimmer
            - verse

        suggestions:
          ai_extraction_enabled: false

        context:
          current_instance_id: ""
          provider_instances: []

        current_locale:
        """.trimIndent()

    // Lazy, thread-safe init
    private val delegate: AppConfigData
        get() =
            cached ?: synchronized(this) {
                cached ?: loadOnce().also { cached = it }
            }

    private fun loadOnce(): AppConfigData {
        val path = resolveOrCreateConfigPath()
        val result = if (path != null && path.isRegularFile()) {
            val raw = Files.readString(path)
            try {
                val overrideTree = mapper.readTree(raw)
                val migratedProviders = migrateLegacyProviders(overrideTree)
                val migratedLocalLlm = migrateStaleLocalLlmProvenance(overrideTree)

                // Merge the current build's compiled-in defaults (base layer, always fresh — see
                // class doc) with the user's on-disk overrides (override layer, wins per-field) —
                // this is what lets a newer Askimo version's updated defaults (e.g. a bumped
                // llama.cpp mirror URL) reach existing users automatically, without ever having
                // persisted those defaults into askimo.yml in the first place. See deepMerge.
                //
                // Deliberately done BEFORE the migration write-back below: treeToValue can throw
                // (e.g. a hand-edited invalid field value), and an invalid config must never be
                // partially rewritten on disk — only a config that loads successfully end-to-end
                // gets its migration applied and persisted.
                val baseTree = mapper.valueToTree<JsonNode>(AppConfigData())
                val mergedTree = deepMerge(baseTree, overrideTree)
                val loaded = mapper.treeToValue(mergedTree, AppConfigData::class.java)
                val normalized = loaded.copy(memory = normalizeMemoryConfig(loaded.memory))

                if (migratedProviders || migratedLocalLlm) {
                    log.info(
                        "Migrated askimo.yml (legacy provider instances: {}, stale local_llm provenance: {})",
                        migratedProviders,
                        migratedLocalLlm,
                    )
                    try {
                        Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(overrideTree))
                    } catch (e: Exception) {
                        log.error("Failed to write migrated config", e)
                    }
                }
                normalized
            } catch (e: Exception) {
                log.error("Config parse failed at $path ", e)
                AppConfigData()
            }
        } else {
            AppConfigData()
        }
        writeEffectiveConfigSnapshot(result)
        return result
    }

    /**
     * Recursively merges [override] onto [base]: for every key present in [override], its value
     * wins — recursing into nested objects so a single overridden leaf field (e.g.
     * `local_llm.macos_download_url`) doesn't blow away sibling fields from [base] that the user
     * never touched. Non-object values (including arrays/collections) in [override] fully replace
     * the corresponding [base] value rather than attempting an element-wise merge.
     */
    private fun deepMerge(base: JsonNode, override: JsonNode): JsonNode {
        if (base !is ObjectNode || override !is ObjectNode) return override
        val merged = base.deepCopy<ObjectNode>()
        for ((key, overrideValue) in override.properties()) {
            val baseValue = merged.get(key)
            merged.replace(key, if (baseValue != null) deepMerge(baseValue, overrideValue) else overrideValue)
        }
        return merged
    }

    /**
     * Write-side counterpart of [deepMerge]: recursively computes the subset of [current] that
     * differs from [default], returning `null` when they're equal. Used by [writeSparseConfig]
     * so updateField/saveContext only ever persist genuine overrides to askimo.yml, never fields
     * equal to the current build's compiled defaults — see that function's doc for why this is
     * hand-rolled instead of using Jackson's `JsonInclude.Include.NON_DEFAULT`.
     */
    private fun sparseDiff(current: JsonNode, default: JsonNode): JsonNode? {
        if (current == default) return null
        if (current !is ObjectNode || default !is ObjectNode) return current
        val result = mapper.createObjectNode()
        for ((key, currentValue) in current.properties()) {
            val defaultValue = default.get(key)
            if (defaultValue == null) {
                result.replace(key, currentValue)
            } else {
                val diff = sparseDiff(currentValue, defaultValue)
                if (diff != null) result.replace(key, diff)
            }
        }
        return if (result.isEmpty) null else result
    }

    /**
     * Serializes [data] to askimo.yml, keeping only fields that differ from [AppConfigData]'s
     * current compiled defaults (via [sparseDiff]) — so saving one setting never bakes untouched
     * sections (e.g. `local_llm`'s download URLs/checksums) into the file, which would otherwise
     * permanently freeze out a later Askimo version's updated defaults for anything the user
     * never explicitly touched. This is the write-side half of the fix; [loadOnce]'s
     * compiled-defaults merge is the read-side half. Best-effort: failures are logged, never
     * thrown — the in-memory [cached] value stays authoritative for the rest of this process
     * either way, it just won't survive a restart if the write fails.
     */
    private fun writeSparseConfig(configPath: Path, data: AppConfigData) {
        try {
            val currentTree = mapper.valueToTree<JsonNode>(data)
            val defaultTree = mapper.valueToTree<JsonNode>(AppConfigData())
            val sparseTree = sparseDiff(currentTree, defaultTree) ?: mapper.createObjectNode()
            Files.writeString(configPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sparseTree))
        } catch (e: Exception) {
            log.error("Failed to persist config to $configPath", e)
        }
    }

    /**
     * One-time, self-terminating cleanup: strips `local_llm`'s download-provenance fields
     * (`release_tag`, the five `*_download_url` fields, the five `*_sha256` fields) from a user's
     * persisted askimo.yml, but **only when every single one of those 11 fields is present
     * together** — a deliberate hand-edit (the documented, supported way to point at a custom
     * mirror) realistically only ever touches one or two of these fields, never reconstructs the
     * entire block from scratch, so requiring the full set is what distinguishes "the pre-fix
     * AppConfig bug froze the *entire* LocalLlmConfig object into this file" from "the user
     * intentionally overrode one field" — stripping on partial presence would destroy the latter.
     * Naturally idempotent: once stripped, there's nothing left to strip on the next launch, so
     * no separate cache/marker file is needed — this check *is* the cache signal, matching
     * [migrateLegacyProviders]'s existing pattern. Never touches genuinely user-editable
     * local_llm fields (ctx_size, cache_type_k, last_selected_model_file_name, etc.).
     */
    private fun migrateStaleLocalLlmProvenance(config: JsonNode): Boolean {
        val localLlm = config.path("local_llm") as? ObjectNode ?: return false
        val provenanceFields = listOf(
            "release_tag",
            "macos_download_url", "linux_x86_download_url", "linux_arm_download_url",
            "windows_x86_download_url", "windows_arm_download_url",
            "macos_sha256", "linux_x86_sha256", "linux_arm_sha256",
            "windows_x86_sha256", "windows_arm_sha256",
        )
        if (!provenanceFields.all { localLlm.has(it) }) return false
        provenanceFields.forEach { localLlm.remove(it) }
        return true
    }

    /**
     * Writes a snapshot of the fully-resolved effective configuration (compiled defaults merged
     * with the user's askimo.yml overrides, i.e. exactly what [loadOnce] just computed) to
     * `askimo.effective.yml` next to askimo.yml — purely for human/support inspection (e.g.
     * "what llama.cpp mirror URL is this install actually using right now").
     *
     * **Never read back by the app** (not referenced anywhere in [loadOnce] or [resolveOrCreateConfigPath]) —
     * the header comment says so explicitly — so a stale, hand-edited, or corrupted copy of this
     * file can never affect runtime behavior. Best-effort: failures are logged, never thrown,
     * since this is a convenience file and must never block startup.
     */
    private fun writeEffectiveConfigSnapshot(data: AppConfigData) {
        try {
            val snapshotPath = AskimoHome.base().resolve("askimo.effective.yml")
            val header =
                "# Snapshot of the currently effective Askimo configuration (compiled defaults\n" +
                    "# merged with your askimo.yml overrides) — regenerated on every launch.\n" +
                    "# DO NOT EDIT: this file is NEVER read back by the app. To override a value,\n" +
                    "# edit askimo.yml instead.\n\n"
            val body = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data)
            Files.writeString(snapshotPath, header + body)
        } catch (e: Exception) {
            log.debug("Failed to write effective config snapshot: ${e.message}")
        }
    }

    private fun migrateLegacyProviders(config: JsonNode): Boolean {
        val instances = config.path("context").path("provider_instances")
        if (!instances.isArray) return false

        var migrated = false
        for (node in instances) {
            val instance = node as? ObjectNode ?: continue
            val templateName = when (instance.path("provider_type").asText()) {
                "DOCKER", "DOCKER_AI" -> "DOCKER_AI"
                "LMSTUDIO" -> "LMSTUDIO"
                "OLLAMA" -> "OLLAMA"
                "LOCALAI" -> "LOCALAI"
                else -> continue
            }
            val settings = instance.get("settings") as? ObjectNode
            if (settings != null && settings.path("template_name").asText("").isBlank()) {
                settings.put("template_name", templateName)
            }
            instance.put("provider_type", ModelProvider.OPENAI_COMPATIBLE.name)
            migrated = true
        }
        return migrated
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
            log.error("Failed to create default config at $target ", e)
        }
    }

    /**
     * Persists the given [AppContextParams] into the in-memory cache and YAML file,
     * replacing the current `context:` section.
     * API keys are sanitised via [SecureSessionManager] before writing to disk.
     */
    fun saveContext(params: AppContextParams) {
        synchronized(this) {
            val sanitized = secureSessionManager.saveSecureSession(params)

            // ASKIMO_PRO instances carry a transient accessToken and a type not registered
            // in shared's @JsonSubTypes — strip them before persisting to disk.
            val persistable = sanitized.copy(
                providerInstances = sanitized.providerInstances
                    .filter { it.providerType != ModelProvider.ASKIMO_PRO }
                    .toMutableList(),
            )

            val current = cached ?: loadOnce()

            cached = current.copy(context = sanitized)

            val configPath = resolveOrCreateConfigPath()
            if (configPath != null && configPath.exists()) {
                writeSparseConfig(configPath, current.copy(context = persistable))
                log.info("Saved context to $configPath")
            }
        }
    }

    /**
     * Generic method to update any config field and persist to YAML file.
     *
     * [cached] is updated with the new value immediately (synchronous, in-memory). The YAML
     * write to disk is best-effort: failures (e.g. disk full, permissions, a locked file) are
     * caught and logged here rather than thrown, so callers never need to handle a persistence
     * exception. In that case the new value remains active for the rest of this process but
     * won't survive a restart — the previous value will be reloaded from disk instead.
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
                    writeSparseConfig(configPath, cached!!)
                    log.debug("Updated currentLocale=$tag in $configPath")
                }
                return
            }

            val parts = path.split(".")

            if (parts.size !in 2..3) {
                log.error("Invalid config path: $path. Must be in format 'section.field' or 'models.provider.field'", null)
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

                "memory" -> current.copy(memory = updateMemoryField(current.memory, field, value))

                "rag" -> current.copy(rag = updateRagField(current.rag, field, value))

                "models" -> current.copy(models = updateModelsField(current.models, field, value))

                "localLlm" -> current.copy(localLlm = updateLocalLlmField(current.localLlm, field, value))

                "proxy" -> current.copy(proxy = updateProxyField(current.proxy, field, value))

                "analytics" -> current.copy(analytics = updateAnalyticsField(current.analytics, field, value))

                "indexing" -> current.copy(indexing = updateIndexingField(current.indexing, field, value))

                "webSearch" -> current.copy(webSearch = updateWebSearchField(current.webSearch, field, value))

                "voice" -> current.copy(voice = updateVoiceField(current.voice, field, value))

                "notifications" -> current.copy(notifications = updateNotificationsField(current.notifications, field, value))

                "suggestions" -> current.copy(suggestions = updateSuggestionsField(current.suggestions, field, value))

                else -> {
                    log.error("Unknown config section: $section", null)
                    return
                }
            }

            val configPath = resolveOrCreateConfigPath()
            if (configPath != null && configPath.exists()) {
                writeSparseConfig(configPath, cached!!)
                log.debug("Updated {}={} in {}", path, value, configPath)
            }
        }
    }

    /**

     * Sets [ModelsConfig.requestTimeoutInSeconds] — the unified request timeout control
     * surfaced in Settings > Advanced, applied to both the utility model (title generation,
     * RAG query compression, summarization) and the default/primary model.
     *
     * [seconds] is clamped to a minimum of [MIN_MODEL_TIMEOUT_SECONDS] to prevent
     * misconfiguration that would make every AI request fail immediately.
     */
    fun setGlobalModelTimeoutSeconds(seconds: Long) {
        val clamped = seconds.coerceAtLeast(MIN_MODEL_TIMEOUT_SECONDS)
        updateField("models.requestTimeoutInSeconds", clamped)
    }

    /**
     * After YAML deserialization, checks whether [MemoryConfig] fields are consistent with
     * the selected [MemoryMode]. If a non-BALANCED mode is selected but all numeric fields
     * still hold the BALANCED defaults (e.g. the YAML had `null` values that Jackson defaulted),
     * the config is auto-resolved to the correct mode preset and a warning is logged.
     */
    private fun normalizeMemoryConfig(memory: MemoryConfig): MemoryConfig {
        if (memory.mode == MemoryMode.BALANCED) return memory
        val balanced = MemoryConfig.BALANCED
        val looksDefaulted = memory.summarizationThreshold == balanced.summarizationThreshold &&
            memory.protectedRecentTurns == balanced.protectedRecentTurns &&
            memory.summarizationPruneFraction == balanced.summarizationPruneFraction &&
            memory.maxKeyFacts == balanced.maxKeyFacts &&
            memory.maxMainTopics == balanced.maxMainTopics &&
            memory.maxSummaryLength == balanced.maxSummaryLength &&
            memory.memoryBudgetFraction == balanced.memoryBudgetFraction
        return if (looksDefaulted) {
            val resolved = MemoryConfig.preset(memory.mode)
            log.warn("Memory config fields appear unset (defaulted to BALANCED) but mode=${memory.mode}. Auto-resolving to ${memory.mode} preset.")
            resolved
        } else {
            memory
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

        "defaultResponseAILocale" -> {
            val newLocale = if (value is String && value.isBlank()) null else value as? String
            EventBus.post(
                LanguageDirectiveChangedEvent(localeString = newLocale),
            )
            config.copy(defaultResponseAILocale = newLocale)
        }

        else -> config
    }

    private fun updateMemoryField(config: MemoryConfig, field: String, value: Any): MemoryConfig = when (field) {
        "mode" -> {
            val newMode = when (value) {
                is MemoryMode -> value
                else -> runCatching { MemoryMode.valueOf(value.toString()) }.getOrElse { config.mode }
            }
            // Swapping mode replaces the entire config with the mode's preset.
            // Individual fields can still be overridden afterwards via separate updateField calls.
            MemoryConfig.preset(newMode)
        }

        "summarizationThreshold" -> config.copy(summarizationThreshold = (value as Number).toDouble())

        "protectedRecentTurns" -> config.copy(protectedRecentTurns = (value as Number).toInt())

        "summarizationPruneFraction" -> config.copy(summarizationPruneFraction = (value as Number).toDouble())

        "maxKeyFacts" -> config.copy(maxKeyFacts = (value as Number).toInt())

        "maxMainTopics" -> config.copy(maxMainTopics = (value as Number).toInt())

        "maxSummaryLength" -> config.copy(maxSummaryLength = (value as Number).toInt())

        "memoryBudgetFraction" -> config.copy(memoryBudgetFraction = (value as Number).toDouble())

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
        // Handle top-level scalar fields on ModelsConfig (no nested dot)
        if (field == "maxToolCallingRoundTrips") {
            return config.copy(
                maxToolCallingRoundTrips = (value as? Int) ?: value.toString().toIntOrNull() ?: config.maxToolCallingRoundTrips,
            )
        }

        if (field == "requestTimeoutInSeconds") {
            val stringValue = value as? String ?: value.toString()
            return config.copy(
                requestTimeoutInSeconds = stringValue.toLongOrNull()
                    ?.coerceAtLeast(MIN_MODEL_TIMEOUT_SECONDS)
                    ?: config.requestTimeoutInSeconds,
            )
        }

        log.error("Unknown models config path '$field'. Per-provider model fields are now configured per-instance in Settings > AI Provider.", null)
        return config
    }

    private fun updateLocalLlmField(config: LocalLlmConfig, field: String, value: Any): LocalLlmConfig = when (field) {
        "lastSelectedModelFileName" -> config.copy(lastSelectedModelFileName = (value as? String)?.ifBlank { null })

        "lastSelectedModelIsEmbedding" -> config.copy(lastSelectedModelIsEmbedding = value as Boolean)

        "ctxSize" -> config.copy(ctxSize = (value as Int).coerceAtLeast(512))

        "nGpuLayers" -> config.copy(nGpuLayers = (value as Int).coerceAtLeast(0))

        "cacheTypeK" -> config.copy(cacheTypeK = (value as String).ifBlank { config.cacheTypeK })

        "cacheTypeV" -> config.copy(cacheTypeV = (value as String).ifBlank { config.cacheTypeV })

        "batchSize" -> config.copy(batchSize = (value as Int).coerceAtLeast(1))

        "ubatchSize" -> config.copy(ubatchSize = (value as Int).coerceAtLeast(1))

        "flashAttn" -> config.copy(flashAttn = value as Boolean)

        "noMmap" -> config.copy(noMmap = value as Boolean)

        "mlock" -> config.copy(mlock = value as Boolean)

        "numParallel" -> config.copy(numParallel = (value as Int).coerceAtLeast(1))

        "contBatching" -> config.copy(contBatching = value as Boolean)

        "noWarmup" -> config.copy(noWarmup = value as Boolean)

        "numaMode" -> config.copy(numaMode = value as String)

        "nPredict" -> config.copy(nPredict = value as Int)

        "ropeFreqBase" -> config.copy(ropeFreqBase = (value as? Double) ?: (value as? Int)?.toDouble() ?: config.ropeFreqBase)

        "ropeFreqScale" -> config.copy(ropeFreqScale = (value as? Double) ?: (value as? Int)?.toDouble() ?: config.ropeFreqScale)

        else -> {
            log.error("Unknown localLlm field: $field", null)
            config
        }
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
            } else if (password.isEmpty()) {
                // User explicitly cleared the field — remove the stored password too, otherwise
                // AppConfig.proxy would keep resolving the stale keychain entry.
                ProxyConfig.setSecurePassword(config.type, "")
                config.copy(password = "")
            } else {
                // Keep placeholder as-is
                config.copy(password = password)
            }
        }

        else -> {
            log.error("Unknown proxy field: $field", null)
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
            when {
                WebSearchConfig.isActualKey(key) -> {
                    val result = WebSearchConfig.setSecureBraveKey(key)
                    when (result.method) {
                        StorageMethod.KEYCHAIN ->
                            log.debug("Brave Search API key stored securely in keychain")

                        StorageMethod.ENCRYPTED ->
                            log.warn("Brave Search API key stored with encryption ({})", result.warningMessage)

                        StorageMethod.INSECURE_FALLBACK ->
                            log.warn("⚠️ Brave Search API key storage: {}", result.warningMessage)
                    }
                    config.copy(braveApiKey = WebSearchConfig.getKeyPlaceholder())
                }

                key.isEmpty() -> {
                    WebSearchConfig.setSecureBraveKey("")
                    config.copy(braveApiKey = "")
                }

                else -> config.copy(braveApiKey = key)
            }
        }

        "tavilyApiKey" -> {
            val key = value as String
            when {
                WebSearchConfig.isActualKey(key) -> {
                    val result = WebSearchConfig.setSecureTavilyKey(key)
                    when (result.method) {
                        StorageMethod.KEYCHAIN ->
                            log.debug("Tavily API key stored securely in keychain")

                        StorageMethod.ENCRYPTED ->
                            log.warn("Tavily API key stored with encryption ({})", result.warningMessage)

                        StorageMethod.INSECURE_FALLBACK ->
                            log.warn("⚠️ Tavily API key storage: {}", result.warningMessage)
                    }
                    config.copy(tavilyApiKey = WebSearchConfig.getKeyPlaceholder())
                }

                key.isEmpty() -> {
                    WebSearchConfig.setSecureTavilyKey("")
                    config.copy(tavilyApiKey = "")
                }

                else -> config.copy(tavilyApiKey = key)
            }
        }

        "serplyApiKey" -> {
            val key = value as String
            when {
                WebSearchConfig.isActualKey(key) -> {
                    val result = WebSearchConfig.setSecureSerplyKey(key)
                    when (result.method) {
                        StorageMethod.KEYCHAIN ->
                            log.debug("Serply API key stored securely in keychain")

                        StorageMethod.ENCRYPTED ->
                            log.warn("Serply API key stored with encryption ({})", result.warningMessage)

                        StorageMethod.INSECURE_FALLBACK ->
                            log.warn("⚠️ Serply API key storage: {}", result.warningMessage)
                    }
                    config.copy(serplyApiKey = WebSearchConfig.getKeyPlaceholder())
                }

                key.isEmpty() -> {
                    WebSearchConfig.setSecureSerplyKey("")
                    config.copy(serplyApiKey = "")
                }

                else -> config.copy(serplyApiKey = key)
            }
        }

        else -> {
            log.error("Unknown webSearch field: $field", null)
            config
        }
    }

    private fun updateVoiceField(config: VoiceConfig, field: String, value: Any): VoiceConfig = when (field) {
        "enabled" -> config.copy(enabled = value as Boolean)

        "sttProvider" -> config.copy(
            sttProvider = value as? VoiceProvider ?: runCatching { VoiceProvider.valueOf(value.toString()) }.getOrElse { config.sttProvider },
        )

        "ttsProvider" -> config.copy(
            ttsProvider = value as? VoiceProvider ?: runCatching { VoiceProvider.valueOf(value.toString()) }.getOrElse { config.ttsProvider },
        )

        "sttModel" -> config.copy(sttModel = value as String)

        "ttsModel" -> config.copy(ttsModel = value as String)

        "ttsVoice" -> config.copy(ttsVoice = value as String)

        "ttsSpeed" -> {
            val parsed = (value as? Double) ?: (value as? Number)?.toDouble() ?: value.toString().toDoubleOrNull()
            config.copy(ttsSpeed = parsed?.takeIf { it.isFinite() }?.coerceIn(0.25, 4.0) ?: config.ttsSpeed)
        }

        "useProviderKeyForVoice" -> config.copy(useProviderKeyForVoice = value as Boolean)

        "localSttEndpoint" -> config.copy(localSttEndpoint = value as String)

        "localWhisperModelPath" -> config.copy(localWhisperModelPath = value as String)

        "whisperModelBaseUrl" -> config.copy(whisperModelBaseUrl = (value as String).trimEnd('/').ifBlank { config.whisperModelBaseUrl })

        "localTtsEndpoint" -> config.copy(localTtsEndpoint = value as String)

        "autoSendTranscript" -> config.copy(autoSendTranscript = value as Boolean)

        "autoPlayResponses" -> config.copy(autoPlayResponses = value as Boolean)

        "autoStopOnSilence" -> config.copy(autoStopOnSilence = value as Boolean)

        "autoStopSilenceDurationMs" -> {
            val parsed = (value as? Long) ?: (value as? Int)?.toLong() ?: value.toString().toLongOrNull()
            config.copy(autoStopSilenceDurationMs = parsed?.takeIf { it > 0 } ?: config.autoStopSilenceDurationMs)
        }

        "openAiTtsVoices" -> {
            val voices = when (value) {
                is List<*> -> value.map { it.toString() }.filter { it.isNotBlank() }
                is String -> value.split(",").map { it.trim() }.filter { it.isNotBlank() }
                else -> config.openAiTtsVoices
            }
            // Never persist an empty list — that would leave the Settings UI dropdown with
            // nothing to select. Falls back to the current value instead.
            config.copy(openAiTtsVoices = voices.ifEmpty { config.openAiTtsVoices })
        }

        "ttsCacheMaxMessages" -> {
            val parsed = (value as? Int) ?: value.toString().toIntOrNull()
            config.copy(ttsCacheMaxMessages = parsed?.takeIf { it > 0 } ?: config.ttsCacheMaxMessages)
        }

        "ttsCacheMaxBytes" -> {
            val parsed = (value as? Long) ?: (value as? Int)?.toLong() ?: value.toString().toLongOrNull()
            config.copy(ttsCacheMaxBytes = parsed?.takeIf { it > 0 } ?: config.ttsCacheMaxBytes)
        }

        "openAiApiKey" -> {
            val key = value as String
            when {
                VoiceConfig.isActualKey(key) -> {
                    val result = VoiceConfig.setSecureOpenAiKey(key)
                    when (result.method) {
                        StorageMethod.KEYCHAIN ->
                            log.debug("Voice OpenAI API key stored securely in keychain")

                        StorageMethod.ENCRYPTED ->
                            log.warn("Voice OpenAI API key stored with encryption ({})", result.warningMessage)

                        StorageMethod.INSECURE_FALLBACK ->
                            log.warn("⚠️ Voice OpenAI API key storage: {}", result.warningMessage)
                    }
                    config.copy(openAiApiKey = VoiceConfig.getKeyPlaceholder())
                }

                key.isEmpty() -> {
                    // User explicitly cleared the field — remove the stored key too, otherwise
                    // AppConfig.voice would keep resolving the stale keychain entry.
                    VoiceConfig.setSecureOpenAiKey("")
                    config.copy(openAiApiKey = "")
                }

                else -> config.copy(openAiApiKey = key)
            }
        }

        else -> {
            log.error("Unknown voice field: $field", null)
            config
        }
    }

    private fun updateNotificationsField(config: NotificationsConfig, field: String, value: Any): NotificationsConfig = when (field) {
        "enabled" -> config.copy(enabled = value as Boolean)

        "onlyWhenUnfocused" -> config.copy(onlyWhenUnfocused = value as Boolean)

        "showDetails" -> config.copy(showDetails = value as Boolean)

        else -> {
            log.error("Unknown notifications field: $field", null)
            config
        }
    }

    private fun updateSuggestionsField(config: SuggestionsConfig, field: String, value: Any): SuggestionsConfig = when (field) {
        "aiExtractionEnabled" -> config.copy(aiExtractionEnabled = value as Boolean)

        else -> {
            log.error("Unknown suggestions field: $field", null)
            config
        }
    }
}
