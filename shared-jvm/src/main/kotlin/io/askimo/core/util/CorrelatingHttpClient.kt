/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

/**
 * A [HttpClient.Builder] decorator that wraps the built [HttpClient] in a
 * [CorrelatingHttpClient], injecting headers from [extraHeaders] on every `send`/
 * `sendAsync` call. [extraHeaders] is evaluated per-request (not at build time), so
 * callers can supply dynamic values — e.g. a correlation ID from a `ThreadLocal`.
 *
 * [headersToStrip] (case-insensitive) removes existing headers before [extraHeaders]
 * is applied — useful for vendor clients that hardcode an auth header name (e.g.
 * Anthropic's `x-api-key`) so it can be replaced with the proxy's own header.
 * Defaults to empty, preserving simple append-only behavior for callers that don't
 * need it.
 *
 * Only used by the default JDK-backed [HttpClientBuilderFactory]; other platform
 * factories supply their own equivalent.
 *
 * Usage:
 * ```kotlin
 * val builder = CorrelatingHttpClientBuilder(
 *     delegate = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1),
 *     extraHeaders = listener::requestHeaders,
 * )
 * JdkHttpClient.builder().httpClientBuilder(builder)
 * ```
 */
class CorrelatingHttpClientBuilder(
    private val delegate: HttpClient.Builder,
    private val extraHeaders: () -> Map<String, String>,
    private val headersToStrip: Set<String> = emptySet(),
) : HttpClient.Builder by delegate {
    override fun build(): HttpClient = CorrelatingHttpClient(delegate.build(), extraHeaders, headersToStrip)
}

/**
 * A [HttpClient] wrapper that injects additional headers into every outgoing request.
 *
 * [extraHeaders] is called on every [send] / [sendAsync] invocation so the returned map
 * is always fresh — enabling per-request dynamic values such as correlation IDs.
 *
 * [headersToStrip] (case-insensitive) are removed from the request's existing headers
 * before [extraHeaders] is applied — see [CorrelatingHttpClientBuilder] for why.
 */
class CorrelatingHttpClient(
    private val delegate: HttpClient,
    private val extraHeaders: () -> Map<String, String>,
    private val headersToStrip: Set<String> = emptySet(),
) : HttpClient() {

    override fun <T> send(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> = delegate.send(request.withExtraHeaders(), responseBodyHandler)

    override fun <T> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = delegate.sendAsync(request.withExtraHeaders(), responseBodyHandler)

    override fun <T> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
    ): CompletableFuture<HttpResponse<T>> = delegate.sendAsync(request.withExtraHeaders(), responseBodyHandler, pushPromiseHandler)

    // ── HttpClient delegation boilerplate ─────────────────────────────────────

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

    private fun HttpRequest.withExtraHeaders(): HttpRequest {
        val extra = extraHeaders()
        if (extra.isEmpty() && headersToStrip.isEmpty()) return this
        val builder = HttpRequest.newBuilder(uri())
            .method(method(), bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()))
        timeout().ifPresent { builder.timeout(it) }
        version().ifPresent { builder.version(it) }
        headers().map().forEach { (name, values) ->
            if (!headersToStrip.any { it.equals(name, ignoreCase = true) }) {
                values.forEach { builder.header(name, it) }
            }
        }
        extra.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }
}
