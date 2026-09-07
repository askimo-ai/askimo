/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice

/**
 * Audio encoding used when exchanging bytes with [SpeechToTextService]/[TextToSpeechService].
 * Deliberately distinct from the low-level PCM description used by the audio recorder/player —
 * this is just a wire-format label for HTTP payloads.
 */
enum class VoiceAudioFormat(val mimeType: String, val fileExtension: String) {
    WAV("audio/wav", "wav"),
    MP3("audio/mpeg", "mp3"),
}
