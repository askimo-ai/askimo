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
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

private val log = currentFileLogger()

/** Header names whose values must be masked in log output. */
private val SENSITIVE_HEADERS = setOf("authorization", "x-api-key", "api-key", "x-goog-api-key")

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
@Suppress("JAVA_DEFAULT_METHODS_NOT_OVERRIDDEN_BY_DELEGATION")
class LoggingHttpClientBuilder(
    private val delegate: HttpClient.Builder,
) : HttpClient.Builder by delegate {
    override fun build(): HttpClient = LoggingHttpClient(delegate.build())
}

/**
 * A [HttpClient] wrapper that logs every outgoing HTTP request (URI, method, headers, body)
 * at DEBUG / TRACE level before delegating to the real client.
 *
 * - **Headers**: sensitive values (`Authorization`, `x-api-key`, etc.) are masked via [Masking].
 * - **Body**: the `BodyPublisher` is drained into a buffer, logged, then rebuilt as a fresh
 *   [HttpRequest.BodyPublishers.ofByteArray] publisher so the actual HTTP call still carries
 *   its full payload intact. Body text is only printed at TRACE level; DEBUG shows byte-count.
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
        return delegate.send(loggable, responseBodyHandler)
    }

    override fun <T> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> {
        val (loggable, body) = interceptRequest(request)
        logRequest(loggable, body)
        return delegate.sendAsync(loggable, responseBodyHandler)
    }

    override fun <T> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
    ): CompletableFuture<HttpResponse<T>> {
        val (loggable, body) = interceptRequest(request)
        logRequest(loggable, body)
        return delegate.sendAsync(loggable, responseBodyHandler, pushPromiseHandler)
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

    // ── Private helpers ────────────────────────────────────────────────────────

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
        // No isDebugEnabled guard needed — this class is only instantiated when debug is on.
        val sb = StringBuilder()
        sb.appendLine("──── Outgoing HTTP Request ────────────────────────────────────────────")
        sb.appendLine("${request.method()} ${request.uri()}")
        sb.appendLine()
        sb.appendLine("Headers:")
        request.headers().map().forEach { (name, values) ->
            val display = if (name.lowercase() in SENSITIVE_HEADERS) {
                values.map { Masking.maskSecret(it) }
            } else {
                values
            }
            sb.appendLine("  $name: ${display.joinToString(", ")}")
        }
        if (body != null) {
            sb.appendLine()
            sb.appendLine("Body:")
            sb.append("  ")
            sb.appendLine(body.replace("\n", "\n  "))
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
