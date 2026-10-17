/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice.impl

import io.askimo.core.config.VoiceConfig
import io.askimo.core.config.VoiceProvider
import io.askimo.core.util.PcmAudioEncoder
import io.askimo.ui.voice.AudioPlayer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineUnavailableException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end test against the *real* OS TTS tool (no mocking) — skips itself (via [assumeTrue])
 * when the required tool isn't available, so it stays green on CI images without `espeak-ng`
 * while still exercising the real command where it is installed.
 */
class SystemTextToSpeechServiceTest {
    private val service = SystemTextToSpeechService(VoiceConfig())

    @Test
    fun `synthesize produces a non-empty WAV file via the real OS TTS tool`() {
        assumeTrue(systemTtsToolAvailable(), "No OS TTS tool available in this environment")

        val bytes = runBlocking { service.synthesize("Hello from Askimo") }

        assertTrue(bytes.isNotEmpty(), "Expected non-empty audio bytes")
        // RIFF/WAVE header — all three branches (say/PowerShell/espeak-ng) emit a linear-PCM WAV.
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
    }

    @Test
    fun `declared outputFormat is WAV`() {
        assertEquals(io.askimo.ui.voice.VoiceAudioFormat.WAV, service.outputFormat)
    }

    @Test
    fun `factory is registered for the SYSTEM provider`() {
        assertEquals(VoiceProvider.SYSTEM, SystemTextToSpeechFactory.provider)
    }

    @Test
    fun `an invalid leftover voice name (e g OpenAI's 'alloy') falls back to the OS default instead of failing`() {
        assumeTrue(systemTtsToolAvailable(), "No OS TTS tool available in this environment")

        // Regression test: a ttsVoice value carried over from another provider (OpenAI defaults
        // to "alloy", not a real macOS/Linux voice name) made `say`/espeak-ng fail outright. The
        // service now retries once without the voice flag as a defensive fallback.
        val serviceWithBadVoice = SystemTextToSpeechService(VoiceConfig(ttsVoice = "alloy"))

        val bytes = runBlocking { serviceWithBadVoice.synthesize("The capital of Vietnam is Hanoi.") }

        assertTrue(bytes.isNotEmpty(), "Expected synthesis to succeed via fallback despite the invalid voice name")
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
    }

