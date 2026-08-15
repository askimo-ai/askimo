/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

import io.askimo.core.logging.currentFileLogger
import java.io.ByteArrayOutputStream
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

private val log = currentFileLogger()

/** Header names whose values must be masked in log output. */
private val SENSITIVE_HEADERS = setOf("authorization", "x-api-key", "api-key", "x-goog-api-key")

/** Maximum bytes captured from a response body for logging (avoids OOM on large payloads). */
private const val MAX_RESPONSE_LOG_BYTES = 8_192

/**
 * Wraps this builder in a [LoggingHttpClientBuilder] only when DEBUG logging is enabled.
 * When DEBUG is off the original builder is returned unchanged — zero overhead, no wrapper allocated.
 */
fun HttpClient.Builder.withLoggingIfDebug(): HttpClient.Builder = if (log.isDebugEnabled) LoggingHttpClientBuilder(this) else this

/**
 * A [HttpClient.Builder] decorator that wraps the built [HttpClient] in a [LoggingHttpClient].
 * Prefer constructing via [withLoggingIfDebug] so the wrapper is skipped entirely when DEBUG is off.
 *
 * Enable DEBUG logging for `io.askimo.core.util.LoggingHttpClientBuilder` to activate:
 * ```xml
 * <logger name="io.askimo.core.util.LoggingHttpClientBuilder" level="DEBUG"/>
 * ```
 */
class LoggingHttpClientBuilder(
    private val delegate: HttpClient.Builder,
) : HttpClient.Builder by delegate {
    override fun build(): HttpClient = LoggingHttpClient(delegate.build())
}

/**
 * A [HttpClient] wrapper that logs every outgoing HTTP request and incoming HTTP response at
 * DEBUG level before/after delegating to the real client.
 *
 * - **Request**: method, URI, headers (sensitive values masked), body (rebuilt via a fresh
 *   publisher so the actual HTTP call still carries its full payload intact).
 * - **Response**: status code, headers, and up to [MAX_RESPONSE_LOG_BYTES] bytes of body.
 *   The response body is captured by a tee-subscriber — each [ByteBuffer] is duplicated
 *   for logging so the originals are forwarded to the real [HttpResponse.BodyHandler] untouched.
 */
