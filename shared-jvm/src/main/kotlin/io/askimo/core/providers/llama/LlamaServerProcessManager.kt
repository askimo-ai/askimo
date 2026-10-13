/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.llama

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.askimo.core.config.AppConfig
import io.askimo.core.event.EventBus
import io.askimo.core.event.error.LlamaServerStartFailedEvent
import io.askimo.core.logging.logger
import io.askimo.core.util.AskimoHome
import io.askimo.core.util.ProcessBuilderExt
import io.askimo.core.util.appJson
import io.askimo.core.util.httpGet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds

/**
 * Stable local endpoint the "Local LLM (built-in)" provider talks to (via
 * `OpenAiCompatibleTemplate.ASKIMO_LOCAL_LLM`) — one host for all models, Ollama/LM
 * Studio-style, without requiring either to be installed.
 *
 * Runs up to two `llama-server` child processes — one [ChildSlot] for chat, one for
 * embeddings — each with its own mutex/port/lifecycle, so a chat request and an embedding
 * request never contend for the same process (e.g. a RAG indexing job can't starve an
 * in-flight chat response). Swaps only happen *within* a slot (switching chat models);
 * embedding models stay small enough to keep resident alongside a chat model.
 *
 * Acts as a tiny reverse proxy (plain JDK [HttpServer]/[HttpClient], no langchain4j) in front
 * of the two slots:
 * 1. Request arrives on [proxyBaseUrl].
 * 2. Proxy reads the body's `"model"` field ([LlamaModelEntry.fileName]) and routes by path
 *    (`/v1/embeddings` → embedding slot).
 * 3. If the target slot is loaded with a different model, its process is stopped (draining
 *    in-flight requests — see [DRAIN_TIMEOUT_SECONDS]) and restarted with `--model <path>` on
 *    an ephemeral port, waiting for `/health`.
 * 4. Request is forwarded and the response streamed back verbatim (including SSE).
 *
 * Each slot auto-unloads after [IDLE_UNLOAD_TIMEOUT_MINUTES] idle to free RAM — a fixed
 * constant for now, may become user-configurable later.
 */

object LlamaServerProcessManager {

    private val log = logger<LlamaServerProcessManager>()

    /** How long an idle `llama-server` child process is left resident before being stopped to free RAM. */
    const val IDLE_UNLOAD_TIMEOUT_MINUTES: Long = 10

    /**
     * How long [stopSlotLocked] polls `/health` before giving up and killing the child process.
     * Must comfortably cover loading a multi-GB `.gguf` file into RAM on CPU-only hardware — 60s
     * proved too short for larger catalog tiers on slower disks/CPUs. Deliberately generous since
     * this only delays detecting a genuinely rare broken binary/model; a slow-but-working load is
     * the common case being protected here.
     */
    private const val HEALTH_CHECK_TIMEOUT_SECONDS = 300L
    private const val HEALTH_CHECK_POLL_INTERVAL_MS = 300L

    /** Number of recent lines of a child process's merged stdout/stderr kept for crash diagnostics — see [recentOutputTail]. */
    private const val RECENT_OUTPUT_LINES = 40

    /**
     * How long [stopSlotLocked] waits for in-flight requests against a slot's *current* child
     * process to finish before killing it to start a swap. Guards against a response being
     * truncated mid-flight by a same-slot swap (e.g. user switches chat model while a response
     * is streaming). Generous since this only delays a swap, never blocks it forever (see the
     * force-stop fallback after the deadline).
     */
    private const val DRAIN_TIMEOUT_SECONDS = 60L
    private const val DRAIN_POLL_INTERVAL_MS = 100L

    /** Number of CPU threads passed to `llama-server` — defaults to all available cores. */
    private val DEFAULT_THREADS = Runtime.getRuntime().availableProcessors()

    /** HTTP header browsers attach to cross-origin requests; rejected in [handleProxyRequest]. */
    private const val REJECTED_ORIGIN_HEADER = "Origin"

    private val forwardClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    @Volatile private var proxyServer: HttpServer? = null

    @Volatile private var proxyExecutor: ExecutorService? = null

    @Volatile private var proxyPort: Int = -1

