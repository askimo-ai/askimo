/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import kotlinx.serialization.Serializable

/**
 * Functional category of an AI model, mirroring the server-side enum.
 *
 * Only [CHAT] and [VISION] are exposed to desktop clients via the model selector.
 * [EMBEDDING], [IMAGE], and [AUDIO] are hidden from end users.
 */
@Serializable
enum class ModelCategory {
    /** General-purpose text chat (default for unrecognised models). */
    CHAT,

    /** Multimodal models that accept image input alongside text. */
    VISION,

    /** Image generation models (DALL-E, Flux, Stable Diffusion, etc.). */
    IMAGE,

    /** Embedding / vector models — never shown to end users. */
    EMBEDDING,

    /** Speech-to-text or text-to-speech models. */
    AUDIO,
}
