/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.domain

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Comprehensive domain model for storing AI model capabilities and classifications.
 * This allows tracking of various model features including modality support (text, image, audio, video),
 * tool/function calling support, and sampling parameter support.
 *
 * This is particularly useful for custom models from providers like Ollama where capabilities
 * cannot be automatically determined.
 */
data class ModelClassification(
    val id: String,
    val provider: String,
    val modelName: String,

    // Modality capabilities
    val supportsText: Boolean = true,
    val supportsImage: Boolean = false,
    val supportsAudio: Boolean = false,
    val supportsVideo: Boolean = false,

    // Feature capabilities
    val supportsTools: Boolean = false,
    val supportsSampling: Boolean = true,
    val supportsStreaming: Boolean = true,

    // Metadata
    val description: String? = null,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
)
