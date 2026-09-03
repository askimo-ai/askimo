/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

import org.junit.jupiter.api.Test
import java.nio.file.Files
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcmAudioEncoderTest {
    // 16kHz mono 16-bit PCM — same format AudioRecorder captures at.
    private val format = AudioFormat(16_000f, 16, 1, true, false)

    private fun littleEndianSample(value: Short): ByteArray = byteArrayOf((value.toInt() and 0xFF).toByte(), ((value.toInt() shr 8) and 0xFF).toByte())

    // ── computeRmsLevel ─────────────────────────────────────────────────────────────────

    @Test
    fun `computeRmsLevel returns 0 for silence`() {
        val silence = ByteArray(64) // all-zero bytes decode to sample value 0
        assertEquals(0f, PcmAudioEncoder.computeRmsLevel(silence, silence.size))
    }

    @Test
    fun `computeRmsLevel returns 1 for a full-scale constant signal`() {
        val fullScale = (0 until 32).fold(ByteArray(0)) { acc, _ -> acc + littleEndianSample(Short.MAX_VALUE) }
        val level = PcmAudioEncoder.computeRmsLevel(fullScale, fullScale.size)
        assertEquals(1f, level, "Full-scale constant PCM should normalize to 1.0")
    }

    @Test
    fun `computeRmsLevel returns 0_5 for a half-scale constant signal`() {
        val halfScale = (0 until 32).fold(ByteArray(0)) { acc, _ ->
            acc + littleEndianSample((Short.MAX_VALUE / 2).toShort())
        }
        val level = PcmAudioEncoder.computeRmsLevel(halfScale, halfScale.size)
        assertTrue(level in 0.49f..0.51f, "Expected ~0.5, got $level")
    }

    @Test
    fun `computeRmsLevel handles an empty buffer`() {
        assertEquals(0f, PcmAudioEncoder.computeRmsLevel(ByteArray(0), 0))
    }

    @Test
    fun `computeRmsLevel ignores a trailing odd byte`() {
        // 3 bytes: one full 16-bit sample (full-scale) + one dangling byte that can't form a pair.
        val bytes = littleEndianSample(Short.MAX_VALUE) + byteArrayOf(0x7F)
        val level = PcmAudioEncoder.computeRmsLevel(bytes, bytes.size)
        assertEquals(1f, level, "The dangling trailing byte should be ignored, not crash or skew the result")
    }

    @Test
    fun `computeRmsLevel result is always clamped between 0 and 1`() {
        val fullScale = (0 until 8).fold(ByteArray(0)) { acc, _ -> acc + littleEndianSample(Short.MAX_VALUE) }
        val level = PcmAudioEncoder.computeRmsLevel(fullScale, fullScale.size)
        assertTrue(level in 0f..1f)
    }

    // ── encodeBytesAsWav / encodeFileAsWav ──────────────────────────────────────────────

    @Test
    fun `encodeBytesAsWav produces a valid RIFF WAVE header`() {
        val pcm = ByteArray(320) // 160 samples of silence
        val wav = PcmAudioEncoder.encodeBytesAsWav(format, pcm)

        assertTrue(wav.size >= 44, "Expected at least a 44-byte WAV header, got ${wav.size}")
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
    }

    @Test
    fun `encodeBytesAsWav round-trips through AudioSystem with the original PCM data`() {
        val samples = shortArrayOf(0, 100, -100, Short.MAX_VALUE, Short.MIN_VALUE, 42)
        val pcm = samples.fold(ByteArray(0)) { acc, s -> acc + littleEndianSample(s) }

        val wav = PcmAudioEncoder.encodeBytesAsWav(format, pcm)

        AudioSystem.getAudioInputStream(wav.inputStream()).use { decoded ->
            assertEquals(format.sampleRate, decoded.format.sampleRate)
            assertEquals(format.channels, decoded.format.channels)
            assertEquals(format.sampleSizeInBits, decoded.format.sampleSizeInBits)
            assertEquals(samples.size.toLong(), decoded.frameLength)

            val decodedBytes = decoded.readAllBytes()
            assertEquals(pcm.size, decodedBytes.size)
            assertTrue(pcm.contentEquals(decodedBytes), "Decoded PCM should exactly match the original samples")
        }
    }

    @Test
    fun `encodeFileAsWav with null path produces an empty but valid WAV`() {
        val wav = PcmAudioEncoder.encodeFileAsWav(format, null, 0L)

        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        AudioSystem.getAudioInputStream(wav.inputStream()).use { decoded ->
            assertEquals(0L, decoded.frameLength)
        }
    }

    @Test
    fun `encodeFileAsWav with a non-positive frame count produces an empty WAV even if the file has data`() {
        val tempFile = Files.createTempFile("pcm-audio-encoder-test-", ".pcm")
        try {
            Files.write(tempFile, littleEndianSample(Short.MAX_VALUE))

            val wav = PcmAudioEncoder.encodeFileAsWav(format, tempFile, 0L)

            AudioSystem.getAudioInputStream(wav.inputStream()).use { decoded ->
                assertEquals(0L, decoded.frameLength)
            }
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun `encodeFileAsWav streams PCM from disk and matches encodeBytesAsWav for the same data`() {
        val samples = shortArrayOf(1, 2, 3, -1, -2, -3, 12345, -12345)
        val pcm = samples.fold(ByteArray(0)) { acc, s -> acc + littleEndianSample(s) }
        val tempFile = Files.createTempFile("pcm-audio-encoder-test-", ".pcm")
        try {
            Files.write(tempFile, pcm)

            val fromFile = PcmAudioEncoder.encodeFileAsWav(format, tempFile, samples.size.toLong())
            val fromBytes = PcmAudioEncoder.encodeBytesAsWav(format, pcm)

            assertTrue(fromFile.contentEquals(fromBytes), "Streaming-from-disk and in-memory encoding should be byte-identical")
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }
}
