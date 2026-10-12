/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.zip.GZIPInputStream

/**
 * Percent-encodes each `/`-separated segment of a URL path (e.g. a server-provided file path
 * being spliced into a `resolve/.../<path>`-style URL), so segments containing spaces or
 * reserved characters (`#`, `?`, `%`, etc.) don't break [URI] parsing or get misinterpreted as
 * part of the URL's structure (e.g. a literal `#` starting a fragment). [URLEncoder] encodes for
 * query-string/form use (e.g. turns spaces into `+`, which is wrong in a path segment), so each
 * segment is encoded with it and then `+` is corrected back to `%20`.
 *
 * Use this instead of splicing a raw path into a URL string directly whenever that path
 * originates from an external source (API response, user input) rather than a fixed literal.
 */
fun encodeUrlPathSegments(path: String): String = path.split("/").joinToString("/") { segment ->
    URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")
}

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
    val response = sendRequest(url, headers, connectTimeoutMs, readTimeoutMs, httpVersion) { GET() }
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
    val response = sendJsonPost(url, body, headers, connectTimeoutMs, readTimeoutMs, httpVersion)
    val decompressedBody = decompressGzipIfNeeded(response.body(), response)
    return response.statusCode() to decompressedBody
}

/**
 * Perform a POST request with a JSON body, returning the **raw** response bytes instead of a
 * decoded String — required for binary responses (e.g. synthesized TTS audio) where [httpPost]'s
 * UTF-8 string decoding would corrupt the payload.
 */
fun httpPostForBytes(
    url: String,
    body: String,
    headers: Map<String, String> = emptyMap(),
    connectTimeoutMs: Long = 15_000,
    readTimeoutMs: Long = 600_000,
    httpVersion: HttpClient.Version = HttpClient.Version.HTTP_2,
): Pair<Int, ByteArray> {
    val response = sendJsonPost(url, body, headers, connectTimeoutMs, readTimeoutMs, httpVersion)
    val decompressedBody = decompressGzipBytesIfNeeded(response.body(), response)
    return response.statusCode() to decompressedBody
}

private fun sendJsonPost(
    url: String,
    body: String,
    headers: Map<String, String>,
    connectTimeoutMs: Long,
    readTimeoutMs: Long,
    httpVersion: HttpClient.Version,
): HttpResponse<ByteArray> = sendRequest(url, headers, connectTimeoutMs, readTimeoutMs, httpVersion) {
    header("Content-Type", "application/json")
    POST(HttpRequest.BodyPublishers.ofString(body))
}

/**
 * Builds a proxy-aware [HttpClient] + [HttpRequest] for [url] and sends it, wrapping connection
 * errors — shared by [httpGet]/[httpPost]/[httpPostForBytes] which otherwise only differ in the
 * HTTP method/body via [configureRequest].
 */
private fun sendRequest(
    url: String,
    headers: Map<String, String>,
    connectTimeoutMs: Long,
    readTimeoutMs: Long,
    httpVersion: HttpClient.Version,
    configureRequest: HttpRequest.Builder.() -> Unit,
): HttpResponse<ByteArray> {
    val client = ProxyUtil.configureProxy(HttpClient.newBuilder().version(httpVersion), url)
        .connectTimeout(Duration.ofMillis(connectTimeoutMs))
        .build()
    val requestBuilder = HttpRequest.newBuilder()
        .uri(URI(url))
        .timeout(Duration.ofMillis(readTimeoutMs))
    requestBuilder.configureRequest()
    headers.forEach { (k, v) -> requestBuilder.header(k, v) }
    return client.sendWithErrorHandling(requestBuilder.build(), url)
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
private fun decompressGzipIfNeeded(body: ByteArray, response: HttpResponse<ByteArray>): String = String(decompressGzipBytesIfNeeded(body, response), Charsets.UTF_8)

/**
 * Decompresses gzip-encoded response bytes if the Content-Encoding header indicates gzip.
 * Otherwise returns the bytes as-is. Used for raw/binary responses (e.g. TTS audio) where
 * decoding to a String would corrupt the payload.
 */
private fun decompressGzipBytesIfNeeded(body: ByteArray, response: HttpResponse<ByteArray>): ByteArray {
    val encoding = response.headers()
        .allValues("Content-Encoding")
        .firstOrNull { it.contains("gzip", ignoreCase = true) } ?: ""
    return if (encoding.isNotEmpty()) {
        GZIPInputStream(ByteArrayInputStream(body)).use { it.readBytes() }
    } else {
        body
    }
}
