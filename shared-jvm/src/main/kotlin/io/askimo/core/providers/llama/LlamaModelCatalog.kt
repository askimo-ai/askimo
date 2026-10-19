/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.llama

import kotlinx.serialization.Serializable

/**
 * A downloadable `.gguf` model weight file for the built-in local LLM provider.
 *
 * [fileName] is also what's sent as the OpenAI `"model"` field by langchain4j's OpenAI client —
 * [LlamaServerProcessManager]'s reverse proxy reads that same field back out of each incoming
 * request to decide which GGUF file to load, so this value must round-trip exactly.
 *
 * Not every entry comes from the curated [LlamaModelCatalog] — see
 * [io.askimo.core.providers.llama.HuggingFaceGgufBrowser] for entries discovered dynamically by
 * searching Hugging Face (the *default*, un-curated way chat models are now offered — see
 * [LlamaModelCatalog]'s class doc). [LlamaServerProcessManager]'s proxy and [LlamaModelDownloader]
 * both resolve purely by [fileName] under [io.askimo.core.util.AskimoHome.llamaModelsDir], so
 * dynamically-discovered entries work identically to curated ones without needing to be
 * registered anywhere.
 *
 * `@Serializable` so [LlamaModelDownloader] can persist an entry's display metadata as a JSON
 * sidecar next to its `.gguf` file on successful download (see
 * [LlamaModelDownloader]'s class doc) — without it, a model discovered via Hugging Face search
 * would lose its label/repo-link/popularity stats the moment it scrolls out of the current
 * search/popular results.
 */
@Serializable
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
     *
     * This is exactly why embedding models stay a small hand-vetted list in [LlamaModelCatalog]
     * rather than being sourced live from Hugging Face like chat models — pooling type isn't
     * derivable from the API/tags, and guessing wrong here crashes rather than just degrading
     * quality.
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
    /** Hugging Face repo download count at search time. Null for curated entries. */
    val downloads: Long? = null,
    /** Hugging Face repo "likes" count at search time. Null for curated entries. */
    val likes: Long? = null,
    /** Hugging Face repo's `lastModified` timestamp (ISO-8601, as returned by the API). Null for curated entries. */
    val lastModified: String? = null,
    /** Hugging Face repo's `createdAt` timestamp (ISO-8601, as returned by the API). Null for curated entries. */
    val createdAt: String? = null,
    /**
     * Hugging Face repo's `pipeline_tag` (e.g. `"text-generation"`), as used by
     * [io.askimo.core.providers.llama.HuggingFaceGgufBrowser.search]'s `restrictToChatModels`
     * filter. Null for curated entries and for any repo Hugging Face didn't tag.
     */
    val pipelineTag: String? = null,
)

/**
 * Curated catalog for the built-in local LLM provider — **embedding models only**.
 *
 * Chat models are deliberately *not* curated/hardcoded here. A hand-maintained list inevitably
 * drifts from reality (stale file sizes, superseded releases, etc.), and there's no correctness
 * risk in sourcing chat models live — any `.gguf` chat model works the same way through
 * `llama-server`. Settings > Local LLM instead shows Hugging Face's most-downloaded GGUF repos by
 * default (see [HuggingFaceGgufBrowser.popularModels]), with the same live
 * downloads/likes/size/timestamp data as an explicit search — unifying "browse popular" and
 * "search" into one code path and one display model.
 *
 * Embedding models stay curated because [LlamaModelEntry.poolingType] has a real correctness
 * risk if guessed wrong (see its doc) — something only worth hand-vetting for the two entries
 * below, rather than trying to auto-detect from Hugging Face's API/tags.
 */
object LlamaModelCatalog {

    private const val HF_NOMIC = "https://huggingface.co/nomic-ai"
    private const val HF_QWEN = "https://huggingface.co/Qwen"

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

    /** Embedding entries shown in the "embedding model" picker. */
    val embeddingEntries: List<LlamaModelEntry> = listOf(EMBEDDING, EMBEDDING_MULTILINGUAL)

    /** Every curated entry — currently just [embeddingEntries] (see class doc for why). */
    val allEntries: List<LlamaModelEntry> = embeddingEntries

    fun fromFileName(fileName: String): LlamaModelEntry? = allEntries.find { it.fileName == fileName }
}
