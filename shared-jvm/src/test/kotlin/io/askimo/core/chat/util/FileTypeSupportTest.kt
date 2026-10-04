/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.util

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileTypeSupportTest {

    // ============================================================================
    // isImageExtension
    // ============================================================================

    @Test
    fun `isImageExtension returns true for jpg`() {
        assertTrue(FileTypeSupport.isImageExtension("jpg"))
    }

    @Test
    fun `isImageExtension returns true for jpeg`() {
        assertTrue(FileTypeSupport.isImageExtension("jpeg"))
    }

    @Test
    fun `isImageExtension returns true for png`() {
        assertTrue(FileTypeSupport.isImageExtension("png"))
    }

    @Test
    fun `isImageExtension returns true for gif`() {
        assertTrue(FileTypeSupport.isImageExtension("gif"))
    }

    @Test
    fun `isImageExtension returns true for webp`() {
        assertTrue(FileTypeSupport.isImageExtension("webp"))
    }

    @Test
    fun `isImageExtension returns true for bmp`() {
        assertTrue(FileTypeSupport.isImageExtension("bmp"))
    }

    @Test
    fun `isImageExtension accepts extension with leading dot`() {
        assertTrue(FileTypeSupport.isImageExtension(".png"))
    }

    @Test
    fun `isImageExtension is case insensitive`() {
        assertTrue(FileTypeSupport.isImageExtension("JPG"))
        assertTrue(FileTypeSupport.isImageExtension("PNG"))
        assertTrue(FileTypeSupport.isImageExtension(".WEBP"))
    }

    @Test
    fun `isImageExtension returns false for text extension`() {
        assertFalse(FileTypeSupport.isImageExtension("txt"))
    }

    @Test
    fun `isImageExtension returns false for code extension`() {
        assertFalse(FileTypeSupport.isImageExtension("kt"))
    }

    @Test
    fun `isImageExtension returns false for document extension`() {
        assertFalse(FileTypeSupport.isImageExtension("pdf"))
    }

    @Test
    fun `isImageExtension returns false for video extension`() {
        assertFalse(FileTypeSupport.isImageExtension("mp4"))
    }

    @Test
    fun `isImageExtension returns false for empty string`() {
        assertFalse(FileTypeSupport.isImageExtension(""))
    }

    // ============================================================================
    // isTextExtractable – TEXT_EXTENSIONS
    // ============================================================================

    @Test
    fun `isTextExtractable returns true for txt`() {
        assertTrue(FileTypeSupport.isTextExtractable("txt"))
    }

    @Test
    fun `isTextExtractable returns true for md`() {
        assertTrue(FileTypeSupport.isTextExtractable("md"))
    }

    @Test
    fun `isTextExtractable returns true for json`() {
        assertTrue(FileTypeSupport.isTextExtractable("json"))
    }

    @Test
    fun `isTextExtractable returns true for yaml`() {
        assertTrue(FileTypeSupport.isTextExtractable("yaml"))
    }

    @Test
    fun `isTextExtractable returns true for yml`() {
        assertTrue(FileTypeSupport.isTextExtractable("yml"))
    }

    @Test
    fun `isTextExtractable returns true for csv`() {
        assertTrue(FileTypeSupport.isTextExtractable("csv"))
    }

    @Test
    fun `isTextExtractable returns true for sh`() {
        assertTrue(FileTypeSupport.isTextExtractable("sh"))
    }

    @Test
    fun `isTextExtractable returns true for env`() {
        assertTrue(FileTypeSupport.isTextExtractable("env"))
    }

    // ============================================================================
    // isTextExtractable – CODE_EXTENSIONS
    // ============================================================================

    @Test
    fun `isTextExtractable returns true for kt`() {
        assertTrue(FileTypeSupport.isTextExtractable("kt"))
    }

    @Test
    fun `isTextExtractable returns true for java`() {
        assertTrue(FileTypeSupport.isTextExtractable("java"))
    }

    @Test
    fun `isTextExtractable returns true for py`() {
        assertTrue(FileTypeSupport.isTextExtractable("py"))
    }

    @Test
    fun `isTextExtractable returns true for js`() {
        assertTrue(FileTypeSupport.isTextExtractable("js"))
    }

    @Test
    fun `isTextExtractable returns true for ts`() {
        assertTrue(FileTypeSupport.isTextExtractable("ts"))
    }

    @Test
    fun `isTextExtractable returns true for go`() {
        assertTrue(FileTypeSupport.isTextExtractable("go"))
    }

    @Test
    fun `isTextExtractable returns true for rs`() {
        assertTrue(FileTypeSupport.isTextExtractable("rs"))
    }

    @Test
    fun `isTextExtractable returns true for sql`() {
        assertTrue(FileTypeSupport.isTextExtractable("sql"))
    }

    @Test
    fun `isTextExtractable returns true for html`() {
        assertTrue(FileTypeSupport.isTextExtractable("html"))
    }

    @Test
    fun `isTextExtractable returns true for css`() {
        assertTrue(FileTypeSupport.isTextExtractable("css"))
    }

    @Test
    fun `test new config file extensions`() {
        assertTrue(FileTypeSupport.isTextExtractable("toml"))
        assertTrue(FileTypeSupport.isTextExtractable("lock"))
    }

    @Test
    fun `test Terraform extensions`() {
        assertTrue(FileTypeSupport.isTextExtractable("tf"))
        assertTrue(FileTypeSupport.isTextExtractable("tfvars"))
    }

    @Test
    fun `test data format extensions`() {
        assertTrue(FileTypeSupport.isTextExtractable("jsonc"))
        assertTrue(FileTypeSupport.isTextExtractable("json5"))
        assertTrue(FileTypeSupport.isTextExtractable("graphql"))
        assertTrue(FileTypeSupport.isTextExtractable("gql"))
        assertTrue(FileTypeSupport.isTextExtractable("proto"))
    }

    @Test
    fun `test WebAssembly extension`() {
        assertTrue(FileTypeSupport.isTextExtractable("wat"))
    }

    @Test
    fun `test emerging language extensions`() {
        assertTrue(FileTypeSupport.isTextExtractable("zig"))
        assertTrue(FileTypeSupport.isTextExtractable("v"))
    }

    @Test
    fun `test Go module extensions`() {
        assertTrue(FileTypeSupport.isTextExtractable("mod"))
        assertTrue(FileTypeSupport.isTextExtractable("sum"))
    }

    @Test
    fun `test Nix and Dhall extensions`() {
        assertTrue(FileTypeSupport.isTextExtractable("nix"))
        assertTrue(FileTypeSupport.isTextExtractable("dhall"))
    }

    // ============================================================================
    // isTextExtractable – DOCUMENT_EXTENSIONS
    // ============================================================================

    @Test
    fun `isTextExtractable returns true for pdf`() {
        assertTrue(FileTypeSupport.isTextExtractable("pdf"))
    }

    @Test
    fun `isTextExtractable returns true for docx`() {
        assertTrue(FileTypeSupport.isTextExtractable("docx"))
    }

    @Test
    fun `isTextExtractable returns true for doc`() {
        assertTrue(FileTypeSupport.isTextExtractable("doc"))
    }

    @Test
    fun `isTextExtractable returns true for xlsx`() {
        assertTrue(FileTypeSupport.isTextExtractable("xlsx"))
    }

    @Test
    fun `isTextExtractable returns true for pptx`() {
        assertTrue(FileTypeSupport.isTextExtractable("pptx"))
    }

    @Test
    fun `isTextExtractable returns true for rtf`() {
        assertTrue(FileTypeSupport.isTextExtractable("rtf"))
    }

    @Test
    fun `isTextExtractable accepts extension with leading dot`() {
        assertTrue(FileTypeSupport.isTextExtractable(".pdf"))
        assertTrue(FileTypeSupport.isTextExtractable(".kt"))
        assertTrue(FileTypeSupport.isTextExtractable(".txt"))
    }

    @Test
    fun `isTextExtractable is case insensitive`() {
        assertTrue(FileTypeSupport.isTextExtractable("PDF"))
        assertTrue(FileTypeSupport.isTextExtractable("KT"))
        assertTrue(FileTypeSupport.isTextExtractable("TXT"))
    }

    @Test
    fun `isTextExtractable returns false for image extension`() {
        assertFalse(FileTypeSupport.isTextExtractable("jpg"))
        assertFalse(FileTypeSupport.isTextExtractable("png"))
    }

    @Test
    fun `isTextExtractable returns false for unknown extension`() {
        assertFalse(FileTypeSupport.isTextExtractable("xyz"))
        assertFalse(FileTypeSupport.isTextExtractable("mp4"))
    }

    @Test
    fun `isTextExtractable returns false for empty string`() {
        assertFalse(FileTypeSupport.isTextExtractable(""))
    }

    // ============================================================================
    // getExtension
    // ============================================================================

    @Test
    fun `getExtension returns extension for simple filename`() {
        assertEquals("txt", FileTypeSupport.getExtension("readme.txt"))
    }

    @Test
    fun `getExtension returns extension for kotlin file`() {
        assertEquals("kt", FileTypeSupport.getExtension("Main.kt"))
    }

    @Test
    fun `getExtension returns empty string for extensionless file`() {
        assertEquals("", FileTypeSupport.getExtension("Dockerfile"))
        assertEquals("", FileTypeSupport.getExtension("Makefile"))
    }

    @Test
    fun `getExtension returns last segment for double extension`() {
        assertEquals("gz", FileTypeSupport.getExtension("archive.tar.gz"))
    }

    @Test
    fun `getExtension returns last segment for triple extension`() {
        assertEquals("gz", FileTypeSupport.getExtension("data.tar.bz2.gz"))
    }

    @Test
    fun `getExtension returns extension part for dotfile`() {
        assertEquals("gitignore", FileTypeSupport.getExtension(".gitignore"))
        assertEquals("dockerignore", FileTypeSupport.getExtension(".dockerignore"))
        assertEquals("editorconfig", FileTypeSupport.getExtension(".editorconfig"))
    }

    @Test
    fun `getExtension returns empty string for empty input`() {
        assertEquals("", FileTypeSupport.getExtension(""))
    }

    @Test
    fun `getExtension returns empty string for filename without dot`() {
        assertEquals("", FileTypeSupport.getExtension("noextension"))
    }

    @Test
    fun `getExtension returns extension when filename has path-like dots`() {
        assertEquals("kt", FileTypeSupport.getExtension("com.example.Main.kt"))
    }

    // ============================================================================
    // isSupported – ALL_SUPPORTED_EXTENSIONS
    // ============================================================================

    @Test
    fun `isSupported returns true for image extension`() {
        assertTrue(FileTypeSupport.isSupported("jpg"))
        assertTrue(FileTypeSupport.isSupported("png"))
        assertTrue(FileTypeSupport.isSupported("webp"))
    }

    @Test
    fun `isSupported returns true for text extension`() {
        assertTrue(FileTypeSupport.isSupported("txt"))
        assertTrue(FileTypeSupport.isSupported("md"))
        assertTrue(FileTypeSupport.isSupported("json"))
    }

    @Test
    fun `isSupported returns true for code extension`() {
        assertTrue(FileTypeSupport.isSupported("kt"))
        assertTrue(FileTypeSupport.isSupported("py"))
        assertTrue(FileTypeSupport.isSupported("ts"))
    }

    @Test
    fun `isSupported returns true for document extension`() {
        assertTrue(FileTypeSupport.isSupported("pdf"))
        assertTrue(FileTypeSupport.isSupported("docx"))
        assertTrue(FileTypeSupport.isSupported("xlsx"))
    }

    @Test
    fun `isSupported accepts extension with leading dot`() {
        assertTrue(FileTypeSupport.isSupported(".jpg"))
        assertTrue(FileTypeSupport.isSupported(".kt"))
        assertTrue(FileTypeSupport.isSupported(".pdf"))
    }

    @Test
    fun `isSupported is case insensitive`() {
        assertTrue(FileTypeSupport.isSupported("PDF"))
        assertTrue(FileTypeSupport.isSupported("JPG"))
        assertTrue(FileTypeSupport.isSupported("KT"))
    }

    @Test
    fun `isSupported returns false for unknown extension`() {
        assertFalse(FileTypeSupport.isSupported("exe"))
        assertFalse(FileTypeSupport.isSupported("dll"))
        assertFalse(FileTypeSupport.isSupported("mp4"))
        assertFalse(FileTypeSupport.isSupported("zip"))
    }

    @Test
    fun `isSupported returns false for empty string`() {
        assertFalse(FileTypeSupport.isSupported(""))
    }

    @Test
    fun `ALL_SUPPORTED_EXTENSIONS contains every TEXT_EXTENSIONS entry`() {
        assertTrue(FileTypeSupport.ALL_SUPPORTED_EXTENSIONS.containsAll(FileTypeSupport.TEXT_EXTENSIONS))
    }

    @Test
    fun `ALL_SUPPORTED_EXTENSIONS contains every CODE_EXTENSIONS entry`() {
        assertTrue(FileTypeSupport.ALL_SUPPORTED_EXTENSIONS.containsAll(FileTypeSupport.CODE_EXTENSIONS))
    }

    @Test
    fun `ALL_SUPPORTED_EXTENSIONS contains every DOCUMENT_EXTENSIONS entry`() {
        assertTrue(FileTypeSupport.ALL_SUPPORTED_EXTENSIONS.containsAll(FileTypeSupport.DOCUMENT_EXTENSIONS))
    }

    @Test
    fun `ALL_SUPPORTED_EXTENSIONS contains every IMAGE_EXTENSIONS entry`() {
        assertTrue(FileTypeSupport.ALL_SUPPORTED_EXTENSIONS.containsAll(FileTypeSupport.IMAGE_EXTENSIONS))
    }

    // ============================================================================
    // isConfigFile – CONFIG_EXTENSIONS
    // ============================================================================

    @Test
    fun `isConfigFile returns true for Dockerfile`() {
        assertTrue(FileTypeSupport.isConfigFile("Dockerfile"))
    }

    @Test
    fun `isConfigFile returns true for Makefile`() {
        assertTrue(FileTypeSupport.isConfigFile("Makefile"))
    }

    @Test
    fun `isConfigFile returns true for Jenkinsfile`() {
        assertTrue(FileTypeSupport.isConfigFile("Jenkinsfile"))
    }

    @Test
    fun `isConfigFile returns true for Procfile`() {
        assertTrue(FileTypeSupport.isConfigFile("Procfile"))
    }

    @Test
    fun `isConfigFile returns true for Vagrantfile`() {
        assertTrue(FileTypeSupport.isConfigFile("Vagrantfile"))
    }

    @Test
    fun `isConfigFile returns true for Gemfile`() {
        assertTrue(FileTypeSupport.isConfigFile("Gemfile"))
    }

    @Test
    fun `isConfigFile is case insensitive`() {
        assertTrue(FileTypeSupport.isConfigFile("dockerfile"))
        assertTrue(FileTypeSupport.isConfigFile("MAKEFILE"))
        assertTrue(FileTypeSupport.isConfigFile("Gemfile"))
    }

    @Test
    fun `isConfigFile returns false for file with extension`() {
        assertFalse(FileTypeSupport.isConfigFile("docker-compose.yml"))
        assertFalse(FileTypeSupport.isConfigFile("Makefile.am"))
    }

    @Test
    fun `isConfigFile returns false for unknown extensionless file`() {
        assertFalse(FileTypeSupport.isConfigFile("RandomFile"))
        assertFalse(FileTypeSupport.isConfigFile("NOTICE"))
    }

    @Test
    fun `isConfigFile returns false for empty string`() {
        assertFalse(FileTypeSupport.isConfigFile(""))
    }

    @Test
    fun `test config files without extensions`() {
        assertTrue("dockerfile" in FileTypeSupport.CONFIG_EXTENSIONS)
        assertTrue("makefile" in FileTypeSupport.CONFIG_EXTENSIONS)
        assertTrue("gemfile" in FileTypeSupport.CONFIG_EXTENSIONS)
        assertFalse("randomfile" in FileTypeSupport.CONFIG_EXTENSIONS)
    }

    @Test
    fun `getExtension returns empty string for all CONFIG_EXTENSIONS entries`() {
        FileTypeSupport.CONFIG_EXTENSIONS.forEach { name ->
            assertEquals("", FileTypeSupport.getExtension(name), "Expected no extension for config file: $name")
        }
    }
}
