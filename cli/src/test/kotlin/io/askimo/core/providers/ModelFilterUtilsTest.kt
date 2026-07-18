/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [filterChatModels] and [filterModelsForType].
 *
 * No AppContext / IO needed – both functions are pure transformations.
 *
 * Key bug reproduction:
 *   Before the fix, [filterChatModels] fell back to the **full** original list when
 *   fewer than 2 models survived filtering.  This caused `nomic-embed-xxx` models
 *   (tagged [ModelCategory.EMBEDDING]) to re-appear in the chat list whenever the
 *   provider had a small model catalogue.
 *
 *   After the fix the fallback uses the *category-safe* list, so explicitly tagged
 *   EMBEDDING / IMAGE / AUDIO models are never reinstated.
 */
@DisplayName("ModelFilterUtils")
class ModelFilterUtilsTest {

    // ── Helpers ───────────────────────────────────────────────────────────────────────────

    /** Builds a minimal [ModelDTO] using Ollama as a stand-in provider. */
    private fun model(id: String, category: ModelCategory? = null): ModelDTO = ModelDTO(provider = ModelProvider.OLLAMA, modelId = id, category = category)

    // ── filterChatModels ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("filterChatModels")
    inner class FilterChatModels {

        // ── category-based exclusion ──────────────────────────────────────────────────────

        @Nested
        @DisplayName("category-based exclusion")
        inner class CategoryBasedExclusion {

            @Test
            fun `keeps CHAT-tagged models`() {
                val models = listOf(
                    model("gpt-4o", ModelCategory.CHAT),
                    model("claude-3-5-sonnet", ModelCategory.CHAT),
                )
                assertEquals(models, filterChatModels(models))
            }

            @Test
            fun `keeps VISION-tagged models`() {
                val models = listOf(
                    model("gpt-4o-vision", ModelCategory.VISION),
                    model("claude-3-vision", ModelCategory.VISION),
                )
                assertEquals(models, filterChatModels(models))
            }

            @Test
            fun `excludes EMBEDDING-tagged models from large list`() {
                val embedding = model("nomic-embed-text", ModelCategory.EMBEDDING)
                val chat1 = model("llama3:8b", ModelCategory.CHAT)
                val chat2 = model("llama3:70b", ModelCategory.CHAT)
                val result = filterChatModels(listOf(embedding, chat1, chat2))
                assertFalse(result.contains(embedding))
                assertTrue(result.containsAll(listOf(chat1, chat2)))
            }

            @Test
            fun `excludes IMAGE-tagged models from large list`() {
                val image = model("dall-e-3", ModelCategory.IMAGE)
                val chat1 = model("gpt-4o", ModelCategory.CHAT)
                val chat2 = model("gpt-4-turbo", ModelCategory.CHAT)
                val result = filterChatModels(listOf(image, chat1, chat2))
                assertFalse(result.contains(image))
            }

            @Test
            fun `excludes AUDIO-tagged models from large list`() {
                val audio = model("whisper-1", ModelCategory.AUDIO)
                val chat1 = model("gpt-4o", ModelCategory.CHAT)
                val chat2 = model("gpt-4-turbo", ModelCategory.CHAT)
                val result = filterChatModels(listOf(audio, chat1, chat2))
                assertFalse(result.contains(audio))
            }

            // ── BUG REGRESSION ────────────────────────────────────────────────────────────
            //
            // Before the fix the fallback returned `models` (the raw input), so an
            // EMBEDDING-tagged model could re-appear when only one chat model was present.

            @Test
            fun `nomic-embed with EMBEDDING category is excluded even when only 1 chat model remains`() {
                // Small Ollama catalogue: one embed model + one chat model
                val embed = model("nomic-embed-text", ModelCategory.EMBEDDING)
                val chat = model("llama3:8b", ModelCategory.CHAT)

                val result = filterChatModels(listOf(embed, chat))

                assertFalse(result.contains(embed), "nomic-embed-text must never appear in chat list")
                assertTrue(result.contains(chat))
            }

            @Test
            fun `IMAGE-tagged model is excluded even when only 1 chat model remains (fallback safety)`() {
                val image = model("dall-e-3", ModelCategory.IMAGE)
                val chat = model("gpt-4o", ModelCategory.CHAT)

                val result = filterChatModels(listOf(image, chat))

                assertFalse(result.contains(image))
                assertTrue(result.contains(chat))
            }

            @Test
            fun `AUDIO-tagged model is excluded even when only 1 chat model remains (fallback safety)`() {
                val audio = model("whisper-1", ModelCategory.AUDIO)
                val chat = model("gpt-4o", ModelCategory.CHAT)

                val result = filterChatModels(listOf(audio, chat))

                assertFalse(result.contains(audio))
                assertTrue(result.contains(chat))
            }

            @Test
            fun `multiple EMBEDDING models are all excluded regardless of list size`() {
                val embed1 = model("nomic-embed-text", ModelCategory.EMBEDDING)
                val embed2 = model("mxbai-embed-large", ModelCategory.EMBEDDING)
                val chat = model("llama3:8b", ModelCategory.CHAT)

                val result = filterChatModels(listOf(embed1, embed2, chat))

                assertFalse(result.contains(embed1))
                assertFalse(result.contains(embed2))
                assertTrue(result.contains(chat))
            }
        }

        // ── keyword-based exclusion (untagged / category = null) ──────────────────────────

        @Nested
        @DisplayName("keyword-based exclusion for untagged models")
        inner class KeywordBasedExclusion {

            @Test
            fun `nomic-embed with null category is excluded when enough chat models exist`() {
                val embed = model("nomic-embed-text") // category = null
                val chat1 = model("llama3:8b")
                val chat2 = model("mistral:7b")

                val result = filterChatModels(listOf(embed, chat1, chat2))

                assertFalse(result.contains(embed))
                assertTrue(result.containsAll(listOf(chat1, chat2)))
            }

            @Test
            fun `embed keyword is excluded when enough chat models exist`() {
                val embed = model("all-minilm-embed-v2")
                val chat1 = model("llama3:8b")
                val chat2 = model("mistral:7b")

                val result = filterChatModels(listOf(embed, chat1, chat2))
                assertFalse(result.contains(embed))
            }

            @Test
            fun `minilm keyword is excluded when enough chat models exist`() {
                val embed = model("all-minilm-l6-v2")
                val chat1 = model("llama3:8b")
                val chat2 = model("mistral:7b")

                val result = filterChatModels(listOf(embed, chat1, chat2))
                assertFalse(result.contains(embed))
            }

            @Test
            fun `bge- keyword is excluded when enough chat models exist`() {
                val embed = model("bge-large-en")
                val chat1 = model("llama3:8b")
                val chat2 = model("mistral:7b")

                val result = filterChatModels(listOf(embed, chat1, chat2))
                assertFalse(result.contains(embed))
            }

            @Test
            fun `e5- keyword is excluded when enough chat models exist`() {
                val embed = model("e5-large-v2")
                val chat1 = model("llama3:8b")
                val chat2 = model("mistral:7b")

                val result = filterChatModels(listOf(embed, chat1, chat2))
                assertFalse(result.contains(embed))
            }

            @Test
            fun `dall-e keyword is excluded when enough chat models exist`() {
                val image = model("dall-e-3")
                val chat1 = model("llama3:8b")
                val chat2 = model("mistral:7b")

                val result = filterChatModels(listOf(image, chat1, chat2))
                assertFalse(result.contains(image))
            }

            @Test
            fun `untagged chat model with generic name always passes through`() {
                val chat1 = model("llama3:8b")
                val chat2 = model("mistral:7b")
                val chat3 = model("qwen2:7b")

                val result = filterChatModels(listOf(chat1, chat2, chat3))
                assertTrue(result.containsAll(listOf(chat1, chat2, chat3)))
            }

            @Test
            fun `keyword matching is case-insensitive`() {
                val embed = model("NOMIC-EMBED-TEXT")
                val chat1 = model("llama3:8b")
                val chat2 = model("mistral:7b")

                val result = filterChatModels(listOf(embed, chat1, chat2))
                assertFalse(result.contains(embed))
            }

            // ── Fallback behaviour ────────────────────────────────────────────────────────

            @Test
            fun `untagged embed model is excluded when at least 1 chat model survives keyword pass`() {
                // Only one "real" chat model + one embed-named model (both untagged)
                val embed = model("nomic-embed-text") // category = null, keyword hit
                val chat = model("llama3:8b") // category = null, safe

                // chatModels = [chat] (non-empty) → no fallback → embed stays out
                val result = filterChatModels(listOf(embed, chat))

                assertFalse(
                    result.contains(embed),
                    "keyword-matched embed model must not appear even when it is the only non-chat model",
                )
                assertTrue(result.contains(chat))
            }

            @Test
            fun `docker-registry-prefixed embed model is excluded by keyword filter`() {
                // Reported: "docker.io/ai/nomic-embed-text-v1.5:latest" appeared in chat list
                val embed = model("docker.io/ai/nomic-embed-text-v1.5:latest")
                val chat = model("llama3:8b")

                val result = filterChatModels(listOf(embed, chat))

                assertFalse(
                    result.contains(embed),
                    "docker-registry-prefixed embed model must be excluded by keyword filter",
                )
                assertTrue(result.contains(chat))
            }

            @Test
            fun `fallback returns category-safe list only when ALL models are keyword-filtered out`() {
                // Pathological case: every untagged model has an embed-like name and there are no
                // tagged CHAT models — the fallback must kick in to avoid an empty picker.
                val e1 = model("nomic-embed-text")
                val e2 = model("bge-large-en")

                val result = filterChatModels(listOf(e1, e2))

                // chatModels is empty → fallback to categorySafe → return both
                assertTrue(
                    result.containsAll(listOf(e1, e2)),
                    "fallback must activate when keyword pass leaves nothing, to avoid an empty model list",
                )
            }

            @Test
            fun `fallback does NOT reinstate explicitly-tagged EMBEDDING model`() {
                // Explicit EMBEDDING category is removed at pass 1 — fallback only covers pass 2
                val embed = model("nomic-embed-text", ModelCategory.EMBEDDING)
                val chat = model("llama3:8b")

                val result = filterChatModels(listOf(embed, chat))

                assertFalse(
                    result.contains(embed),
                    "explicitly tagged EMBEDDING model must not be reinstated by fallback",
                )
                assertTrue(result.contains(chat))
            }
        }

        // ── edge cases ────────────────────────────────────────────────────────────────────

        @Nested
        @DisplayName("edge cases")
        inner class EdgeCases {

            @Test
            fun `empty list returns empty list`() {
                assertEquals(emptyList(), filterChatModels(emptyList()))
            }

            @Test
            fun `single chat model is returned as-is`() {
                val chat = model("llama3:8b", ModelCategory.CHAT)
                assertEquals(listOf(chat), filterChatModels(listOf(chat)))
            }

            @Test
            fun `mixed category and null-category chat models are all kept`() {
                val tagged = model("gpt-4o", ModelCategory.CHAT)
                val vision = model("gpt-4o-vision", ModelCategory.VISION)
                val untagged = model("llama3:8b")

                val result = filterChatModels(listOf(tagged, vision, untagged))
                assertTrue(result.containsAll(listOf(tagged, vision, untagged)))
            }
        }
    }

