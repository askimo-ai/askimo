/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice

/** How a [TextToSpeechService] wants its input split before repeated [TextToSpeechService.synthesize] calls. */
sealed interface TtsChunkMode {
    /** Packs as many sentences as fit under [maxChars] per chunk — see [chunkTextForTts]. */
    data class Packed(val maxChars: Int = MAX_TTS_CHARS) : TtsChunkMode

    /** One chunk per complete sentence — see [chunkTextForTtsBySentence]. */
    data class PerSentence(val maxCharsPerSentence: Int = MAX_TTS_SENTENCE_CHARS) : TtsChunkMode
}

/** Splits [text] per this service's declared [TextToSpeechService.chunkMode]. */
fun TextToSpeechService.chunkText(text: String): List<String> = when (val mode = chunkMode) {
    is TtsChunkMode.Packed -> chunkTextForTts(text, mode.maxChars)
    is TtsChunkMode.PerSentence -> chunkTextForTtsBySentence(text, mode.maxCharsPerSentence)
}

/**
 * Synthesises plain text into spoken audio.
 *
 * Callers with long text should split it via [chunkText] first (not [chunkTextForTts] directly)
 * so each provider's own [chunkMode] is respected.
 */
interface TextToSpeechService {
    /** Audio format returned by [synthesize] — used by the audio player to decode correctly. */
    val outputFormat: VoiceAudioFormat

    /**
     * How [chunkText] should granularize input before repeated [synthesize] calls. Defaults to
     * [TtsChunkMode.Packed] — fewer/larger requests suit network providers (OpenAI, Piper).
     *
     * Local-subprocess providers (say/PowerShell/espeak-ng) should override this to
     * [TtsChunkMode.PerSentence]: each call is cheap, and chunking per sentence means audio
     * starts almost instantly instead of waiting for a large packed chunk to finish synthesizing.
     */
    val chunkMode: TtsChunkMode get() = TtsChunkMode.Packed()

    /**
     * Synthesises [text] into audio bytes.
     *
     * @throws VoiceServiceException on network, authentication, or server-side errors.
     */
    suspend fun synthesize(text: String): ByteArray
}