    /** Observable running state of a `llama-server` child process, for Settings UI. */
    data class LlamaServerStatus(
        val running: Boolean = false,
        val modelFileName: String? = null,
        val embeddingMode: Boolean = false,
        /** Local port the running `llama-server` child process is bound to (not the stable [proxyBaseUrl] port). */
        val port: Int? = null,
        /**
         * True while this slot is stopping its current process and/or starting a new one for
         * [loadingModelFileName] — covers a manual Settings click, the last-used-model restore on
         * app launch, and the on-demand swap on first request (see [ensureModelLoaded]). Single
         * source of truth per slot so every consumer sees the same in-progress state instead of
         * drifting out of sync during a (sometimes multi-minute) model load.
         */
        val loading: Boolean = false,
        /** Which model is being loaded while [loading] is true; null otherwise. */
        val loadingModelFileName: String? = null,
    )

    /**
     * All mutable state for one independently-managed `llama-server` child process. See the
     * class doc for why chat and embedding each get their own slot instead of sharing one.
     */
    private class ChildSlot(val embeddingMode: Boolean) {
        val mutex = Mutex()

        @Volatile var process: Process? = null

        @Volatile var port: Int = -1

        @Volatile var modelFileName: String? = null

        /**
         * Pooling type the currently-running child process was actually started with (see
         * [LlamaModelEntry.poolingType]). Compared alongside [modelFileName] so a catalog change
         * to a model's pooling type forces a real restart instead of reusing a stale process
         * started with the old flag.
         */
        @Volatile var poolingType: String? = null

        @Volatile var lastUsedAt: Instant = Instant.now()

        /**
         * Number of requests currently forwarding a response from this slot's child process.
         * Incremented right before returning the port from [ensureModelLoaded] (still holding
         * [mutex]), decremented once forwarding finishes. [stopSlotLocked] waits for this to
         * drain before killing the process.
         */
        val activeRequestCount = java.util.concurrent.atomic.AtomicInteger(0)

        val status = MutableStateFlow(LlamaServerStatus(embeddingMode = embeddingMode))
    }

    private val chatSlot = ChildSlot(embeddingMode = false)
    private val embeddingSlot = ChildSlot(embeddingMode = true)

    private fun slotFor(embeddingMode: Boolean): ChildSlot = if (embeddingMode) embeddingSlot else chatSlot

    /** Collect this from Settings UI to show whether the chat engine is running and which model is loaded. */
    val status: StateFlow<LlamaServerStatus> = chatSlot.status.asStateFlow()

    /**
     * Observable running state of the stable reverse-proxy endpoint itself, independent of
     * whether any model is currently loaded — see [startProxy]. Settings UI uses this
     * to show "server reachable on port N" even before any `.gguf` model has been downloaded.
     */
    data class ProxyStatus(
        val running: Boolean = false,
        val port: Int? = null,
    )

    private val _proxyStatus = MutableStateFlow(ProxyStatus())

    /** Collect this from Settings UI to show whether the stable reverse-proxy port is listening. */
    val proxyStatus: StateFlow<ProxyStatus> = _proxyStatus.asStateFlow()

    /**
     * True once the user has explicitly stopped the engine (footer pill or Settings binary
     * switch) — gates [proxyBaseUrl] so that merely *constructing* a chat/embedding client
     * (which happens on every app launch, provider selection, and background session
     * pre-creation) never silently resurrects a proxy the user turned off. Without this, "Stop"
     * was immediately undone by the next chat message rebuilding its client. Reset to `false` by
     * [startProxy], so explicit Start always wins.
     */
    @Volatile private var engineManuallyStopped = false

    /**
     * The stable local endpoint to configure as the `baseUrl` for the built-in local LLM
     * provider. Starts the reverse proxy (not the `llama-server` child — that only starts lazily
     * on first real request) on first access — unless the user explicitly stopped the engine
     * (see [engineManuallyStopped]), in which case this still returns the last-known (now
     * unreachable) URL rather than silently restarting it, so the next request fails with a
     * clear "connection refused"-style error instead of quietly reviving a stopped engine.
     */
    val proxyBaseUrl: String
        get() {
            if (!engineManuallyStopped) {
                ensureProxyStarted()
            }
            return "http://127.0.0.1:$proxyPort/v1"
        }

    /**
     * Starts the stable reverse-proxy endpoint (if not already running), **without** requiring
     * any model to be downloaded or loaded — this is what "start/stop the server" means from a
     * user's perspective (see the class doc). Lets Settings UI mark "Local LLM" as reachable, and
     * the AI Provider wizard's "Test Connection" step succeed (empty model list via
     * [writeModelsList]), as soon as the engine binary is downloaded — the actual `llama-server`
     * child process(es) are a separate concern (see [loadModel]/[unloadModel]/[unloadModels])
     * that still only start once a real request names a downloaded model, or the user picks one
     * in Settings. Clears [engineManuallyStopped] — an explicit Start always undoes a prior Stop.
     */
    fun startProxy() {
        engineManuallyStopped = false
        ensureProxyStarted()
    }

