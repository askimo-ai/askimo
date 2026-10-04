/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.file.Path
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.sqrt

/**
 * Pure PCM/WAV helpers — no microphone/audio-device access, just byte-level computation and
 * [javax.sound.sampled] encoding. Lives in `:shared` (rather than alongside
 * `io.askimo.ui.voice.AudioRecorder` in `desktop-shared`) specifically so it can be unit-tested
 * from modules like `:cli` that don't pull in the desktop UI/Compose dependency graph, and
 * without requiring an actual audio input device (unavailable on headless CI runners).
 */
object PcmAudioEncoder {
    /**
     * Computes the normalized RMS (root-mean-square) amplitude of a chunk of 16-bit
     * little-endian PCM samples, as a value in `0f..1f` (0 = silence, 1 = full-scale).
     */
    fun computeRmsLevel(buf: ByteArray, length: Int): Float {
        var sumSquares = 0.0
        var sampleCount = 0
        var i = 0
        while (i + 1 < length) {
            val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort()
            sumSquares += sample * sample.toDouble()
            sampleCount++
            i += 2
        }
        if (sampleCount == 0) return 0f
        val rms = sqrt(sumSquares / sampleCount)
        return (rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Encodes the raw PCM audio at [pcmFile] (described by [format]) as a complete WAV file,
     * streaming directly from disk — the recording is never materialized as a single
     * in-memory byte array. Falls back to an empty (silent) WAV clip when [pcmFile] is null or
     * [frameCount] is non-positive.
     */
    fun encodeFileAsWav(format: AudioFormat, pcmFile: Path?, frameCount: Long): ByteArray {
        if (pcmFile == null || frameCount <= 0) {
            return encodeBytesAsWav(format, ByteArray(0))
        }
        val output = ByteArrayOutputStream()
        FileInputStream(pcmFile.toFile()).use { pcmStream ->
            AudioInputStream(pcmStream, format, frameCount).use { audioStream ->
                AudioSystem.write(audioStream, AudioFileFormat.Type.WAVE, output)
            }
        }
        return output.toByteArray()
    }

    /**
     * Encodes raw PCM [bytes] (described by [format]) as a complete WAV file — convenience
     * overload for small in-memory clips (e.g. tests), computing the frame count from
     * `bytes.size / format.frameSize`.
     */
    fun encodeBytesAsWav(format: AudioFormat, bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        val frameCount = (bytes.size / format.frameSize).toLong()
        bytes.inputStream().use { pcmStream ->
            AudioInputStream(pcmStream, format, frameCount).use { audioStream ->
                AudioSystem.write(audioStream, AudioFileFormat.Type.WAVE, output)
            }
        }
        return output.toByteArray()
    }
}
