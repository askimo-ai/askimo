/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import io.askimo.core.memory.SessionConversationSummary
import io.askimo.core.memory.UserMemorySummary
import io.askimo.core.util.JsonUtils.json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the structured output parsing pipeline used by getSummary() and getUserMemoryFacts().
 *
 * Scenarios:
 * - Happy path: model returns exactly the expected JSON schema
 * - mainTopics returned as a string instead of an array  (real regression)
 * - interests/other user-memory values returned as arrays (real regression)
 * - Response wrapped in markdown code fence
 * - Extra whitespace / newlines in the response
 * - Empty / missing optional fields
 */
class StructuredOutputParsingTest {

    // ── helpers that mirror the private functions ─────────────────────────────

    private fun parseConversationSummary(rawResponse: String): SessionConversationSummary {
        var jsonText = rawResponse
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        jsonText = cleanJsonResponse(jsonText)
        jsonText = normalizeJsonFieldTypes(jsonText, arrayKeys = setOf("mainTopics"), stringKeys = setOf("recentContext"))

        return json.decodeFromString<SessionConversationSummary>(jsonText)
    }

    private fun parseUserMemoryFacts(rawResponse: String): Map<String, String> {
        var jsonText = rawResponse
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        jsonText = cleanAndWrapUserMemory(jsonText)
        return json.decodeFromString<UserMemorySummary>(jsonText).facts
    }

    /** Mirrors the logic in getUserMemoryFacts() */
    private fun cleanAndWrapUserMemory(raw: String): String {
        val cleaned = cleanJsonResponse(raw)
        val wrapped = """{"facts": $cleaned}"""
        return normalizeJsonFieldTypes(wrapped, emptySet(), emptySet())
    }

    // ── ConversationSummary tests ─────────────────────────────────────────────

    @Test
    fun `parses well-formed ConversationSummary response`() {
        val raw = """
            {
              "keyFacts": { "language": "Kotlin", "framework": "LangChain4j" },
              "mainTopics": ["structured outputs", "memory management"],
              "recentContext": "User is building a summarization system."
            }
        """.trimIndent()

        val result = parseConversationSummary(raw)

        assertEquals(mapOf("language" to "Kotlin", "framework" to "LangChain4j"), result.keyFacts)
        assertEquals(listOf("structured outputs", "memory management"), result.mainTopics)
        assertEquals("User is building a summarization system.", result.recentContext)
    }

    @Test
    fun `handles mainTopics returned as comma-string instead of array`() {
        // Real regression: model returns "a, b, c" instead of ["a", "b", "c"]
        val raw = """{"keyFacts":{"role":"ML engineer"},"mainTopics":"Python for Machine Learning, model training","recentContext":"user wants to fine-tune a model"}"""

        val result = parseConversationSummary(raw)

        assertEquals(listOf("Python for Machine Learning", "model training"), result.mainTopics)
        assertEquals("user wants to fine-tune a model", result.recentContext)
    }

    @Test
    fun `handles recentContext returned as array instead of string`() {
        val raw = """{"keyFacts":{},"mainTopics":["topic1"],"recentContext":["step1","step2"]}"""

        val result = parseConversationSummary(raw)

        assertEquals("step1, step2", result.recentContext)
    }

    @Test
    fun `strips markdown code fence from ConversationSummary response`() {
        val raw = """
            ```json
            {"keyFacts":{"lang":"Kotlin"},"mainTopics":["DI"],"recentContext":"Koin setup"}
            ```
        """.trimIndent()

        val result = parseConversationSummary(raw)

        assertEquals(listOf("DI"), result.mainTopics)
        assertEquals("Koin setup", result.recentContext)
    }

    @Test
    fun `handles empty keyFacts and mainTopics`() {
        val raw = """{"keyFacts":{},"mainTopics":[],"recentContext":""}"""

        val result = parseConversationSummary(raw)

        assertTrue(result.keyFacts.isEmpty())
        assertTrue(result.mainTopics.isEmpty())
        assertEquals("", result.recentContext)
    }

    // ── UserMemorySummary / getUserMemoryFacts tests ──────────────────────────