    /**
     * Stops the server from the user's perspective: unloads any resident chat/embedding model
     * and shuts down the reverse-proxy endpoint, so [proxyBaseUrl] binds a fresh port if accessed
     * again. Used by the Settings "Local LLM" binary switch's "off" position — contrast with
     * [unloadModel]/[unloadModels], which only free a model's process while leaving the proxy up.
     * Sets [engineManuallyStopped] so it stays stopped until [startProxy] is called again.
     */
    suspend fun stopProxy() {
        engineManuallyStopped = true
        unloadModels()
        proxyServer?.stop(1)
        proxyServer = null
        // HttpServer.stop() only stops accepting connections/exchanges — it never touches a
        // caller-supplied executor (see HttpServer.setExecutor's docs), so without this every
        // stop/start cycle leaked another cached thread pool, and its worker threads (non-daemon
        // by default) could delay JVM exit.
        proxyExecutor?.shutdownNow()
        proxyExecutor = null
        _proxyStatus.value = ProxyStatus()
    }

    // ── Proxy lifecycle ──────────────────────────────────────────────────────────────────────

    @Synchronized
    private fun ensureProxyStarted() {
        if (proxyServer != null) return

        // Pick a free port only once per app launch, then reuse it on every subsequent
        // Stop→Start — never re-randomize it. Langchain4j clients bake proxyBaseUrl in at
        // construction time and are cached (ChatSessionService); picking a new port on restart
        // would leave previously-built clients pointed at a dead port with no recovery short of
        // reselecting the model or restarting the app.
        if (proxyPort == -1) {
            proxyPort = findFreePort()
        }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", proxyPort), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/") { exchange -> handleProxyRequest(exchange) }
        server.start()
        proxyServer = server
        proxyExecutor = executor
        _proxyStatus.value = ProxyStatus(running = true, port = proxyPort)
        log.info("llama local-LLM reverse proxy listening on 127.0.0.1:{}", proxyPort)

