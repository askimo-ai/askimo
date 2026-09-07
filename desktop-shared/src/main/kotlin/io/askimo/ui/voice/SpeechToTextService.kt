/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice

/**
 * Converts recorded audio into plain text.
 *
 * Implementations may call a cloud API (OpenAI Whisper) or a locally-hosted server
 * (whisper.cpp / faster-whisper) — the concrete implementation is selected via
 * [VoiceConfig.sttProvider] through [VoiceServiceRegistry], fully independent of the
 * active chat [io.askimo.core.providers.ModelProvider].
 */
interface SpeechToTextService {
    /**
     * Transcribes [audio] bytes encoded as [format] into plain text.
     *
     * @throws VoiceServiceException on network, authentication, or server-side errors.
     *   Callers should catch this and surface [VoiceServiceException.message] to the user
     *   (e.g. via `EventBus`/`AppErrorEvent`) rather than a raw stack trace.
     */
    suspend fun transcribe(audio: ByteArray, format: VoiceAudioFormat = VoiceAudioFormat.WAV): String
}
