/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.mcp

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemplateResolverTest {

    @Nested
    inner class Resolve {

        @Test
        fun `replaces a simple placeholder with its value`() {
            val resolver = TemplateResolver(mapOf("mongoUri" to "mongodb://localhost"))
            assertEquals("mongodb://localhost", resolver.resolve("{{mongoUri}}"))
        }

        @Test
        fun `replaces multiple simple placeholders in one template`() {
            val resolver = TemplateResolver(mapOf("host" to "localhost", "port" to "27017"))
            assertEquals("localhost:27017", resolver.resolve("{{host}}:{{port}}"))
        }

        @Test
        fun `missing simple placeholder resolves to empty string`() {
            val resolver = TemplateResolver(emptyMap())
            assertEquals("", resolver.resolve("{{missing}}"))
        }

        @Test
        fun `leaves text without placeholders unchanged`() {
            val resolver = TemplateResolver(emptyMap())
            assertEquals("--stdio", resolver.resolve("--stdio"))
        }

        @Test
        fun `conditional placeholder includes value when flag is true`() {
            val resolver = TemplateResolver(mapOf("readOnly" to "true"))
            assertEquals("--readOnly", resolver.resolve("{{?readOnly:--readOnly}}"))
        }

        @Test
        fun `conditional placeholder is case-insensitive for the true value`() {
            val resolver = TemplateResolver(mapOf("readOnly" to "TRUE"))
            assertEquals("--readOnly", resolver.resolve("{{?readOnly:--readOnly}}"))
        }

        @Test
        fun `conditional placeholder resolves to empty when flag is false`() {
            val resolver = TemplateResolver(mapOf("readOnly" to "false"))
            assertEquals("", resolver.resolve("{{?readOnly:--readOnly}}"))
        }

        @Test
        fun `conditional placeholder resolves to empty when flag is missing`() {
            val resolver = TemplateResolver(emptyMap())
            assertEquals("", resolver.resolve("{{?readOnly:--readOnly}}"))
        }

        @Test
        fun `conditional and simple placeholders can combine in one template`() {
            val resolver = TemplateResolver(mapOf("readOnly" to "true", "path" to "/data"))
            assertEquals("--readOnly /data", resolver.resolve("{{?readOnly:--readOnly }}{{path}}"))
        }
    }

    @Nested
    inner class ResolveListTest {

        @Test
        fun `resolves every template in the list`() {
            val resolver = TemplateResolver(mapOf("tool" to "server-filesystem"))
            val result = resolver.resolveList(listOf("npx", "-y", "{{tool}}"))
            assertEquals(listOf("npx", "-y", "server-filesystem"), result)
        }

        @Test
        fun `filters out entries that resolve to blank, eg unmet conditional flags`() {
            val resolver = TemplateResolver(mapOf("readOnly" to "false"))
            val result = resolver.resolveList(listOf("npx", "{{?readOnly:--readOnly}}", "server"))
            assertEquals(listOf("npx", "server"), result)
        }

        @Test
        fun `matches resolveListWithSources resolved values`() {
            val resolver = TemplateResolver(mapOf("readOnly" to "false"))
            val templates = listOf("npx", "{{?readOnly:--readOnly}}", "server")
            assertEquals(resolver.resolveList(templates), resolver.resolveListWithSources(templates).first)
        }
    }

    @Nested
    inner class ResolveListWithSourcesTest {

        @Test
        fun `keeps surviving entries aligned with their own template`() {
            val resolver = TemplateResolver(mapOf("tool" to "server-filesystem"))
            val (resolved, sources) = resolver.resolveListWithSources(listOf("npx", "-y", "{{tool}}"))

            assertEquals(listOf("npx", "-y", "server-filesystem"), resolved)
            assertEquals(listOf("npx", "-y", "{{tool}}"), sources)
        }

        @Test
        fun `keeps resolved and source entries aligned when an earlier entry is filtered out`() {
            // Regression: a filtered-out conditional flag ahead of a secret placeholder must not
            // shift the secret's resolved index out of alignment with its own template.
            val resolver = TemplateResolver(mapOf("debug" to "false", "apiKey" to "sk-real-secret"))
            val (resolved, sources) = resolver.resolveListWithSources(
                listOf("{{?debug:--verbose}}", "{{apiKey}}"),
            )

            assertEquals(listOf("sk-real-secret"), resolved)
            assertEquals(listOf("{{apiKey}}"), sources)
        }
    }

    @Nested
    inner class ResolveMapTest {

        @Test
        fun `resolves a simple key-template pair`() {
            val resolver = TemplateResolver(mapOf("mode" to "prod"))
            val result = resolver.resolveMap(mapOf("NODE_ENV" to "{{mode}}"))
            assertEquals(mapOf("NODE_ENV" to "prod"), result)
        }

        @Test
        fun `skips entries that resolve to blank`() {
            val resolver = TemplateResolver(emptyMap())
            val result = resolver.resolveMap(mapOf("TOKEN" to "{{missing}}"))
            assertTrue(result.isEmpty())
        }

        @Test
        fun `expands a comma-separated KEY=value template into multiple output entries`() {
            val resolver = TemplateResolver(mapOf("apiKey" to "secret-value"))
            val result = resolver.resolveMap(mapOf("ENV" to "API_KEY={{apiKey}},MODE=prod"))
            assertEquals(mapOf("API_KEY" to "secret-value", "MODE" to "prod"), result)
        }

        @Test
        fun `matches resolveMapWithSources resolved values`() {
            val resolver = TemplateResolver(mapOf("apiKey" to "secret-value"))
            val templates = mapOf("ENV" to "API_KEY={{apiKey}},MODE=prod")
            assertEquals(resolver.resolveMap(templates), resolver.resolveMapWithSources(templates).first)
        }
    }

    @Nested
    inner class ResolveMapWithSourcesTest {

        @Test
        fun `keeps simple entries mapped to their own template`() {
            val resolver = TemplateResolver(mapOf("mode" to "prod"))
            val (resolved, sources) = resolver.resolveMapWithSources(mapOf("NODE_ENV" to "{{mode}}"))

            assertEquals("prod", resolved.getValue("NODE_ENV"))
            assertEquals("{{mode}}", sources.getValue("NODE_ENV"))
        }

        @Test
        fun `traces comma-expanded KEY=value entries back to their source template`() {
            val resolver = TemplateResolver(mapOf("apiKey" to "secret-value"))
            val (resolved, sources) = resolver.resolveMapWithSources(
                mapOf("ENV" to "API_KEY={{apiKey}},MODE=prod"),
            )

            assertEquals("secret-value", resolved.getValue("API_KEY"))
            assertEquals("prod", resolved.getValue("MODE"))

            // Both output keys must trace back to the original "ENV" template, not be missing/blank,
            // so that secret-placeholder detection on the source template still works post-expansion.
            assertEquals("API_KEY={{apiKey}},MODE=prod", sources.getValue("API_KEY"))
            assertEquals("API_KEY={{apiKey}},MODE=prod", sources.getValue("MODE"))
        }

        @Test
        fun `skips entries that resolve to blank for both result and sources`() {
            val resolver = TemplateResolver(emptyMap())
            val (resolved, sources) = resolver.resolveMapWithSources(mapOf("TOKEN" to "{{missing}}"))

            assertTrue(resolved.isEmpty())
            assertTrue(sources.isEmpty())
        }
    }

    @Nested
    inner class Validate {

        @Test
        fun `valid template with matched braces has no errors`() {
            val result = TemplateResolver.validate("{{mongoUri}}")
            assertTrue(result.isValid)
            assertTrue(result.errors.isEmpty())
        }

        @Test
        fun `detects unmatched opening braces`() {
            val result = TemplateResolver.validate("{{mongoUri")
            assertFalse(result.isValid)
            assertTrue(result.errors.any { it.contains("Unmatched opening braces") })
        }

        @Test
        fun `detects unmatched closing braces`() {
            val result = TemplateResolver.validate("mongoUri}}")
            assertFalse(result.isValid)
            assertTrue(result.errors.any { it.contains("Unmatched closing braces") })
        }

        @Test
        fun `detects an empty placeholder`() {
            val result = TemplateResolver.validate("{{}}")
            assertFalse(result.isValid)
            assertTrue(result.errors.any { it.contains("Empty placeholder") })
        }
    }

    @Nested
    inner class ExtractParameters {

        @Test
        fun `extracts a simple placeholder key`() {
            assertEquals(setOf("mongoUri"), TemplateResolver.extractParameters("{{mongoUri}}"))
        }

        @Test
        fun `extracts a conditional placeholder key`() {
            assertEquals(setOf("readOnly"), TemplateResolver.extractParameters("{{?readOnly:--readOnly}}"))
        }

        @Test
        fun `extracts keys from a template combining simple and conditional placeholders`() {
            val result = TemplateResolver.extractParameters("{{?readOnly:--readOnly }}{{path}}")
            assertEquals(setOf("readOnly", "path"), result)
        }

        @Test
        fun `returns an empty set for a template without placeholders`() {
            assertTrue(TemplateResolver.extractParameters("--stdio").isEmpty())
        }
    }
}
