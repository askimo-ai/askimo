/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.util

/**
 * Centralized file type support definitions for both text extraction and vision capabilities.
 * This object provides a single source of truth for all supported file types across the application.
 */
object FileTypeSupport {
    /**
     * Plain text file extensions (for content extraction).
     */
    val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "json", "xml", "yaml", "yml",
        "csv", "log", "ini", "conf", "properties", "env",
        "sh", "bash", "bat", "cmd", "ps1",
    )

    /**
     * Programming language file extensions (for content extraction).
     */
    val CODE_EXTENSIONS = setOf(
        "kt", "kts", "java", "py", "js", "ts", "jsx", "tsx",
        "c", "cpp", "h", "hpp", "cs", "go", "rs", "rb",
        "php", "swift", "scala", "r", "sql", "html", "css",
        "scss", "sass", "less", "vue", "svelte", "gradle",
        "groovy", "lua", "pl", "pm",
        // Config & build files
        "toml", "lock",
        // Terraform
        "tf", "tfvars",
        // Data formats
        "jsonc", "json5", "graphql", "gql", "proto",
        // WebAssembly
        "wat",
        // Emerging languages
        "zig", "v",
        // Go modules
        "mod", "sum",
        // Nix & Dhall
        "nix", "dhall",
    )

    /**
     * Document file extensions (for content extraction).
     */
    val DOCUMENT_EXTENSIONS = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "odt", "ods", "odp", "rtf",
    )

    /**
     * Image file extensions (for vision capabilities).
     * These files are sent to vision-capable AI models for analysis.
     */
    val IMAGE_EXTENSIONS = setOf(
        "jpg",
        "jpeg",
        "png",
        "gif",
        "webp",
        "bmp",
    )

    /**
     * Extensionless configuration filenames stored in lowercase.
     * These files have no extension but are recognised by their full filename.
     */
    val CONFIG_EXTENSIONS = setOf(
        "dockerfile",
        "makefile",
        "jenkinsfile",
        "procfile",
        "vagrantfile",
        "rakefile",
        "gemfile",
        "podfile",
        "brewfile",
        "guardfile",
    )

    /**
     * All text-extractable file extensions.
     * These files have their content extracted and included in the prompt.
     */
    val TEXT_EXTRACTABLE_EXTENSIONS = TEXT_EXTENSIONS + CODE_EXTENSIONS + DOCUMENT_EXTENSIONS

    /**
     * All supported file extensions (text + images).
     * These files can be attached to messages.
     */
    val ALL_SUPPORTED_EXTENSIONS = TEXT_EXTRACTABLE_EXTENSIONS + IMAGE_EXTENSIONS

    /**
     * Check if a file extension represents an image type.
     *
     * @param extension The file extension (with or without leading dot)
     * @return true if the extension is an image type
     */
    fun isImageExtension(extension: String): Boolean = extension.removePrefix(".").lowercase() in IMAGE_EXTENSIONS

    /**
     * Check if a file extension can have its text content extracted.
     *
     * @param extension The file extension (with or without leading dot)
     * @return true if the extension is text-extractable
     */
    fun isTextExtractable(extension: String): Boolean = extension.removePrefix(".").lowercase() in TEXT_EXTRACTABLE_EXTENSIONS

    /**
     * Check if a file extension is supported (either text or image).
     *
     * @param extension The file extension (with or without leading dot)
     * @return true if the extension is supported
     */
    fun isSupported(extension: String): Boolean = extension.removePrefix(".").lowercase() in ALL_SUPPORTED_EXTENSIONS

    /**
     * Get the file extension from a filename.
     *
     * @param fileName The file name
     * @return The extension without the dot, or empty string if no extension
     */
    fun getExtension(fileName: String): String = fileName.substringAfterLast('.', "")

    /**
     * Check if a filename is a known extensionless configuration file.
     * Comparison is case-insensitive.
     *
     * @param fileName The file name (without path)
     * @return true if the filename is in CONFIG_EXTENSIONS
     */
    fun isConfigFile(fileName: String): Boolean = fileName.lowercase() in CONFIG_EXTENSIONS

    /**
     * Returns the list of all supported file extensions (text + images).
     * Convenience accessor for file picker extension filters.
     */
    fun supportedExtensions(): List<String> = ALL_SUPPORTED_EXTENSIONS.toList()
}
