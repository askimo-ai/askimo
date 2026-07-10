/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.i18n

import java.text.MessageFormat
import java.text.NumberFormat
import java.util.Locale
import java.util.Properties

/**
 * Manages localization for the application.
 * Uses Kotlin's resource loading with UTF-8 support instead of Java's ResourceBundle.
 *
 * This is a shared component that can be used by both desktop and CLI modules.
 */
object LocalizationManager {
    private var currentLocale: Locale = Locale.ENGLISH
    private val loadedBundles = mutableMapOf<String, Properties>()

    /**
     * Available locales with their display names.
     * Dynamically discovers locales based on existing properties files.
     * Supports both language_country (e.g., ja_JP, zh_CN, zh_TW) and language-only formats.
     */
    val availableLocales: Map<Locale, String> by lazy {
        val localesMap = mutableMapOf<Locale, String>()

        // Track which locale combinations we've already processed
        val seenLocales = mutableSetOf<String>()

        // Scan for all available locale properties files
        val allAvailableLocales = Locale.getAvailableLocales()

        // Always include base English first (no country variant)
        localesMap[Locale.ENGLISH] = "English"
        seenLocales.add("en")

        allAvailableLocales.forEach { locale ->
            val language = locale.language
            val country = locale.country

            // Skip English - already added above
            if (language.isNotEmpty() && language != "en") {
                // Try language_country format first (e.g., ja_JP, zh_CN, zh_TW)
                val localeKey = if (country.isNotEmpty()) {
                    "${language}_$country"
                } else {
                    language
                }

                // Skip if we've already processed this exact locale
                if (!seenLocales.contains(localeKey)) {
                    val resourcePath = "i18n/messages_$localeKey.properties"

                    try {
                        this::class.java.classLoader.getResourceAsStream(resourcePath)?.use {
                            // File exists! Use this locale
                            val displayName = buildDisplayName(locale)
                            localesMap[locale] = displayName
                            seenLocales.add(localeKey)
                        }
                    } catch (_: Exception) {
                        // File doesn't exist, try next locale
                    }
                }
            }
        }

        // Sort: English first, then alphabetically by English language name
        val sortedEntries = localesMap.entries.sortedWith(
            compareBy(
                { it.key.language != "en" }, // English first (false < true)
                { it.key.getDisplayLanguage(Locale.ENGLISH) }, // Then by English language name (Japanese, Vietnamese)
                { it.key.getDisplayCountry(Locale.ENGLISH) }, // Then by English country name if same language
            ),
        )

        linkedMapOf<Locale, String>().apply {
            sortedEntries.forEach { (locale, displayName) ->
                put(locale, displayName)
            }
        }
    }

    /**
     * Build a display name for a locale showing native name, country, and English translation.
     * Examples:
     * - 日本語 - 日本 (Japanese - Japan)
     * - 中文 - 中国 (Chinese - China)
     * - 中文 - 台灣 (Chinese - Taiwan)
     */
    private fun buildDisplayName(locale: Locale): String {
        val nativeName = locale.getDisplayLanguage(locale).replaceFirstChar { it.uppercase() }
        val englishName = locale.getDisplayLanguage(Locale.ENGLISH).replaceFirstChar { it.uppercase() }

        // Include country/region if present
        val nativeCountry = if (locale.country.isNotEmpty()) {
            locale.getDisplayCountry(locale)
        } else {
            null
        }

        val englishCountry = if (locale.country.isNotEmpty()) {
            locale.getDisplayCountry(Locale.ENGLISH)
        } else {
            null
        }

        return when {
            nativeCountry != null && englishCountry != null && nativeName != englishName -> {
                // Full format with country: 日本語 - 日本 (Japanese - Japan)
                "$nativeName - $nativeCountry ($englishName - $englishCountry)"
            }

            nativeCountry != null && nativeName == englishName -> {
                // Same language name, show country: English - United States
                "$nativeName - $englishCountry"
            }

            nativeName != englishName -> {
                // No country, different names: 日本語 (Japanese)
                "$nativeName ($englishName)"
            }

            else -> {
                // No country, same name: English
                nativeName
            }
        }
    }

    /**
     * Get the current locale.
     */
    fun getCurrentLocale(): Locale = currentLocale

