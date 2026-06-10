/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Lightweight syntax highlighter for code blocks.
 * Supports multiple programming languages with color-coded syntax.
 */
object CodeHighlighter {
    private data class LanguageProfile(
        val keywords: Set<String>,
        val commentPrefix: String,
    )

    data class Theme(
        val keyword: Color,
        val string: Color,
        val comment: Color,
        val number: Color,
        val function: Color,
        val type: Color,
        val operator: Color,
        val punctuation: Color,
    )

    fun darkTheme() = Theme(
        keyword = Color(0xFFCC7832),
        string = Color(0xFF6A8759),
        comment = Color(0xFF808080),
        number = Color(0xFF6897BB),
        function = Color(0xFFFFC66D),
        type = Color(0xFFB5CEA8),
        operator = Color(0xFFA9B7C6),
        punctuation = Color(0xFFA9B7C6),
    )

    fun lightTheme() = Theme(
        keyword = Color(0xFFAF00DB), // Purple - vibrant and visible
        string = Color(0xFF008000), // Green - classic and readable
        comment = Color(0xFF707070), // Medium gray - not too light
        number = Color(0xFF0000FF), // Blue - strong contrast
        function = Color(0xFF795E26), // Brown - warm and distinct
        type = Color(0xFF0070C1), // Medium blue - clear distinction
        operator = Color(0xFF000000), // Black - maximum readability
        punctuation = Color(0xFF000000), // Black - maximum readability
    )

    private val kotlinKeywords = setOf(
        "abstract", "actual", "annotation", "as", "break", "by", "catch", "class", "companion",
        "const", "constructor", "continue", "crossinline", "data", "delegate", "do", "else",
        "enum", "expect", "external", "false", "field", "file", "final", "finally", "for",
        "fun", "get", "if", "import", "in", "infix", "init", "inline", "inner", "interface",
        "internal", "is", "lateinit", "noinline", "null", "object", "open", "operator", "out",
        "override", "package", "param", "private", "property", "protected", "public", "receiver",
        "reified", "return", "sealed", "set", "setparam", "super", "suspend", "tailrec", "this",
        "throw", "true", "try", "typealias", "typeof", "val", "var", "vararg", "when", "where", "while",
    )

