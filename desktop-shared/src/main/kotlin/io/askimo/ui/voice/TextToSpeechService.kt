/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice

/**
 * Synthesises plain text into spoken audio.
 *
 * Callers with long text should split it via [chunkTextForTts] first — TTS providers cap
 * request input length (see that function's docs).
 */
interface TextToSpeechService {
    /** Audio format returned by [synthesize] — used by the audio player to decode correctly. */
    val outputFormat: VoiceAudioFormat

    /**
     * Synthesises [text] into audio bytes.
     *
     * @throws VoiceServiceException on network, authentication, or server-side errors.
     */
    suspend fun synthesize(text: String): ByteArray
}
