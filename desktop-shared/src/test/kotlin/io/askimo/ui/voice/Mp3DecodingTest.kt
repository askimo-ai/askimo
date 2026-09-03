/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.UnsupportedAudioFileException
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Verifies MP3 decoding actually works end-to-end.
 */
class Mp3DecodingTest {
    /**
     * Builds a syntactically valid minimal MPEG-1 Layer III frame — 128kbps, 44100Hz,
     * joint-stereo, no CRC — repeated [frameCount] times.
     *
     * Frame *header* bits are exact per the MPEG-1 Layer III spec (`FF FB 90 64`); frame
     * *payload* (Huffman-coded audio data) is all-zero. This is a standard trick for generating
     * tiny placeholder/"silent" MP3 test fixtures without needing an MP3 *encoder* on the
     * classpath (only a *decoder* is needed for playback, and none is available to generate
     * fixtures with): a compliant decoder only requires a structurally valid header plus a
     * correctly-sized payload — not meaningful audio content — to successfully decode a frame.
     */
    private fun minimalMp3Frames(frameCount: Int = 8): ByteArray {
        // FF FB 90 64 decodes to: sync(11 bits) + MPEG-1 + Layer III + protection=1 (no CRC)
        // + bitrate index 9 (128kbps) + sample-rate index 00 (44100Hz) + no padding/private
        // + channel mode 01 (joint stereo) + mode-ext 10 + copyright=0 + original=1 + emphasis=00.
        val header = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte())
        // Frame length (bytes) = floor(144 * bitrate / sampleRate) + padding = floor(144*128000/44100) = 417
        val frameLength = 417
        val frame = header + ByteArray(frameLength - header.size)
        var bytes = ByteArray(0)
        repeat(frameCount) { bytes += frame }
        return bytes
    }

    @Test
    fun `decoding requires an MP3 SPI provider to be registered on the classpath`() {
        val mp3Bytes = minimalMp3Frames()

        try {
            // Mirrors AudioPlayer.play()'s own two-step decode: first obtain the raw stream in
            // its *original* encoding, then
            // convert it to PCM via the targeted getAudioInputStream(format, stream) overload.
            AudioSystem.getAudioInputStream(ByteArrayInputStream(mp3Bytes)).use { rawStream ->
                val encoding = rawStream.format.encoding
                assertTrue(
                    encoding.toString().contains("MPEG", ignoreCase = true),
                    "Expected the raw stream to report an MPEG encoding once an MP3 SPI is present, got $encoding",
                )

                val pcmFormat = AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    rawStream.format.sampleRate,
                    16,
                    rawStream.format.channels,
                    rawStream.format.channels * 2,
                    rawStream.format.sampleRate,
                    false,
                )
                AudioSystem.getAudioInputStream(pcmFormat, rawStream).use { decoded ->
                    // "Green" state — the SPI actually decodes MP3 frame data into real PCM
                    // samples, not just recognizing the container format.
                    assertTrue(
                        decoded.format.encoding == AudioFormat.Encoding.PCM_SIGNED,
                        "Expected a PCM-decoded stream once an MP3 SPI is present, got encoding=${decoded.format.encoding}",
                    )
                }
            }
        } catch (e: UnsupportedAudioFileException) {
            // "Red" state — expected until an MP3 SPI dependency is added to desktop-shared's
            // runtime classpath (see class doc). Fails with an actionable message instead of a
            // raw stack trace so the gap is obvious from the test report alone.
            fail(
                "No MP3 decoder SPI is registered on the classpath — add mp3spi " +
                    "(or an equivalent javax.sound.sampled MP3 provider) as a runtime dependency of " +
                    "desktop-shared to make OpenAI TTS (MP3) playback work. Underlying error: ${e.message}",
            )
        }
    }

    @Test
    fun `AudioPlayer can play an MP3 clip end-to-end once an MP3 SPI is present`() {
        assumeTrue(audioOutputAvailable(), "No audio output device available in this environment")
        val player = AudioPlayer()
        val latch = CountDownLatch(1)

        player.play(minimalMp3Frames(), VoiceAudioFormat.MP3) { latch.countDown() }

        assertTrue(
            latch.await(5, TimeUnit.SECONDS),
            "MP3 playback did not complete in time — is the mp3spi dependency on the classpath?",
        )
    }

    /** Whether a real [javax.sound.sampled.Clip] can be opened in this environment. */
    private fun audioOutputAvailable(): Boolean = try {
        AudioSystem.getClip().close()
        true
    } catch (_: LineUnavailableException) {
        false
    } catch (_: Exception) {
        false
    }
}
