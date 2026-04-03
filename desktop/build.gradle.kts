import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.Year
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// Load environment variables from .env file if it exists
val envFile = rootProject.file(".env")
val envVars = mutableMapOf<String, String>()

if (envFile.exists()) {
    envFile.readLines().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && "=" in trimmed) {
            val key = trimmed.substringBefore("=").trim().removePrefix("export ")
            val value = trimmed.substringAfter("=").trim().removeSurrounding("\"")
            envVars[key] = value
            System.setProperty(key, value)
        }
    }
    println("✅ Loaded ${envVars.size} variables from .env file")
}

// Helper function to get value from either environment variable or .env file
fun getEnvOrProperty(key: String): String? = System.getenv(key) ?: envVars[key] ?: System.getProperty(key)

abstract class ExecHelper
    @Inject
    constructor(
        val execOps: org.gradle.process.ExecOperations,
    )
val execOps = objects.newInstance<ExecHelper>().execOps

group = rootProject.group
version = rootProject.version

// jpackage requires a clean MAJOR[.MINOR][.PATCH] version — strip any pre-release suffix
// (e.g. "1.2.25-SNAPSHOT" → "1.2.25"). Used only for native distribution packaging.
val distPackageVersion = project.version.toString().substringBefore("-")

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(project(":shared"))
    implementation(project(":desktop-shared"))

    implementation(libs.konform)

    testImplementation(kotlin("test"))
    testImplementation(libs.bundles.koin.test)
    testImplementation(testFixtures(project(":shared")))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

// Generate about.properties with version info
val author = property("author") as String
val licenseId = property("licenseId") as String
val homepage = property("homepage") as String

val aboutDir = layout.buildDirectory.dir("generated-resources/about")
val aboutFile = aboutDir.map { it.file("about.properties") }

val generateAbout =
    tasks.register("generateAbout") {
        outputs.file(aboutFile)

        doLast {
            val buildDate =
                DateTimeFormatter.ISO_LOCAL_DATE
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.now())

            val text =
                """
                name=Askimo Desktop
                version=${project.version}
                author=$author
                buildDate=$buildDate
                license=$licenseId
                homepage=$homepage
                buildJdk=${System.getProperty("java.version") ?: "unknown"}
                """.trimIndent()

            val f = aboutFile.get().asFile
            f.parentFile.mkdirs()
            f.writeText(text)
        }
    }

tasks.named<ProcessResources>("processResources") {
    dependsOn(generateAbout)
    from(aboutDir)
    filteringCharset = "UTF-8"
}

compose.desktop {
    application {
        mainClass = "io.askimo.desktop.MainKt"

        // Enable Vector API for better JVector performance
        jvmArgs("--add-modules", "jdk.incubator.vector")

        nativeDistributions {
            targetFormats(
                TargetFormat.Dmg,
                TargetFormat.Msi,
                TargetFormat.Deb,
            )
            packageName = "Askimo"
            packageVersion = distPackageVersion
            description = "AI assistant with multi-LLM support and local document intelligence"
            copyright = "© ${Year.now()} $author. All rights reserved."
            vendor = "Askimo"

            // Automatically include all Java modules to support dependencies
            // This ensures modules like java.sql, java.naming, etc. are available
            includeAllModules = true

            macOS {
                bundleID = "io.askimo.askimo-personal"
                iconFile.set(project.file("src/main/resources/images/askimo.icns"))

                // Disable automatic signing - we do custom signing with entitlements in signMacApp task
                signing {
                    sign.set(false)
                }
            }
            windows {
                iconFile.set(project.file("src/main/resources/images/askimo.ico"))
                menuGroup = "Askimo"
                perUserInstall = true
            }
            linux {
                iconFile.set(project.file("src/main/resources/images/askimo_512.png"))

                // Package metadata
                packageName = "askimo"
                debMaintainer = author
                menuGroup = "Utility;Office;"
                appCategory = "utils"

                // Add desktop entry details
                shortcut = true

                // Debian package dependencies
                debPackageVersion = distPackageVersion

                // Let jpackage auto-detect dependencies from the bundled libraries
                // Build on Ubuntu 22.04 to ensure compatibility with both 22.04 and 24.04
                args(
                    "--verbose",
                )
            }
        }
    }
}

// Fix for "Archive contains more than 65535 entries" error
// Enable ZIP64 format for Compose Desktop packaging (supports unlimited entries)
tasks.withType<Zip> {
    isZip64 = true
}

// Configure Compose Desktop uber JAR tasks to exclude signature files
// These tasks are created by the Compose Desktop plugin
// The plugin creates uber JARs that may contain conflicting signatures from dependencies
// We need to post-process the JAR to remove signature files
afterEvaluate {
    fun createStripSignaturesTask(
        sourceTaskName: String,
        targetTaskName: String,
    ) {
        tasks.register(targetTaskName) {
            group = "compose desktop"
            description = "Strip signature files from $sourceTaskName output"

            val sourceTask = tasks.findByName(sourceTaskName)
            if (sourceTask != null) {
                dependsOn(sourceTask)

                doLast {
                    // Find the output JAR from the source task
                    val jarFiles =
                        fileTree(layout.buildDirectory.dir("compose/jars")) {
                            include("*.jar")
                        }

                    jarFiles.forEach { jarFile ->
                        logger.lifecycle("Stripping signatures from: ${jarFile.name}")

                        // Check if JAR has signature files
                        val hasSignatures =
                            ByteArrayOutputStream().use { output ->
                                execOps.exec {
                                    commandLine("jar", "tf", jarFile.absolutePath)
                                    standardOutput = output
                                    isIgnoreExitValue = true
                                }
                                val contents = output.toString()
                                contents.contains(".SF") || contents.contains(".DSA") || contents.contains(".RSA")
                            }

                        if (!hasSignatures) {
                            logger.lifecycle("   No signature files found, skipping...")
                            return@forEach
                        }

                        // Create a temporary directory for extraction
                        val tempDir =
                            layout.buildDirectory
                                .dir("tmp/jar-strip/${jarFile.nameWithoutExtension}")
                                .get()
                                .asFile
                        tempDir.deleteRecursively()
                        tempDir.mkdirs()

                        try {
                            // Extract JAR contents using verbose mode to track all files
                            val extractOutput = ByteArrayOutputStream()
                            execOps.exec {
                                commandLine("jar", "xf", jarFile.absolutePath)
                                workingDir = tempDir
                                standardOutput = extractOutput
                            }

                            // Count extracted files for verification
                            val extractedFiles = tempDir.walkTopDown().filter { it.isFile }.count()
                            logger.lifecycle("   Extracted $extractedFiles files")

                            // Delete signature files from META-INF
                            val metaInfDir = File(tempDir, "META-INF")
                            var deletedCount = 0
                            if (metaInfDir.exists()) {
                                metaInfDir.listFiles()?.forEach { file ->
                                    if (file.extension in listOf("SF", "DSA", "RSA")) {
                                        file.delete()
                                        logger.lifecycle("   Deleted: META-INF/${file.name}")
                                        deletedCount++
                                    }
                                }
                            }

                            // Re-create JAR without signature files
                            // Important: List all files explicitly to avoid missing hidden files or special resources
                            val manifestFile = File(metaInfDir, "MANIFEST.MF")
                            val repackOutput = ByteArrayOutputStream()

                            // Build the file list to include in the JAR
                            val allFiles =
                                tempDir
                                    .walkTopDown()
                                    .filter { it.isFile }
                                    .map { it.relativeTo(tempDir).path }
                                    .toList()

                            // Create a temporary file list
                            val fileListFile = File(tempDir.parentFile, "${jarFile.nameWithoutExtension}-files.txt")
                            fileListFile.writeText(allFiles.joinToString("\n"))

                            try {
                                execOps.exec {
                                    if (manifestFile.exists()) {
                                        // Create JAR with manifest and all files from list
                                        commandLine(
                                            "jar",
                                            "cfm",
                                            jarFile.absolutePath,
                                            "META-INF/MANIFEST.MF",
                                            "@${fileListFile.absolutePath}",
                                        )
                                    } else {
                                        // Create JAR with all files from list
                                        commandLine("jar", "cf", jarFile.absolutePath, "@${fileListFile.absolutePath}")
                                    }
                                    workingDir = tempDir
                                    standardOutput = repackOutput
                                }
                            } finally {
                                fileListFile.delete()
                            }

                            // Verify the repacked JAR
                            val repackedFileCount =
                                ByteArrayOutputStream().use { output ->
                                    execOps.exec {
                                        commandLine("jar", "tf", jarFile.absolutePath)
                                        standardOutput = output
                                        isIgnoreExitValue = true
                                    }
                                    output
                                        .toString()
                                        .lines()
                                        .filter { it.isNotBlank() }
                                        .size
                                }

                            logger.lifecycle(
                                "   Repacked JAR contains $repackedFileCount entries (original: $extractedFiles, deleted: $deletedCount)",
                            )

                            // Verify no files were lost (allowing for deleted signature files)
                            val expectedCount = extractedFiles - deletedCount
                            if (repackedFileCount < expectedCount - 5) { // Allow small variance for directory entries
                                logger.warn(
                                    "   ⚠️ Warning: Repacked JAR may be missing files (expected ~$expectedCount, got $repackedFileCount)",
                                )
                            }

                            logger.lifecycle("✅ Successfully stripped signatures from: ${jarFile.name}")
                        } finally {
                            // Clean up temporary directory
                            tempDir.deleteRecursively()
                        }
                    }
                }
            }
        }
    }

    // Create signature stripping tasks for uber JARs
    createStripSignaturesTask("packageUberJarForCurrentOS", "stripSignaturesFromUberJar")
    createStripSignaturesTask("packageReleaseUberJarForCurrentOS", "stripSignaturesFromReleaseUberJar")

    // Make the uber JAR tasks finalize with signature stripping
    tasks.findByName("packageUberJarForCurrentOS")?.finalizedBy("stripSignaturesFromUberJar")
    tasks.findByName("packageReleaseUberJarForCurrentOS")?.finalizedBy("stripSignaturesFromReleaseUberJar")
}

