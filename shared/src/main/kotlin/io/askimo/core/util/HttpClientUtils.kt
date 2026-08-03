/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

import dev.langchain4j.http.client.jdk.JdkHttpClient
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder
import io.askimo.core.config.AppConfig
import io.askimo.core.providers.HttpVersion
import java.net.http.HttpClient
import java.time.Duration

/**
 * Creates a [JdkHttpClient] builder configured with the given HTTP version, proxy settings,
 * and standard timeouts from [AppConfig].
 *
 * Proxy is automatically bypassed for localhost/private-IP URLs when [baseUrl] is provided.
 * Pass `null` for cloud providers (e.g. Anthropic, Gemini) where no local bypass is needed.
 *
 * This is the shared HTTP-client factory used by all model factories — both those that extend
 * [io.askimo.core.providers.openaicompatible.OpenAiCompatibleChatModelFactory] and standalone factories
 * (Anthropic, Gemini) that implement [io.askimo.core.providers.ChatModelFactory] directly.
 */
fun createJdkHttpClientBuilder(
    baseUrl: String? = null,
    httpVersion: HttpVersion = HttpVersion.HTTP_2,
): JdkHttpClientBuilder = JdkHttpClient.builder().httpClientBuilder(
    ProxyUtil.configureProxy(
        HttpClient.newBuilder().version(httpVersion.toJdkVersion()),
        baseUrl,
    ).withLoggingIfDebug(),
).readTimeout(Duration.ofSeconds(AppConfig.models.timeouts.defaultModelTimeoutSeconds))
    .connectTimeout(Duration.ofSeconds(AppConfig.models.timeouts.defaultModelTimeoutSeconds))

/**
 * Maps the provider-agnostic [HttpVersion] enum to the JDK [HttpClient.Version] enum
 * used by [java.net.http.HttpClient] and the LangChain4j JDK HTTP client.
 */
fun HttpVersion.toJdkVersion(): HttpClient.Version = when (this) {
    HttpVersion.HTTP_1_1 -> HttpClient.Version.HTTP_1_1
    HttpVersion.HTTP_2 -> HttpClient.Version.HTTP_2
}