    @Test
    fun `a short synthesized phrase actually plays to completion via AudioPlayer`() {
        assumeTrue(systemTtsToolAvailable(), "No OS TTS tool available in this environment")
        assumeTrue(audioOutputAvailable(), "No audio output device available in this environment")

        // Regression test: a short synthesized clip's onComplete callback never fired (playback
        // hung forever). Root cause was in AudioPlayer comparing Clip.framePosition to
        // Clip.frameLength, which is unreliable for real OS-synthesized WAVs (frameLength can be
        // AudioSystem.NOT_SPECIFIED), misreading the first STOP event as "finished" and cutting
        // the clip off. Exercises the actual synthesize() -> play() pipeline end-to-end.
        val bytes = runBlocking { service.synthesize("The capital of Vietnam is Hanoi.") }

        val player = AudioPlayer()
        val latch = CountDownLatch(1)

        player.play(bytes, io.askimo.ui.voice.VoiceAudioFormat.WAV) { latch.countDown() }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "onComplete was not invoked within timeout — playback hung or never started")
        assertTrue(!player.isPlaying, "Expected playback to have finished")
    }

    @Test
    fun `text ending exactly at sentence-final punctuation produces audible (non-silent) speech`() {
        assumeTrue(systemTtsToolAvailable(), "No OS TTS tool available in this environment")

        // Regression test: text ending exactly at a period, piped via stdin with no trailing
        // newline, produced a structurally valid but silent/near-empty WAV — racing `say`/
        // espeak-ng's sentence-boundary detection against immediate EOF. Fixed by appending a
        // trailing newline in stdinPayload(). Decodes actual PCM to check for real signal energy.
        val endsWithPeriod = runBlocking { service.synthesize("The capital of Vietnam is Hanoi.") }
        val noPunctuation = runBlocking { service.synthesize("Hello from Askimo") }

        val periodRms = decodedRms(endsWithPeriod)
        val controlRms = decodedRms(noPunctuation)

        assertTrue(
            periodRms > 0.01f,
            "Expected audible (non-silent) speech for text ending at sentence-final punctuation, " +
                "got RMS=$periodRms (control phrase RMS=$controlRms)",
        )
    }

    /** Decodes [wavBytes] to 16-bit PCM and computes its RMS amplitude via [PcmAudioEncoder.computeRmsLevel]. */
    private fun decodedRms(wavBytes: ByteArray): Float = AudioSystem.getAudioInputStream(ByteArrayInputStream(wavBytes)).use { stream ->
        val pcm = stream.readBytes()
        PcmAudioEncoder.computeRmsLevel(pcm, pcm.size)
    }

    @Test
    fun `speedArgs maps 1_0x to each platform's own documented default`() {
        // macOS `say`'s default is ~175-200 wpm; 180 lands squarely in that range.
        assertEquals(listOf("-r", "180"), service.speedArgs(SystemTtsPlatform.MACOS, 1.0))
        // espeak-ng's default is ~175 wpm.
        assertEquals(listOf("-s", "175"), service.speedArgs(SystemTtsPlatform.LINUX, 1.0))
        // Windows has no -r/-s flag — handled separately via windowsRate() in the PowerShell script.
        assertEquals(emptyList(), service.speedArgs(SystemTtsPlatform.WINDOWS, 1.0))
    }

    @Test
    fun `speedArgs scales proportionally and clamps at the extremes of the 0_25x-4_0x range`() {
        // 180 * 0.25 = 45, below the 90 wpm floor say still articulates clearly at, so it clamps up.
        assertEquals(listOf("-r", "90"), service.speedArgs(SystemTtsPlatform.MACOS, 0.25))
        assertEquals(listOf("-r", "720"), service.speedArgs(SystemTtsPlatform.MACOS, 4.0))
        // 175 * 0.25 = 43.75 -> truncated to 43, within espeak-ng's valid range.
        assertEquals(listOf("-s", "43"), service.speedArgs(SystemTtsPlatform.LINUX, 0.25))
        assertEquals(listOf("-s", "600"), service.speedArgs(SystemTtsPlatform.LINUX, 4.0))
        // Out-of-range input must still clamp rather than produce a nonsensical value.
        assertEquals(listOf("-r", "720"), service.speedArgs(SystemTtsPlatform.MACOS, 100.0))
    }

    @Test
    fun `windowsRate is 0 at 1_0x and spans -10 to 10 piecewise-linearly around it`() {
        assertEquals(0, service.windowsRate(1.0))
        assertEquals(-10, service.windowsRate(0.25))
        assertEquals(10, service.windowsRate(4.0))
        // Out-of-range input must still clamp to the valid -10..10 scale.
        assertEquals(10, service.windowsRate(100.0))
        assertEquals(-10, service.windowsRate(0.01))
    }

    @Test
    fun `listAvailableVoices returns installed voice names without throwing`() {
        assumeTrue(systemTtsToolAvailable(), "No OS TTS tool available in this environment")

        val voices = runBlocking { service.listAvailableVoices() }

        assertTrue(voices.isNotEmpty(), "Expected at least one installed OS voice to be listed")
        assertTrue(voices.all { it.isNotBlank() }, "Voice names should never be blank")
    }

    private fun audioOutputAvailable(): Boolean = try {
        AudioSystem.getClip().close()
        true
    } catch (_: LineUnavailableException) {
        false
    } catch (_: Exception) {
        false
    }

    private fun systemTtsToolAvailable(): Boolean {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val tool = when {
            os.contains("mac") -> "say"
            os.contains("win") -> "powershell"
            else -> "espeak-ng"
        }
        return try {
            val which = ProcessBuilder(if (os.contains("win")) listOf("where", tool) else listOf("which", tool))
                .redirectErrorStream(true)
                .start()
            which.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }
}
