/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.service

import com.lowagie.text.Chunk
import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.Font
import com.lowagie.text.Image
import com.lowagie.text.PageSize
import com.lowagie.text.Paragraph
import com.lowagie.text.Phrase
import com.lowagie.text.Rectangle
import com.lowagie.text.pdf.BaseFont
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfPageEventHelper
import com.lowagie.text.pdf.PdfWriter
import com.lowagie.text.pdf.draw.LineSeparator
import io.askimo.core.logging.logger
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Generic markdown-to-PDF renderer using OpenPDF with full Unicode support.
 *
 * Fonts are loaded from bundled NotoSans TTF resources (src/main/resources/fonts/)
 * using IDENTITY_H encoding so that any Unicode character — including emoji,
 * CJK, Arabic, etc. — renders correctly instead of appearing as "?".
 *
 * Handles: headings (H1–H6), horizontal rules, bullet lists, code fences,
 * blockquotes, GFM tables, blank lines, and inline **bold** / *italic* / `code`.
 */
internal object PdfExporter {

    private val log = logger<PdfExporter>()

    // ── Unicode-capable base fonts loaded from bundled TTF resources ──────────
    private val baseFontRegular: BaseFont by lazy { loadBaseFont("/fonts/NotoSans-Regular.ttf") }
    private val baseFontBold: BaseFont by lazy { loadBaseFont("/fonts/NotoSans-Bold.ttf") }
    private val baseFontItalic: BaseFont by lazy { loadBaseFont("/fonts/NotoSans-Italic.ttf") }

    // NotoEmoji covers emoji codepoints as a fallback
    private val baseFontEmoji: BaseFont by lazy { loadBaseFont("/fonts/NotoEmoji-Regular.ttf") }

    private fun loadBaseFont(resourcePath: String): BaseFont {
        val bytes = PdfExporter::class.java.getResourceAsStream(resourcePath)?.readBytes()
            ?: error("Bundled font not found: $resourcePath")
        return BaseFont.createFont(resourcePath, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, bytes, null)
    }

    // ── Font helpers — create a new Font wrapping the shared BaseFont ─────────
    private fun fontNormal(size: Float = 11f, color: Color = Color.BLACK) = Font(baseFontRegular, size, Font.NORMAL, color)

    private fun fontBold(size: Float = 11f, color: Color = Color.BLACK) = Font(baseFontBold, size, Font.BOLD, color)

    private fun fontItalic(size: Float = 11f, color: Color = Color.BLACK) = Font(baseFontItalic, size, Font.ITALIC, color)

    private fun fontH1() = Font(baseFontBold, 20f, Font.BOLD, Color(30, 90, 180))
    private fun fontH2() = Font(baseFontBold, 15f, Font.BOLD, Color(50, 50, 50))
    private fun fontH3() = Font(baseFontBold, 12f, Font.BOLD, Color(80, 80, 80))
    private fun fontH4() = Font(baseFontBold, 11f, Font.BOLD, Color(90, 90, 90))
    private fun fontH5() = Font(baseFontBold, 10f, Font.BOLD, Color(100, 100, 100))
    private fun fontH6() = Font(baseFontItalic, 10f, Font.ITALIC, Color(110, 110, 110))
    private fun fontCode() = Font(baseFontRegular, 9f, Font.NORMAL, Color(60, 60, 60))
    private fun fontFooter() = Font(baseFontItalic, 8f, Font.ITALIC, Color(130, 130, 130))

    /**
     * Builds a [Chunk] for [text] using [base], with per-character fallback to
     * [baseFontEmoji] for codepoints not covered by the base font.
     *
     * OpenPDF doesn't support per-character font fallback natively, so we split
     * the string into runs of "base-font chars" vs "emoji/fallback chars" and
     * emit alternating Chunks.
     */
    private fun chunksWithFallback(text: String, base: BaseFont, font: Font): List<Chunk> {
        if (text.isEmpty()) return emptyList()
        val chunks = mutableListOf<Chunk>()
        val buf = StringBuilder()
        var useEmoji = false

        fun flush() {
            if (buf.isEmpty()) return
            val f = if (useEmoji) Font(baseFontEmoji, font.size, font.style, font.color) else font
            chunks.add(Chunk(buf.toString(), f))
            buf.clear()
        }

        text.codePoints().forEach { cp ->
            val ch = String(Character.toChars(cp))
            val needsEmoji = !base.charExists(cp) && baseFontEmoji.charExists(cp)
            if (needsEmoji != useEmoji) {
                flush()
                useEmoji = needsEmoji
            }
            buf.append(ch)
        }
        flush()
        return chunks
    }

