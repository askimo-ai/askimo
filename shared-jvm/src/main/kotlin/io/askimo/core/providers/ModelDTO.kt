/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import kotlinx.serialization.Serializable

/**
 * Represents a single selectable model, carrying both the routing identity
 * and a human-readable label for the UI.
 *
 * ```
 * ModelDTO(provider=OPENAI, modelId="openai:gpt-4o", displayName="OpenAI · gpt-4o")
 * ```
 *
 * @property provider    The [ModelProvider] that owns this model (used for grouping in the UI).
 * @property modelId     The identifier sent as the `model` field in API requests.
 * @property displayName Human-readable label shown in the dropdown / dialog.
 *                       Defaults to [modelId] when not explicitly set.
 */
@Serializable
data class ModelDTO(
    val provider: ModelProvider,
    val modelId: String,
    val displayName: String = modelId,
    val category: ModelCategory? = null,
    /** True when this EMBEDDING model is the designated RAG anchor. */
    val isRagDefault: Boolean = false,
) {
    companion object {
        /**
         * Convenience factory for community factories — wraps a plain model name string.
         * The [displayName] equals [modelId] so existing UI behaviour is unchanged.
         */
        fun of(provider: ModelProvider, modelId: String): ModelDTO = ModelDTO(provider = provider, modelId = modelId, displayName = modelId)
    }
}
