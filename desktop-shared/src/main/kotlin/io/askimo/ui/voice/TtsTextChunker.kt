/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice

/**
 * Max characters accepted per [TextToSpeechService.synthesize] call. OpenAI's `/v1/audio/speech`
 * endpoint (used by both `io.askimo.ui.voice.impl.OpenAiTextToSpeechService` and
 * `io.askimo.ui.voice.impl.PiperTextToSpeechService`, which shares the same OpenAI-compatible
 * client) rejects input longer than 4096 characters. Kept a bit under that hard limit as safety
 * margin — see [chunkTextForTts].
 */
const val MAX_TTS_CHARS = 4000

/**
 * Splits [text] into chunks of at most [maxChars] characters so it can be fed to
 * [TextToSpeechService.synthesize] one piece at a time, e.g. by
 * `io.askimo.ui.chat.VoicePlaybackController`.
 *
 * Splits preferentially on sentence boundaries (after `.`, `!`, `?` followed by whitespace) so a
 * chunk boundary doesn't land mid-sentence; a single sentence longer than [maxChars] is hard-split
 * as a last resort. Returns `listOf(text)` unchanged when it already fits in one chunk.
 */
fun chunkTextForTts(text: String, maxChars: Int = MAX_TTS_CHARS): List<String> {
    if (text.length <= maxChars) return listOf(text)

    val sentences = text.split(Regex("(?<=[.!?])\\s+"))
    val chunks = mutableListOf<String>()
    val current = StringBuilder()

    fun flush() {
        if (current.isNotEmpty()) {
            chunks += current.toString().trim()
            current.clear()
        }
    }

    for (sentence in sentences) {
        if (sentence.isEmpty()) continue
        if (current.isNotEmpty() && current.length + 1 + sentence.length > maxChars) {
            flush()
        }
        if (sentence.length > maxChars) {
            flush()
            sentence.chunked(maxChars).forEach { chunks += it }
            continue
        }
        if (current.isNotEmpty()) current.append(' ')
        current.append(sentence)
    }
    flush()

    return chunks.ifEmpty { listOf(text.take(maxChars)) }
}