    /**
     * Adds a copyright + page-number footer to every PDF page via OpenPDF's event system.
     */
    private class FooterPageEvent(
        private val copyright: String,
        private val ftr: Font,
    ) : PdfPageEventHelper() {
        override fun onEndPage(writer: PdfWriter, document: Document) {
            val cb = writer.directContent
            val left = document.left()
            val right = document.right()
            val bottom = document.bottom() - 18f
            val width = right - left

            cb.setLineWidth(0.5f)
            cb.setColorStroke(Color(200, 200, 200))
            cb.moveTo(left, bottom + 10f)
            cb.lineTo(right, bottom + 10f)
            cb.stroke()

            val copyrightTable = PdfPTable(2).apply {
                totalWidth = width
                isLockedWidth = true
            }
            copyrightTable.addCell(
                PdfPCell(Phrase(copyright, ftr)).apply {
                    border = Rectangle.NO_BORDER
                    setPaddingTop(2f)
                },
            )
            copyrightTable.addCell(
                PdfPCell(Phrase("Page ${writer.pageNumber}", ftr)).apply {
                    border = Rectangle.NO_BORDER
                    horizontalAlignment = Element.ALIGN_RIGHT
                    setPaddingTop(2f)
                },
            )
            copyrightTable.writeSelectedRows(0, -1, left, bottom + 8f, cb)
        }
    }

    suspend fun export(markdown: String, copyright: String, targetFile: File) {
        targetFile.parentFile?.mkdirs()

        val bos = ByteArrayOutputStream(65536)
        val document = Document(PageSize.A4, 72f, 72f, 72f, 90f)
        val writer = PdfWriter.getInstance(document, bos)
        writer.pageEvent = FooterPageEvent(copyright, fontFooter())
        document.open()

        renderMarkdownToDocument(document, markdown)

        document.close()

        val bytes = bos.toByteArray()
        if (bytes.isEmpty()) error("PDF rendering produced 0 bytes")
        FileOutputStream(targetFile).use { it.write(bytes) }
        log.debug("PDF written: {} bytes -> {}", bytes.size, targetFile.absolutePath)
    }

    /**
     * Light normalisation — only fixes characters that cause layout bugs in OpenPDF
     * (e.g. NON-BREAKING HYPHEN collapses adjacent spaces). Unicode characters that
     * are simply "unsupported by Helvetica" are no longer replaced — NotoSans handles them.
     */
    internal fun normalizePdfText(text: String): String = text
        .replace('\u2011', '-') // NON-BREAKING HYPHEN — causes space collapsing in OpenPDF
        .replace('\u00A0', ' ') // NO-BREAK SPACE → regular space
        .replace('\u202F', ' ') // NARROW NO-BREAK SPACE
        .replace("\u200B", "") // ZERO WIDTH SPACE
        .replace("\u200C", "") // ZERO WIDTH NON-JOINER
        .replace("\u200D", "") // ZERO WIDTH JOINER
        .replace("\uFEFF", "") // BYTE ORDER MARK

