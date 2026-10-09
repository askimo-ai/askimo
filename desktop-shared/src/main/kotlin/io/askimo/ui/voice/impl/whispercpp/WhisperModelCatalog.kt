/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice.impl.whispercpp

/**
 * Whisper.cpp `ggml-*.bin` model tiers offered in Settings > Voice for
 * [io.askimo.core.config.VoiceProvider.LOCAL_WHISPER_FFM]. All are **multilingual** conversions
 * from the official `ggerganov/whisper.cpp` Hugging Face repo (filenames confirmed against the
 * live repo listing on 2026-10-08) — Askimo's voice input is not English-only (see
 * `OpenAiSpeechToTextService.whisperLanguageCode()`), so `.en`-suffixed variants are excluded.
 *
 * Sizes are approximate (actual download size reported by the HTTP response is used for the
 * progress UI) — listed here only for the Settings picker's human-readable size hints.
 */
enum class WhisperModelCatalog(
    /** Filename on the HF repo, also used as the local filename under [io.askimo.core.util.AskimoHome.whisperModelsDir]. */
    val fileName: String,
    val approxSizeBytes: Long,
    /** i18n key for the tier's short label (e.g. "Fast", "Balanced") — see messages.properties. */
    val labelKey: String,
) {
    FAST("ggml-base.bin", 142L * 1024 * 1024, "settings.voice.whisper_model.tier.fast"),
    BALANCED("ggml-small.bin", 466L * 1024 * 1024, "settings.voice.whisper_model.tier.balanced"),
    BEST("ggml-large-v3.bin", 3_095L * 1024 * 1024, "settings.voice.whisper_model.tier.best"),
    ;

    val downloadUrl: String get() = "$HF_BASE_URL/$fileName"

    companion object {
        private const val HF_BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

        fun fromFileName(fileName: String): WhisperModelCatalog? = entries.find { it.fileName == fileName }
    }
}