tasks.test {
    useJUnitPlatform()

    // Enable Vector API for better JVector performance
    jvmArgs("--add-modules", "jdk.incubator.vector")
}

kotlin {
    jvmToolchain(21)
}

// Task to check for missing i18n keys across language files
tasks.register("checkI18nKeys") {
    group = "verification"
    description = "Check for missing i18n keys across language files"

    doLast {
        // i18n files now live in desktop-shared so both desktop and askimo-app share the same base
        val i18nDir = file("../desktop-shared/src/main/resources/i18n")
        val baseFile = i18nDir.resolve("messages.properties")

        if (!baseFile.exists()) {
            throw GradleException("Base messages.properties not found at ${baseFile.absolutePath}")
        }

        // Read base keys in order
        val baseKeysInOrder =
            baseFile
                .readLines()
                .filter { it.isNotBlank() && !it.trim().startsWith("#") && it.contains("=") }
                .map { it.substringBefore("=").trim() }

        val baseKeys = baseKeysInOrder.toSet()

        logger.lifecycle("📋 Found ${baseKeys.size} keys in base messages.properties")

        // Check other language files
        val languageFiles =
            i18nDir.listFiles { file ->
                file.name.startsWith("messages_") && file.extension == "properties"
            } ?: emptyArray()

        if (languageFiles.isEmpty()) {
            logger.lifecycle("⚠️  No language files found")
            return@doLast
        }

        var hasErrors = false
        val reportLines = mutableListOf<String>()

        languageFiles.sorted().forEach { langFile ->
            val langKeys =
                langFile
                    .readLines()
                    .filter { it.isNotBlank() && !it.trim().startsWith("#") && it.contains("=") }
                    .map { it.substringBefore("=").trim() }
                    .toSet()

            val missingKeys = baseKeys - langKeys
            val extraKeys = langKeys - baseKeys

            if (missingKeys.isNotEmpty() || extraKeys.isNotEmpty()) {
                hasErrors = true
                reportLines.add("")
                reportLines.add("❌ ${langFile.name}:")

                if (missingKeys.isNotEmpty()) {
                    reportLines.add("   Missing ${missingKeys.size} key(s) (in order of appearance):")
                    // Sort by order of occurrence in base file
                    baseKeysInOrder.filter { it in missingKeys }.forEach { key ->
                        reportLines.add("     - $key")
                    }
                }

                if (extraKeys.isNotEmpty()) {
                    reportLines.add("   Extra ${extraKeys.size} key(s) (not in base):")
                    extraKeys.sorted().forEach { key ->
                        reportLines.add("     + $key")
                    }
                }
            } else {
                logger.lifecycle("✅ ${langFile.name} - All keys present (${langKeys.size} keys)")
            }
        }

        if (hasErrors) {
            reportLines.forEach { logger.lifecycle(it) }
            logger.lifecycle("")
            throw GradleException("Some language files are missing keys or have extra keys. See output above.")
        } else {
            logger.lifecycle("")
            logger.lifecycle("✅ All ${languageFiles.size} language files have all required keys!")
        }
    }
}

