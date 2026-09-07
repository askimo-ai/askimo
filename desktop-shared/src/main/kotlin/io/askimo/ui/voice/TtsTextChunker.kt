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
 * Splits preferentially on sentence boundaries (after `.`, `!`, `?` followed by whitespace); a
 * single sentence longer than [maxChars] is hard-split via [chunkPreservingSurrogatePairs] (safe
 * for emoji/supplementary Unicode) as a last resort. Returns `listOf(text)` unchanged when it
 * already fits in one chunk.
 *
 * @throws IllegalArgumentException if [maxChars] is not positive.
 */
fun chunkTextForTts(text: String, maxChars: Int = MAX_TTS_CHARS): List<String> {
    require(maxChars > 0) { "maxChars must be positive, was $maxChars" }
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
            chunks += chunkPreservingSurrogatePairs(sentence, maxChars)
            continue
        }
        if (current.isNotEmpty()) current.append(' ')
        current.append(sentence)
    }
    flush()

    return chunks.ifEmpty { listOf(text) }
}

/**
 * Like [String.chunked], but never splits a UTF-16 surrogate pair (e.g. an emoji) across two
 * chunks — [String.chunked] is codepoint-agnostic and would otherwise leave each half with an
 * unpaired/invalid surrogate, corrupting downstream encoding or TTS synthesis.
 *
 * Pulls a boundary back by one char whenever it would land inside a pair, except when
 * [maxChars] == 1, where no boundary can keep a 2-char pair whole.
 */
private fun chunkPreservingSurrogatePairs(text: String, maxChars: Int): List<String> {
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = (start + maxChars).coerceAtMost(text.length)
        if (end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) {
            end--
        }
        end = end.coerceAtLeast(start + 1)
        chunks += text.substring(start, end)
        start = end
    }
    return chunks
}
