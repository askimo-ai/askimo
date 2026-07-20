/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.GZIPInputStream

/**
 * Perform a GET request to a local/remote URL with proxy support.
 * Returns the HTTP status code and response body, or throws on connection failure.
 */
fun httpGet(
    url: String,
    headers: Map<String, String> = emptyMap(),
    connectTimeoutMs: Long = 5_000,
    readTimeoutMs: Long = 8_000,
    httpVersion: HttpClient.Version = HttpClient.Version.HTTP_2,
): Pair<Int, String> {
    val client = ProxyUtil.configureProxy(HttpClient.newBuilder().version(httpVersion), url)
        .connectTimeout(Duration.ofMillis(connectTimeoutMs))
        .build()
    val requestBuilder = HttpRequest.newBuilder()
        .uri(URI(url))
        .timeout(Duration.ofMillis(readTimeoutMs))
        .GET()
    headers.forEach { (k, v) -> requestBuilder.header(k, v) }
    val response = client.sendWithErrorHandling(requestBuilder.build(), url)
    val decompressedBody = decompressGzipIfNeeded(response.body(), response)
    return response.statusCode() to decompressedBody
}

/**
 * Perform a POST request with a JSON body to a local/remote URL with proxy support.
 * Returns the HTTP status code and response body, or throws on connection failure.
 */
fun httpPost(
    url: String,
    body: String,
    headers: Map<String, String> = emptyMap(),
    connectTimeoutMs: Long = 15_000,
    readTimeoutMs: Long = 600_000,
    httpVersion: HttpClient.Version = HttpClient.Version.HTTP_2,
): Pair<Int, String> {
    val client = ProxyUtil.configureProxy(HttpClient.newBuilder().version(httpVersion), url)
        .connectTimeout(Duration.ofMillis(connectTimeoutMs))
        .build()
    val requestBuilder = HttpRequest.newBuilder()
        .uri(URI(url))
        .timeout(Duration.ofMillis(readTimeoutMs))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
    headers.forEach { (k, v) -> requestBuilder.header(k, v) }
    val response = client.sendWithErrorHandling(requestBuilder.build(), url)
    val decompressedBody = decompressGzipIfNeeded(response.body(), response)
    return response.statusCode() to decompressedBody
}

/**
 * Sends an HTTP request and wraps any connection/IO errors with a descriptive message.
 */
private fun HttpClient.sendWithErrorHandling(
    request: HttpRequest,
    url: String,
): HttpResponse<ByteArray> = try {
    send(request, HttpResponse.BodyHandlers.ofByteArray())
} catch (e: ConnectException) {
    throw RuntimeException("Connection failed to $url: ${e.message}", e)
} catch (e: HttpConnectTimeoutException) {
    throw RuntimeException("Connect timeout to $url", e)
} catch (e: IOException) {
    throw RuntimeException("IO error for $url: ${e.message}", e)
} catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    throw RuntimeException("Request interrupted for $url", e)
}

/**
 * Decompresses gzip-encoded response body if the Content-Encoding header indicates gzip.
 * Otherwise returns the body as-is.
 */
private fun decompressGzipIfNeeded(body: ByteArray, response: HttpResponse<ByteArray>): String {
    val encoding = response.headers()
        .allValues("Content-Encoding")
        .firstOrNull { it.contains("gzip", ignoreCase = true) } ?: ""
    return if (encoding.isNotEmpty()) {
        GZIPInputStream(ByteArrayInputStream(body)).bufferedReader().use { it.readText() }
    } else {
        String(body, Charsets.UTF_8)
    }
}