// Task to detect unused localization keys
tasks.register("detectUnusedLocalizations") {
    group = "verification"
    description = "Detect unused localization keys in properties files. Use -Pdelete=true to remove them."

    doLast {
        // i18n files now live in desktop-shared so both desktop and askimo-app share the same base
        val i18nDir = file("../desktop-shared/src/main/resources/i18n")
        val desktopSrcDir = file("src/main/kotlin")
        val desktopSharedSrcDir = file("../desktop-shared/src/main/kotlin")
        val sharedSrcDir = file("../shared/src/main/kotlin")
        val reportFile = file("${layout.buildDirectory.get()}/reports/unused-localizations.txt")

        // Check if delete mode is enabled
        val deleteMode = project.findProperty("delete")?.toString()?.toBoolean() ?: false

        if (deleteMode) {
            println("🗑️  DELETE MODE ENABLED - Unused keys will be removed from all properties files")
        }

        // Read all keys from messages.properties
        val basePropertiesFile = file("$i18nDir/messages.properties")
        if (!basePropertiesFile.exists()) {
            println("❌ Base properties file not found: ${basePropertiesFile.path}")
            return@doLast
        }

        val allKeys = mutableMapOf<String, String>() // key -> value

        basePropertiesFile.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && "=" in trimmed) {
                val key = trimmed.substringBefore("=").trim()
                val value = trimmed.substringAfter("=").trim()
                allKeys[key] = value
            }
        }

        println("📋 Found ${allKeys.size} localization keys in messages.properties")

        // Scan all Kotlin files for key usage in both desktop and shared modules
        val usedKeys = mutableSetOf<String>()
        val keyUsageMap = mutableMapOf<String, MutableList<String>>() // key -> list of files

        fun scanDirectory(
            srcDir: File,
            moduleName: String,
        ) {
            if (!srcDir.exists()) {
                println("⚠️  Directory not found: ${srcDir.path}")
                return
            }

            println("🔍 Scanning $moduleName module: ${srcDir.path}")
            var fileCount = 0

            srcDir
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    fileCount++
                    val content = file.readText()
                    val relativePath = "$moduleName/${file.relativeTo(srcDir).path}"

                    // Pattern 1: stringResource("key")
                    Regex("""stringResource\s*\(\s*"([^"]+)"""").findAll(content).forEach { match ->
                        val key = match.groupValues[1]
                        usedKeys.add(key)
                        keyUsageMap.getOrPut(key) { mutableListOf() }.add(relativePath)
                    }

                    // Pattern 2: LocalizationManager.getString("key")
                    Regex("""LocalizationManager\.getString\s*\(\s*"([^"]+)"""").findAll(content).forEach { match ->
                        val key = match.groupValues[1]
                        usedKeys.add(key)
                        keyUsageMap.getOrPut(key) { mutableListOf() }.add(relativePath)
                    }

                    // Pattern 3: String literals that look like i18n keys (in lists, assignments, etc.)
                    // Matches: "export.format.html.benefit.1", "settings.model.name", etc.
                    Regex(""""([a-z][a-z0-9._-]*[a-z0-9])"""").findAll(content).forEach { match ->
                        val potentialKey = match.groupValues[1]
                        // Check if this string matches a known key pattern and exists in our keys
                        if (potentialKey in allKeys && potentialKey.contains(".")) {
                            usedKeys.add(potentialKey)
                            keyUsageMap.getOrPut(potentialKey) { mutableListOf() }.add(relativePath)
                        }
                    }

                    // Pattern 4: Keys with variable interpolation
                    // Matches: stringResource("log.level.${variable}"), stringResource("prefix.${var}.suffix")
                    Regex("""stringResource\s*\(\s*"([a-z][a-z0-9._-]*)\$\{[^}]+\}([a-z0-9._-]*)"""")
                        .findAll(content)
                        .forEach { match ->
                            val prefix = match.groupValues[1]
                            val suffix = match.groupValues[2]
                            // Find all keys that start with the prefix and end with the suffix
                            allKeys.keys.forEach { key ->
                                if (key.startsWith(prefix) && (suffix.isEmpty() || key.endsWith(suffix))) {
                                    usedKeys.add(key)
                                    keyUsageMap.getOrPut(key) { mutableListOf() }.add(relativePath)
                                }
                            }
                        }

                    // Pattern 5: LocalizationManager.getString with interpolation
                    Regex(
                        """LocalizationManager\.getString\s*\(\s*"([a-z][a-z0-9._-]*)\$\{[^}]+\}([a-z0-9._-]*)"""",
                    ).findAll(content).forEach { match ->
                        val prefix = match.groupValues[1]
                        val suffix = match.groupValues[2]
                        // Find all keys that start with the prefix and end with the suffix
                        allKeys.keys.forEach { key ->
                            if (key.startsWith(prefix) && (suffix.isEmpty() || key.endsWith(suffix))) {
                                usedKeys.add(key)
                                keyUsageMap.getOrPut(key) { mutableListOf() }.add(relativePath)
                            }
                        }
                    }
                }

            println("   Scanned $fileCount Kotlin files")
        }

        // Scan both modules
        scanDirectory(desktopSrcDir, "desktop")
        scanDirectory(desktopSharedSrcDir, "desktop-shared")
        scanDirectory(sharedSrcDir, "shared")

        println("✅ Found ${usedKeys.size} used keys across both modules")

        // Find unused keys
        val unusedKeys = allKeys.keys - usedKeys

        // Delete unused keys if requested
        if (deleteMode && unusedKeys.isNotEmpty()) {
            println("\n🗑️  Removing ${unusedKeys.size} unused keys from all properties files...")

            val propertiesFiles =
                i18nDir.listFiles { file ->
                    file.name.endsWith(".properties")
                } ?: emptyArray()

            propertiesFiles.forEach { propsFile ->
                val lines = propsFile.readLines()
                val filteredLines = mutableListOf<String>()
                var removedCount = 0

                lines.forEach { line ->
                    val trimmed = line.trim()
                    // Keep comments, empty lines, and used keys
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        filteredLines.add(line)
                    } else if ("=" in trimmed) {
                        val key = trimmed.substringBefore("=").trim()
                        if (key !in unusedKeys) {
                            filteredLines.add(line)
                        } else {
                            removedCount++
                        }
                    } else {
                        filteredLines.add(line)
                    }
                }

                if (removedCount > 0) {
                    propsFile.writeText(filteredLines.joinToString("\n") + "\n")
                    println("   ✓ ${propsFile.name}: removed $removedCount key(s)")
                }
            }

            println("\n✅ Successfully removed unused keys from all properties files")
        }

        // Generate report
        reportFile.parentFile.mkdirs()
        reportFile.writeText(
            buildString {
                appendLine("=".repeat(80))
                appendLine("UNUSED LOCALIZATION KEYS REPORT")
                appendLine("=".repeat(80))
                appendLine("Generated: ${Instant.now()}")
                appendLine("Total keys: ${allKeys.size}")
                appendLine("Used keys: ${usedKeys.size}")
                appendLine("Unused keys: ${unusedKeys.size}")
                if (deleteMode) {
                    appendLine("Delete mode: ENABLED (keys were removed)")
                }
                appendLine("=".repeat(80))
                appendLine()

                if (unusedKeys.isNotEmpty()) {
                    appendLine("UNUSED KEYS:")
                    appendLine("-".repeat(80))
                    unusedKeys.sorted().forEach { key ->
                        appendLine("Key: $key")
                        appendLine("Value: ${allKeys[key]}")
                        appendLine()
                    }
                } else {
                    appendLine("✅ All localization keys are being used!")
                }

                appendLine()
                appendLine("=".repeat(80))
                appendLine("KEY USAGE DETAILS:")
                appendLine("-".repeat(80))
                usedKeys.sorted().forEach { key ->
                    appendLine("Key: $key")
                    val files = keyUsageMap[key] ?: emptyList()
                    appendLine("Used in ${files.size} file(s):")
                    files.forEach { file ->
                        appendLine("  - $file")
                    }
                    appendLine()
                }
            },
        )

        println("\n📊 Report generated: ${reportFile.absolutePath}")

        if (unusedKeys.isEmpty()) {
            println("🎉 All localization keys are being used!")
        } else {
            println("⚠️  Found ${unusedKeys.size} unused localization keys\n")
            println("Unused keys:")
            unusedKeys.sorted().forEach { key ->
                println("  - $key = ${allKeys[key]}")
            }

            if (!deleteMode) {
                println("\n💡 To automatically remove these keys, run:")
                println("   ./gradlew detectUnusedLocalizations -Pdelete=true")
            }
        }
    }
}

// =============================================================================
// macOS Code Signing and Notarization Tasks
//
// All certificate/keychain setup lives here — portable across local and CI.
//
// Environment variables (pass via .env or command line):
//
//   ── Signing ──────────────────────────────────────────────────────────────
//   KEYCHAIN_PASSWORD          Required always.
//                              Local dev : your macOS login password.
//                              CI        : any random string (ephemeral keychain).
//
//   MACOS_CERTIFICATE_BASE64   Optional.  base64-encoded .p12 file.
//                              Set this in CI where the cert is not pre-installed.
//                              If absent the login keychain is used as-is.
//
//   MACOS_CERTIFICATE_PASSWORD Optional.  Password for the .p12 (default: "").
//
//   MACOS_IDENTITY             Optional.  "Developer ID Application: Name (TEAM)".
//                              Auto-detected from the keychain when not set.
//
//   ── Notarization ─────────────────────────────────────────────────────────
//   APPLE_ID                   Required.  Your Apple ID email.
//   APPLE_PASSWORD             Required.  App-specific password.
//   APPLE_TEAM_ID              Required.  10-character team ID.
//
// Usage (local and CI are identical):
//   ./gradlew customNotarizeMacApp
// =============================================================================

// -----------------------------------------------------------------------------
// Shared helpers
// -----------------------------------------------------------------------------

/**
 * Prepares the macOS Keychain so codesign can access the Developer ID private key.
 *
 * CI mode   (MACOS_CERTIFICATE_BASE64 set):
 *   Decodes the base64 .p12, creates a short-lived keychain, imports the cert,
 *   grants codesign partition-list access, and puts the keychain first in the
 *   search list.  No Apple intermediate cert download is needed — codesign
 *   fetches them automatically via the --timestamp server.
 *
 * Local dev (MACOS_CERTIFICATE_BASE64 absent):
 *   Just unlocks the existing login keychain with KEYCHAIN_PASSWORD and raises
 *   the auto-lock timeout so it stays open for the whole build.
 *
 * After this call use [resolveIdentity] to get the signing identity string.
 */