        startIdleWatcherIfNeeded()
    }

    private var idleWatcherStarted = false

    private fun startIdleWatcherIfNeeded() {
        if (idleWatcherStarted) return
        idleWatcherStarted = true
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            while (isActive) {
                delay(Duration.ofMinutes(1).toMillis().milliseconds)
                checkIdleSlot(chatSlot)
                checkIdleSlot(embeddingSlot)
            }
        }
    }

    private suspend fun checkIdleSlot(slot: ChildSlot) {
        // Cheap pre-check outside the lock; the real idle + zero-active-request check happens
        // again below under the mutex, since lastUsedAt is set when forwarding *starts*, not
        // finishes — otherwise a still-streaming request past the timeout gets force-killed.
        val idleFor = Duration.between(slot.lastUsedAt, Instant.now())
        if (slot.process != null && idleFor.toMinutes() >= IDLE_UNLOAD_TIMEOUT_MINUTES) {
            slot.mutex.withLock {
                val stillIdle = Duration.between(slot.lastUsedAt, Instant.now()).toMinutes() >= IDLE_UNLOAD_TIMEOUT_MINUTES
                if (slot.process != null && stillIdle && slot.activeRequestCount.get() == 0) {
                    log.info(
                        "llama-server ({}) idle for {} minutes — stopping to free memory",
                        if (slot.embeddingMode) "embedding" else "chat",
                        idleFor.toMinutes(),
                    )
                    stopSlotLocked(slot)
                }
            }
        }
    }

    /**
     * Stops the reverse proxy and both child processes. Blocking — never call from a UI/Compose
     * dispatcher thread that a slot-mutex-holding coroutine (e.g. a suspended `loadModel()`
     * health-check) might need to resume on, or this deadlocks. See `Main.kt`'s `performExit`,
     * which calls the suspend [stopProxy] directly on a background dispatcher instead.
     */
    fun shutdown() {
        runBlocking { stopProxy() }
    }

    // ── Manual control (Settings UI "Local LLM" page) ───────────────────────────────────────

    /**
     * Loads (or swaps to) [entry] in its corresponding slot (chat or embedding) — used by the
     * Settings "Local LLM" page's Select/Start button, independent of the proxy's own lifecycle
     * (see [startProxy]/[stopProxy]). Throws if the binary or the model file isn't downloaded yet.
     * Also posts a [LlamaServerStartFailedEvent] if the child process itself fails to start or
     * become healthy — see [ensureModelLoaded] for the equivalent on-demand-request path.
     */
    suspend fun loadModel(entry: LlamaModelEntry) {
        val modelPath = LlamaModelDownloader.modelPath(entry)
        if (!Files.exists(modelPath)) {
            error("Model \"${entry.fileName}\" is not downloaded.")
        }
        LlamaModelDownloader.validationError(entry)?.let { error(it) }
        val slot = slotFor(entry.isEmbedding)
        val resolvedPooling = if (entry.isEmbedding) entry.poolingType else null
        slot.mutex.withLock {
            val alreadyLoaded = slot.process?.isAlive == true &&
                slot.modelFileName == entry.fileName &&
                slot.poolingType == resolvedPooling
            if (alreadyLoaded) return@withLock
            slot.status.value = slot.status.value.copy(loading = true, loadingModelFileName = entry.fileName)
            try {
                stopSlotLocked(slot)
                startSlotLocked(slot, modelPath, entry.isEmbedding)
            } catch (e: CancellationException) {
                // Not a real startup failure — the Settings page's last-used-model warm-up runs
                // this from a LaunchedEffect (see localLlmBinaryCard), so leaving/recomposing
                // that effect cancels this coroutine normally. CancellationException is a
                // subclass of Exception, so without this it'd otherwise be caught below and
                // misreported as the child process failing to start.
                throw e
            } catch (e: Exception) {
                EventBus.post(
                    LlamaServerStartFailedEvent(
                        modelFileName = entry.fileName,
                        isEmbedding = entry.isEmbedding,
                        cause = e,
                    ),
                )
                throw e
            } finally {
                if (slot.status.value.loading) {
                    slot.status.value = slot.status.value.copy(loading = false, loadingModelFileName = null)
                }
            }
        }
    }

    /** Unloads both the chat and embedding child processes — the proxy itself stays up; see [stopProxy] to also stop it. */
    suspend fun unloadModels() {
        chatSlot.mutex.withLock { stopSlotLocked(chatSlot) }
        embeddingSlot.mutex.withLock { stopSlotLocked(embeddingSlot) }
    }

    /** Unloads only the chat or only the embedding child process — see [unloadModels] to unload both. */
    suspend fun unloadModel(embeddingMode: Boolean) {
        val slot = slotFor(embeddingMode)
        slot.mutex.withLock { stopSlotLocked(slot) }
    }

    /**
     * Deletes [entry]'s downloaded `.gguf` file, first unloading *every* slot currently serving
     * it and waiting for full termination. Deliberately does not trust [LlamaModelEntry.isEmbedding]
     * to pick a single slot: that's static catalog metadata, whereas which slot a file actually
     * loaded into is decided dynamically by the request path that last loaded it (chat vs.
     * `/embeddings` — see `ensureModelLoaded`) and can disagree with the catalog (e.g. a
     * chat-tagged model manually used for embeddings, or a non-catalog fallback file). Checking
     * both slots' live `modelFileName` instead of `entry.isEmbedding` avoids unloading the wrong
     * slot, which would otherwise throw on Windows (the actually-open file stays locked) and
     * leave a process serving a now-unlinked file on Unix.
     */
    suspend fun deleteModel(entry: LlamaModelEntry) {
        for (slot in listOf(chatSlot, embeddingSlot)) {
            slot.mutex.withLock {
                if (slot.modelFileName == entry.fileName) stopSlotLocked(slot)
            }
        }
        LlamaModelDownloader.delete(entry)
    }

    // ── Request handling ─────────────────────────────────────────────────────────────────────

    /**
     * Security note: this proxy is unauthenticated and trusts any client that can reach
     * 127.0.0.1 — acceptable for a loopback-only endpoint talking to our own in-process
     * langchain4j clients, but it also means a malicious web page could issue a simple
     * cross-origin `POST` here (e.g. via `fetch`) and trigger multi-GB model loading/inference
     * as a side effect, even though CORS still blocks that page from *reading* the response
     * (classic DNS-rebinding/CSRF-style risk against local servers). [REJECTED_ORIGIN_HEADER]
     * below rejects exactly that: browsers always attach an `Origin` header to cross-origin
     * fetch/XHR requests, whereas our own JDK [HttpClient]-based callers never send one — so any
     * request carrying it is dropped before being dispatched to a child process. A same-origin
     * page (e.g. one actually served from 127.0.0.1) could still omit `Origin` and slip through;
     * a per-run shared-secret header would close that gap too, but isn't implemented yet since
     * nothing is currently served from this host besides the proxy itself.
     */
    private fun handleProxyRequest(exchange: HttpExchange) {
        var acquiredSlot: ChildSlot? = null
        try {
            if (exchange.requestHeaders.containsKey(REJECTED_ORIGIN_HEADER)) {
                writeError(exchange, 403, "Cross-origin requests are not allowed.")
                return
            }

            val path = exchange.requestURI.path
            val method = exchange.requestMethod.uppercase()

            // GET/HEAD "/models" has no request body, so it can't carry a "model" field to route
            // on — used by the AI Provider wizard's "Test Connection" step and model picker.
            // Answer directly from the downloaded catalog instead of requiring a running process.
            if ((method == "GET" || method == "HEAD") && path.trimEnd('/').endsWith("/models")) {
                writeModelsList(exchange)
                return
            }

            val bodyBytes = exchange.requestBody.readBytes()
            val isEmbeddingRequest = path.contains("/embeddings")
            val modelFileName = extractModelFileName(bodyBytes)

            if (modelFileName == null) {
                writeError(exchange, 400, "Request body is missing a \"model\" field naming a downloaded .gguf file.")
                return
            }

            val entry = LlamaModelCatalog.fromFileName(modelFileName)
            val modelPath = entry?.let { LlamaModelDownloader.modelPath(it) }
                ?: AskimoHomeModelsFallback.resolve(modelFileName)

            if (modelPath == null || !Files.exists(modelPath)) {
                writeError(
                    exchange,
                    404,
                    "Model \"$modelFileName\" is not downloaded. Download it from Settings > AI Provider first.",
                )
                return
            }

            // Catches a corrupted/truncated file before handing it to llama-server, which would
            // otherwise fail with an opaque "exited before becoming healthy" error — see
            // LlamaModelDownloader.validationError's doc.
            if (entry != null) {
                LlamaModelDownloader.validationError(entry)?.let {
                    writeError(exchange, 500, it)
                    return
                }
            } else if (Files.size(modelPath) == 0L) {
                // Non-catalog fallback file with no known approxSizeBytes — still catch the
                // unambiguous zero-byte/interrupted case.
                writeError(exchange, 500, "Model \"$modelFileName\" is empty (0 bytes) — likely an interrupted download.")
                return
            }

            val slot = slotFor(isEmbeddingRequest)
            val targetPort = try {
                runBlocking { ensureModelLoaded(slot, modelPath, isEmbeddingRequest) }
            } catch (e: Exception) {
                log.warn("llama-server failed to load model \"{}\": {}", modelFileName, e.message, e)
                EventBus.post(
                    LlamaServerStartFailedEvent(
                        modelFileName = modelFileName,
                        isEmbedding = isEmbeddingRequest,
                        cause = e,
                    ),
                )
                writeError(exchange, 502, "Local LLM proxy error: ${e.message}")
                return
            }
            acquiredSlot = slot
            forwardRequest(exchange, targetPort, bodyBytes)
        } catch (e: Exception) {
            log.warn("llama proxy request failed: {}", e.message, e)
            runCatching { writeError(exchange, 502, "Local LLM proxy error: ${e.message}") }
        } finally {
            acquiredSlot?.activeRequestCount?.decrementAndGet()
            exchange.close()
        }
    }

    /**
     * Synthesizes an OpenAI-style `GET /models` response listing every `.gguf` file actually
     * present under [AskimoHome.llamaModelsDir] — not just [LlamaModelCatalog.allEntries].
     * Dynamically discovered downloads (Hugging Face browser search results) and manually
     * dropped-in files are intentionally absent from the curated catalog, but still need to show
     * up here: this is what the "Local LLM (built-in)" provider's model picker lists from, so a
     * catalog-only response would make those models undownloadable from a user's perspective
     * despite the file already being on disk.
     */
    private fun writeModelsList(exchange: HttpExchange) {
        val modelsDir = AskimoHome.llamaModelsDir()
        val fileNames = if (Files.isDirectory(modelsDir)) {
            Files.list(modelsDir).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".gguf", ignoreCase = true) }
                    .map { it.fileName.toString() }
                    .sorted()
                    .toList()
            }
        } else {
            emptyList()
        }
        val data = fileNames.joinToString(",") { fileName ->
            """{"id":"${escapeJsonString(fileName)}","object":"model","created":0,"owned_by":"llama.cpp"}"""
        }
        val body = """{"object":"list","data":[$data]}""".toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun extractModelFileName(bodyBytes: ByteArray): String? {
        if (bodyBytes.isEmpty()) return null
        return try {
            val json = appJson.parseToJsonElement(String(bodyBytes, Charsets.UTF_8))
            json.jsonObject["model"]?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) {
            null
        }
    }

    private fun forwardRequest(exchange: HttpExchange, targetPort: Int, bodyBytes: ByteArray) {
        val targetUri = URI("http://127.0.0.1:$targetPort${exchange.requestURI.path}${exchange.requestURI.rawQuery?.let { "?$it" } ?: ""}")
        val requestBuilder = HttpRequest.newBuilder(targetUri)
            .timeout(Duration.ofMinutes(10))

        exchange.requestHeaders.forEach { (name, values) ->
            if (!name.equals("Host", ignoreCase = true) && !name.equals("Content-Length", ignoreCase = true)) {
                values.forEach { value -> runCatching { requestBuilder.header(name, value) } }
            }
        }

        val method = exchange.requestMethod.uppercase()
        val bodyPublisher = if (method == "GET" || method == "HEAD" || bodyBytes.isEmpty()) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofByteArray(bodyBytes)
        }
        requestBuilder.method(method, bodyPublisher)

        val response = forwardClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())

        val responseHeaders = exchange.responseHeaders
        response.headers().map().forEach { (name, values) ->
            if (!name.equals("Transfer-Encoding", ignoreCase = true) && !name.equals("Content-Length", ignoreCase = true)) {
                values.forEach { value -> responseHeaders.add(name, value) }
            }
        }

        exchange.sendResponseHeaders(response.statusCode(), 0)
        response.body().use { input -> input.copyTo(exchange.responseBody) }
    }

    private fun writeError(exchange: HttpExchange, status: Int, message: String) {
        val body = """{"error":{"message":"${escapeJsonString(message)}"}}""".toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    /** Minimal JSON string escaping for values embedded in a hand-built JSON response body. */
    private fun escapeJsonString(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    // ── Child process swap logic ─────────────────────────────────────────────────────────────

    /**
     * Ensures [slot]'s child process is serving [modelPath], swapping out any different
     * currently-loaded model in that slot first. Returns the child process's internal port once
     * ready. Only ever contends with other requests targeting the *same* slot — see class doc.
     */
    private suspend fun ensureModelLoaded(slot: ChildSlot, modelPath: java.nio.file.Path, embeddingMode: Boolean): Int = slot.mutex.withLock {
        slot.lastUsedAt = Instant.now()
        val fileName = modelPath.fileName.toString()
        val resolvedPooling = if (embeddingMode) LlamaModelCatalog.fromFileName(fileName)?.poolingType ?: "mean" else null

        val alreadyLoaded = slot.process?.isAlive == true &&
            slot.modelFileName == fileName &&
            slot.poolingType == resolvedPooling

        if (!alreadyLoaded) {
            slot.status.value = slot.status.value.copy(loading = true, loadingModelFileName = fileName)
            try {
                stopSlotLocked(slot)
                startSlotLocked(slot, modelPath, embeddingMode)
            } finally {
                if (slot.status.value.loading) {
                    slot.status.value = slot.status.value.copy(loading = false, loadingModelFileName = null)
                }
            }
        }
        slot.activeRequestCount.incrementAndGet()
        slot.port
    }

    private suspend fun stopSlotLocked(slot: ChildSlot) {
        val process = slot.process ?: return
        if (slot.activeRequestCount.get() > 0) {
            val deadline = Instant.now().plusSeconds(DRAIN_TIMEOUT_SECONDS)
            while (slot.activeRequestCount.get() > 0 && Instant.now().isBefore(deadline)) {
                delay(DRAIN_POLL_INTERVAL_MS.milliseconds)
            }
            if (slot.activeRequestCount.get() > 0) {
                log.warn(
                    "Stopping llama-server ({}) with {} still-active request(s) after waiting {}s — " +
                        "forcing stop anyway (in-flight connection(s) may be interrupted)",
                    if (slot.embeddingMode) "embedding" else "chat",
                    slot.activeRequestCount.get(),
                    DRAIN_TIMEOUT_SECONDS,
                )
            }
        }
        log.info("Stopping llama-server child process (was serving {})", slot.modelFileName)
        process.destroy()
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
        slot.process = null
        slot.modelFileName = null
        slot.poolingType = null
        // Preserve loading/loadingModelFileName (set by the caller swapping models) instead of
        // resetting status — otherwise the "loading" indicator would flicker off between the
        // stop-old-process phase and the new process starting to load.
        slot.status.value = slot.status.value.copy(running = false, modelFileName = null, port = null)
    }

    private suspend fun startSlotLocked(slot: ChildSlot, modelPath: java.nio.file.Path, embeddingMode: Boolean) {
        val platform = LlamaServerCatalog.detectCurrentPlatform()
            ?: error("Unsupported OS/architecture for the built-in local LLM provider.")
        val exe = LlamaServerDownloader.executablePath(platform)
        if (!Files.exists(exe)) {
            error("llama-server binary is not downloaded. Download it from Settings > AI Provider first.")
        }

        val port = findFreePort()
        // Pooling type is model-specific — bi-encoders (e.g. nomic-embed-text) need "mean",
        // decoder-only/causal models (e.g. Qwen3-Embedding) need "last". See
        // LlamaModelEntry.poolingType for why getting this wrong produces NaN embeddings
        // (silently serialized as JSON null, crashing langchain4j). Computed up front so it can
        // also be recorded in slot.poolingType below, for change detection.
        val poolingType = if (embeddingMode) LlamaModelCatalog.fromFileName(modelPath.fileName.toString())?.poolingType ?: "mean" else null
        val command = buildList {
            add(exe.toAbsolutePath().toString())
            add("--model")
            add(modelPath.toAbsolutePath().toString())
            add("--port")
            add(port.toString())
            add("--host")
            add("127.0.0.1")
            add("--ctx-size")
            // See AppConfig.LocalLlmConfig.ctxSize's doc for why this must be bounded instead of
            // left at the model's native training context (can be 128K+ tokens, causing severe
            // CPU memory pressure / apparent hangs). User-configurable from Settings > Local LLM.
            add(AppConfig.localLlm.ctxSize.toString())
            add("--threads")
            add(DEFAULT_THREADS.toString())
            add("--batch-size")
            add(AppConfig.localLlm.batchSize.toString())
            add("--ubatch-size")
            add(AppConfig.localLlm.ubatchSize.toString())
            // Dormant until a GPU-enabled catalog build exists (see LocalLlmConfig.nGpuLayers's
            // doc) — only pass the flag when > 0 so CPU-only builds aren't handed an arg they'd
            // reject.
            if (AppConfig.localLlm.nGpuLayers > 0) {
                add("--n-gpu-layers")
                add(AppConfig.localLlm.nGpuLayers.toString())
            }
            add("--cache-type-k")
            add(AppConfig.localLlm.cacheTypeK)
            add("--cache-type-v")
            add(AppConfig.localLlm.cacheTypeV)
            // llama-server requires flash attention whenever either KV-cache type is quantized
            // (non-"f16") — forced on in that case regardless of the user's flashAttn setting,
            // since it's a hard correctness requirement, not a preference.
            //
            // --flash-attn takes an explicit value (on/off/auto) on current builds (older
            // releases used a bare boolean switch) — passing no value makes the CLI parser
            // swallow the *next* argument as its value instead, so a value must always be given.
            val cacheQuantized = AppConfig.localLlm.cacheTypeK != "f16" || AppConfig.localLlm.cacheTypeV != "f16"
            add("--flash-attn")
            add(
                when {
                    cacheQuantized -> "on"
                    AppConfig.localLlm.flashAttn -> "auto"
                    else -> "off"
                },
            )
            if (AppConfig.localLlm.noMmap) {
                add("--no-mmap")
            }
            if (AppConfig.localLlm.mlock) {
                add("--mlock")
            }
            if (AppConfig.localLlm.numParallel > 1) {
                add("--parallel")
                add(AppConfig.localLlm.numParallel.toString())
                if (AppConfig.localLlm.contBatching) {
                    add("--cont-batching")
                }
            }
            if (AppConfig.localLlm.noWarmup) {
                add("--no-warmup")
            }
            if (AppConfig.localLlm.numaMode.isNotBlank()) {
                add("--numa")
                add(AppConfig.localLlm.numaMode)
            }
            if (AppConfig.localLlm.nPredict != -1) {
                add("--n-predict")
                add(AppConfig.localLlm.nPredict.toString())
            }
            if (AppConfig.localLlm.ropeFreqBase != 0.0) {
                add("--rope-freq-base")
                add(AppConfig.localLlm.ropeFreqBase.toString())
            }
            if (AppConfig.localLlm.ropeFreqScale != 0.0) {
                add("--rope-freq-scale")
                add(AppConfig.localLlm.ropeFreqScale.toString())
            }
            add("--jinja")
            if (embeddingMode) {
                add("--embedding")
                add("--pooling")
                add(poolingType!!)
            }
        }

        log.info("Starting llama-server: {}", command.joinToString(" "))
        val process = ProcessBuilderExt(command)
            .redirectErrorStream(true)
            .start()

        // Keeps the last few lines of the child's merged stdout/stderr so a crash before/during
        // the health check below surfaces the *actual* llama-server error (unsupported flag,
        // assertion failure, GGUF compatibility issue) instead of an opaque "exited before
        // becoming healthy (exit code N)" — see waitForHealthy.
        val recentOutput = ArrayDeque<String>()
        // Drain child output on a background thread so it never blocks on a full pipe buffer.
        Thread({
            process.inputStream.bufferedReader().forEachLine { line ->
                log.trace("[llama-server] {}", line)
                synchronized(recentOutput) {
                    recentOutput.addLast(line)
                    if (recentOutput.size > RECENT_OUTPUT_LINES) recentOutput.removeFirst()
                }
            }
        }, "llama-server-output-drain").apply { isDaemon = true }.start()

        // waitForHealthy's own destroyForcibly() only fires on its normal timeout path. If
        // this coroutine is cancelled while suspended here (e.g. Settings is navigated away
        // from mid-load), slot.process is never assigned and the child leaks. Catch Throwable
        // (not just Exception) so cancellation cleans up too, then rethrow.
        try {
            waitForHealthy(port, process, recentOutput)
        } catch (t: Throwable) {
            process.destroyForcibly()
            throw t
        }

        slot.process = process
        slot.port = port
        slot.modelFileName = modelPath.fileName.toString()
        slot.poolingType = poolingType
        slot.status.value = LlamaServerStatus(
            running = true,
            modelFileName = slot.modelFileName,
            embeddingMode = embeddingMode,
            port = port,
        )
        // Persists whichever model actually became resident in this slot — covers both manual
        // Select/Start clicks and automatic swaps from a real request (see ensureModelLoaded) —
        // so the next app launch can restore it automatically. See AppConfig.LocalLlmConfig's doc.
        AppConfig.updateField("localLlm.lastSelectedModelFileName", slot.modelFileName ?: "")
        AppConfig.updateField("localLlm.lastSelectedModelIsEmbedding", embeddingMode)
    }

    private suspend fun waitForHealthy(port: Int, process: Process, recentOutput: ArrayDeque<String>) {
        val deadline = Instant.now().plusSeconds(HEALTH_CHECK_TIMEOUT_SECONDS)
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive) {
                error(
                    "llama-server exited before becoming healthy (exit code ${process.exitValue()}).\n" +
                        recentOutputTail(recentOutput),
                )
            }
            try {
                val (statusCode, _) = httpGet(
                    url = "http://127.0.0.1:$port/health",
                    connectTimeoutMs = 2_000,
                    readTimeoutMs = 2_000,
                )
                if (statusCode in 200..299) return
            } catch (_: Exception) {
                // Not ready yet — keep polling.
            }
            delay(HEALTH_CHECK_POLL_INTERVAL_MS.milliseconds)
        }
        process.destroyForcibly()
        error("llama-server did not become healthy within ${HEALTH_CHECK_TIMEOUT_SECONDS}s.\n" + recentOutputTail(recentOutput))
    }

    /** Formats the last captured lines of a crashed child process's output for an error message. */
    private fun recentOutputTail(recentOutput: ArrayDeque<String>): String {
        val lines = synchronized(recentOutput) { recentOutput.toList() }
        return if (lines.isEmpty()) "(no output captured)" else "Last output:\n" + lines.joinToString("\n")
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
}

