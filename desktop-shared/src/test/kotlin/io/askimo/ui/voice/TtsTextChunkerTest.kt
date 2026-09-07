/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers [chunkTextForTts] — verifies chunks respect the [maxChars] limit, split on sentence
 * boundaries rather than mid-sentence/mid-word, and reconstruct the original content when
 * rejoined.
 */
class TtsTextChunkerTest {

    @Test
    fun `text shorter than maxChars is returned as a single chunk unchanged`() {
        val text = "This is a short sentence."
        assertEquals(listOf(text), chunkTextForTts(text, maxChars = 100))
    }

    @Test
    fun `text exactly at maxChars is returned as a single chunk`() {
        val text = "a".repeat(50)
        assertEquals(listOf(text), chunkTextForTts(text, maxChars = 50))
    }

    @Test
    fun `empty text returns a single empty chunk`() {
        assertEquals(listOf(""), chunkTextForTts("", maxChars = 10))
    }

    @Test
    fun `no chunk ever exceeds maxChars`() {
        val text = (1..50).joinToString(" ") { "Sentence number $it." }
        val chunks = chunkTextForTts(text, maxChars = 40)
        chunks.forEach { chunk ->
            assertTrue(chunk.length <= 40, "Chunk exceeded maxChars: \"$chunk\" (${chunk.length} chars)")
        }
    }

    @Test
    fun `splits on sentence boundaries, not mid-sentence`() {
        val sentences = listOf(
            "The quick brown fox jumps over the lazy dog.",
            "Sphinx of black quartz, judge my vow!",
            "How vexingly quick daft zebras jump?",
            "Pack my box with five dozen liquor jugs.",
        )
        val text = sentences.joinToString(" ")
        // Small enough to force splitting, large enough that no single sentence needs hard-splitting.
        val chunks = chunkTextForTts(text, maxChars = 60)

        assertTrue(chunks.size > 1, "Expected the text to be split into multiple chunks")
        chunks.forEach { chunk ->
            assertTrue(chunk.length <= 60)
            assertTrue(
                chunk.last() in charArrayOf('.', '!', '?'),
                "Chunk did not end on a sentence boundary: \"$chunk\"",
            )
        }
        // Rejoining chunks reproduces the original sentences in order.
        assertEquals(sentences.joinToString(" "), chunks.joinToString(" "))
    }

    @Test
    fun `groups multiple short sentences into a single chunk when they fit`() {
        val text = "One. Two. Three."
        assertEquals(listOf(text), chunkTextForTts(text, maxChars = 100))
    }

    @Test
    fun `a single sentence longer than maxChars is hard-split`() {
        val longSentence = "a".repeat(95) + "."
        val chunks = chunkTextForTts(longSentence, maxChars = 40)

        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue(it.length <= 40) }
        // No characters lost or reordered by the hard-split.
        assertEquals(longSentence, chunks.joinToString(""))
    }

    @Test
    fun `hard-split sentence does not swallow preceding normal sentences`() {
        val normal = "Short sentence one. Short sentence two."
        val longSentence = "b".repeat(90)
        val text = "$normal $longSentence"

        val chunks = chunkTextForTts(text, maxChars = 30)

        assertEquals("Short sentence one.", chunks[0])
        assertEquals("Short sentence two.", chunks[1])
        chunks.forEach { assertTrue(it.length <= 30) }
    }

    @Test
    fun `whitespace including newlines and tabs between sentences is normalized when chunking occurs`() {
        val text = "First sentence.\n\nSecond sentence.\tThird sentence."
        val chunks = chunkTextForTts(text, maxChars = 20)

        chunks.forEach { chunk ->
            assertTrue(chunk.length <= 20)
            assertTrue(!chunk.contains('\n') && !chunk.contains('\t'), "Chunk retained raw whitespace: \"$chunk\"")
        }
        assertEquals("First sentence. Second sentence. Third sentence.", chunks.joinToString(" "))
    }

    @Test
    fun `default maxChars matches the documented TTS provider limit`() {
        assertEquals(4000, MAX_TTS_CHARS)
    }

    @Test
    fun `rejects a non-positive maxChars instead of silently truncating or misbehaving`() {
        assertFailsWith<IllegalArgumentException> { chunkTextForTts("hello world", maxChars = 0) }
        assertFailsWith<IllegalArgumentException> { chunkTextForTts("hello world", maxChars = -5) }
    }

    @Test
    fun `never truncates text even in the defensive empty-chunks fallback path`() {
        // Every non-empty text yields at least one non-empty sentence token, so the internal
        // "no chunks produced" fallback is unreachable for any text this function actually
        // accepts — but it must stay lossless (return the full text, not a truncated prefix)
        // rather than silently dropping content, which would produce incomplete TTS audio.
        val text = "x".repeat(10_000)
        val chunks = chunkTextForTts(text, maxChars = 4000)
        assertEquals(text, chunks.joinToString(""))
        chunks.forEach { assertTrue(it.length <= 4000) }
    }

    /** Asserts no chunk contains a lone (unpaired) high or low UTF-16 surrogate. */
    private fun assertNoSplitSurrogatePairs(chunks: List<String>) {
        chunks.forEach { chunk ->
            chunk.forEachIndexed { i, c ->
                if (Character.isHighSurrogate(c)) {
                    assertTrue(
                        i + 1 < chunk.length && Character.isLowSurrogate(chunk[i + 1]),
                        "Lone high surrogate in \"$chunk\"",
                    )
                }
                if (Character.isLowSurrogate(c)) {
                    assertTrue(
                        i > 0 && Character.isHighSurrogate(chunk[i - 1]),
                        "Lone low surrogate in \"$chunk\"",
                    )
                }
            }
        }
    }

    @Test
    fun `hard-split never separates a surrogate pair, even at the exact chunk boundary`() {
        // Each 🎉 is one codepoint but 2 UTF-16 chars — with maxChars = 2, a naive chunked(2)
        // would place the boundary right between the two halves of the second emoji's pair.
        val text = "🎉🎉🎉🎉🎉" // 5 codepoints, 10 UTF-16 chars, no sentence punctuation
        val chunks = chunkTextForTts(text, maxChars = 2)

        assertNoSplitSurrogatePairs(chunks)
        // No codepoints lost or reordered.
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun `hard-split with emoji mixed into a long sentence preserves all codepoints`() {
        val text = "Look at this 🎉🎊🥳 celebration " + "a".repeat(60) + "!"
        val chunks = chunkTextForTts(text, maxChars = 20)

        chunks.forEach { assertTrue(it.length <= 20) }
        assertNoSplitSurrogatePairs(chunks)
        assertEquals(text, chunks.joinToString(""))
    }
}
