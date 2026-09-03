/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice

import io.askimo.core.util.PcmAudioEncoder
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineUnavailableException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [AudioPlayer].
 *
 * Split into two groups:
 *  - Device-independent tests (always run) — no-op safety of `stop`/`pause`/`resume` on a fresh
 *    instance, and the format-decoding failure path, neither of which touch an actual audio
 *    output line.
 *  - Device-dependent tests (guarded by [audioOutputAvailable], skipped via JUnit's
 *    `Assumptions` when unavailable — headless CI runners typically have no sound card) — real
 *    playback via [javax.sound.sampled.Clip], exercising the `play`/`stop` race-safety fix
 *    (synchronized methods + stale-clip guard on the completion callback).
 */
class AudioPlayerTest {
    // 16kHz mono 16-bit PCM — same format AudioRecorder captures at; any valid WAV works for playback.
    private val format = AudioFormat(16_000f, 16, 1, true, false)

    /** Builds a silent WAV clip of the given [durationMillis] length. */
    private fun silentWav(durationMillis: Int): ByteArray {
        val sampleCount = (format.sampleRate * durationMillis / 1000).toInt()
        val pcm = ByteArray(sampleCount * format.frameSize)
        return PcmAudioEncoder.encodeBytesAsWav(format, pcm)
    }

    /**
     * Polls [predicate] every 10ms for up to [timeoutMillis] — [javax.sound.sampled.Clip.start]/
     * [javax.sound.sampled.Clip.stop] can take a brief moment to actually flip
     * [javax.sound.sampled.Clip.isRunning] on some audio backends, so tests that assert on
     * [AudioPlayer.isPlaying] right after `play`/`resume` poll instead of asserting
     * immediately, to avoid flakiness unrelated to the behavior under test.
     */
    private fun waitUntil(timeoutMillis: Long = 500, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(10)
        }
        return predicate()
    }

    // ── Device-independent ──────────────────────────────────────────────────────────────

    @Test
    fun `stop without play does not throw and leaves isPlaying false`() {
        val player = AudioPlayer()

        player.stop()

        assertFalse(player.isPlaying)
    }

    @Test
    fun `pause and resume without play do not throw`() {
        val player = AudioPlayer()

        player.pause()
        player.resume()

        assertFalse(player.isPlaying)
    }

    @Test
    fun `repeated stop calls are idempotent`() {
        val player = AudioPlayer()

        player.stop()
        player.stop()

        assertFalse(player.isPlaying)
    }

    @Test
    fun `play with unsupported audio bytes throws AudioPlaybackException`() {
        val player = AudioPlayer()
        val garbage = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        val exception = assertFailsWith<AudioPlaybackException> {
            player.play(garbage, VoiceAudioFormat.WAV)
        }
        assertTrue(
            exception.message?.contains("Unsupported audio format") == true,
            "Expected an 'Unsupported audio format' message, got: ${exception.message}",
        )
        assertFalse(player.isPlaying)
    }

    // ── Device-dependent (real playback) ────────────────────────────────────────────────

    companion object {
        /**
         * Whether a real [javax.sound.sampled.Clip] can be opened in this environment — false on
         * headless CI runners with no sound card. Computed once and reused by every guarded test
         * via [assumeTrue] so they skip cleanly instead of failing when unavailable.
         */
        private val audioOutputAvailable: Boolean by lazy {
            try {
                AudioSystem.getClip().close()
                true
            } catch (_: LineUnavailableException) {
                false
            } catch (_: Exception) {
                false
            }
        }
    }

    @Test
    fun `play a short clip fires onComplete exactly once`() {
        assumeTrue(audioOutputAvailable, "No audio output device available in this environment")
        val player = AudioPlayer()
        val completions = mutableListOf<Unit>()
        val latch = CountDownLatch(1)

        player.play(silentWav(200), VoiceAudioFormat.WAV) {
            completions.add(Unit)
            latch.countDown()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "onComplete was not invoked within timeout")
        assertEquals(1, completions.size)
        assertFalse(player.isPlaying)
    }

    @Test
    fun `stop immediately after play prevents onComplete from ever firing`() {
        assumeTrue(audioOutputAvailable, "No audio output device available in this environment")
        val player = AudioPlayer()
        var completed = false

        // A few seconds long so there's no realistic chance it finishes naturally before stop().
        player.play(silentWav(3_000), VoiceAudioFormat.WAV) { completed = true }
        player.stop()

        // Give the line-event dispatch thread a moment to (incorrectly) fire, if it were going to.
        Thread.sleep(300)

        assertFalse(completed, "onComplete must not fire for a clip that was stop()ped before completion")
        assertFalse(player.isPlaying)
    }

    @Test
    fun `pause stops playback in place and resume continues it`() {
        assumeTrue(audioOutputAvailable, "No audio output device available in this environment")
        val player = AudioPlayer()

        player.play(silentWav(2_000), VoiceAudioFormat.WAV)
        assertTrue(waitUntil { player.isPlaying }, "Expected isPlaying to become true after play()")

        player.pause()
        assertTrue(waitUntil { !player.isPlaying }, "Expected isPlaying to become false after pause()")

        player.resume()
        assertTrue(waitUntil { player.isPlaying }, "Expected isPlaying to become true again after resume()")

        player.stop()
    }

    @Test
    fun `a second play call stops and replaces the first clip, only firing the new completion`() {
        assumeTrue(audioOutputAvailable, "No audio output device available in this environment")
        val player = AudioPlayer()
        var firstCompleted = false
        val secondLatch = CountDownLatch(1)

        player.play(silentWav(3_000), VoiceAudioFormat.WAV) { firstCompleted = true }
        player.play(silentWav(200), VoiceAudioFormat.WAV) { secondLatch.countDown() }

        assertTrue(secondLatch.await(5, TimeUnit.SECONDS), "Second clip's onComplete was not invoked within timeout")
        assertFalse(firstCompleted, "The first (replaced) clip's onComplete must never fire")
    }
}
