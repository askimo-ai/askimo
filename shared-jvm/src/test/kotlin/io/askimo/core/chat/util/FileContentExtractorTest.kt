/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.util

import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileContentExtractorTest {

    companion object {
        private lateinit var testBaseScope: AskimoHome.TestBaseScope

        // FileContentExtractor reads AppConfig on init, which needs AskimoHome
        @JvmStatic
        @BeforeAll
        fun setUpClass(@TempDir tempDir: Path) {
            testBaseScope = AskimoHome.withTestBase(tempDir)
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            if (::testBaseScope.isInitialized) testBaseScope.close()
        }
    }

    @Test
    fun `should support PDF files`(@TempDir tempDir: Path) {
        val pdfFile = tempDir.resolve("test.pdf").toFile()
        // Create a minimal PDF file
        pdfFile.writeText("%PDF-1.4\n%EOF")
        assertTrue(FileContentExtractor.isSupported(pdfFile))
    }

    @Test
    fun `should support DOCX files`(@TempDir tempDir: Path) {
        val docxFile = tempDir.resolve("test.docx").toFile()
        // Create a file (MIME type will be detected by Tika)
        docxFile.writeText("") // Empty file for test
        assertTrue(FileContentExtractor.isSupported(docxFile))
    }

    @Test
    fun `should support DOC files`(@TempDir tempDir: Path) {
        val docFile = tempDir.resolve("test.doc").toFile()
        // Create a file (MIME type will be detected by Tika)
        docFile.writeText("") // Empty file for test
        assertTrue(FileContentExtractor.isSupported(docFile))
    }

    @Test
    fun `should support text files`(@TempDir tempDir: Path) {
        val textFile = tempDir.resolve("test.txt").toFile()
        textFile.writeText("Sample text content")
        assertTrue(FileContentExtractor.isSupported(textFile))
    }

    @Test
    fun `should not support images`(@TempDir tempDir: Path) {
        val pngFile = tempDir.resolve("test.png").toFile()
        pngFile.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) // PNG header
        assertFalse(FileContentExtractor.isSupported(pngFile))

        val jpgFile = tempDir.resolve("test.jpg").toFile()
        jpgFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) // JPEG header
        assertFalse(FileContentExtractor.isSupported(jpgFile))
    }

    @Test
    fun `should detect RTF as supported`(@TempDir tempDir: Path) {
        val rtfFile = tempDir.resolve("test.rtf").toFile()
        rtfFile.writeText("{\\rtf1\\ansi\\deff0 Test RTF content}")

        assertTrue(FileContentExtractor.isSupported(rtfFile))
    }

    @Test
    fun `should extract text from plain text file`(@TempDir tempDir: Path) {
        val textFile = tempDir.resolve("test.txt").toFile()
        val testContent = "Hello, World!"
        textFile.writeText(testContent)

        val extracted = FileContentExtractor.extractContent(textFile)
        assertTrue(extracted.contains("Hello, World!"))
    }

    @Test
    fun `should support markdown files`(@TempDir tempDir: Path) {
        val mdFile = tempDir.resolve("test.md").toFile()
        mdFile.writeText("# Markdown Header\n\nSome content")
        assertTrue(FileContentExtractor.isSupported(mdFile))
    }

    @Test
    fun `should extract text from markdown file`(@TempDir tempDir: Path) {
        val mdFile = tempDir.resolve("README.md").toFile()
        val testContent = "# My Project\n\nThis is a test markdown file."
        mdFile.writeText(testContent)

        val extracted = FileContentExtractor.extractContent(mdFile)
        assertTrue(extracted.contains("My Project"))
        assertTrue(extracted.contains("test markdown file"))
    }

    @Test
    fun `should extract text from Dockerfile`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("Dockerfile").toFile()
        file.writeText("FROM eclipse-temurin:21\nCOPY app.jar /app.jar")

        assertTrue(FileContentExtractor.isSupported(file))
        assertTrue(FileContentExtractor.extractContent(file).contains("FROM eclipse-temurin:21"))
    }

    @Test
    fun `should extract text from gitignore`(@TempDir tempDir: Path) {
        val file = tempDir.resolve(".gitignore").toFile()
        file.writeText("build/\n*.log")

        assertTrue(FileContentExtractor.isSupported(file))
        assertTrue(FileContentExtractor.extractContent(file).contains("build/"))
    }

    @Test
    fun `should extract text from env variant files`(@TempDir tempDir: Path) {
        for (name in listOf(".env.local", ".ENV.production")) {
            val file = tempDir.resolve(name).toFile()
            file.writeText("API_URL=http://localhost:8080")

            assertTrue(FileContentExtractor.isSupported(file), name)
            assertTrue(FileContentExtractor.extractContent(file).contains("API_URL"), name)
        }
    }

    @Test
    fun `should extract text from unknown extensionless text file`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("CODEOWNERS").toFile()
        file.writeText("* @askimo-ai/maintainers")

        assertTrue(FileContentExtractor.isSupported(file))
        assertTrue(FileContentExtractor.extractContent(file).contains("@askimo-ai/maintainers"))
    }

    @Test
    fun `should extract empty gitignore and env files`(@TempDir tempDir: Path) {
        // Empty files give Tika nothing to sniff, so detection has to rely on the name
        for (name in listOf(".gitignore", ".env.local")) {
            val file = tempDir.resolve(name).toFile()
            file.writeText("")

            assertTrue(FileContentExtractor.isSupported(file), name)
            assertTrue(FileContentExtractor.isTextFile(file), name)
            assertEquals("", FileContentExtractor.extractContent(file), name)
        }
    }

    @Test
    fun `isTextFile by name recognises gitignore and env files`() {
        assertTrue(FileContentExtractor.isTextFile(".gitignore"))
        assertTrue(FileContentExtractor.isTextFile(".env.production"))
        assertTrue(FileContentExtractor.isTextFile("Dockerfile"))
        assertFalse(FileContentExtractor.isTextFile("archive.bin"))
    }
}
