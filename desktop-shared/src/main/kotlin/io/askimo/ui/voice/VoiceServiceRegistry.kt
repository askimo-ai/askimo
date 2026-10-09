/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice

import io.askimo.core.config.VoiceConfig
import io.askimo.core.logging.logger
import io.askimo.ui.voice.impl.LocalWhisperSpeechToTextFactory
import io.askimo.ui.voice.impl.OpenAiSpeechToTextFactory
import io.askimo.ui.voice.impl.OpenAiTextToSpeechFactory
import io.askimo.ui.voice.impl.PiperTextToSpeechFactory
import io.askimo.ui.voice.impl.whispercpp.WhisperCppFfmSpeechToTextFactory

/**
 * Resolves the active [SpeechToTextService]/[TextToSpeechService] from [VoiceConfig].
 *
 * Mirrors how `io.askimo.core.providers.ChatModelFactory` implementations are looked up per
 * `io.askimo.core.providers.ModelProvider` — but voice provider selection is entirely
 * orthogonal to the active chat provider (see `io.askimo.core.config.VoiceProvider` docs).
 */
object VoiceServiceRegistry {
    private val log = logger<VoiceServiceRegistry>()

    private val sttFactories: List<SpeechToTextFactory> = listOf(
        OpenAiSpeechToTextFactory,
        LocalWhisperSpeechToTextFactory,
        WhisperCppFfmSpeechToTextFactory,
    )

    private val ttsFactories: List<TextToSpeechFactory> = listOf(
        OpenAiTextToSpeechFactory,
        PiperTextToSpeechFactory,
    )

    // Caches the last [SpeechToTextService] built for [cachedConfig] so repeated calls with an
    // unchanged config (the common case — one dictation after another with the same settings)
    // reuse the same instance instead of rebuilding one every time. Matters most for
    // [io.askimo.ui.voice.impl.whispercpp.WhisperCppFfmSpeechToTextService], whose context holds
    // a multi-GB model in native (off-heap) memory the JVM GC can't reclaim — without this cache,
    // every dictation would load a fresh model copy and leak the previous one.
    //
    // No JVM shutdown hook cleans this up (unlike SessionManager/AgentRunManager): that service's
    // close() blocks on its mutex for the full, non-cancellable whisper_full call, and the JVM
    // waits for shutdown hooks to finish — quitting mid-dictation would hang the app for no
    // benefit, since the OS reclaims all process memory (native included) on exit regardless.
    @Volatile
    private var cachedConfig: VoiceConfig? = null

    @Volatile
    private var cachedService: SpeechToTextService? = null

    /**
     * Resolves the configured [VoiceConfig.sttProvider] to a ready-to-use [SpeechToTextService],
     * reusing the previously-built instance when [config] is unchanged from the last call (see
     * [cachedService]). Closes the outgoing instance (if [AutoCloseable]) whenever the provider
     * or any config field changes, so native resources are never silently abandoned.
     *
     * @throws VoiceServiceException if no factory is registered for the configured provider —
     *   keeps the same exception type callers already catch for network/auth failures.
     */
    fun speechToText(config: VoiceConfig): SpeechToTextService = synchronized(this) {
        cachedService?.let { if (cachedConfig == config) return it }

        val factory = sttFactories.find { it.provider == config.sttProvider }
            ?: throw VoiceServiceException("No speech-to-text implementation registered for provider ${config.sttProvider}")

        closeCachedServiceLocked()
        factory.create(config).also {
            cachedConfig = config
            cachedService = it
        }
    }

    /** Must be called while holding the monitor on `this` (see [speechToText]). */
    private fun closeCachedServiceLocked() {
        (cachedService as? AutoCloseable)?.let {
            try {
                it.close()
            } catch (e: Exception) {
                log.warn("Failed to close previous speech-to-text service", e)
            }
        }
        cachedService = null
        cachedConfig = null
    }

    /**
     * Resolves the configured [VoiceConfig.ttsProvider] to a ready-to-use [TextToSpeechService].
     * @throws VoiceServiceException if no factory is registered for the configured provider —
     *   keeps the same exception type callers already catch for network/auth failures.
     */
    fun textToSpeech(config: VoiceConfig): TextToSpeechService {
        val factory = ttsFactories.find { it.provider == config.ttsProvider }
            ?: throw VoiceServiceException("No text-to-speech implementation registered for provider ${config.ttsProvider}")
        return factory.create(config)
    }
}
