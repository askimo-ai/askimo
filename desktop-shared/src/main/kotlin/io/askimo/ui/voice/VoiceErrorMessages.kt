/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice

import java.net.ConnectException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

/**
 * Builds a user-presentable error message for a failure encountered while calling a
 * voice (speech-to-text / text-to-speech) backend.
 *
 * Network-related causes (DNS resolution failure, connection refused, etc.) often carry a
 * `null` message on the underlying JDK exception (e.g. [UnresolvedAddressException]), which
 * langchain4j re-wraps without adding its own message. Left unhandled, this surfaces confusing
 * messages like `"OpenAI TTS request failed: null"`. This walks the cause chain to detect those
 * cases and substitutes a friendlier, actionable explanation; otherwise it falls back to the
 * closest non-blank message found in the chain, or the exception's simple class name.
 *
 * @param prefix a short description of what failed, e.g. `"OpenAI TTS request failed"`.
 */
fun Throwable.toFriendlyVoiceErrorMessage(prefix: String): String {
    val networkHint = findNetworkErrorHint()
    if (networkHint != null) {
        return "$prefix: $networkHint"
    }
    return "$prefix: ${findBestAvailableDetail()}"
}

/**
 * Walks the cause chain looking for well-known network-related exceptions and returns a
 * friendly, actionable hint describing them. Returns `null` if none is found.
 */
private fun Throwable.findNetworkErrorHint(): String? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 10) {
        when (current) {
            is UnresolvedAddressException ->
                return "unable to resolve the server address. Check your internet connection and the configured endpoint URL."

            is UnknownHostException ->
                return "unknown host" +
                    (current.message?.takeIf { it.isNotBlank() }?.let { " (\"$it\")" } ?: "") +
                    ". Check your internet connection and the configured endpoint URL."

            is ConnectException ->
                return "connection refused" +
                    (current.message?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
                    ". Is the server running and reachable?"

            else -> Unit
        }
        val next = current.cause
        current = if (next !== current) next else null
        depth++
    }
    return null
}

/**
 * Walks the cause chain looking for the first non-blank message, falling back to the
 * exception's simple class name if every exception in the chain has a `null`/blank message.
 */
private fun Throwable.findBestAvailableDetail(): String {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 10) {
        val msg = current.message
        if (!msg.isNullOrBlank()) return msg
        val next = current.cause
        current = if (next !== current) next else null
        depth++
    }
    return this::class.simpleName?.let { "unexpected error ($it)" } ?: "unexpected error"
}
