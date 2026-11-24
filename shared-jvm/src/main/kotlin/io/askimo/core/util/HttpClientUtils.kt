/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

import dev.langchain4j.http.client.HttpClientBuilder
import dev.langchain4j.http.client.jdk.JdkHttpClient
import io.askimo.core.config.AppConfig
import io.askimo.core.providers.HttpVersion
import java.net.http.HttpClient
import java.time.Duration

/**
 * Platform-pluggable factory for the base (unconfigured-timeout) [HttpClientBuilder] used by
 * every langchain4j model factory. The default [HttpClientPlatform.factory] uses the JDK
 * `java.net.http.HttpClient` (with proxy bypass, debug logging, and
 * [CorrelatingHttpClient]-based header injection).
 *
 * Consumers that can't use the JDK HTTP client on their runtime can override
 * [HttpClientPlatform.factory] at startup with an alternative implementation (e.g. one backed
 * by OkHttp). Because every implementation returns the same langchain4j [HttpClientBuilder]
 * interface, every caller of [createJdkHttpClientBuilder] (the OpenAI-compatible, Anthropic,
 * and Askimo Pro model factories) works unmodified regardless of which factory is active —
 * only the `factory` implementation differs.
 */
fun interface HttpClientBuilderFactory {
    /**
     * Builds a fresh [HttpClientBuilder] for [baseUrl] at [httpVersion], with [extraHeaders]
     * injected into every outgoing request (evaluated fresh per-request, not just once at
     * build time) and any header named in [headersToStrip] removed first (case-insensitive;
     * used to drop a vendor-hardcoded auth header, e.g. Anthropic's `x-api-key`, before
     * substituting the proxy's own `Authorization` header).
     *
     * Callers that don't need header injection (e.g. standalone OpenAI/community Anthropic/
     * Gemini usage outside the Askimo Pro proxy) pass an empty-map lambda and an empty set —
     * see [createJdkHttpClientBuilder]'s defaults, which apply at that call site instead of
     * here (functional-interface SAM methods can't declare default parameter values).
     */
    fun create(
        baseUrl: String?,
        httpVersion: HttpVersion,
        extraHeaders: () -> Map<String, String>,
        headersToStrip: Set<String>,
    ): HttpClientBuilder
}

/** Holds the active, platform-specific [HttpClientBuilderFactory]. See [HttpClientBuilderFactory]. */
object HttpClientPlatform {
    var factory: HttpClientBuilderFactory = HttpClientBuilderFactory { baseUrl, httpVersion, extraHeaders, headersToStrip ->
        val httpClientBuilder = ProxyUtil.configureProxy(
            HttpClient.newBuilder().version(httpVersion.toJdkVersion()),
            baseUrl,
        ).withLoggingIfDebug()
        val correlating = CorrelatingHttpClientBuilder(httpClientBuilder, extraHeaders, headersToStrip)
        JdkHttpClient.builder().httpClientBuilder(correlating)
    }
}

/**
 * Creates an [HttpClientBuilder] configured with the given HTTP version, proxy settings
 * (JDK platform only — see [HttpClientBuilderFactory]), header injection/stripping, and
 * standard timeouts from [AppConfig].
 *
 * Delegates the platform-specific client construction to [HttpClientPlatform.factory] — see
 * its doc for why this is pluggable.
 *
 * This is the shared HTTP-client factory used by all model factories — both those that extend
 * [io.askimo.core.providers.openaicompatible.OpenAiCompatibleChatModelFactory] and standalone factories
 * (Anthropic, Gemini) that implement [io.askimo.core.providers.ChatModelFactory] directly.
 */
fun createJdkHttpClientBuilder(
    baseUrl: String? = null,
    httpVersion: HttpVersion = HttpVersion.HTTP_2,
    extraHeaders: () -> Map<String, String> = { emptyMap() },
    headersToStrip: Set<String> = emptySet(),
): HttpClientBuilder = HttpClientPlatform.factory
    .create(baseUrl, httpVersion, extraHeaders, headersToStrip)
    .readTimeout(Duration.ofSeconds(AppConfig.models.requestTimeoutInSeconds))
    .connectTimeout(Duration.ofSeconds(AppConfig.models.requestTimeoutInSeconds))

/**
 * Maps the provider-agnostic [HttpVersion] enum to the JDK [HttpClient.Version] enum
 * used by [java.net.http.HttpClient] and the LangChain4j JDK HTTP client.
 */
fun HttpVersion.toJdkVersion(): HttpClient.Version = when (this) {
    HttpVersion.HTTP_1_1 -> HttpClient.Version.HTTP_1_1
    HttpVersion.HTTP_2 -> HttpClient.Version.HTTP_2
}