    /**
     * Set the application locale and clear cached bundles.
     */
    fun setLocale(locale: Locale) {
        currentLocale = locale
        Locale.setDefault(locale)
        loadedBundles.clear()
    }

    /**
     * Get a localized string by key.
     * @param key The resource key
     * @param args Optional format arguments
     * @return The localized string, or the key if not found
     */
    fun getString(key: String, vararg args: Any): String {
        val properties = getProperties()
        val message = properties.getProperty(key) ?: return key

        return if (args.isEmpty()) {
            message
        } else {
            try {
                // Convert all arguments to strings to avoid MessageFormat's number formatting
                // (which adds thousand separators like "2,025" instead of "2025")
                val stringArgs = args.map { it.toString() }.toTypedArray()
                MessageFormat(message, currentLocale).format(stringArgs)
            } catch (_: Exception) {
                message
            }
        }
    }

    /**
     * Singleton message resolver for provider configuration and other i18n needs.
     * This can be passed to provider settings methods to localize their configuration fields.
     *
     * Usage:
     * ```kotlin
     * val configFields = providerSettings.getConfigFields(LocalizationManager.messageResolver)
     * ```
     */
    val messageResolver: (String) -> String = { key -> getString(key) }

    /**
     * Formats a number using locale-aware grouping separators.
     * e.g. 10000 → "10,000" (en-US), "10.000" (de), "10 000" (fr)
     */
    fun formatNumber(value: Long): String = NumberFormat.getNumberInstance(currentLocale).format(value)

    fun formatNumber(value: Int): String = NumberFormat.getNumberInstance(currentLocale).format(value)

    /**
     * Formats a decimal number using locale-aware grouping and fraction separators.
     * e.g. 3.14159 with fractionDigits=2 → "3.14" (en-US), "3,14" (de)
     */
    fun formatDouble(value: Double, fractionDigits: Int = 2): String = NumberFormat.getNumberInstance(currentLocale).apply {
        minimumFractionDigits = fractionDigits
        maximumFractionDigits = fractionDigits
    }.format(value)

    /**
     * Load properties file for the current locale with UTF-8 encoding.
     * Supports both language_country (ja_JP) and language-only (ja) formats.
     *
     * Loading order (later layers override earlier ones):
     *   1. Base English default  → i18n/messages.properties             (from desktop-shared JAR)
     *   2. Locale translation    → i18n/messages_{locale}.properties     (from desktop-shared JAR)
     *   3. App override (EN)     → i18n/messages_override.properties     (from the app's own resources)
     *   4. App override (locale) → i18n/messages_override_{locale}.properties (from the app's own resources)
     *
     * Only keys present in the override files need to be listed — everything else falls back to the base.
     */
    private fun getProperties(): Properties {
        val language = currentLocale.language
        val country = currentLocale.country

        // Use language_country format if country is present, otherwise just language
        val localeKey = if (country.isNotEmpty()) "${language}_$country" else language

        return loadedBundles.getOrPut(localeKey) {
            val properties = Properties()

            // Layer 1: base English strings (always loaded first as fallback)
            loadInto(properties, "i18n/messages.properties")

            // Layer 2: locale-specific translation (overlaid on top of English)
            if (localeKey.isNotEmpty() && localeKey != "en") {
                loadInto(properties, "i18n/messages_$localeKey.properties")
            }

            // Layer 3: app-specific English overrides (premium / white-label differences)
            loadInto(properties, "i18n/messages_override.properties")

            // Layer 4: app-specific locale overrides
            if (localeKey.isNotEmpty() && localeKey != "en") {
                loadInto(properties, "i18n/messages_override_$localeKey.properties")
            }

            properties
        }
    }

    /**
     * Load a properties file from the classpath into an existing Properties object.
     * Missing files are silently ignored — this is intentional so that apps that don't
     * ship override files don't need any special configuration.
     */
    private fun loadInto(properties: Properties, path: String) {
        try {
            this::class.java.classLoader.getResourceAsStream(path)?.use { stream ->
                stream.reader(Charsets.UTF_8).use { reader ->
                    properties.load(reader)
                }
            }
        } catch (_: Exception) {
            // Silently ignore missing files — not all layers are required
        }
    }
}
