/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import io.askimo.core.providers.openaicompatible.OpenAiApiMode
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
 * @property apiMode     Which OpenAI-compatible wire format the upstream expects — relevant
 *                       for model factories that support dispatching to either a
 *                       `/v1/responses` or `/v1/chat/completions` endpoint/delegate based on
 *                       this value. Defaults to [OpenAiApiMode.RESPONSES] to preserve today's
 *                       exact behaviour for callers that don't set it.
 * @property httpVersion HTTP protocol preferred for this model's upstream connection.
 *                       Defaults to [HttpVersion.HTTP_1_1].
 */
@Serializable
data class ModelDTO(
    val provider: ModelProvider,
    val modelId: String,
    val displayName: String = modelId,
    val category: ModelCategory? = null,
    /** True when this EMBEDDING model is the designated RAG anchor. */
    val isRagDefault: Boolean = false,
    val apiMode: OpenAiApiMode = OpenAiApiMode.RESPONSES,
    val httpVersion: HttpVersion = HttpVersion.HTTP_1_1,
) {
    companion object {
        /**
         * Convenience factory for community factories — wraps a plain model name string.
         * The [displayName] equals [modelId] so existing UI behaviour is unchanged.
         */
        fun of(provider: ModelProvider, modelId: String): ModelDTO = ModelDTO(provider = provider, modelId = modelId, displayName = modelId)
    }
}