    private val javaKeywords = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "extends", "false",
        "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof",
        "int", "interface", "long", "native", "new", "null", "package", "private", "protected",
        "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized",
        "this", "throw", "throws", "transient", "true", "try", "void", "volatile", "while",
    )

    private val pythonKeywords = setOf(
        "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
        "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global",
        "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise",
        "return", "try", "while", "with", "yield",
    )

    private val jsKeywords = setOf(
        "abstract", "arguments", "await", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "debugger", "default", "delete", "do", "double", "else",
        "enum", "eval", "export", "extends", "false", "final", "finally", "float", "for",
        "function", "goto", "if", "implements", "import", "in", "instanceof", "int", "interface",
        "let", "long", "native", "new", "null", "package", "private", "protected", "public",
        "return", "short", "static", "super", "switch", "synchronized", "this", "throw",
        "throws", "transient", "true", "try", "typeof", "var", "void", "volatile", "while",
        "with", "yield", "async",
    )

    private val sqlKeywords = setOf(
        "ADD", "ALL", "ALTER", "AND", "ANY", "AS", "ASC", "BACKUP", "BETWEEN", "CASE", "CHECK",
        "COLUMN", "CONSTRAINT", "CREATE", "DATABASE", "DEFAULT", "DELETE", "DESC", "DISTINCT",
        "DROP", "EXEC", "EXISTS", "FOREIGN", "FROM", "FULL", "GROUP", "HAVING", "IN", "INDEX",
        "INNER", "INSERT", "INTO", "IS", "JOIN", "KEY", "LEFT", "LIKE", "LIMIT", "NOT", "NULL",
        "OR", "ORDER", "OUTER", "PRIMARY", "PROCEDURE", "RIGHT", "ROWNUM", "SELECT", "SET",
        "TABLE", "TOP", "TRUNCATE", "UNION", "UNIQUE", "UPDATE", "VALUES", "VIEW", "WHERE",
    )

    private val shellKeywords = setOf(
        "if", "then", "else", "elif", "fi", "case", "esac", "for", "select", "while", "until",
        "do", "done", "in", "function", "time", "coproc", "export", "readonly", "local",
        "declare", "typeset", "unset", "set", "shift", "return", "exit", "break", "continue",
        "test", "source", "alias", "unalias", "echo", "printf", "read", "cd", "pwd", "pushd",
        "popd", "dirs", "let", "eval", "exec", "trap", "wait", "jobs", "bg", "fg", "kill",
    )

    private val goKeywords = setOf(
        "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough",
        "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range",
        "return", "select", "struct", "switch", "type", "var", "nil", "true", "false", "iota",
    )

    private val cKeywords = setOf(
        "auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else",
        "enum", "extern", "float", "for", "goto", "if", "inline", "int", "long", "register",
        "restrict", "return", "short", "signed", "sizeof", "static", "struct", "switch", "typedef",
        "union", "unsigned", "void", "volatile", "while", "_Bool", "_Complex", "_Imaginary",
    )

    private val cppKeywords = setOf(
        "alignas", "alignof", "and", "asm", "auto", "bool", "break", "case", "catch", "char",
        "class", "const", "constexpr", "continue", "decltype", "default", "delete", "do", "double",
        "else", "enum", "explicit", "export", "extern", "false", "float", "for", "friend", "goto",
        "if", "inline", "int", "long", "mutable", "namespace", "new", "noexcept", "not", "nullptr",
        "operator", "or", "private", "protected", "public", "register", "reinterpret_cast", "return",
        "short", "signed", "sizeof", "static", "struct", "switch", "template", "this", "throw", "true",
        "try", "typedef", "typeid", "typename", "union", "unsigned", "using", "virtual", "void", "volatile",
        "wchar_t", "while",
    )

    private val csharpKeywords = setOf(
        "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char", "checked", "class",
        "const", "continue", "decimal", "default", "delegate", "do", "double", "else", "enum", "event",
        "explicit", "extern", "false", "finally", "fixed", "float", "for", "foreach", "goto", "if",
        "implicit", "in", "int", "interface", "internal", "is", "lock", "long", "namespace", "new", "null",
        "object", "operator", "out", "override", "params", "private", "protected", "public", "readonly",
        "ref", "return", "sbyte", "sealed", "short", "sizeof", "stackalloc", "static", "string", "struct",
        "switch", "this", "throw", "true", "try", "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort",
        "using", "virtual", "void", "volatile", "while", "async", "await", "var",
    )

    private val rustKeywords = setOf(
        "as", "break", "const", "continue", "crate", "else", "enum", "extern", "false", "fn", "for", "if",
        "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return", "self", "Self",
        "static", "struct", "super", "trait", "true", "type", "unsafe", "use", "where", "while", "async", "await",
        "dyn",
    )

    private val rubyKeywords = setOf(
        "BEGIN", "END", "alias", "and", "begin", "break", "case", "class", "def", "defined?", "do", "else",
        "elsif", "end", "ensure", "false", "for", "if", "in", "module", "next", "nil", "not", "or", "redo",
        "rescue", "retry", "return", "self", "super", "then", "true", "undef", "unless", "until", "when", "while",
        "yield",
    )

    private val phpKeywords = setOf(
        "abstract", "and", "array", "as", "break", "callable", "case", "catch", "class", "clone", "const",
        "continue", "declare", "default", "do", "echo", "else", "elseif", "empty", "enddeclare", "endfor", "endforeach",
        "endif", "endswitch", "endwhile", "eval", "exit", "extends", "final", "finally", "fn", "for", "foreach", "function",
        "global", "goto", "if", "implements", "include", "include_once", "instanceof", "insteadof", "interface", "isset", "list",
        "match", "namespace", "new", "null", "or", "print", "private", "protected", "public", "readonly", "require",
        "require_once", "return", "static", "switch", "throw", "trait", "try", "unset", "use", "var", "while", "xor", "yield",
        "true", "false",
    )

    private val swiftKeywords = setOf(
        "associatedtype", "class", "deinit", "enum", "extension", "fileprivate", "func", "import", "init", "inout",
        "internal", "let", "open", "operator", "private", "protocol", "public", "rethrows", "static", "struct", "subscript",
        "typealias", "var", "break", "case", "continue", "default", "defer", "do", "else", "fallthrough", "for", "guard",
        "if", "in", "repeat", "return", "switch", "where", "while", "as", "Any", "catch", "false", "is", "nil", "super",
        "self", "Self", "throw", "throws", "true", "try",
    )

    private val yamlKeywords = setOf("true", "false", "null", "yes", "no", "on", "off")
    private val tomlKeywords = setOf("true", "false")

    private val languageProfiles = mapOf(
        "kotlin" to LanguageProfile(kotlinKeywords, "//"),
        "java" to LanguageProfile(javaKeywords, "//"),
        "python" to LanguageProfile(pythonKeywords, "#"),
        "javascript" to LanguageProfile(jsKeywords, "//"),
        "typescript" to LanguageProfile(jsKeywords, "//"),
        "sql" to LanguageProfile(sqlKeywords, "--"),
        "shell" to LanguageProfile(shellKeywords, "#"),
        "go" to LanguageProfile(goKeywords, "//"),
        "c" to LanguageProfile(cKeywords, "//"),
        "cpp" to LanguageProfile(cppKeywords, "//"),
        "csharp" to LanguageProfile(csharpKeywords, "//"),
        "rust" to LanguageProfile(rustKeywords, "//"),
        "ruby" to LanguageProfile(rubyKeywords, "#"),
        "php" to LanguageProfile(phpKeywords, "//"),
        "swift" to LanguageProfile(swiftKeywords, "//"),
        "yaml" to LanguageProfile(yamlKeywords, "#"),
        "toml" to LanguageProfile(tomlKeywords, "#"),
    )

    fun highlight(code: String, language: String?, theme: Theme, codeFontFamily: FontFamily = FontFamily.Monospace): AnnotatedString {
        val lang = normalizeLanguage(language)

        languageProfiles[lang]?.let { profile ->
            return highlightCode(
                code = code,
                keywords = profile.keywords,
                theme = theme,
                commentPrefix = profile.commentPrefix,
                codeFontFamily = codeFontFamily,
            )
        }

        return when {
            lang in listOf("json") -> highlightJSON(code, theme, codeFontFamily)

            lang in listOf("xml", "html") -> highlightXML(code, theme, codeFontFamily)

            else -> buildAnnotatedString {
                withStyle(SpanStyle(fontFamily = codeFontFamily)) {
                    append(code)
                }
            }
        }
    }

    private fun normalizeLanguage(language: String?): String {
        val raw = language?.trim()?.lowercase().orEmpty()
        if (raw.isEmpty()) return ""

        // Support info strings like "go title=main.go" and aliases like "golang".
        val token = raw
            .substringBefore(' ')
            .substringBefore('\t')
            .substringBefore(',')
            .substringBefore('{')

        return when (token) {
            "kt", "kts" -> "kotlin"
            "py", "python3" -> "python"
            "js", "jsx", "node" -> "javascript"
            "ts", "tsx" -> "typescript"
            "mysql", "postgres", "postgresql", "sqlite" -> "sql"
            "bash", "sh", "zsh", "fish", "shellscript" -> "shell"
            "golang" -> "go"
            "c++", "cc", "cxx" -> "cpp"
            "c#", "cs" -> "csharp"
            "rb" -> "ruby"
            "yml" -> "yaml"
            else -> token
        }
    }

    private fun highlightCode(
        code: String,
        keywords: Set<String>,
        theme: Theme,
        commentPrefix: String = "//",
        codeFontFamily: FontFamily = FontFamily.Monospace,
    ): AnnotatedString = buildAnnotatedString {
        val lines = code.split("\n")
        var inMultiLineComment = false

        lines.forEachIndexed { index, line ->
            if (index > 0) append("\n")

            // Check for multi-line comment start/end
            if (line.trim().startsWith("/*")) inMultiLineComment = true
            if (inMultiLineComment) {
                withStyle(SpanStyle(color = theme.comment, fontFamily = codeFontFamily)) {
                    append(line)
                }
                if (line.trim().endsWith("*/")) inMultiLineComment = false
                return@forEachIndexed
            }

            // Single-line comment
            val commentIndex = line.indexOf(commentPrefix)
            val codeBeforeComment = if (commentIndex >= 0) line.substring(0, commentIndex) else line
            val comment = if (commentIndex >= 0) line.substring(commentIndex) else null

            // Process code
            if (codeBeforeComment.isNotEmpty()) {
                highlightLine(codeBeforeComment, keywords, theme, codeFontFamily)
            }

            // Process comment
            if (comment != null) {
                withStyle(SpanStyle(color = theme.comment, fontFamily = codeFontFamily)) {
                    append(comment)
                }
            }
        }
    }

    private fun AnnotatedString.Builder.highlightLine(
        line: String,
        keywords: Set<String>,
        theme: Theme,
        codeFontFamily: FontFamily,
    ) {
        // Regex patterns
        val annotationPattern = """@[a-zA-Z_][\w.]*""".toRegex()
        val stringPattern = """"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'""".toRegex()
        val numberPattern = """\b\d+\.?\d*[fFlL]?\b""".toRegex()
        val wordPattern = """\b[a-zA-Z_]\w*\b""".toRegex()

        val tokens = mutableListOf<Triple<IntRange, String, SpanStyle>>()

        // Find strings
        stringPattern.findAll(line).forEach { match ->
            tokens.add(
                Triple(
                    match.range,
                    "string",
                    SpanStyle(color = theme.string, fontFamily = codeFontFamily),
                ),
            )
        }

        // Find annotations/decorators (e.g., @RestController, @RequestMapping)
        annotationPattern.findAll(line).forEach { match ->
            tokens.add(
                Triple(
                    match.range,
                    "annotation",
                    SpanStyle(
                        color = theme.type,
                        fontWeight = FontWeight.Bold,
                        fontFamily = codeFontFamily,
                    ),
                ),
            )
        }

        // Find numbers
        numberPattern.findAll(line).forEach { match ->
            tokens.add(
                Triple(
                    match.range,
                    "number",
                    SpanStyle(color = theme.number, fontFamily = codeFontFamily),
                ),
            )
        }

        // Find keywords and functions
        wordPattern.findAll(line).forEach { match ->
            val word = match.value
            when {
                word in keywords -> tokens.add(
                    Triple(
                        match.range,
                        "keyword",
                        SpanStyle(
                            color = theme.keyword,
                            fontWeight = FontWeight.Bold,
                            fontFamily = codeFontFamily,
                        ),
                    ),
                )

                // Check if it's followed by '(' - likely a function
                match.range.last + 1 < line.length && line[match.range.last + 1] == '(' ->
                    tokens.add(
                        Triple(
                            match.range,
                            "function",
                            SpanStyle(color = theme.function, fontFamily = codeFontFamily),
                        ),
                    )
            }
        }

        // Sort and remove overlaps
        val sortedTokens = tokens.sortedBy { it.first.first }
        val nonOverlapping = mutableListOf<Triple<IntRange, String, SpanStyle>>()
        var lastEnd = -1

        sortedTokens.forEach { token ->
            if (token.first.first > lastEnd) {
                nonOverlapping.add(token)
                lastEnd = token.first.last
            }
        }

        // Build annotated string
        var currentIndex = 0
        nonOverlapping.forEach { (range, _, style) ->
            // Append text before token
            if (currentIndex < range.first) {
                withStyle(SpanStyle(fontFamily = codeFontFamily)) {
                    append(line.substring(currentIndex, range.first))
                }
            }
            // Append styled token
            withStyle(style) {
                append(line.substring(range))
            }
            currentIndex = range.last + 1
        }

        // Append remaining text
        if (currentIndex < line.length) {
            withStyle(SpanStyle(fontFamily = codeFontFamily)) {
                append(line.substring(currentIndex))
            }
        }
    }

    private fun highlightJSON(code: String, theme: Theme, codeFontFamily: FontFamily): AnnotatedString = buildAnnotatedString {
        val stringPattern = """"(?:[^"\\]|\\.)*"""".toRegex()
        val numberPattern = """-?\d+\.?\d*([eE][+-]?\d+)?""".toRegex()
        val boolNullPattern = """\b(true|false|null)\b""".toRegex()

        val tokens = collectTokens(
            source = code,
            stringPattern to SpanStyle(color = theme.string, fontFamily = codeFontFamily),
            boolNullPattern to SpanStyle(
                color = theme.keyword,
                fontWeight = FontWeight.Bold,
                fontFamily = codeFontFamily,
            ),
            numberPattern to SpanStyle(color = theme.number, fontFamily = codeFontFamily),
        )

        appendStyledRanges(code, tokens, codeFontFamily)
    }

    private fun highlightXML(code: String, theme: Theme, codeFontFamily: FontFamily): AnnotatedString = buildAnnotatedString {
        val tagPattern = """</?[\w:.-]+|/?>""".toRegex()
        val attrPattern = """\b[\w:.-]+(?==)""".toRegex()
        val stringPattern = """"[^"]*"|'[^']*'""".toRegex()
        val commentPattern = """<!--.*?-->""".toRegex()

        val tokens = collectTokens(
            source = code,
            commentPattern to SpanStyle(color = theme.comment, fontFamily = codeFontFamily),
            tagPattern to SpanStyle(
                color = theme.keyword,
                fontWeight = FontWeight.Bold,
                fontFamily = codeFontFamily,
            ),
            attrPattern to SpanStyle(color = theme.function, fontFamily = codeFontFamily),
            stringPattern to SpanStyle(color = theme.string, fontFamily = codeFontFamily),
        )

        appendStyledRanges(code, tokens, codeFontFamily)
    }

    /**
     * Appends [source] with syntax tokens applied, keeping only the first non-overlapping token
     * at each position and preserving plain text between token ranges.
     */
    private fun AnnotatedString.Builder.appendStyledRanges(
        source: String,
        tokens: List<Pair<IntRange, SpanStyle>>,
        defaultFontFamily: FontFamily,
    ) {
        val sorted = tokens.sortedBy { it.first.first }
        val nonOverlapping = mutableListOf<Pair<IntRange, SpanStyle>>()
        var lastEnd = -1

        sorted.forEach { token ->
            if (token.first.first > lastEnd) {
                nonOverlapping.add(token)
                lastEnd = token.first.last
            }
        }

        var currentIndex = 0
        nonOverlapping.forEach { (range, style) ->
            if (currentIndex < range.first) {
                withStyle(SpanStyle(fontFamily = defaultFontFamily)) {
                    append(source.substring(currentIndex, range.first))
                }
            }
            withStyle(style) {
                append(source.substring(range))
            }
            currentIndex = range.last + 1
        }

        if (currentIndex < source.length) {
            withStyle(SpanStyle(fontFamily = defaultFontFamily)) {
                append(source.substring(currentIndex))
            }
        }
    }

    private fun MutableList<Pair<IntRange, SpanStyle>>.addMatches(
        pattern: Regex,
        source: String,
        style: SpanStyle,
    ) {
        pattern.findAll(source).forEach { match ->
            add(match.range to style)
        }
    }

    private fun collectTokens(
        source: String,
        vararg patternStyles: Pair<Regex, SpanStyle>,
    ): MutableList<Pair<IntRange, SpanStyle>> = mutableListOf<Pair<IntRange, SpanStyle>>().also { tokens ->
        patternStyles.forEach { (pattern, style) ->
            tokens.addMatches(pattern, source, style)
        }
    }
}
