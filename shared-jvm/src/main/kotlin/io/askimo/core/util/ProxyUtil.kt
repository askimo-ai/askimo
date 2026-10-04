/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

import io.askimo.core.config.AppConfig
import io.askimo.core.config.ProxyType
import io.askimo.core.logging.logger
import java.io.IOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient

private val log = logger<ProxyUtil>()

/**
 * Utility for configuring HTTP clients with proxy settings from AppConfig.
 *
 * Supports:
 * - HTTP/HTTPS proxies with optional authentication
 * - SOCKS5 proxies with optional authentication
 * - System proxy detection
 * - Automatic localhost bypass
 *
 * Usage:
 * ```kotlin
 * // For external services
 * val httpClient = ProxyUtil.configureProxy(
 *     HttpClient.newBuilder()
 * ).build()
 *
 * // For local services (auto-detects and skips proxy)
 * val httpClient = ProxyUtil.configureProxy(
 *     HttpClient.newBuilder(),
 *     baseUrl = "http://localhost:11434"
 * ).build()
 * ```
 */
object ProxyUtil {

    /**
     * Checks if a host is local and should bypass proxy.
     *
     * @param host The hostname to check
     * @return true if the host is local (localhost, 127.0.0.1, private IPs, etc.)
     */
    private fun isLocalHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false

        val normalizedHost = host.lowercase().trim()
        return normalizedHost == "localhost" ||
            normalizedHost == "127.0.0.1" ||
            normalizedHost.startsWith("192.168.") ||
            normalizedHost.startsWith("10.") ||
            normalizedHost.startsWith("172.16.") ||
            normalizedHost.startsWith("172.17.") ||
            normalizedHost.startsWith("172.18.") ||
            normalizedHost.startsWith("172.19.") ||
            normalizedHost.startsWith("172.20.") ||
            normalizedHost.startsWith("172.21.") ||
            normalizedHost.startsWith("172.22.") ||
            normalizedHost.startsWith("172.23.") ||
            normalizedHost.startsWith("172.24.") ||
            normalizedHost.startsWith("172.25.") ||
            normalizedHost.startsWith("172.26.") ||
            normalizedHost.startsWith("172.27.") ||
            normalizedHost.startsWith("172.28.") ||
            normalizedHost.startsWith("172.29.") ||
            normalizedHost.startsWith("172.30.") ||
            normalizedHost.startsWith("172.31.") ||
            normalizedHost == "::1" ||
            normalizedHost == "[::1]" ||
            normalizedHost.endsWith(".local")
    }

    /**
     * Configures a java.net.http.HttpClient.Builder with proxy settings from AppConfig.
     * Automatically skips proxy configuration for localhost and private IP addresses.
     *
     * @param builder The HttpClient.Builder to configure
     * @param baseUrl Optional base URL to check if local (e.g., "http://localhost:1234")
     * @return The same builder instance with proxy configured (or not, if local)
     */
    fun configureProxy(
        builder: HttpClient.Builder,
        baseUrl: String? = null,
    ): HttpClient.Builder {
        val proxyConfig = AppConfig.proxy

        // Fast path: if proxy is disabled, return immediately (most common case)
        if (proxyConfig.type == ProxyType.NONE) {
            log.trace("Proxy disabled")
            return builder
        }

        // Check if this is a local connection - skip proxy if so
        if (baseUrl != null) {
            try {
                val uri = URI(baseUrl)
                val host = uri.host
                if (isLocalHost(host)) {
                    log.debug("Skipping proxy for local host: $host")
                    return builder
                }
            } catch (e: Exception) {
                log.debug("Failed to parse URL for proxy check: $baseUrl", e)
            }
        }

        when (proxyConfig.type) {
            ProxyType.SYSTEM -> {
                // Use system proxy settings
                log.info("Using system proxy settings")
                builder.proxy(ProxySelector.getDefault())
            }

            ProxyType.HTTP, ProxyType.HTTPS -> {
                if (proxyConfig.host.isBlank()) {
                    log.warn("HTTP/HTTPS proxy enabled but host is empty")
                    return builder
                }

                try {
                    val proxyAddress = InetSocketAddress(proxyConfig.host, proxyConfig.port)

                    val proxy = Proxy(
                        Proxy.Type.HTTP,
                        proxyAddress,
                    )

                    val proxySelector = object : ProxySelector() {
                        override fun select(uri: URI?): List<Proxy> = listOf(proxy)

                        override fun connectFailed(
                            uri: URI?,
                            sa: SocketAddress?,
                            ioe: IOException?,
                        ) {
                            log.error("Proxy connection failed for $uri", ioe)
                        }
                    }

                    builder.proxy(proxySelector)

                    if (proxyConfig.username.isNotBlank()) {
                        builder.authenticator(
                            createAuthenticator(
                                proxyConfig.port,
                                proxyConfig.username,
                                proxyConfig.password,
                            ),
                        )
                    }

                    log.info("✓ HTTP/HTTPS proxy configured: ${proxyConfig.host}:${proxyConfig.port}")
                } catch (e: Exception) {
                    log.error("Failed to configure HTTP/HTTPS proxy: ${e.message}", e)
                }
            }

            ProxyType.SOCKS5 -> {
                if (proxyConfig.host.isBlank()) {
                    log.warn("SOCKS5 proxy enabled but host is empty")
                    return builder
                }

                try {
                    // For SOCKS5, we need to create a custom ProxySelector
                    val proxyAddress = InetSocketAddress(proxyConfig.host, proxyConfig.port)
                    val proxySelector = object : ProxySelector() {
                        override fun select(uri: URI?): MutableList<Proxy> = mutableListOf(
                            Proxy(
                                Proxy.Type.SOCKS,
                                proxyAddress,
                            ),
                        )

                        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                            log.error("SOCKS5 proxy connection failed for $uri", ioe)
                        }
                    }

                    builder.proxy(proxySelector)

                    log.info("✓ SOCKS5 proxy configured: ${proxyConfig.host}:${proxyConfig.port}")
                } catch (e: Exception) {
                    log.error("Failed to configure SOCKS5 proxy: ${e.message}", e)
                }
            }
        }

        return builder
    }

    private fun createAuthenticator(
        proxyPort: Int,
        username: String,
        password: String,
    ): Authenticator = object : Authenticator() {

        override fun getPasswordAuthentication(): PasswordAuthentication? {
            if (requestorType != RequestorType.PROXY) return null
            if (requestingPort != proxyPort) return null

            return PasswordAuthentication(
                username,
                password.toCharArray(),
            )
        }
    }
}
