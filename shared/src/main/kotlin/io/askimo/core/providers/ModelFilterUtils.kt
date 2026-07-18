/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

/**
 * Filters a model list to only chat-suitable models (CHAT, VISION, or untagged).
 * Excludes embedding, image-only, and audio models by category first, then by keyword
 * when category is null (e.g. local providers that don't tag models).
 * Falls back to the full list if fewer than 2 models would remain after filtering.
 */
fun filterChatModels(models: List<ModelDTO>): List<ModelDTO> {
    val chatModels = models
        .filter {
            when (it.category) {
                ModelCategory.CHAT, ModelCategory.VISION, null -> true
                else -> false
            }
        }
        .filter { dto ->
            // When category is null additionally exclude models whose id strongly implies non-chat
            if (dto.category != null) return@filter true
            val id = dto.modelId.lowercase()
            NON_CHAT_KEYWORDS.none { kw -> id.contains(kw) }
        }
    return if (chatModels.size >= 2) chatModels else models
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
