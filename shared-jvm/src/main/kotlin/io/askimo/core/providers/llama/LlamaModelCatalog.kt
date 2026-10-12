/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.llama

/**
 * A downloadable `.gguf` model weight file for the built-in local LLM provider.
 *
 * [fileName] is also what's sent as the OpenAI `"model"` field by langchain4j's OpenAI client —
 * [LlamaServerProcessManager]'s reverse proxy reads that same field back out of each incoming
 * request to decide which GGUF file to load, so this value must round-trip exactly.
 *
 * Not every entry comes from the curated [LlamaModelCatalog] — see
 * [io.askimo.core.providers.llama.HuggingFaceGgufBrowser] for entries discovered dynamically by
 * searching Hugging Face. [LlamaServerProcessManager]'s proxy and [LlamaModelDownloader] both
 * resolve purely by [fileName] under [io.askimo.core.util.AskimoHome.llamaModelsDir], so
 * dynamically-discovered entries work identically to curated ones without needing to be
 * registered anywhere.
 */
data class LlamaModelEntry(
    val fileName: String,
    /** i18n key for the display label. Ignored when [label] is non-null. */
    val labelKey: String,
    val approxSizeBytes: Long,
    val downloadUrl: String,
    /** `true` for embedding-only models — started with `--embedding` and excluded from chat pickers. */
    val isEmbedding: Boolean = false,
    /**
     * `llama-server --pooling` value used when [isEmbedding] is `true`. Ignored for chat entries.
     *
     * Defaults to `"mean"` — correct for BERT-style bi-encoder embedding models (e.g.
     * `nomic-embed-text`). Decoder-only/causal embedding models trained with last-token pooling
     * (e.g. Qwen3-Embedding) must override this to `"last"` — forcing mean-pooling on their
     * hidden states is numerically unstable and llama-server's JSON serializer silently turns
     * the resulting NaN values into `null` array entries, which crashes langchain4j's response
     * parsing (`Embedding.from` → NPE on `Float.floatValue()`).
     */
    val poolingType: String = "mean",
    /**
     * Plain-text display label, used instead of resolving [labelKey] via i18n — set for entries
     * discovered dynamically (e.g. from a Hugging Face search) that have no translated label.
     */
    val label: String? = null,
    /**
     * Hugging Face repo id this entry came from (e.g. `"bartowski/Llama-3.2-3B-Instruct-GGUF"`),
     * for display/attribution. Null for entries not sourced from a HF search.
     */
    val sourceRepoId: String? = null,
)

/**
 * Curated catalog of GGUF models offered in Settings for the built-in local LLM provider.
 *
 * Quantization: `Q4_K_M` is used for all chat entries — the standard "good quality / reasonable
 * size" default recommended by the llama.cpp community for CPU inference. Sizes are approximate
 * (actual file size varies slightly by quantization build).
 *
 * Deliberately spans multiple model families/sizes (not just one "tier" per size) so users can
 * pick based on their own priorities — general chat, coding, or reasoning — rather than only
 * speed vs. quality.
 */
object LlamaModelCatalog {

    private const val HF_BARTOWSKI = "https://huggingface.co/bartowski"
    private const val HF_NOMIC = "https://huggingface.co/nomic-ai"
    private const val HF_QWEN = "https://huggingface.co/Qwen"

    // ── General-purpose chat, small → large ────────────────────────────────────────────────

    val FAST = LlamaModelEntry(
        fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
        labelKey = "provider.llama.model.fast",
        approxSizeBytes = 2_020L * 1024 * 1024,
        downloadUrl = "$HF_BARTOWSKI/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
    )

    val QWEN_SMALL = LlamaModelEntry(
        fileName = "Qwen2.5-3B-Instruct-Q4_K_M.gguf",
        labelKey = "provider.llama.model.qwen_small",
        approxSizeBytes = 1_930L * 1024 * 1024,
        downloadUrl = "$HF_BARTOWSKI/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_K_M.gguf",
    )

    val PHI = LlamaModelEntry(
        fileName = "Phi-3.5-mini-instruct-Q4_K_M.gguf",
        labelKey = "provider.llama.model.phi",
        approxSizeBytes = 2_390L * 1024 * 1024,
        downloadUrl = "$HF_BARTOWSKI/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
    )