fun prepareSigningKeychain() {
    val keychainPassword =
        getEnvOrProperty("KEYCHAIN_PASSWORD")
            ?: throw GradleException(
                "KEYCHAIN_PASSWORD is required.\n" +
                    "  Local dev: set it to your macOS login password.\n" +
                    "  CI: set it to any random string (the ephemeral keychain password).",
            )
    val certBase64 = getEnvOrProperty("MACOS_CERTIFICATE_BASE64")
    val certPassword = getEnvOrProperty("MACOS_CERTIFICATE_PASSWORD") ?: ""

    if (certBase64 != null) {
        // ── CI mode: import the .p12 into an ephemeral keychain ──────────────
        logger.lifecycle("🔑 CI mode: creating ephemeral keychain and importing certificate...")

        val tmpDir = File(System.getProperty("java.io.tmpdir"))
        val keychainPath = File(tmpDir, "askimo-signing.keychain-db")
        val p12File = File(tmpDir, "askimo-cert.p12")

        try {
            // Write decoded certificate
            p12File.writeBytes(Base64.getDecoder().decode(certBase64.trim()))

            // Delete any leftover keychain from a previous run
            execOps.exec {
                commandLine("security", "delete-keychain", keychainPath.absolutePath)
                isIgnoreExitValue = true
            }

            // Create keychain, unlock it, extend auto-lock timeout to 6 hours
            execOps.exec { commandLine("security", "create-keychain", "-p", keychainPassword, keychainPath.absolutePath) }
            execOps.exec { commandLine("security", "unlock-keychain", "-p", keychainPassword, keychainPath.absolutePath) }
            execOps.exec { commandLine("security", "set-keychain-settings", "-lut", "21600", keychainPath.absolutePath) }

            // Import certificate — grant codesign access without prompts (-A = all apps, -T = specific tool)
            execOps.exec {
                commandLine(
                    "security",
                    "import",
                    p12File.absolutePath,
                    "-P",
                    certPassword,
                    "-k",
                    keychainPath.absolutePath,
                    "-T",
                    "/usr/bin/codesign",
                    "-A",
                )
            }

            // Allow codesign to access the key without a UI password prompt
            execOps.exec {
                commandLine(
                    "security",
                    "set-key-partition-list",
                    "-S",
                    "apple-tool:,apple:,codesign:",
                    "-s",
                    "-k",
                    keychainPassword,
                    keychainPath.absolutePath,
                )
            }

            // Put our keychain first so codesign finds the identity
            execOps.exec {
                commandLine(
                    "security",
                    "list-keychains",
                    "-d",
                    "user",
                    "-s",
                    keychainPath.absolutePath,
                    "login.keychain-db",
                )
            }

            logger.lifecycle("✅ Ephemeral keychain ready: ${keychainPath.absolutePath}")
        } finally {
            p12File.delete()
        }

        resolveAndCacheIdentity(keychainPath.absolutePath)
    } else {
        // ── Local dev mode: unlock the login keychain ─────────────────────────
        // Resolve the actual login keychain path dynamically — on some macOS
        // versions or after upgrades the path may differ from the default.
        val home = System.getProperty("user.home")

        // Candidate paths in priority order
        val keychainCandidates =
            listOf(
                "$home/Library/Keychains/login.keychain-db", // modern macOS
                "$home/Library/Keychains/login.keychain", // legacy path
            )
        val loginKeychain =
            keychainCandidates.firstOrNull { File(it).exists() }
                ?: run {
                    // Last resort: ask the system which keychains are in the list
                    val out = ByteArrayOutputStream()
                    execOps.exec {
                        commandLine("security", "list-keychains", "-d", "user")
                        standardOutput = out
                        isIgnoreExitValue = true
                    }
                    out
                        .toString()
                        .lines()
                        .map { it.trim().removeSurrounding("\"") }
                        .firstOrNull { it.contains("login") && File(it).exists() }
                        ?: throw GradleException(
                            "Cannot find login keychain. Set KEYCHAIN_PASSWORD and ensure " +
                                "your Developer ID certificate is in the login keychain.",
                        )
                }

        logger.lifecycle("🔓 Local mode: unlocking $loginKeychain")

        // Unlock — Gradle commandLine does NOT go through a shell, so passwords
        // with special characters are passed safely as raw process arguments.
        execOps.exec {
            commandLine("security", "unlock-keychain", "-p", keychainPassword, loginKeychain)
        }

        // Prevent auto-lock for 1 hour during the build
        execOps.exec {
            commandLine("security", "set-keychain-settings", "-t", "3600", "-u", loginKeychain)
            isIgnoreExitValue = true
        }

        // Grant codesign access without interactive UI prompts
        execOps.exec {
            commandLine(
                "security",
                "set-key-partition-list",
                "-S",
                "apple-tool:,apple:,codesign:",
                "-s",
                "-k",
                keychainPassword,
                loginKeychain,
            )
            isIgnoreExitValue = true
        }

        // Ensure the login keychain is at the top of the search list
        execOps.exec {
            commandLine("security", "list-keychains", "-d", "user", "-s", loginKeychain)
            isIgnoreExitValue = true
        }

        logger.lifecycle("✅ Login keychain unlocked: $loginKeychain")

        resolveAndCacheIdentity(loginKeychain)
    }
}

/**
 * Finds the first "Developer ID Application" identity in [keychainPath]
 * (or the default search list when null) and caches it in a project extra
 * property so [resolveIdentity] can return it cheaply.
 *
 * MACOS_IDENTITY env var takes precedence if set.
 */
