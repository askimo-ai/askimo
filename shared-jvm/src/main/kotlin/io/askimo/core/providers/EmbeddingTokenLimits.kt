/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

/**
 * Resolves embedding token limits by model name.
 *
 * Works for any OpenAI-compatible endpoint regardless of hosting server
 * (Ollama, Docker AI, LocalAI, LM Studio, vLLM, OpenAI, etc.) — the model
 * name is the stable identifier that determines the token limit.
 *
 * Lookup strategy (in order):
 *  1. Exact match against [KNOWN_LIMITS]
 *  2. Longest-key substring match (handles version tags, namespace prefixes, etc.)
 *  3. Conservative default of 512 — safe for any unrecognised model
 */
object EmbeddingTokenLimits {

    /**
     * Authoritative token limits for widely-used embedding models.
     * Keys are lowercased model IDs. Longer, more-specific keys take precedence
     * during substring matching, so ordering within this map does not matter.
     */
    private val KNOWN_LIMITS: Map<String, Int> = mapOf(
        // ── OpenAI ────────────────────────────────────────────────────────────
        "text-embedding-3-small" to 8191,
        "text-embedding-3-large" to 8191,
        "text-embedding-ada-002" to 8191,

        // ── Nomic ─────────────────────────────────────────────────────────────
        "nomic-embed-text" to 8192,
        "nomic-embed-code" to 8192,

        // ── BAAI BGE ──────────────────────────────────────────────────────────
        "bge-m3" to 8192, // multilingual long-context
        "bge-small-en" to 512,
        "bge-base-en" to 512,
        "bge-large-en" to 512,
        "bge-small-zh" to 512,
        "bge-base-zh" to 512,
        "bge-large-zh" to 512,

        // ── mxbai ─────────────────────────────────────────────────────────────
        "mxbai-embed-large" to 512,

        // ── E5 family ─────────────────────────────────────────────────────────
        "e5-mistral-7b-instruct" to 32768, // long-context instruct variant
        "multilingual-e5-large" to 512,
        "multilingual-e5-base" to 512,
        "multilingual-e5-small" to 512,
        "e5-large-v2" to 512,
        "e5-base-v2" to 512,
        "e5-small-v2" to 512,
        "e5-large" to 512,
        "e5-base" to 512,
        "e5-small" to 512,

        // ── Sentence-Transformers / MiniLM ────────────────────────────────────
        "all-minilm-l6-v2" to 512,
        "all-minilm-l12-v2" to 512,
        "all-mpnet-base-v2" to 384,
        "paraphrase-multilingual-mpnet-base-v2" to 128,

        // ── GTE family ────────────────────────────────────────────────────────
        "gte-qwen2-7b-instruct" to 32768,
        "gte-qwen2-1.5b-instruct" to 32768,
        "gte-large" to 512,
        "gte-base" to 512,
        "gte-small" to 512,

        // ── Qwen Embedding ────────────────────────────────────────────────────
        "qwen3-embedding" to 32768,
        "qwen2-embedding" to 32768,

        // ── Gemini ────────────────────────────────────────────────────────────
        "text-embedding-004" to 2048,
        "embedding-001" to 2048,

        // ── Jina ──────────────────────────────────────────────────────────────
        "jina-embeddings-v3" to 8192,
        "jina-embeddings-v2-base-en" to 8192,

        // ── Cohere ────────────────────────────────────────────────────────────
        "embed-english-v3.0" to 512,
        "embed-multilingual-v3.0" to 512,
        "embed-english-light-v3.0" to 512,
    )

    /**
     * Returns the maximum input token count for [modelName].
     *
     * Prefers the longest matching key so specific variants (e.g. `bge-m3`) are
     * not overshadowed by shorter sibling patterns (e.g. `bge-large-en`).
     */
    fun resolve(modelName: String): Int {
        val name = modelName.lowercase().trim()

        // 1. Exact match
        KNOWN_LIMITS[name]?.let { return it }

        // 2. Longest substring match — handles tags ("nomic-embed-text:latest"),
        //    registry prefixes ("ai/mxbai-embed-large"), and similar variants.
        KNOWN_LIMITS.entries
            .filter { (key, _) -> name.contains(key) }
            .maxByOrNull { (key, _) -> key.length }
            ?.let { return it.value }

        // 3. Conservative default — better to over-chunk than to silently exceed
        //    the limit of an unknown short-context model.
        return 512
    }
}