    // ── filterModelsForType ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("filterModelsForType")
    inner class FilterModelsForType {

        // ── EMBEDDING type ────────────────────────────────────────────────────────────────

        @Nested
        @DisplayName("EMBEDDING type")
        inner class EmbeddingType {

            @Test
            fun `returns category-matched EMBEDDING models when at least 2 exist`() {
                val e1 = model("nomic-embed-text", ModelCategory.EMBEDDING)
                val e2 = model("mxbai-embed-large", ModelCategory.EMBEDDING)
                val chat = model("llama3:8b", ModelCategory.CHAT)

                val result = filterModelsForType(listOf(e1, e2, chat), SpecialModelType.EMBEDDING)
                assertEquals(listOf(e1, e2), result)
            }

            @Test
            fun `falls back to keyword match when fewer than 2 category matches`() {
                val e1 = model("nomic-embed-text", ModelCategory.EMBEDDING) // category match
                val e2 = model("all-minilm-l6-v2") // keyword match
                val chat = model("llama3:8b")

                val result = filterModelsForType(listOf(e1, e2, chat), SpecialModelType.EMBEDDING)
                assertTrue(
                    result.contains(e2),
                    "keyword-matched model should appear in fallback list",
                )
            }

            @Test
            fun `nomic-embed-text is matched by keyword when untagged`() {
                val e1 = model("nomic-embed-text") // keyword: "nomic"
                val e2 = model("bge-large-en") // keyword: "bge-"
                val chat = model("llama3:8b")

                val result = filterModelsForType(listOf(e1, e2, chat), SpecialModelType.EMBEDDING)
                assertTrue(result.containsAll(listOf(e1, e2)))
                assertFalse(result.contains(chat))
            }

            @Test
            fun `returns full list when both passes yield fewer than 2 models`() {
                val e1 = model("nomic-embed-text", ModelCategory.EMBEDDING)
                val chat = model("llama3:8b")

                // Only 1 EMBEDDING-tagged, no keyword hits among the rest → fallback to all
                val result = filterModelsForType(listOf(e1, chat), SpecialModelType.EMBEDDING)
                assertEquals(listOf(e1, chat), result)
            }
        }

        // ── VISION type ───────────────────────────────────────────────────────────────────

        @Nested
        @DisplayName("VISION type")
        inner class VisionType {

            @Test
            fun `returns VISION-tagged models when at least 2 exist`() {
                val v1 = model("gpt-4o-vision", ModelCategory.VISION)
                val v2 = model("claude-3-vision", ModelCategory.VISION)
                val chat = model("gpt-4-turbo", ModelCategory.CHAT)

                val result = filterModelsForType(listOf(v1, v2, chat), SpecialModelType.VISION)
                assertEquals(listOf(v1, v2), result)
            }

            @Test
            fun `llava untagged is matched by keyword`() {
                val v1 = model("llava:13b")
                val v2 = model("llava:34b")
                val chat = model("llama3:8b")

                val result = filterModelsForType(listOf(v1, v2, chat), SpecialModelType.VISION)
                assertTrue(result.containsAll(listOf(v1, v2)))
                assertFalse(result.contains(chat))
            }
        }

        // ── IMAGE type ────────────────────────────────────────────────────────────────────

        @Nested
        @DisplayName("IMAGE type")
        inner class ImageType {

            @Test
            fun `returns IMAGE-tagged models when at least 2 exist`() {
                val i1 = model("dall-e-3", ModelCategory.IMAGE)
                val i2 = model("dall-e-2", ModelCategory.IMAGE)
                val chat = model("gpt-4o", ModelCategory.CHAT)

                val result = filterModelsForType(listOf(i1, i2, chat), SpecialModelType.IMAGE)
                assertEquals(listOf(i1, i2), result)
            }

            @Test
            fun `flux untagged is matched by keyword`() {
                val i1 = model("flux-1-dev")
                val i2 = model("flux-1-schnell")
                val chat = model("llama3:8b")

                val result = filterModelsForType(listOf(i1, i2, chat), SpecialModelType.IMAGE)
                assertTrue(result.containsAll(listOf(i1, i2)))
                assertFalse(result.contains(chat))
            }
        }

        // ── UTILITY type ──────────────────────────────────────────────────────────────────

        @Nested
        @DisplayName("UTILITY type")
        inner class UtilityType {

            @Test
            fun `returns CHAT-tagged models when at least 2 exist (UTILITY maps to CHAT category)`() {
                val u1 = model("gpt-4o-mini", ModelCategory.CHAT)
                val u2 = model("gpt-3.5-turbo", ModelCategory.CHAT)

                val result = filterModelsForType(listOf(u1, u2), SpecialModelType.UTILITY)
                assertEquals(listOf(u1, u2), result)
            }

            @Test
            fun `mini and flash keywords matched when category pass yields fewer than 2`() {
                val u1 = model("gemini-flash")
                val u2 = model("gpt-4o-mini")
                val other = model("llama3:70b")

                val result = filterModelsForType(listOf(u1, u2, other), SpecialModelType.UTILITY)
                assertTrue(result.containsAll(listOf(u1, u2)))
                assertFalse(result.contains(other))
            }
        }

        // ── edge cases ────────────────────────────────────────────────────────────────────

        @Nested
        @DisplayName("edge cases")
        inner class EdgeCases {

            @Test
            fun `empty list returns empty list`() {
                assertEquals(emptyList(), filterModelsForType(emptyList(), SpecialModelType.EMBEDDING))
            }

            @Test
            fun `single matching model returns full list (fallback)`() {
                val e = model("nomic-embed-text", ModelCategory.EMBEDDING)
                val chat = model("llama3:8b")

                val result = filterModelsForType(listOf(e, chat), SpecialModelType.EMBEDDING)
                assertEquals(listOf(e, chat), result)
            }
        }
    }
}
