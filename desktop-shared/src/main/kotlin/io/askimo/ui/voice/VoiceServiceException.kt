/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice

/**
 * Thrown by [SpeechToTextService]/[TextToSpeechService] implementations on recoverable failure
 * (auth error, network error, unreachable local server, unexpected HTTP status, etc.).
 * [message] is expected to be user-presentable.
 */
class VoiceServiceException(message: String, cause: Throwable? = null) : Exception(message, cause)