    private suspend fun renderMarkdownToDocument(doc: Document, markdown: String) {
        val lines = normalizePdfText(markdown).lines()
        var i = 0
        var inCodeBlock = false
        var isMermaidBlock = false
        val codeBuffer = StringBuilder()
        val mermaidService by lazy { MermaidSvgService() }

        while (i < lines.size) {
            val line = lines[i]

            // ── Code fence ────────────────────────────────────────────────────
            if (line.trimStart().startsWith("```")) {
                if (!inCodeBlock) {
                    inCodeBlock = true
                    isMermaidBlock = line.trimStart().removePrefix("```").trim().lowercase() == "mermaid"
                    codeBuffer.clear()
                } else {
                    inCodeBlock = false
                    val fenceContent = codeBuffer.toString().trimEnd()

                    if (isMermaidBlock && mermaidService.isMermaidCliAvailable()) {
                        val pngBytes = runCatching {
                            mermaidService.convertToPng(fenceContent, theme = "default", backgroundColor = "#ffffff")
                        }.getOrNull()

                        if (pngBytes != null && pngBytes.isNotEmpty()) {
                            val img = Image.getInstance(pngBytes)
                            // Scale to fit page width (A4 usable = 72*2 margins → ~451pt)
                            val maxWidth = doc.pageSize.width - doc.leftMargin() - doc.rightMargin()
                            if (img.width > maxWidth) {
                                img.scaleToFit(maxWidth, doc.pageSize.height)
                            }
                            img.alignment = Element.ALIGN_CENTER
                            img.spacingBefore = 8f
                            img.spacingAfter = 8f
                            doc.add(img)
                            log.debug("Embedded mermaid diagram as PNG ({} bytes)", pngBytes.size)
                        } else {
                            // mmdc available but render failed — fall back to code block
                            addCodeBlock(doc, fenceContent)
                        }
                    } else {
                        // Not mermaid, or mmdc not installed — render as code block
                        addCodeBlock(doc, fenceContent)
                    }

                    isMermaidBlock = false
                    codeBuffer.clear()
                }
                i++
                continue
            }
            if (inCodeBlock) {
                codeBuffer.appendLine(line)
                i++
                continue
            }

            // ── Markdown table ────────────────────────────────────────────────
            if (isTableRow(line) && i + 1 < lines.size && isSeparatorRow(lines[i + 1])) {
                val tableLines = mutableListOf<String>()
                var j = i
                while (j < lines.size && isTableRow(lines[j])) {
                    tableLines.add(lines[j])
                    j++
                }
                val headerCells = parseTableRow(tableLines[0])
                val dataRows = tableLines.drop(2).map { parseTableRow(it) }
                val colCount = headerCells.size.coerceAtLeast(1)

                val table = PdfPTable(colCount).apply {
                    widthPercentage = 100f
                    setSpacingBefore(8f)
                    setSpacingAfter(8f)
                }
                headerCells.forEach { cell ->
                    table.addCell(
                        PdfPCell(buildInlinePhrase(cell, fontBold())).apply {
                            backgroundColor = Color(230, 235, 245)
                            setPadding(5f)
                            borderColor = Color(180, 180, 180)
                        },
                    )
                }
                dataRows.forEach { row ->
                    val cells = if (row.size < colCount) {
                        row + List(colCount - row.size) { "" }
                    } else {
                        row.take(colCount)
                    }
                    cells.forEach { cell ->
                        table.addCell(
                            PdfPCell(buildInlinePhrase(cell)).apply {
                                setPadding(4f)
                                borderColor = Color(200, 200, 200)
                            },
                        )
                    }
                }
                doc.add(table)
                i = j
                continue
            }

            when {
                line.startsWith("###### ") -> {
                    val p = Paragraph().apply {
                        font = fontH6()
                        spacingBefore = 4f
                        spacingAfter = 1f
                    }
                    buildInlinePhrase(line.removePrefix("###### "), fontH6()).forEach { p.add(it as Element) }
                    doc.add(p)
                }

                line.startsWith("##### ") -> {
                    val p = Paragraph().apply {
                        font = fontH5()
                        spacingBefore = 4f
                        spacingAfter = 1f
                    }
                    buildInlinePhrase(line.removePrefix("##### "), fontH5()).forEach { p.add(it as Element) }
                    doc.add(p)
                }

                line.startsWith("#### ") -> {
                    val p = Paragraph().apply {
                        font = fontH4()
                        spacingBefore = 6f
                        spacingAfter = 2f
                    }
                    buildInlinePhrase(line.removePrefix("#### "), fontH4()).forEach { p.add(it as Element) }
                    doc.add(p)
                }

                line.startsWith("# ") -> {
                    val p = Paragraph().apply {
                        font = fontH1()
                        spacingBefore = 8f
                        spacingAfter = 4f
                    }
                    buildInlinePhrase(line.removePrefix("# "), fontH1()).forEach { p.add(it as Element) }
                    doc.add(p)
                    doc.add(LineSeparator(1.5f, 100f, Color(70, 130, 200), Element.ALIGN_LEFT, -2f))
                }

                line.startsWith("## ") -> {
                    val p = Paragraph().apply {
                        font = fontH2()
                        spacingBefore = 12f
                        spacingAfter = 4f
                    }
                    buildInlinePhrase(line.removePrefix("## "), fontH2()).forEach { p.add(it as Element) }
                    doc.add(p)
                    doc.add(LineSeparator(0.5f, 100f, Color(200, 200, 200), Element.ALIGN_LEFT, -2f))
                }

                line.startsWith("### ") -> {
                    val p = Paragraph().apply {
                        font = fontH3()
                        spacingBefore = 8f
                        spacingAfter = 2f
                    }
                    buildInlinePhrase(line.removePrefix("### "), fontH3()).forEach { p.add(it as Element) }
                    doc.add(p)
                }

                line == "---" || line == "***" -> {
                    doc.add(
                        Paragraph(" ").apply {
                            spacingBefore = 4f
                            spacingAfter = 4f
                        },
                    )
                    doc.add(LineSeparator(0.5f, 100f, Color(180, 180, 180), Element.ALIGN_CENTER, 0f))
                }

                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    val text = line.trimStart().removePrefix("- ").removePrefix("* ")
                    val bulletPhrase = buildInlinePhrase(text)
                    bulletPhrase.add(0, Chunk("•  ", fontNormal()))
                    doc.add(
                        Paragraph(bulletPhrase).apply {
                            indentationLeft = 16f
                            firstLineIndent = -12f
                            spacingAfter = 2f
                        },
                    )
                }

                line.trimStart().startsWith("> ") -> {
                    val text = line.trimStart().removePrefix("> ")
                    doc.add(
                        Paragraph(buildInlinePhrase(text)).apply {
                            indentationLeft = 20f
                            spacingBefore = 2f
                            spacingAfter = 2f
                        },
                    )
                }

                line.isBlank() -> doc.add(Paragraph(" ").apply { spacingAfter = 4f })

                else -> doc.add(Paragraph(buildInlinePhrase(line)).apply { spacingAfter = 2f })
            }
            i++
        }
    }

    /** Renders [content] as a monospaced code block paragraph. */
    private fun addCodeBlock(doc: Document, content: String) {
        val codePara = Paragraph().apply {
            alignment = Element.ALIGN_LEFT
            spacingBefore = 4f
            spacingAfter = 4f
        }
        content.lines().forEach { codeLine ->
            codePara.add(Chunk("$codeLine\n", fontCode()))
        }
        doc.add(codePara)
    }

    private fun isTableRow(line: String) = line.trim().let { it.startsWith("|") && it.length > 1 }

    private fun isSeparatorRow(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("|") && t.matches(Regex("[|:\\-\\s]+")) && t.contains('-')
    }

    private fun parseTableRow(line: String) = line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    /**
     * Parses inline markdown and returns a [Phrase] with typed, Unicode-aware font runs.
     * Each text segment is split into base-font / emoji chunks via [chunksWithFallback].
     */
    private fun buildInlinePhrase(text: String, baseFont: Font = fontNormal()): Phrase {
        val phrase = Phrase()
        val regex = Regex(
            """\*\*(.+?)\*\*|__(.+?)__|""" +
                """\*(?!\*)(.+?)(?<!\*)\*|_(?!_)(.+?)(?<!_)_|""" +
                """`(.+?)`|\\.{1}""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        var last = 0
        for (match in regex.findAll(text)) {
            if (match.range.first > last) {
                chunksWithFallback(text.substring(last, match.range.first), baseFont.baseFont, baseFont)
                    .forEach { phrase.add(it) }
            }
            val raw = match.value
            val boldFont = fontBold(baseFont.size, baseFont.color ?: Color.BLACK)
            val italicFont = fontItalic(baseFont.size, baseFont.color ?: Color.BLACK)
            when {
                match.groupValues[1].isNotEmpty() ->
                    chunksWithFallback(match.groupValues[1], baseFontBold, boldFont).forEach { phrase.add(it) }

                match.groupValues[2].isNotEmpty() ->
                    chunksWithFallback(match.groupValues[2], baseFontBold, boldFont).forEach { phrase.add(it) }

                match.groupValues[3].isNotEmpty() ->
                    chunksWithFallback(match.groupValues[3], baseFontItalic, italicFont).forEach { phrase.add(it) }

                match.groupValues[4].isNotEmpty() ->
                    chunksWithFallback(match.groupValues[4], baseFontItalic, italicFont).forEach { phrase.add(it) }

                match.groupValues[5].isNotEmpty() ->
                    phrase.add(Chunk(match.groupValues[5], fontCode()))

                raw.startsWith("\\") && raw.length == 2 ->
                    chunksWithFallback(raw.substring(1), baseFont.baseFont, baseFont).forEach { phrase.add(it) }
            }
            last = match.range.last + 1
        }
        if (last < text.length) {
            chunksWithFallback(text.substring(last), baseFont.baseFont, baseFont).forEach { phrase.add(it) }
        }
        return phrase
    }
}