    @Test
    fun `parses well-formed user memory facts`() {
        val raw = """{"role":"senior backend engineer","primary_language":"Kotlin","os":"macOS"}"""

        val result = parseUserMemoryFacts(raw)

        assertEquals("senior backend engineer", result["role"])
        assertEquals("Kotlin", result["primary_language"])
        assertEquals("macOS", result["os"])
    }

    @Test
    fun `handles array values in user memory facts`() {
        // Real regression: model returns ["technology","science"] for "interests"
        val raw = """{"name":"Hai","role":"Java developer","interests":["technology","science","business","travel"]}"""

        val result = parseUserMemoryFacts(raw)

        assertEquals("Java developer", result["role"])
        assertEquals("technology, science, business, travel", result["interests"])
    }

    @Test
    fun `returns empty map for empty user memory response`() {
        val result = parseUserMemoryFacts("{}")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `strips markdown code fence from user memory response`() {
        val raw = """
            ```json
            {"role":"engineer","os":"Linux"}
            ```
        """.trimIndent()

        val result = parseUserMemoryFacts(raw)

        assertEquals("engineer", result["role"])
        assertEquals("Linux", result["os"])
    }

    @Test
    fun `handles extra text before and after JSON`() {
        // Some models prefix/suffix their json with explanation text
        val raw = """Here are the facts: {"role":"engineer","os":"macOS"} Hope that helps!"""

        val result = parseUserMemoryFacts(raw)

        assertEquals("engineer", result["role"])
        assertEquals("macOS", result["os"])
    }

    @Test
    fun `handles multiple array values in ConversationSummary keyFacts`() {
        // keyFacts values may also be arrays from some models
        val raw = """{"keyFacts":{"tools":["Gradle","Docker","Kotlin"]},"mainTopics":["DevOps"],"recentContext":"CI pipeline setup"}"""

        val result = parseConversationSummary(raw)

        assertEquals("Gradle, Docker, Kotlin", result.keyFacts["tools"])
    }
}

class UserMemorySummaryMergeTest {

    @Test
    fun `new key is added on first merge`() {
        val base = UserMemorySummary(facts = mapOf("role" to "developer"))
        val result = base.merge(mapOf("hobby" to "aquariums", "os" to "macOS"))
        assertEquals("aquariums", result.facts["hobby"])
        assertEquals("macOS", result.facts["os"])
        assertEquals("developer", result.facts["role"])
    }

    @Test
    fun `existing key appends new value`() {
        val base = UserMemorySummary(facts = mapOf("role" to "junior developer"))
        val result = base.merge(mapOf("role" to "senior engineer"))
        assertEquals("junior developer, senior engineer", result.facts["role"])
    }

    @Test
    fun `hobby accumulates across multiple merges`() {
        var summary = UserMemorySummary()
        summary = summary.merge(mapOf("hobby" to "gaming"))
        summary = summary.merge(mapOf("hobby" to "aquarium design"))
        summary = summary.merge(mapOf("hobby" to "hiking"))
        assertEquals("gaming, aquarium design, hiking", summary.facts["hobby"])
    }

    @Test
    fun `deduplicates on merge (case-insensitive)`() {
        val base = UserMemorySummary(facts = mapOf("hobby" to "gaming, Aquarium Design"))
        val result = base.merge(mapOf("hobby" to "aquarium design"))
        assertEquals("gaming, Aquarium Design", result.facts["hobby"])
    }

    @Test
    fun `skills accumulates multiple comma-separated values`() {
        val base = UserMemorySummary(facts = mapOf("skills" to "Kotlin, Java"))
        val result = base.merge(mapOf("skills" to "Python, Rust"))
        assertEquals("Kotlin, Java, Python, Rust", result.facts["skills"])
    }

    @Test
    fun `interests accumulates values`() {
        val base = UserMemorySummary(facts = mapOf("interest" to "machine learning"))
        val result = base.merge(mapOf("interest" to "aquariums"))
        assertEquals("machine learning, aquariums", result.facts["interest"])
    }

    @Test
    fun `timezone appends new value rather than overwriting`() {
        val base = UserMemorySummary(facts = mapOf("timezone" to "UTC"))
        val result = base.merge(mapOf("timezone" to "EST"))
        assertEquals("UTC, EST", result.facts["timezone"])
    }
}
