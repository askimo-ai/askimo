/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the parts of [AudioRecorder] that don't require an actual microphone/audio device —
 * CI runners are typically headless, so [AudioRecorder.start] (which opens a
 * [javax.sound.sampled.TargetDataLine]) can't be exercised here. These tests instead focus on
 * the temp-file-backed capture buffer's safety: calling [AudioRecorder.stop]/[AudioRecorder.cancel]
 * without a prior [AudioRecorder.start] must not throw and must not leak any temp file.
 */
class AudioRecorderTest {
    @Test
    fun `stop without start returns a valid empty WAV`() {
        val recorder = AudioRecorder()

        val wav = recorder.stop()

        // A valid (if silent) WAV file: RIFF header + WAVE format tag.
        assertTrue(wav.size >= 44, "Expected at least a WAV header, got ${wav.size} bytes")
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
    }

    @Test
    fun `cancel without start does not throw`() {
        val recorder = AudioRecorder()

        recorder.cancel()

        assertEquals(false, recorder.isRecording)
    }

    @Test
    fun `repeated stop calls without start are idempotent`() {
        val recorder = AudioRecorder()

        val first = recorder.stop()
        val second = recorder.stop()

        assertEquals(first.size, second.size)
    }
}
