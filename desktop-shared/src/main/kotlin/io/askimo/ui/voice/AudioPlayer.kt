/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice

import io.askimo.core.logging.logger
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.UnsupportedAudioFileException

/** Thrown when audio playback cannot start (unsupported format, no output device, etc.). */
class AudioPlaybackException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Plays synthesised audio using [javax.sound.sampled] — no extra native dependencies needed.
 * Supports [pause]/[resume] and [stop]. Handles MP3 (OpenAI TTS) transparently: the JDK's
 * built-in `javax.sound.sampled` SPI only decodes WAV/AIFF/AU natively, so MP3 bytes are
 * transcoded to a PCM-decodable stream via [AudioSystem.getAudioInputStream] chaining.
 *
 * Each instance plays at most one clip at a time — calling [play] again stops any clip already
 * playing on that same instance first. Enforcing "only one message plays at a time" *across the
 * whole app* (the voice-output UX spec) is the caller's responsibility
 */
class AudioPlayer {
    private val log = logger<AudioPlayer>()

    @Volatile private var clip: Clip? = null

    @Volatile private var onFinished: (() -> Unit)? = null

    // True once pause()/stop() is called explicitly — distinguishes a user-initiated STOP event
    // from a natural-completion one. (Not framePosition >= frameLength: real OS-synthesized WAVs
    // can report frameLength as AudioSystem.NOT_SPECIFIED (-1), making that comparison true on
    // the very first STOP event — often just Clip startup/buffering — and cutting clips short.)
    @Volatile private var explicitlyStoppedOrPaused = false

    /** True while a clip is actively playing (not paused, not stopped). */
    val isPlaying: Boolean get() = clip?.isRunning == true

    /**
     * Decodes [audio] (format described by [format]) and starts playback.
     * @param onComplete Invoked on natural playback completion (not on [stop]).
     * @throws AudioPlaybackException if the audio cannot be decoded or no output device is available.
     *
     * Synchronized (along with [pause]/[resume]/[stop]) on this instance's monitor so
     * play/pause/stop calls from different threads/coroutines can't race — e.g. a [stop] call
     * landing in the middle of [play] swapping in a new [clip], or [onFinished] being read after
     * [stop] already cleared it.
     */
    @Synchronized
    fun play(audio: ByteArray, format: VoiceAudioFormat, onComplete: () -> Unit = {}) {
        stop()

        try {
            // Clip.open() reads the entire stream into memory up front, so it's safe to close
            // both the raw and decoded AudioInputStreams (via `use`) once open() returns —
            // otherwise repeated plays leak native/SPI decoder resources.
            AudioSystem.getAudioInputStream(ByteArrayInputStream(audio)).use { rawStream ->
                val decodedFormat = AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    rawStream.format.sampleRate,
                    16,
                    rawStream.format.channels,
                    rawStream.format.channels * 2,
                    rawStream.format.sampleRate,
                    false,
                )
                AudioSystem.getAudioInputStream(decodedFormat, rawStream).use { decodedStream ->
                    val newClip = AudioSystem.getClip()
                    newClip.open(decodedStream)
                    newClip.addLineListener { event ->
                        if (event.type == LineEvent.Type.STOP) {
                            // Runs on the line-event dispatch thread — synchronize, and only act
                            // if `newClip` is still current and this STOP wasn't caused by an
                            // explicit pause()/stop(), so a stale/self-triggered event can't fire
                            // a completion callback that no longer applies.
                            synchronized(this@AudioPlayer) {
                                if (clip === newClip && !explicitlyStoppedOrPaused) {
                                    // Clear state and release the line before invoking the
                                    // callback, so a reentrant play() call isn't clobbered.
                                    val callback = onFinished
                                    stop()
                                    callback?.invoke()
                                }
                            }
                        }
                    }
                    explicitlyStoppedOrPaused = false
                    onFinished = onComplete
                    clip = newClip
                    newClip.start()
                }
            }
        } catch (e: UnsupportedAudioFileException) {
            throw AudioPlaybackException(
                "Unsupported audio format for playback (${format.name}). " +
                    "MP3 playback requires an MP3 SPI provider on the classpath.",
                e,
            )
        } catch (e: LineUnavailableException) {
            throw AudioPlaybackException("No audio output device available for playback.", e)
        } catch (e: Exception) {
            log.warn("Audio playback failed", e)
            throw AudioPlaybackException("Could not play audio: ${e.message}", e)
        }
    }

    /** Pauses playback in place; [resume] continues from the same position. No-op if not playing. */
    @Synchronized
    fun pause() {
        clip?.takeIf { it.isRunning }?.let {
            explicitlyStoppedOrPaused = true
            it.stop()
        }
    }

    /** Resumes playback from where it was paused. No-op if not paused or already playing. */
    @Synchronized
    fun resume() {
        clip?.takeIf { it.isOpen && !it.isRunning }?.let {
            explicitlyStoppedOrPaused = false
            it.start()
        }
    }

    /** Stops playback and releases the underlying audio line. Safe to call when not playing. */
    @Synchronized
    fun stop() {
        explicitlyStoppedOrPaused = true
        clip?.let {
            if (it.isRunning) it.stop()
            it.close()
        }
        clip = null
        onFinished = null
    }
}
