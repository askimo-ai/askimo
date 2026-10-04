/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import dev.langchain4j.spi.prompt.PromptTemplateFactory
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Custom [PromptTemplateFactory] that replaces LangChain4j's default implementation.
 *
 * Registered via `META-INF/services/dev.langchain4j.spi.prompt.PromptTemplateFactory`
 * so that [dev.langchain4j.spi.ServiceHelper] picks it up automatically.
 *
 * The key difference from the default: variables that have **no corresponding value**
 * in the provided map are left as-is in the rendered output rather than throwing an
 * [IllegalArgumentException]. This prevents spurious errors when user-typed text
 * contains `{{…}}` patterns that are not intended as template variables.
 */
class AskimoPromptTemplateFactory : PromptTemplateFactory {

    override fun create(input: PromptTemplateFactory.Input): PromptTemplateFactory.Template = AskimoTemplate(input.template)

    class AskimoTemplate(private val template: String) : PromptTemplateFactory.Template {

        override fun render(variables: Map<String, Any>): String {
            if (template.isBlank()) return template

            val matcher = VARIABLE_PATTERN.matcher(template)
            val result = StringBuffer(template.length)

            while (matcher.find()) {
                val variable = matcher.group(1).trim()
                val value = variables[variable]
                if (value != null) {
                    // Replace placeholder with the provided value
                    matcher.appendReplacement(result, Matcher.quoteReplacement(value.toString()))
                } else {
                    // No value supplied — leave the placeholder unchanged so user text is preserved
                    matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)))
                }
            }
            matcher.appendTail(result)

            return result.toString()
        }

        companion object {
            /**
             * Matches `{{variable}}` or `{{ variable }}` — same pattern as LangChain4j's default.
             */
            @Suppress("RegExpRedundantEscape")
            private val VARIABLE_PATTERN: Pattern =
                Pattern.compile("\\{\\{\\s*(.+?)\\s*\\}\\}")
        }
    }
}