/**
 * Fallback resolution for a requested model file name that isn't in [LlamaModelCatalog] (e.g. a
 * user manually dropped an extra `.gguf` file into the models directory) — looked up directly by
 * file name under [AskimoHome.llamaModelsDir] instead of being rejected.
 *
 * `fileName` is attacker-controlled (it comes straight from the unauthenticated proxy request
 * body's `"model"` field — see `LlamaServerProcessManager.handleProxyRequest`), so this must not
 * resolve it as an arbitrary path: an absolute path or a `../`-laden value would let a request
 * make `llama-server` attempt to open any existing file on disk the app process can read.
 * [resolve] therefore normalizes the candidate and requires it to still be a direct child of
 * [AskimoHome.llamaModelsDir] with a `.gguf` extension, returning `null` otherwise so the caller
 * reports a normal "not downloaded" error instead of touching the path at all.
 */
private object AskimoHomeModelsFallback {
    fun resolve(fileName: String): java.nio.file.Path? {
        val modelsDir = AskimoHome.llamaModelsDir().toAbsolutePath().normalize()
        val candidate = modelsDir.resolve(fileName).normalize()
        if (candidate.parent != modelsDir) return null
        if (!candidate.fileName.toString().endsWith(".gguf", ignoreCase = true)) return null
        return candidate
    }
}
