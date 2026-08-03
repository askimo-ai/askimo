/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.openaicompatible

/**
 * Selects which OpenAI API surface an [OpenAiCompatibleSettings] instance targets.
 *
 * - [CHAT_COMPLETIONS] — standard `/v1/chat/completions` endpoint with `messages[]` and
 *   plain-string `content`. Supported by virtually every OpenAI-compatible provider
 *   (NVIDIA NIM, OpenRouter, Groq, Together AI, Cloudflare AI, Ollama, etc.).
 *   This is the safe default for all new instances.
 *
 * - [RESPONSES] — OpenAI Responses API (`/v1/responses`) with typed `input[]` content parts
 *   and optional reasoning/thinking support. Required for OpenAI o-series and xAI Grok
 *   thinking models when accessed through a custom endpoint. Most third-party providers
 *   do **not** support this endpoint.
 */
enum class OpenAiApiMode {
    CHAT_COMPLETIONS,
    RESPONSES,
}

/**
 * HTTP protocol version used for connections to an OpenAI-compatible endpoint.
 *
 * - [HTTP_1_1] — use for self-hosted servers running on uvicorn, Gunicorn, FastAPI, or vLLM.
 *   These stacks commonly have incomplete HTTP/2 support that causes the request body to
 *   arrive as null. **This is the safe default for the OpenAI-compatible provider.**
 *
 * - [HTTP_2] — use for production cloud endpoints (OpenAI, Groq, OpenRouter, etc.) that
 *   fully support HTTP/2 multiplexing.
 */
enum class OpenAiHttpVersion {
    HTTP_1_1,
    HTTP_2,
}