class LoggingHttpClient(
    private val delegate: HttpClient,
) : HttpClient() {

    override fun <T> send(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> {
        val (loggable, body) = interceptRequest(request)
        logRequest(loggable, body)
        return delegate.send(loggable, loggingBodyHandler(responseBodyHandler, loggable))
    }

    override fun <T> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> {
        val (loggable, body) = interceptRequest(request)
        logRequest(loggable, body)
        return delegate.sendAsync(loggable, loggingBodyHandler(responseBodyHandler, loggable))
    }

    override fun <T> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
    ): CompletableFuture<HttpResponse<T>> {
        val (loggable, body) = interceptRequest(request)
        logRequest(loggable, body)
        return delegate.sendAsync(loggable, loggingBodyHandler(responseBodyHandler, loggable), pushPromiseHandler)
    }

    // ── HttpClient delegation boilerplate ──────────────────────────────────────

    override fun cookieHandler(): Optional<CookieHandler> = delegate.cookieHandler()
    override fun connectTimeout(): Optional<Duration> = delegate.connectTimeout()
    override fun followRedirects(): Redirect = delegate.followRedirects()
    override fun proxy(): Optional<ProxySelector> = delegate.proxy()
    override fun sslContext(): SSLContext = delegate.sslContext()
    override fun sslParameters(): SSLParameters = delegate.sslParameters()
    override fun authenticator(): Optional<Authenticator> = delegate.authenticator()
    override fun version(): Version = delegate.version()
    override fun executor(): Optional<Executor> = delegate.executor()
    override fun newWebSocketBuilder(): WebSocket.Builder = delegate.newWebSocketBuilder()

    // ── Response logging ───────────────────────────────────────────────────────

    /**
     * Wraps [original] so each response is routed through a [TeeBodySubscriber] that
     * captures up to [MAX_RESPONSE_LOG_BYTES] bytes before forwarding the raw buffers
     * to the real subscriber unchanged.
     */
    private fun <T> loggingBodyHandler(
        original: HttpResponse.BodyHandler<T>,
        request: HttpRequest,
    ): HttpResponse.BodyHandler<T> = HttpResponse.BodyHandler { responseInfo ->
        TeeBodySubscriber(original.apply(responseInfo), responseInfo, request)
    }

    private fun StringBuilder.appendHeaders(headers: HttpHeaders) {
        appendLine("Headers:")
        headers.map().forEach { (name, values) ->
            val display = if (name.lowercase() in SENSITIVE_HEADERS) {
                values.map { Masking.maskSecret(it) }
            } else {
                values
            }
            appendLine("  $name: ${display.joinToString(", ")}")
        }
    }

    private fun logResponse(
        responseInfo: HttpResponse.ResponseInfo,
        request: HttpRequest,
        bodyBytes: ByteArray,
        truncated: Boolean,
    ) {
        val version = when (responseInfo.version()) {
            Version.HTTP_1_1 -> "HTTP/1.1"
            Version.HTTP_2 -> "HTTP/2"
            else -> responseInfo.version().name
        }
        val sb = StringBuilder()
        sb.appendLine("──── Incoming HTTP Response ────────────────────────────────────────────")
        sb.appendLine("${responseInfo.statusCode()} ${request.method()} ${request.uri()} [$version]")
        sb.appendLine()
        sb.appendHeaders(responseInfo.headers())
        if (bodyBytes.isNotEmpty()) {
            val text = bodyBytes.toString(Charsets.UTF_8)
            val sizeLabel = if (truncated) "≥${MAX_RESPONSE_LOG_BYTES} bytes (truncated)" else "${bodyBytes.size} bytes"
            sb.appendLine()
            sb.appendLine("Body [$sizeLabel]:")
            sb.append("  ")
            sb.appendLine(text.replace("\n", "\n  "))
        } else {
            sb.appendLine()
            sb.appendLine("Body [0 bytes]")
        }
        sb.append("───────────────────────────────────────────────────────────────────────")
        log.debug(sb.toString())
    }

    /**
     * Tee subscriber — duplicates each [ByteBuffer] via [ByteBuffer.duplicate] (independent
     * position, same backing data) so we can read for logging without advancing the original
     * buffer's position. The originals are forwarded to [delegate] completely unmodified.
     *
     * Logging fires in [onComplete] / [onError]:
     * - Regular responses: fires after all bytes have arrived.
     * - SSE / streaming: fires when the connection closes.
     */
    private inner class TeeBodySubscriber<T>(
        private val delegate: HttpResponse.BodySubscriber<T>,
        private val responseInfo: HttpResponse.ResponseInfo,
        private val request: HttpRequest,
    ) : HttpResponse.BodySubscriber<T> {

        private val captureBuffer = ByteArrayOutputStream()
        private var captureExhausted = false

        override fun getBody(): CompletionStage<T> = delegate.getBody()

        override fun onSubscribe(subscription: Flow.Subscription) = delegate.onSubscribe(subscription)

        override fun onNext(item: List<ByteBuffer>) {
            if (!captureExhausted) {
                for (bb in item) {
                    val available = MAX_RESPONSE_LOG_BYTES - captureBuffer.size()
                    if (available <= 0) {
                        captureExhausted = true
                        break
                    }
                    val dup = bb.duplicate() // independent position — original is untouched
                    val toRead = minOf(dup.remaining(), available)
                    val bytes = ByteArray(toRead)
                    dup.get(bytes)
                    captureBuffer.write(bytes)
                    if (captureBuffer.size() >= MAX_RESPONSE_LOG_BYTES) captureExhausted = true
                }
            }
            delegate.onNext(item) // always forward originals
        }

        override fun onError(throwable: Throwable) {
            logResponse(responseInfo, request, captureBuffer.toByteArray(), captureExhausted)
            delegate.onError(throwable)
        }

        override fun onComplete() {
            logResponse(responseInfo, request, captureBuffer.toByteArray(), captureExhausted)
            delegate.onComplete()
        }
    }

    /**
     * Short-circuits when DEBUG is off (zero overhead).
     * Otherwise drains the [HttpRequest.BodyPublisher], captures the bytes, and returns a
     * rebuilt request with a fresh [HttpRequest.BodyPublishers.ofByteArray] publisher so
     * the actual HTTP send still carries the full payload.
     */
    private fun interceptRequest(request: HttpRequest): Pair<HttpRequest, String?> {
        // No isDebugEnabled guard needed — this class is only instantiated when debug is on.
        val publisher = request.bodyPublisher().orElse(null)
            ?: return request to null
        if (publisher.contentLength() == 0L) return request to null

        val bodyBytes = drainPublisher(publisher)
            ?: return request to "[unreadable body — ${publisher.contentLength()} bytes]"

        // Rebuild with a fresh publisher so the real HTTP send still carries its body.
        val rebuilt = HttpRequest.newBuilder(request.uri())
            .method(request.method(), HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
            .apply {
                request.timeout().ifPresent { timeout(it) }
                request.version().ifPresent { version(it) }
                request.headers().map().forEach { (name, values) ->
                    values.forEach { value -> header(name, value) }
                }
            }
            .build()

        val text = bodyBytes.toString(Charsets.UTF_8)
        val bodyText = if (text.length > 4096) text.take(4096) + "\n…[truncated at 4 096 chars]" else text

        return rebuilt to bodyText
    }

    private fun logRequest(request: HttpRequest, body: String?) {
        val version = request.version()
            .map { v ->
                when (v) {
                    Version.HTTP_1_1 -> "HTTP/1.1"
                    Version.HTTP_2 -> "HTTP/2"
                    else -> v.name
                }
            }
            .orElse("default")
        val sb = StringBuilder()
        sb.appendLine("──── Outgoing HTTP Request ────────────────────────────────────────────")
        sb.appendLine("${request.method()} ${request.uri()} [$version]")
        sb.appendLine()
        sb.appendHeaders(request.headers())
        if (body != null) {
            val byteSize = body.toByteArray(Charsets.UTF_8).size
            val truncated = body.endsWith("[truncated at 4 096 chars]")
            val sizeLabel = if (truncated) "≥$byteSize bytes (truncated)" else "$byteSize bytes"
            sb.appendLine()
            sb.appendLine("Body [$sizeLabel]:")
            sb.append("  ")
            sb.appendLine(body.replace("\n", "\n  "))
        } else {
            sb.appendLine()
            sb.appendLine("Body [0 bytes]")
        }
        sb.append("───────────────────────────────────────────────────────────────────────")
        log.debug(sb.toString())
    }

    /**
     * Synchronously drains a [HttpRequest.BodyPublisher] into a [ByteArray].
     * Returns null if completion does not arrive within 2 s or an error is signalled.
     */
    private fun drainPublisher(publisher: HttpRequest.BodyPublisher): ByteArray? = try {
        val future = CompletableFuture<ByteArray>()
        val baos = ByteArrayOutputStream()
        publisher.subscribe(object : Flow.Subscriber<ByteBuffer> {
            override fun onSubscribe(subscription: Flow.Subscription) = subscription.request(Long.MAX_VALUE)
            override fun onNext(item: ByteBuffer) {
                val bytes = ByteArray(item.remaining())
                item.get(bytes)
                baos.write(bytes)
            }
            override fun onError(throwable: Throwable) = future.completeExceptionally(throwable).let {}
            override fun onComplete() = future.complete(baos.toByteArray()).let {}
        })
        future.get(2, TimeUnit.SECONDS)
    } catch (e: Exception) {
        log.trace("Could not drain body publisher for logging: {}", e.message)
        null
    }
}