fun resolveAndCacheIdentity(keychainPath: String?) {
    val explicit = getEnvOrProperty("MACOS_IDENTITY")

    // Determine which keychain actually holds the signing identity.
    // In CI mode the identity is always in the ephemeral keychain we just created,
    // so we can use keychainPath directly.
    // In local-dev mode the cert may live in System.keychain (or any other keychain
    // in the search list), NOT necessarily in login.keychain-db.  Passing the wrong
    // --keychain path to codesign causes errSecInternalComponent even when the
    // keychain is unlocked, so we probe for the real keychain here.
    val effectiveKeychainPath: String? =
        run {
            if (keychainPath == null) return@run null

            // Check whether the identity is actually present in keychainPath.
            val probeOut = ByteArrayOutputStream()
            execOps.exec {
                commandLine("security", "find-identity", "-v", "-p", "codesigning", keychainPath)
                standardOutput = probeOut
                isIgnoreExitValue = true
            }
            val identityName = explicit?.takeIf { it.isNotBlank() } ?: "Developer ID Application"
            if (probeOut.toString().contains(identityName)) {
                // Identity lives in the supplied keychain — use it.
                keychainPath
            } else {
                // Identity is NOT in keychainPath (e.g. it lives in System.keychain).
                // Find the actual keychain by asking security for the certificate.
                val certOut = ByteArrayOutputStream()
                execOps.exec {
                    commandLine(
                        "security",
                        "find-certificate",
                        "-c",
                        if (!explicit.isNullOrBlank()) {
                            explicit.substringAfter(": ").substringBefore(" (")
                        } else {
                            "Developer ID Application"
                        },
                    )
                    standardOutput = certOut
                    isIgnoreExitValue = true
                }
                val match = Regex("""keychain:\s*"([^"]+)"""").find(certOut.toString())
                val foundKeychain = match?.groupValues?.get(1)
                if (foundKeychain != null) {
                    logger.lifecycle("🔑 Identity found in keychain: $foundKeychain (not in $keychainPath)")
                    // Do NOT pass --keychain for System.keychain: codesign can reach it via the
                    // default search list, and explicitly passing System.keychain with
                    // set-key-partition-list would require sudo (causing errSecInternalComponent).
                    if (foundKeychain == "/Library/Keychains/System.keychain") {
                        logger.lifecycle(
                            "ℹ️  Identity is in System.keychain — omitting --" +
                                "keychain from codesign (uses default search list)",
                        )
                        null
                    } else {
                        foundKeychain
                    }
                } else {
                    // Cannot determine keychain — let codesign use the default search list.
                    logger.lifecycle("⚠️  Could not determine keychain for identity; omitting --keychain from codesign")
                    null
                }
            }
        }

    if (effectiveKeychainPath != null) {
        project.extra["macosKeychainPath"] = effectiveKeychainPath
    }

    if (!explicit.isNullOrBlank()) {
        project.extra["macosIdentityResolved"] = explicit
        logger.lifecycle("🔑 Using provided identity: $explicit")
        return
    }

    val output = ByteArrayOutputStream()
    val args =
        buildList {
            add("security")
            add("find-identity")
            add("-v")
            add("-p")
            add("codesigning")
            if (effectiveKeychainPath != null) {
                add(effectiveKeychainPath)
            }
        }
    execOps.exec {
        commandLine(args)
        standardOutput = output
        isIgnoreExitValue = true
    }

    val identity =
        output
            .toString()
            .lines()
            .firstOrNull { it.contains("Developer ID Application") }
            ?.let { Regex(""""([^"]+)"""").find(it)?.groupValues?.get(1) }
            ?: throw GradleException(
                "❌ No 'Developer ID Application' certificate found.\n" +
                    "  Local: make sure your cert is in the login keychain.\n" +
                    "  CI: verify MACOS_CERTIFICATE_BASE64 is correct.",
            )

    project.extra["macosIdentityResolved"] = identity
    logger.lifecycle("🔑 Resolved identity: $identity")
}

/** Returns the signing identity resolved by [prepareSigningKeychain]. */
fun resolveIdentity(): String =
    (project.extra.get("macosIdentityResolved") as? String)
        ?: throw GradleException("prepareSigningKeychain() must run before resolveIdentity()")

/**
 * Returns the keychain path cached by [prepareSigningKeychain], or null when
 * not available (falls back to the system default keychain search list).
 */
fun resolveKeychainPath(): String? = project.extra.properties["macosKeychainPath"] as? String

/**
 * Staples the notarization ticket to [target] with up to [maxAttempts] retries,
 * waiting [waitSeconds] between attempts.
 *
 * Apple's CDN can take several minutes to publish the ticket after "Accepted"
 * (Error 65 = "Record not found" = CDN lag, safe to retry).
 */
fun stapleWithRetry(
    target: File,
    maxAttempts: Int = 5,
    waitSeconds: Long = 60,
) {
    var stapled = false
    for (attempt in 1..maxAttempts) {
        logger.lifecycle("⏳ Waiting ${waitSeconds}s for ticket CDN propagation (attempt $attempt/$maxAttempts)...")
        Thread.sleep(waitSeconds * 1000)

        logger.lifecycle("📎 Stapling: ${target.name} ...")
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val result =
            execOps.exec {
                commandLine("xcrun", "stapler", "staple", "-v", target.absolutePath)
                isIgnoreExitValue = true
                standardOutput = out
                errorOutput = err
            }

        if (result.exitValue == 0) {
            logger.lifecycle("✅ Stapled successfully on attempt $attempt")
            stapled = true
            break
        }

        val combined = out.toString() + err.toString()
        logger.lifecycle("⚠️  Attempt $attempt failed (exit ${result.exitValue}):\n$combined")

        if (!combined.contains("Error 65") && !combined.contains("Record not found")) {
            throw GradleException("❌ Stapler failed with unexpected error:\n$combined")
        }
    }
    if (!stapled) {
        throw GradleException(
            "❌ Stapler failed after $maxAttempts attempts (${maxAttempts * waitSeconds}s).\n" +
                "Run manually when the ticket propagates:\n" +
                "  xcrun stapler staple -v \"${target.absolutePath}\"",
        )
    }
}

// -----------------------------------------------------------------------------
// Task: createEntitlements
// -----------------------------------------------------------------------------

/**
 * Create entitlements file for hardened runtime
 */
tasks.register("createEntitlements") {
    group = "distribution"
    description = "Create entitlements.plist for macOS hardened runtime"

    val entitlementsFile = file("${layout.buildDirectory.get()}/compose/notarized/entitlements.plist")

    doLast {
        entitlementsFile.parentFile.mkdirs()
        entitlementsFile.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>com.apple.security.cs.allow-jit</key>
                <true/>
                <key>com.apple.security.cs.allow-unsigned-executable-memory</key>
                <true/>
                <key>com.apple.security.cs.allow-dyld-environment-variables</key>
                <true/>
                <key>com.apple.security.cs.disable-library-validation</key>
                <true/>
            </dict>
            </plist>
            """.trimIndent(),
        )
        logger.lifecycle("✅ Created entitlements file: ${entitlementsFile.absolutePath}")
    }
}

/**
 * Returns true if the file is a macOS Mach-O binary (dylib, executable, bundle, etc.)
 * by inspecting its magic bytes. This catches extension-less binaries like
 * pty4j-unix-spawn-helper that are bundled inside JARs.
 *
 * Mach-O magic values:
 *   0xFEEDFACE  - 32-bit big-endian
 *   0xCEFAEDFE  - 32-bit little-endian
 *   0xFEEDFACF  - 64-bit big-endian
 *   0xCFFAEDFE  - 64-bit little-endian
 *   0xBEBAFECA  - fat/universal binary
 *   0xCAFEBABE  - fat/universal binary (big-endian)
 */
fun isMachOBinary(file: File): Boolean {
    if (!file.isFile || file.length() < 4) return false
    return try {
        val bytes = file.inputStream().use { it.readNBytes(4) }
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        val b3 = bytes[3].toInt() and 0xFF
        // little-endian 32-bit:  CE FA ED FE
        (b0 == 0xCE && b1 == 0xFA && b2 == 0xED && b3 == 0xFE) ||
            // little-endian 64-bit:  CF FA ED FE
            (b0 == 0xCF && b1 == 0xFA && b2 == 0xED && b3 == 0xFE) ||
            // big-endian 32-bit:  FE ED FA CE
            (b0 == 0xFE && b1 == 0xED && b2 == 0xFA && b3 == 0xCE) ||
            // big-endian 64-bit:  FE ED FA CF
            (b0 == 0xFE && b1 == 0xED && b2 == 0xFA && b3 == 0xCF) ||
            // fat/universal:  CA FE BA BE
            (b0 == 0xCA && b1 == 0xFE && b2 == 0xBA && b3 == 0xBE) ||
            // fat/universal big-endian:  BE BA FE CA
            (b0 == 0xBE && b1 == 0xBA && b2 == 0xFE && b3 == 0xCA)
    } catch (_: Exception) {
        false
    }
}

fun signDylibsInsideJar(
    jarFile: File,
    identity: String,
    @Suppress("UNUSED_PARAMETER") entitlementsFile: File,
    workRoot: File,
    keychainPath: String? = null,
) {
    logger.lifecycle("   • Signing native binaries inside JAR: ${jarFile.name}")
    logger.lifecycle("     Path: ${jarFile.absolutePath}")

    // Check if JAR exists
    if (!jarFile.exists()) {
        logger.warn("     ⚠️ JAR missing, skipping")
        return
    }

    // Check if JAR likely contains macOS native binaries by scanning entry names.
    // pty4j embeds:
    //   resources/com/pty4j/native/darwin/libpty.dylib          (.dylib - caught by extension)
    //   resources/com/pty4j/native/darwin/pty4j-unix-spawn-helper (Mach-O, NO extension!)
    // We use "darwin" as an additional hint so we don't skip JARs with extension-less helpers.
    val jarContents =
        ByteArrayOutputStream().use { output ->
            execOps.exec {
                commandLine("jar", "tf", jarFile.absolutePath)
                standardOutput = output
                isIgnoreExitValue = true
            }
            output.toString()
        }

    val hasNativeLibs =
        jarContents.contains(".dylib") ||
            jarContents.contains(".jnilib") ||
            jarContents.contains("darwin/") // catches extension-less Mach-O helpers

    if (!hasNativeLibs) {
        return
    }

    val workDir = File(workRoot, "${jarFile.name}.work").apply { mkdirs() }
    val extractDir = File(workDir, "contents").apply { mkdirs() }
    val tmpJar = File(workDir, "repacked.jar")

    try {
        // Extract JAR
        logger.lifecycle("     Extracting JAR...")
        execOps.exec {
            workingDir = extractDir
            commandLine("jar", "xf", jarFile.absolutePath)
        }

        // Find and sign ALL macOS native binaries:
        //  - files with .dylib or .jnilib extension
        //  - extension-less files that are Mach-O binaries (e.g. pty4j-unix-spawn-helper)
        logger.lifecycle("     Signing native binaries...")
        extractDir
            .walkTopDown()
            .filter { it.isFile }
            .filter { file ->
                file.extension == "dylib" ||
                    file.extension == "jnilib" ||
                    (file.extension.isEmpty() && isMachOBinary(file))
            }.forEach { nativeBinary ->
                logger.lifecycle("       Signing: ${nativeBinary.relativeTo(extractDir)}")
                // Make sure the binary is executable before signing
                nativeBinary.setExecutable(true, false)
                execOps.exec {
                    // Dylibs/jnilibs do NOT need entitlements — passing --entitlements
                    // to a dylib causes errSecInternalComponent on some macOS versions.
                    val cmd =
                        buildList {
                            add("codesign")
                            if (keychainPath != null) {
                                add("--keychain")
                                add(keychainPath)
                            }
                            add("--force")
                            add("--sign")
                            add(identity)
                            add("--timestamp")
                            add("--options")
                            add("runtime")
                            add(nativeBinary.absolutePath)
                        }
                    commandLine(cmd)
                }
            }

        // Repack JAR using an explicit file list to correctly preserve structure.
        // Avoid `jar cf tmpJar .` which adds a stray "." directory entry and can
        // drop files on some JDK versions.
        logger.lifecycle("     Repacking JAR...")
        tmpJar.delete()

        val manifestFile = File(extractDir, "META-INF/MANIFEST.MF")

        // Collect all relative file paths (MANIFEST.MF is handled separately via cfm)
        val allRelativeFiles =
            extractDir
                .walkTopDown()
                .filter { it.isFile && it.relativeTo(extractDir).path != "META-INF/MANIFEST.MF" }
                .map { it.relativeTo(extractDir).path }
                .toList()

        if (manifestFile.exists()) {
            execOps.exec {
                workingDir = extractDir
                commandLine(
                    listOf("jar", "cfm", tmpJar.absolutePath, "META-INF/MANIFEST.MF") + allRelativeFiles,
                )
            }
        } else {
            execOps.exec {
                workingDir = extractDir
                commandLine(listOf("jar", "cf", tmpJar.absolutePath) + allRelativeFiles)
            }
        }

        // Replace original JAR
        logger.lifecycle("     Replacing original JAR...")
        if (!tmpJar.exists()) {
            throw GradleException("Repacked JAR missing: ${tmpJar.absolutePath}")
        }
        tmpJar.copyTo(jarFile, overwrite = true)
        logger.lifecycle("     ✅ Done: ${jarFile.name}")
    } finally {
        workDir.deleteRecursively()
    }
}

/**
 * Sign all components of the .app bundle.
 * Keychain setup (cert import in CI, or unlock in local dev) happens here.
 */
tasks.register("signMacApp") {
    group = "distribution"
    description = "Prepare keychain + sign all components of the macOS .app bundle"

    dependsOn("createEntitlements", "createDistributable")

    @Suppress("DEPRECATION")
    doLast {
        // Prepares keychain (imports cert in CI, unlocks login keychain locally)
        // and resolves the signing identity — works on any macOS machine.
        prepareSigningKeychain()
        val macosIdentity = resolveIdentity()
        val keychainPath = resolveKeychainPath()

        // Build a helper that appends "--keychain <path>" when a keychain is known.
        // Passing --keychain explicitly ensures codesign can reach the private key
        // even when running inside a JVM subprocess that doesn't inherit the macOS
        // GUI session's keychain search list.
        fun codesignArgs(vararg extra: String): List<String> =
            buildList {
                add("codesign")
                if (keychainPath != null) {
                    add("--keychain")
                    add(keychainPath)
                }
                addAll(extra)
            }

        val appDir = file("${layout.buildDirectory.get()}/compose/binaries/main/app")
        val appBundle =
            appDir.listFiles()?.firstOrNull { it.extension == "app" }
                ?: throw GradleException("No .app bundle found in $appDir")

        // Copy to notarized directory for signing
        val notarizedDir = file("${layout.buildDirectory.get()}/compose/notarized").apply { mkdirs() }
        val appToSign = File(notarizedDir, appBundle.name)

        logger.lifecycle("📤 Copying .app to staging for signing...")
        // Delete existing if present
        if (appToSign.exists()) {
            appToSign.deleteRecursively()
        }

        // Use rsync to preserve permissions and attributes
        execOps.exec {
            commandLine("rsync", "-a", "--delete", "${appBundle.absolutePath}/", "${appToSign.absolutePath}/")
        }

        logger.lifecycle("🔏 Will sign app: ${appToSign.absolutePath}")
        logger.lifecycle("🔑 Identity: $macosIdentity")

        val entitlementsFile = file("$notarizedDir/entitlements.plist")
        val appContents = File(appToSign, "Contents")
        val runtimeDir = File(appContents, "runtime")
        val appLibDir = File(appContents, "app")

        // Get main executable name from Info.plist
        val infoPlist = File(appContents, "Info.plist")
        val mainExeName =
            ByteArrayOutputStream()
                .use { output ->
                    execOps.exec {
                        commandLine("defaults", "read", infoPlist.absolutePath.removeSuffix(".plist"), "CFBundleExecutable")
                        standardOutput = output
                        isIgnoreExitValue = true
                    }
                    output.toString().trim()
                }.takeIf { it.isNotEmpty() } ?: run {
                // Fallback: find executable in MacOS directory
                val macosDir = File(appContents, "MacOS")
                macosDir.listFiles()?.firstOrNull { it.isFile && it.canExecute() }?.name ?: "Askimo"
            }

        val mainExe = File(appContents, "MacOS/$mainExeName")

        logger.lifecycle("Main executable: $mainExeName at ${mainExe.absolutePath}")

        // 1) Sign embedded runtime dylibs + jspawnhelper
        //    Dylibs do NOT need entitlements — passing --entitlements to a dylib
        //    triggers errSecInternalComponent on some macOS versions.  Just sign
        //    with --options runtime and --timestamp, which is all Apple requires.
        if (runtimeDir.exists()) {
            logger.lifecycle("🔧 Signing embedded runtime dylibs + helpers...")
            runtimeDir
                .walk()
                .filter { it.extension == "dylib" || it.name == "jspawnhelper" }
                .forEach { file ->
                    execOps.exec {
                        commandLine(
                            codesignArgs(
                                "--force",
                                "--sign",
                                macosIdentity,
                                "--timestamp",
                                "--options",
                                "runtime",
                                file.absolutePath,
                            ),
                        )
                    }
                    logger.lifecycle("${file.absolutePath}: replacing existing signature")
                }
        }

        // 2) Sign loose dylibs under Contents/app (no entitlements needed)
        if (appLibDir.exists()) {
            logger.lifecycle("🔧 Signing loose dylibs under Contents/app...")
            appLibDir
                .walk()
                .filter { it.extension == "dylib" && it.isFile }
                .forEach { dylibFile ->
                    execOps.exec {
                        commandLine(
                            codesignArgs(
                                "--force",
                                "--sign",
                                macosIdentity,
                                "--timestamp",
                                "--options",
                                "runtime",
                                dylibFile.absolutePath,
                            ),
                        )
                    }
                }
        }

        // 3) Sign dylibs inside JARs
        if (appLibDir.exists()) {
            logger.lifecycle("🔧 Signing dylibs inside JARs under Contents/app...")
            val workRoot = File(notarizedDir, "codesign-jar-work").apply { mkdirs() }

            appLibDir
                .walk()
                .filter { it.extension == "jar" && it.isFile }
                .forEach { jarFile ->
                    signDylibsInsideJar(jarFile, macosIdentity, entitlementsFile, workRoot, keychainPath)
                }

            workRoot.deleteRecursively()
        }

        // 4) Sign main executable (with entitlements — executable needs them)
        if (mainExe.exists()) {
            logger.lifecycle("🔧 Signing main executable: ${mainExe.absolutePath}")
            execOps.exec {
                commandLine(
                    codesignArgs(
                        "--force",
                        "--sign",
                        macosIdentity,
                        "--entitlements",
                        entitlementsFile.absolutePath,
                        "--timestamp",
                        "--options",
                        "runtime",
                        mainExe.absolutePath,
                    ),
                )
            }
        } else {
            logger.warn("⚠️ Main executable not found: ${mainExe.absolutePath}")
        }

        // 5) Sign app bundle (deep, with entitlements)
        logger.lifecycle("🔧 Signing app bundle (deep): ${appToSign.absolutePath}")
        execOps.exec {
            commandLine(
                codesignArgs(
                    "--force",
                    "--deep",
                    "--sign",
                    macosIdentity,
                    "--entitlements",
                    entitlementsFile.absolutePath,
                    "--timestamp",
                    "--options",
                    "runtime",
                    appToSign.absolutePath,
                ),
            )
        }

        // Verify signature
        logger.lifecycle("🔎 Verifying signature...")
        execOps.exec {
            commandLine("codesign", "--verify", "--deep", "--strict", "--verbose=2", appToSign.absolutePath)
        }

        logger.lifecycle("✅ App bundle signed successfully")
    }
}

/**
 * Notarize the .app bundle
 */
tasks.register("notarizeApp") {
    group = "distribution"
    description = "Notarize the signed .app bundle with Apple's notarization service"

    dependsOn("signMacApp")

    @Suppress("DEPRECATION")
    doLast {
        val notarizedDir = file("${layout.buildDirectory.get()}/compose/notarized")
        val appToSign =
            notarizedDir.listFiles()?.firstOrNull { it.extension == "app" }
                ?: throw GradleException("No signed .app bundle found in $notarizedDir")

        // Notarization credentials - App-Specific Password
        val appleId =
            getEnvOrProperty("APPLE_ID")
                ?: throw GradleException("APPLE_ID environment variable is required")
        val applePassword =
            getEnvOrProperty("APPLE_PASSWORD")
                ?: throw GradleException("APPLE_PASSWORD environment variable is required (use app-specific password)")
        val appleTeamId =
            getEnvOrProperty("APPLE_TEAM_ID")
                ?: throw GradleException("APPLE_TEAM_ID environment variable is required")

        val notaryArgs =
            listOf(
                "--apple-id",
                appleId,
                "--team-id",
                appleTeamId,
                "--password",
                applePassword,
            )

        logger.lifecycle("🔐 Notarizing .app bundle...")
        logger.lifecycle("📦 App: ${appToSign.name}")

        // Create ZIP of .app for notarization (Apple requires ZIP for .app bundles)
        val appZip = File(notarizedDir, "${appToSign.nameWithoutExtension}.zip")
        appZip.delete()

        logger.lifecycle("📦 Creating ZIP for notarization...")
        execOps.exec {
            workingDir = notarizedDir
            commandLine("ditto", "-c", "-k", "--keepParent", appToSign.name, appZip.name)
        }

        // Calculate SHA256 before submission
        val sha256Before =
            ByteArrayOutputStream().use { output ->
                execOps.exec {
                    commandLine("shasum", "-a", "256", appZip.absolutePath)
                    standardOutput = output
                }
                output.toString().split(" ")[0]
            }

        logger.lifecycle("🔎 ZIP SHA256: $sha256Before")

        // Submit for notarization
        val notarizationOutput =
            ByteArrayOutputStream().use { output ->
                execOps.exec {
                    commandLine(
                        "xcrun",
                        "notarytool",
                        "submit",
                        appZip.absolutePath,
                        *notaryArgs.toTypedArray(),
                        "--wait",
                        "--output-format",
                        "json",
                    )
                    standardOutput = output
                }
                output.toString()
            }

        logger.lifecycle(notarizationOutput)

        // Parse JSON response
        val statusMatch = Regex(""""status":\s*"([^"]+)"""").find(notarizationOutput)
        val status = statusMatch?.groupValues?.get(1)

        val idMatch = Regex(""""id":\s*"([^"]+)"""").find(notarizationOutput)
        val submissionId = idMatch?.groupValues?.get(1)

        logger.lifecycle(".app submission: id=$submissionId, status=$status")

        if (status != "Accepted") {
            // Fetch the detailed log from Apple so we can see exactly which file was rejected
            if (submissionId != null) {
                logger.lifecycle("📋 Fetching notarization log for submission $submissionId...")
                val logOutput =
                    ByteArrayOutputStream().use { output ->
                        execOps.exec {
                            commandLine(
                                "xcrun",
                                "notarytool",
                                "log",
                                submissionId,
                                *notaryArgs.toTypedArray(),
                            )
                            standardOutput = output
                            isIgnoreExitValue = true
                        }
                        output.toString()
                    }
                logger.lifecycle("📋 Notarization log:\n$logOutput")
            }
            throw GradleException("❌ .app notarization failed with status: $status")
        }

        // Wait for ticket propagation and staple with retries
        appZip.delete()
        stapleWithRetry(appToSign)
        logger.lifecycle("✅ .app notarization complete!")
    }
}

/**
 * Create a signed DMG with Applications folder symlink
 */
tasks.register("createSignedDmg") {
    group = "distribution"
    description = "Create a signed DMG with Applications folder symlink"

    dependsOn("notarizeApp") // Changed from signMacApp to notarizeApp

    @Suppress("DEPRECATION")
    doLast {
        // Identity was already resolved by signMacApp earlier in the chain
        val macosIdentity = resolveIdentity()
        val keychainPath = resolveKeychainPath()

        val notarizedDir = file("${layout.buildDirectory.get()}/compose/notarized")
        val appToSign =
            notarizedDir.listFiles()?.firstOrNull { it.extension == "app" }
                ?: throw GradleException("No signed .app bundle found in $notarizedDir")

        // Create DMG staging folder with Applications symlink
        val dmgStaging =
            File(notarizedDir, "dmg-staging").apply {
                deleteRecursively()
                mkdirs()
            }

        logger.lifecycle("📦 Preparing DMG contents...")
        // Use rsync to preserve permissions
        execOps.exec {
            commandLine(
                "rsync",
                "-a",
                "${appToSign.absolutePath}/",
                "${File(dmgStaging, appToSign.name).absolutePath}/",
            )
        }

        // Create Applications symlink
        execOps.exec {
            workingDir = dmgStaging
            commandLine("ln", "-s", "/Applications", "Applications")
        }

        // Create temporary read-write DMG
        val tempDmg = File(notarizedDir, "Askimo-temp.dmg").apply { delete() }
        logger.lifecycle("📀 Creating temporary DMG...")
        execOps.exec {
            commandLine(
                "hdiutil",
                "create",
                "-volname",
                "Askimo",
                "-srcfolder",
                dmgStaging.absolutePath,
                "-ov",
                "-format",
                "UDRW",
                tempDmg.absolutePath,
            )
        }

        // Mount the DMG
        logger.lifecycle("💿 Mounting DMG for customization...")
        val mountOutput = ByteArrayOutputStream()
        execOps.exec {
            commandLine("hdiutil", "attach", "-readwrite", "-noverify", tempDmg.absolutePath)
            standardOutput = mountOutput
        }

        val mountPath =
            mountOutput
                .toString()
                .lines()
                .firstOrNull { it.contains("/Volumes/") }
                ?.substringAfter("/Volumes/")
                ?.trim()
                ?.let { "/Volumes/$it" }
                ?: throw GradleException("Could not determine mount path")

        logger.lifecycle("✅ Mounted at: $mountPath")

        try {
            // Copy background image to .background folder in DMG
            val backgroundDir = File(mountPath, ".background")
            backgroundDir.mkdirs()
            val backgroundImage = project.file("src/main/resources/images/dmg-background.png")

            if (backgroundImage.exists()) {
                logger.lifecycle("🖼️ Copying background image...")
                backgroundImage.copyTo(File(backgroundDir, "background.png"), overwrite = true)
            }

            // Wait for Finder to recognize the mounted volume
            logger.lifecycle("⏳ Waiting for Finder to recognize volume...")
            Thread.sleep(2000)

            // Set background image and window settings
            val backgroundScript =
                """
                tell application "Finder"
                    tell disk "Askimo"
                        open
                        set current view of container window to icon view
                        set toolbar visible of container window to false
                        set statusbar visible of container window to false
                        set the bounds of container window to {400, 100, 920, 480}
                        set viewOptions to the icon view options of container window
                        set arrangement of viewOptions to not arranged
                        set icon size of viewOptions to 100
                        set background picture of viewOptions to file ".background:background.png"
                        set text size of viewOptions to 13
                        set position of item "${appToSign.name}" of container window to {130, 190}
                        set position of item "Applications" of container window to {390, 190}
                        update without registering applications
                        delay 1
                        close
                    end tell
                end tell
                """.trimIndent()

            logger.lifecycle("🎨 Applying window settings...")
            execOps.exec {
                commandLine("osascript", "-e", backgroundScript)
                isIgnoreExitValue = true
            }

            // Give Finder time to save the settings
            logger.lifecycle("⏳ Waiting for Finder to save settings...")
            Thread.sleep(3000)

            // Sync to ensure all changes are written
            logger.lifecycle("💾 Syncing filesystem...")
            execOps.exec {
                commandLine("sync")
                isIgnoreExitValue = true
            }
            Thread.sleep(500)

            // Force Finder to close all windows for the volume
            logger.lifecycle("🔒 Closing Finder windows...")
            execOps.exec {
                commandLine("osascript", "-e", "tell application \"Finder\" to close every window")
                isIgnoreExitValue = true
            }
            Thread.sleep(1000)
        } finally {
            // Unmount with retries
            logger.lifecycle("💿 Unmounting DMG...")
            var unmounted = false
            var attempts = 0
            val maxAttempts = 5

            while (!unmounted && attempts < maxAttempts) {
                attempts++
                try {
                    if (attempts > 1) {
                        logger.lifecycle("   Retry attempt $attempts/$maxAttempts...")
                        Thread.sleep(2000)
                    }

                    execOps.exec {
                        commandLine("hdiutil", "detach", mountPath, "-force")
                        isIgnoreExitValue = false
                    }
                    unmounted = true
                    logger.lifecycle("✅ DMG unmounted successfully")
                } catch (_: Exception) {
                    if (attempts < maxAttempts) {
                        logger.lifecycle("⚠️  Unmount failed, will retry...")
                        // Kill any processes that might be using the volume
                        execOps.exec {
                            commandLine("lsof", "+D", mountPath)
                            isIgnoreExitValue = true
                            standardOutput = System.out
                        }
                    } else {
                        logger.warn("⚠️  Could not unmount DMG after $maxAttempts attempts, continuing anyway...")
                    }
                }
            }
        }

        // Convert to compressed, read-only DMG
        val signedDmg = File(notarizedDir, "Askimo-signed.dmg").apply { delete() }
        logger.lifecycle("📀 Creating final compressed DMG...")
        execOps.exec {
            commandLine(
                "hdiutil",
                "convert",
                tempDmg.absolutePath,
                "-format",
                "UDZO",
                "-o",
                signedDmg.absolutePath,
            )
        }

        // Clean up
        tempDmg.delete()
        dmgStaging.deleteRecursively()

        // Sign DMG
        logger.lifecycle("🔧 Signing DMG...")
        execOps.exec {
            val cmd =
                buildList {
                    add("codesign")
                    if (keychainPath != null) {
                        add("--keychain")
                        add(keychainPath)
                    }
                    add("--force")
                    add("--sign")
                    add(macosIdentity)
                    add("--timestamp")
                    add(signedDmg.absolutePath)
                }
            commandLine(cmd)
        }

        // Verify DMG signature
        logger.lifecycle("🔎 Verifying DMG signature...")
        execOps.exec {
            commandLine("codesign", "--verify", "--verbose=2", signedDmg.absolutePath)
        }

        logger.lifecycle("✅ DMG created and signed: ${signedDmg.absolutePath}")
    }
}

/**
 * Notarize the DMG with Apple
 */
tasks.register("customNotarizeDmg") {
    group = "distribution"
    description = "Notarize the signed DMG with Apple's notarization service (custom implementation)"

    dependsOn("createSignedDmg")

    @Suppress("DEPRECATION")
    doLast {
        val notarizedDir = file("${layout.buildDirectory.get()}/compose/notarized")
        val signedDmg = File(notarizedDir, "Askimo-signed.dmg")

        if (!signedDmg.exists()) {
            throw GradleException("Signed DMG not found: ${signedDmg.absolutePath}")
        }

        // Notarization credentials - App-Specific Password
        val appleId =
            getEnvOrProperty("APPLE_ID")
                ?: throw GradleException("APPLE_ID environment variable is required")
        val applePassword =
            getEnvOrProperty("APPLE_PASSWORD")
                ?: throw GradleException("APPLE_PASSWORD environment variable is required (use app-specific password)")
        val appleTeamId =
            getEnvOrProperty("APPLE_TEAM_ID")
                ?: throw GradleException("APPLE_TEAM_ID environment variable is required")

        val notaryArgs =
            listOf(
                "--apple-id",
                appleId,
                "--team-id",
                appleTeamId,
                "--password",
                applePassword,
            )

        logger.lifecycle("🔐 Notarizing DMG...")

        // Submit for notarization
        val notarizationOutput =
            ByteArrayOutputStream().use { output ->
                execOps.exec {
                    commandLine(
                        "xcrun",
                        "notarytool",
                        "submit",
                        signedDmg.absolutePath,
                        *notaryArgs.toTypedArray(),
                        "--wait",
                        "--output-format",
                        "json",
                    )
                    standardOutput = output
                }
                output.toString()
            }

        logger.lifecycle(notarizationOutput)

        // Parse JSON response (simple parsing)
        val statusMatch = Regex(""""status"\s*:\s*"([^"]+)"""").find(notarizationOutput)
        val idMatch = Regex(""""id"\s*:\s*"([^"]+)"""").find(notarizationOutput)

        val status = statusMatch?.groupValues?.get(1) ?: "Unknown"
        val submissionId = idMatch?.groupValues?.get(1) ?: ""

        logger.lifecycle("📋 Status: $status  (id: $submissionId)")

        if (status != "Accepted") {
            // Fetch the detailed log from Apple so we can see exactly which file was rejected
            if (submissionId.isNotEmpty()) {
                logger.lifecycle("📋 Fetching notarization log for submission $submissionId...")
                val logOutput =
                    ByteArrayOutputStream().use { output ->
                        execOps.exec {
                            commandLine(
                                "xcrun",
                                "notarytool",
                                "log",
                                submissionId,
                                *notaryArgs.toTypedArray(),
                            )
                            standardOutput = output
                            isIgnoreExitValue = true
                        }
                        output.toString()
                    }
                logger.lifecycle("📋 Notarization log:\n$logOutput")
            }
            throw GradleException("❌ Notarization failed (status=$status)")
        }

        // Wait for ticket propagation and staple with retries
        stapleWithRetry(signedDmg)

        logger.lifecycle("✅ Done. Output: ${signedDmg.absolutePath}")
        logger.lifecycle("")
        logger.lifecycle("Verify with:")
        logger.lifecycle("  spctl -a -t open --context context:primary-signature -v \"${signedDmg.absolutePath}\"")
    }
}

/**
 * Complete notarization workflow - notarizes both .app and DMG
 */
tasks.register("customNotarizeMacApp") {
    group = "distribution"
    description = "Complete macOS code signing and notarization workflow (signs app, notarizes app, creates DMG, notarizes DMG)"

    dependsOn("customNotarizeDmg")

    doLast {
        val notarizedDir = file("${layout.buildDirectory.get()}/compose/notarized")
        val signedDmg = File(notarizedDir, "Askimo-signed.dmg")

        logger.lifecycle("✅ Notarization complete!")
        logger.lifecycle("📦 Notarized DMG: ${signedDmg.absolutePath}")
        logger.lifecycle("")
        logger.lifecycle("You can now distribute this DMG to users.")
        logger.lifecycle("When they mount it, they will see:")
        logger.lifecycle("  - Askimo.app")
        logger.lifecycle("  - Applications folder (for drag-and-drop installation)")
        logger.lifecycle("")
        logger.lifecycle("The app will launch without security warnings.")
    }
}

/**
 * CI-only task: wraps the notarized + stapled DMG inside a tar archive so that
 * macOS extended attributes (including the notarization staple ticket stored as
 * com.apple.stapler-ticket) survive the GitHub Actions artifact upload/download
 * cycle.  actions/upload-artifact zips files internally which strips xattrs;
 * wrapping in tar first preserves them end-to-end.
 *
 * Output: build/compose/notarized/Askimo-signed.dmg.tar
 *
 * Local usage: no need to run this — use customNotarizeMacApp directly.
 * CI usage:    ./gradlew desktop:packageNotarizedDmgForCI
 *              Then upload build/compose/notarized/Askimo-signed.dmg.tar as the artifact.
 *              In the release job, extract with: tar --xattrs --xattrs-include='*' -xf ...
 */
tasks.register("packageNotarizedDmgForCI") {
    group = "distribution"
    description = "Wraps the notarized DMG in a tar archive to preserve the staple ticket xattr through GitHub Actions artifact upload"

    dependsOn("customNotarizeMacApp")

    @Suppress("DEPRECATION")
    doLast {
        val notarizedDir = file("${layout.buildDirectory.get()}/compose/notarized")
        val signedDmg = File(notarizedDir, "Askimo-signed.dmg")
        val tarFile = File(notarizedDir, "Askimo-signed.dmg.tar")

        if (!signedDmg.exists()) {
            throw GradleException("Notarized DMG not found: ${signedDmg.absolutePath}")
        }

        tarFile.delete()

        // Prefer GNU tar (gtar) which has reliable --xattrs support on macOS.
        // GitHub-hosted macOS runners have gnu-tar pre-installed as 'gtar'.
        val tarCmd =
            listOf("gtar", "tar").firstOrNull { cmd ->
                val result =
                    execOps.exec {
                        commandLine("which", cmd)
                        isIgnoreExitValue = true
                        standardOutput = ByteArrayOutputStream()
                        errorOutput = ByteArrayOutputStream()
                    }
                result.exitValue == 0
            } ?: throw GradleException("Neither 'gtar' nor 'tar' found on PATH")

        logger.lifecycle("📦 Wrapping DMG in tar (using $tarCmd) to preserve staple ticket xattr...")
        execOps.exec {
            commandLine(
                tarCmd,
                "--xattrs",
                "--xattrs-include=*",
                "-cf",
                tarFile.absolutePath,
                "-C",
                notarizedDir.absolutePath,
                signedDmg.name,
            )
        }

        // Verify the xattr round-trips correctly inside the tar
        val verifyOut = ByteArrayOutputStream()
        execOps.exec {
            commandLine(tarCmd, "--xattrs", "--xattrs-include=*", "-tvf", tarFile.absolutePath)
            standardOutput = verifyOut
            isIgnoreExitValue = true
        }
        logger.lifecycle("📋 Tar contents:\n$verifyOut")

        logger.lifecycle("✅ CI-ready tar: ${tarFile.absolutePath} (${tarFile.length() / 1_048_576} MB)")
        logger.lifecycle("")
        logger.lifecycle("Upload this tar as the artifact. In the release job, extract with:")
        logger.lifecycle("  tar --xattrs --xattrs-include='*' -xf Askimo-signed.dmg.tar")
        logger.lifecycle("Then verify:")
        logger.lifecycle("  xcrun stapler validate Askimo-signed.dmg")
    }
}