    val MISTRAL = LlamaModelEntry(
        fileName = "Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
        labelKey = "provider.llama.model.mistral",
        approxSizeBytes = 4_370L * 1024 * 1024,
        downloadUrl = "$HF_BARTOWSKI/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
    )

    val BALANCED = LlamaModelEntry(
        fileName = "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
        labelKey = "provider.llama.model.balanced",
        approxSizeBytes = 4_920L * 1024 * 1024,
        downloadUrl = "$HF_BARTOWSKI/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
    )

    val GEMMA = LlamaModelEntry(
        fileName = "gemma-2-9b-it-Q4_K_M.gguf",
        labelKey = "provider.llama.model.gemma",
        approxSizeBytes = 5_760L * 1024 * 1024,
        downloadUrl = "$HF_BARTOWSKI/gemma-2-9b-it-GGUF/resolve/main/gemma-2-9b-it-Q4_K_M.gguf",
    )

    val BEST = LlamaModelEntry(
        fileName = "Qwen2.5-14B-Instruct-Q4_K_M.gguf",
        labelKey = "provider.llama.model.best",
        approxSizeBytes = 8_990L * 1024 * 1024,
        downloadUrl = "$HF_BARTOWSKI/Qwen2.5-14B-Instruct-GGUF/resolve/main/Qwen2.5-14B-Instruct-Q4_K_M.gguf",
    )

    // ── Specialized: coding & reasoning ─────────────────────────────────────────────────────

    val CODER = LlamaModelEntry(
        fileName = "Qwen2.5-Coder-7B-Instruct-Q4_K_M.gguf",
        labelKey = "provider.llama.model.coder",
        approxSizeBytes = 4_680L * 1024 * 1024,
        downloadUrl = "$HF_BARTOWSKI/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-7B-Instruct-Q4_K_M.gguf",
    )

    val REASONING = LlamaModelEntry(
        fileName = "DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
        labelKey = "provider.llama.model.reasoning",
        approxSizeBytes = 4_680L * 1024 * 1024,
        downloadUrl = "$HF_BARTOWSKI/DeepSeek-R1-Distill-Qwen-7B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
    )

    // ── Embedding ────────────────────────────────────────────────────────────────────────────

    val EMBEDDING = LlamaModelEntry(
        fileName = "nomic-embed-text-v1.5.Q8_0.gguf",
        labelKey = "provider.llama.model.embedding",
        approxSizeBytes = 274L * 1024 * 1024,
        downloadUrl = "$HF_NOMIC/nomic-embed-text-v1.5-GGUF/resolve/main/nomic-embed-text-v1.5.Q8_0.gguf",
        isEmbedding = true,
    )

    val EMBEDDING_MULTILINGUAL = LlamaModelEntry(
        fileName = "Qwen3-Embedding-0.6B-Q8_0.gguf",
        labelKey = "provider.llama.model.embedding_multilingual",
        approxSizeBytes = 610L * 1024 * 1024,
        downloadUrl = "$HF_QWEN/Qwen3-Embedding-0.6B-GGUF/resolve/main/Qwen3-Embedding-0.6B-Q8_0.gguf",
        isEmbedding = true,
        // Qwen3-Embedding is a decoder-only/causal model trained with last-token pooling
        // (unlike nomic-embed-text's BERT-style mean pooling) — see LlamaModelEntry.poolingType.
        poolingType = "last",
    )

    /** Chat-capable entries shown in the "chat / utility model" pickers, small → large. */
    val chatEntries: List<LlamaModelEntry> = listOf(QWEN_SMALL, FAST, PHI, MISTRAL, BALANCED, CODER, REASONING, GEMMA, BEST)

    /** Embedding entries shown in the "embedding model" picker. */
    val embeddingEntries: List<LlamaModelEntry> = listOf(EMBEDDING, EMBEDDING_MULTILINGUAL)

    val allEntries: List<LlamaModelEntry> = chatEntries + embeddingEntries

    fun fromFileName(fileName: String): LlamaModelEntry? = allEntries.find { it.fileName == fileName }
}
