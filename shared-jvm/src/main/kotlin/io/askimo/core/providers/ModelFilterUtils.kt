/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

/**
 * Filters a model list to only chat-suitable models (CHAT, VISION, or untagged).
 * Excludes embedding, image-only, and audio models by category first, then by keyword
 * when category is null (e.g. local providers that don't tag models).
 *
 */
fun filterChatModels(models: List<ModelDTO>): List<ModelDTO> {
    // Pass 1 – hard-exclude explicitly non-chat categories (no fallback override)
    val categorySafe = models.filter {
        when (it.category) {
            ModelCategory.CHAT, ModelCategory.VISION, null -> true
            else -> false
        }
    }

    // Pass 2 – additionally exclude untagged models whose ID implies non-chat
    val chatModels = categorySafe.filter { dto ->
        if (dto.category != null) return@filter true
        val id = dto.modelId.lowercase()
        NON_CHAT_KEYWORDS.none { kw -> id.contains(kw) }
    }

    // Fall back to categorySafe only when keyword filtering leaves absolutely nothing,
    // so a single legitimate chat model is never hidden by an over-aggressive keyword hit
    // on a sibling model (e.g. one chat + one embed model in an Ollama instance).
    return if (chatModels.isNotEmpty()) chatModels else categorySafe
}

/**
 * Filters models for a specific [SpecialModelType].
 * 1st pass: exact [ModelCategory] match.
 * 2nd pass: keyword match on modelId.
 * Falls back to the full list if fewer than 2 models remain after both passes.
 */
fun filterModelsForType(models: List<ModelDTO>, type: SpecialModelType): List<ModelDTO> {
    val byCategory = models.filter { it.category == type.category }
    if (byCategory.size >= 2) return byCategory
    val byKeyword = models.filter { m -> type.keywords.any { kw -> m.modelId.lowercase().contains(kw) } }
    if (byKeyword.size >= 2) return byKeyword
    return models
}

/** Model ID substrings that strongly indicate a non-chat model when category is unset. */
private val NON_CHAT_KEYWORDS = listOf(
    "embed", "nomic", "minilm", "e5-", "bge-",
    "dall-e", "flux", "stable-diff", "imagen", "aurora",
)

/**
 * Functional category used to drive smart model filtering in the UI.
 * Each entry carries a human-readable [label], a target [ModelCategory],
 * and [keywords] for keyword-based fallback on providers that don't tag models.
 */
enum class SpecialModelType(
    val label: String,
    val category: ModelCategory,
    val keywords: List<String>,
) {
    UTILITY("Utility", ModelCategory.CHAT, listOf("mini", "flash", "haiku", "turbo", "lite", "fast", "nano")),
    VISION("Vision", ModelCategory.VISION, listOf("vision", "-vl", "llava", "bakllava", "visual", "pixtral")),
    IMAGE("Image", ModelCategory.IMAGE, listOf("dall-e", "flux", "stable-diff", "imagen", "aurora", "grok-2-vision")),
    EMBEDDING("Embedding", ModelCategory.EMBEDDING, listOf("embed", "nomic", "minilm", "e5-", "bge-")),
}
