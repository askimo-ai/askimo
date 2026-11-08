plugins {
    alias(libs.plugins.spotless)
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.graalvm.native) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.license.report) apply false
    alias(libs.plugins.kover)
}

group = property("projectGroup") as String
version = property("projectVersion") as String

extensions.extraProperties["spotlessSetLicenseHeaderYearsFromGitHistory"] = true

val ratchetRef = providers.gradleProperty("spotlessRatchetFrom").orElse("origin/main").get()

spotless {
    ratchetFrom(ratchetRef)
    json {
        target("**/*.json")
        targetExclude("**/build/**")
        jackson()
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("html") {
        target("**/*.html")
        targetExclude("**/build/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("javascript") {
        target("**/*.js")
        targetExclude("**/build/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("css") {
        target("**/*.css")
        targetExclude("**/build/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// ── Third-party dependency license report ─────────────────────────────────
// The jk1 plugin is applied per-subproject (rather than aggregated at the
// root via `licenseReport.projects`) because resolving other projects'
// dependency configurations from a root-level task is flagged as "unsafe
// configuration resolution" when `org.gradle.parallel=true` (see
// gradle.properties). Each subproject therefore generates its own report
// under `<subproject>/build/reports/dependency-license/`, and the root tasks
// below aggregate them.
//
// Usage:
//   ./gradlew generateLicenseReport          — regenerate every subproject's report
//   ./gradlew checkThirdPartyLicenses        — fail if any subproject uses a
//                                               license not in gradle/allowed-licenses.json
//   ./gradlew collectThirdPartyNotices        — merge all reports into
//                                               THIRD-PARTY-NOTICES.txt at the repo root

subprojects {
    plugins.apply("com.github.jk1.dependency-license-report")

    configure<com.github.jk1.license.LicenseReportExtension> {
        renderers = arrayOf(
            com.github.jk1.license.render.InventoryHtmlReportRenderer("licenses.html", "Askimo Third-Party Licenses"),
            com.github.jk1.license.render.TextReportRenderer("THIRD-PARTY-NOTICES.txt"),
        )
        // Normalizes differently-worded but equivalent license names (e.g. "Apache 2.0"
        // vs "The Apache Software License, Version 2.0") so the allow-list stays small.
        filters = arrayOf(com.github.jk1.license.filter.LicenseBundleNormalizer())
        allowedLicensesFile = rootProject.file("gradle/allowed-licenses.json")
    }
}

tasks.register("checkThirdPartyLicenses") {
    group = "verification"
    description = "Runs checkLicense on every subproject; fails if a dependency uses a disallowed license."
    dependsOn(subprojects.mapNotNull { it.tasks.findByName("checkLicense") })
}

// Third-party software that Askimo bundles/downloads at runtime but that never appears as a
// Gradle dependency (so the jk1 license-report plugin — which only inspects resolved JVM
// dependency graphs — can never discover it on its own). Each entry here is merged into
// THIRD-PARTY-NOTICES.txt by collectThirdPartyNotices alongside the auto-detected ones below.
// Add a new entry whenever Askimo starts bundling/downloading another non-Gradle third-party
// binary (e.g. a new native tool), so attribution stays accurate without relying on someone
// remembering to hand-edit the generated file (which collectThirdPartyNotices overwrites wholesale).
data class ManualThirdPartyNotice(
    val name: String,
    val version: String,
    val licenses: Set<String>,
    val urls: List<String>,
)

// Not a Gradle dependency: see that file's doc comments for the mirrored
// release URL/tag this notice should be kept in sync with.
val manualThirdPartyNotices = listOf(
    ManualThirdPartyNotice(
        name = "llama.cpp (ggml-org)",
        version = "b11433",
        licenses = setOf("MIT License"),
        urls = listOf("https://github.com/ggml-org/llama.cpp"),
    ),
    ManualThirdPartyNotice(
        name = "whisper.cpp (ggerganov)",
        version = "unpinned", // bundled binaries predate release-tag tracking; see note above
        licenses = setOf("MIT License"),
        urls = listOf("https://github.com/ggerganov/whisper.cpp"),
    ),
    ManualThirdPartyNotice(
        name = "whisper.cpp ggml model weights (ggerganov)",
        version = "ggml-base / ggml-small / ggml-large-v3",
        licenses = setOf("MIT License"),
        urls = listOf("https://huggingface.co/ggerganov/whisper.cpp"),
    ),
)

// `compose.desktop.currentOs` resolves only the host's skiko-awt-runtime-<os>-<arch> jar, so the
// notices file would otherwise differ by build machine. Strip the suffix to keep it deterministic.
val platformSpecificArtifactSuffix = Regex("-(macos|windows|linux)-(x64|arm64|x86)$")

fun canonicalDependencyName(rawName: String): String = rawName.replace(platformSpecificArtifactSuffix, "")

tasks.register("collectThirdPartyNotices") {
    group = "documentation"
    description = "Merges every subproject's resolved dependency licenses, plus manually-tracked " +
        "non-Gradle third-party binaries (see manualThirdPartyNotices), into one deduplicated " +
        "THIRD-PARTY-NOTICES.txt at the repo root."
    dependsOn(subprojects.mapNotNull { it.tasks.findByName("generateLicenseReport") })
    dependsOn(subprojects.mapNotNull { it.tasks.findByName("checkLicense") })
    outputs.upToDateWhen { false }

    doLast {
        data class Dep(val name: String, val version: String, val licenses: Set<String>, val urls: List<String>)

        val merged = sortedMapOf<String, Dep>()

        manualThirdPartyNotices.forEach { manual ->
            merged["${manual.name}:${manual.version}"] = Dep(manual.name, manual.version, manual.licenses, manual.urls)
        }

        subprojects.forEach { sub ->
            val jsonFile = sub.layout.buildDirectory
                .file("reports/dependency-license/project-licenses-for-check-license-task.json")
                .get().asFile
            if (!jsonFile.exists()) return@forEach

            @Suppress("UNCHECKED_CAST")
            val data = groovy.json.JsonSlurper().parse(jsonFile) as Map<String, Any>
            val deps = data["dependencies"] as? List<Map<String, Any>> ?: emptyList()

            deps.forEach { d ->
                val rawName = d["moduleName"] as? String ?: return@forEach
                val name = canonicalDependencyName(rawName)
                val version = d["moduleVersion"] as? String ?: ""
                val urls = (d["moduleUrls"] as? List<String>) ?: emptyList()
                val licenses = ((d["moduleLicenses"] as? List<Map<String, Any>>) ?: emptyList())
                    .mapNotNull { it["moduleLicense"] as? String }
                    .toSet()

                val key = "$name:$version"
                val existing = merged[key]
                merged[key] = if (existing == null) {
                    Dep(name, version, licenses, urls)
                } else {
                    existing.copy(licenses = existing.licenses + licenses, urls = (existing.urls + urls).distinct())
                }
            }
        }

        val out = StringBuilder()
        out.appendLine("Askimo — Third-Party Software Notices")
        out.appendLine(
            "This file lists every third-party dependency bundled in Askimo's distributed " +
                "artifacts (CLI, desktop app) across all modules, deduplicated, along with its license.",
        )
        out.appendLine("=".repeat(80))
        out.appendLine()
        out.appendLine("Total unique dependencies: ${merged.size}")
        out.appendLine()

        merged.values.forEach { dep ->
            out.appendLine("* ${dep.name}:${dep.version}")
            if (dep.urls.isNotEmpty()) out.appendLine("  URL: ${dep.urls.joinToString(", ")}")
            out.appendLine("  License(s): ${dep.licenses.sorted().joinToString(" OR ")}")
            out.appendLine()
        }

        rootDir.resolve("THIRD-PARTY-NOTICES.txt").writeText(out.toString())
        println("Wrote ${rootDir.resolve("THIRD-PARTY-NOTICES.txt")} (${merged.size} unique dependencies)")
    }
}

subprojects {
    plugins.apply("com.diffplug.spotless")

    // Applies detekt + Kotlin-aware spotless (ktlint, license header, etc.) to this subproject.
    // Shared across both the Kotlin/JVM and Kotlin Multiplatform plugin hooks below so that
    // multiplatform modules (e.g. :shared) get the same unused-import cleanup as JVM modules.
    val configureKotlinQualityTools: () -> Unit = {
        plugins.apply("dev.detekt")

        configure<dev.detekt.gradle.extensions.DetektExtension> {
            buildUponDefaultConfig = true
            config.setFrom(rootProject.file("detekt.yml"))
            dependencies {
                "detektPlugins"(project(":detekt-rules"))
            }
        }

        tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
            reports {
                html.required.set(true)
                sarif.required.set(true)
            }
            // Always re-analyse — never serve a cached report
            outputs.upToDateWhen { false }
            // Always succeed so SARIF is written even when findings exist.
            // The root-level `detekt` task reads SARIF and decides pass/fail.
            ignoreFailures = true
        }

        configure<com.diffplug.gradle.spotless.SpotlessExtension> {
            kotlin {
                target("src/**/*.kt")
                targetExclude("**/build/**")
                ktlint().editorConfigOverride(
                    mapOf(
                        "ktlint_standard_no-unused-imports" to "enabled",
                    ),
                )
                licenseHeaderFile(
                    rootProject.file("HEADER-SRC"),
                    "(package|import|@file:)",
                )
                trimTrailingWhitespace()
                leadingTabsToSpaces(4)
                endWithNewline()
            }
            kotlinGradle {
                ktlint()
                trimTrailingWhitespace()
                leadingTabsToSpaces(4)
                endWithNewline()
            }
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") { configureKotlinQualityTools() }
    plugins.withId("org.jetbrains.kotlin.multiplatform") { configureKotlinQualityTools() }
}

// ── Kover test coverage ────────────────────────────────────────────────────
// Every Kotlin module except :detekt-rules (a build-tooling module with no test
// source — measuring it would just dilute the real numbers) gets Kover applied
// and configured to exclude SQLDelight-generated code (it's never hand-written,
// so its "coverage" is meaningless noise). The root project is the Kover
// "merging module": it depends on every module via `kover(project(":x"))`, so
// `:koverXmlReport` / `:koverHtmlReport` run at the root produce one genuine,
// Kover-native merged report in addition to each module's own per-module report.
//
// Usage:
//   ./gradlew coverageReport     — run tests, merge coverage, print/HTML summary
//   ./gradlew koverHtmlReport    — just the root merged HTML (reports/kover/html)

val koverExcludedPackages = listOf("io.askimo.core.db.sqldelight", "io.askimo.core.db.sqldelight.generated")

subprojects {
    if (name == "detekt-rules") return@subprojects

    val configureKoverForModule: () -> Unit = {
        plugins.apply("org.jetbrains.kotlinx.kover")

        configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
            reports {
                filters {
                    excludes {
                        packages(koverExcludedPackages)
                    }
                }
            }
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") { configureKoverForModule() }
    plugins.withId("org.jetbrains.kotlin.multiplatform") { configureKoverForModule() }
}

kover {
    reports {
        filters {
            excludes {
                packages(koverExcludedPackages)
            }
        }
    }
}

dependencies {
    subprojects.filter { it.name != "detekt-rules" }.forEach { sub ->
        add("kover", project(sub.path))
    }
}

// ── Detekt aggregated reports ─────────────────────────────────────────────
// Usage:
//   ./gradlew detekt            — analyse all modules, write merged HTML, fail if errors
//   ./gradlew openDetektReport  — open the merged HTML in the browser

/** Builds a single-page HTML from a list of detekt findings. */
fun buildDetektHtml(
    findings: List<Map<String, String>>,
    errorCount: Int,
    warningCount: Int,
): String {
    fun badge(count: Int, color: String) =
        """<span style="background:$color;color:#fff;padding:2px 10px;border-radius:12px;font-weight:bold;">$count</span>"""

    fun section(title: String, color: String, items: List<Map<String, String>>) = buildString {
        if (items.isEmpty()) return@buildString
        val byRule = items.groupBy { it["rule"] ?: "unknown" }.toSortedMap()
        append("""<h2 style="color:$color;margin-top:32px">$title (${items.size})</h2>""")
        byRule.forEach { (rule, ruleItems) ->
            append("""<details open><summary style="cursor:pointer;font-weight:bold;padding:6px 0">""")
            append("""[$rule] &nbsp;${badge(ruleItems.size, color)}</summary>""")
            append("""<table style="width:100%;border-collapse:collapse;margin:8px 0 16px 0">""")
            append("""<tr style="background:#f0f0f0"><th style="text-align:left;padding:6px 8px">File</th>""")
            append("""<th style="text-align:center;padding:6px 8px;width:60px">Line</th>""")
            append("""<th style="text-align:left;padding:6px 8px">Message</th></tr>""")
            ruleItems.forEach { f ->
                val file = f["file"] ?: ""
                val line = f["line"] ?: "?"
                val msg = f["message"] ?: ""
                append("""<tr style="border-top:1px solid #ddd">""")
                append("""<td style="padding:5px 8px;font-family:monospace;font-size:13px">$file</td>""")
                append("""<td style="padding:5px 8px;text-align:center">$line</td>""")
                append("""<td style="padding:5px 8px;font-size:13px">$msg</td></tr>""")
            }
            append("</table></details>")
        }
    }

    return """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<title>Detekt Report</title>
<style>body{font-family:sans-serif;max-width:1200px;margin:0 auto;padding:24px}
summary::-webkit-details-marker{color:#888}</style>
</head><body>
<h1>Detekt Aggregated Report</h1>
<p>Generated: ${java.time.LocalDateTime.now()}</p>
<div style="display:flex;gap:24px;margin:16px 0">
  <div style="padding:16px 24px;background:#fff0f0;border-radius:8px;text-align:center">
    <div style="font-size:36px;font-weight:bold;color:#cc0000">$errorCount</div>
    <div style="color:#666">Errors</div>
  </div>
  <div style="padding:16px 24px;background:#fffbe6;border-radius:8px;text-align:center">
    <div style="font-size:36px;font-weight:bold;color:#b8860b">$warningCount</div>
    <div style="color:#666">Warnings</div>
  </div>
  <div style="padding:16px 24px;background:#f0f0f0;border-radius:8px;text-align:center">
    <div style="font-size:36px;font-weight:bold;color:#333">${errorCount + warningCount}</div>
    <div style="color:#666">Total</div>
  </div>
</div>
${section("Errors", "#cc0000", findings.filter { it["severity"] == "error" })}
${section("Warnings", "#b8860b", findings.filter { it["severity"] == "warning" })}
${if (findings.isEmpty()) "<p style='color:#008800;font-size:20px'>✅ No findings — clean code!</p>" else ""}
</body></html>"""
}

/** Parses a SARIF file produced by detekt 2.x and returns a list of finding maps. */
@Suppress("UNCHECKED_CAST")
fun parseDetektSarif(sarifFile: java.io.File): List<Map<String, String>> {
    if (!sarifFile.exists()) return emptyList()
    return try {
        val sarif = groovy.json.JsonSlurper().parse(sarifFile) as Map<String, Any>
        val runs = sarif["runs"] as? List<Map<String, Any>> ?: return emptyList()
        runs.flatMap { run ->
            val results = run["results"] as? List<Map<String, Any>> ?: emptyList()
            results.map { result ->
                val ruleId = result["ruleId"] as? String ?: "unknown"
                val level  = result["level"]  as? String ?: "warning"
                val message = (result["message"] as? Map<String, Any>)?.get("text") as? String ?: ""
                val location = (result["locations"] as? List<Map<String, Any>>)?.firstOrNull()
                val physLoc  = location?.get("physicalLocation") as? Map<String, Any>
                val uri  = (physLoc?.get("artifactLocation") as? Map<String, Any>)?.get("uri") as? String ?: ""
                val line = (physLoc?.get("region") as? Map<String, Any>)?.get("startLine")?.toString() ?: "?"
                mapOf(
                    "severity" to if (level == "error") "error" else "warning",
                    "rule"     to ruleId.substringAfterLast("/").substringAfterLast("."),
                    "file"     to uri.removePrefix("file://").substringAfter("/kotlin/").ifBlank { uri },
                    "line"     to line,
                    "message"  to message,
                )
            }
        }
    } catch (e: Exception) {
        logger.warn("Could not parse SARIF ${sarifFile.path}: ${e.message}")
        emptyList()
    }
}

// Root-level `detekt` task — single command that runs analysis on every subproject,
// collects all SARIF reports, generates a single HTML, prints a summary, and fails if errors.
// Usage: ./gradlew detekt
tasks.register("detekt") {
    group = "verification"
    description = "Runs detekt on all subprojects, generates a merged report, and fails if errors exist."

    dependsOn(subprojects.mapNotNull { sub -> sub.tasks.findByName("detekt") })
    outputs.upToDateWhen { false }

    doLast {
        val reportDir = layout.buildDirectory.dir("reports/detekt").get().asFile.also { it.mkdirs() }

        // Rules that must always fail the build when found — regardless of the severity
        // Detekt itself reports (custom rules are always reported as "warning", and 2.x
        // no longer lets detekt.yml override per-rule severity).
        val hardStopRules = setOf(
            "UnsafeLaunchInStreamingCallback",
            "RememberCoroutineScopeInConditionalComposable",
        )

        // ── 1. Collect findings from every subproject SARIF report ─────────
        val findings = subprojects.flatMap { sub ->
            val sarif = sub.layout.buildDirectory.file("reports/detekt/detekt.sarif").get().asFile
            parseDetektSarif(sarif)
        }
        val errors   = findings.filter { it["severity"] == "error" || it["rule"] in hardStopRules }
        val warnings = findings.filter { it["severity"] != "error" && it["rule"] !in hardStopRules }

        // ── 2. Generate merged HTML report ─────────────────────────────────
        val htmlFile = reportDir.resolve("detekt.html")
        htmlFile.writeText(buildDetektHtml(findings, errors.size, warnings.size))

        // ── 3. Print console summary ───────────────────────────────────────
        println("\n======================================================")
        println("  Detekt Aggregated Report Summary")
        println("======================================================")
        println("  Errors   : ${errors.size}")
        println("  Warnings : ${warnings.size}")
        println("  Total    : ${findings.size}")
        println("======================================================\n")

        if (errors.isNotEmpty()) {
            println("--- ERRORS (${errors.size}) ---")
            errors.groupBy { it["rule"] ?: "?" }.toSortedMap().forEach { (rule, items) ->
                println("  [$rule] x${items.size}")
                items.take(3).forEach { println("    ${it["file"]}:${it["line"]}") }
                if (items.size > 3) println("    ... and ${items.size - 3} more")
            }
            println()
        }

        if (warnings.isNotEmpty()) {
            println("--- WARNINGS (${warnings.size}) ---")
            warnings.groupBy { it["rule"] ?: "?" }.toSortedMap().forEach { (rule, items) ->
                println("  [$rule] x${items.size}")
            }
            println()
        }

        println("Report: file://${htmlFile.absolutePath}")

        if (errors.isNotEmpty()) {
            throw GradleException("Detekt found ${errors.size} error(s) that must be fixed. See report above.")
        }
    }
}

// ── Kover coverage aggregation report ──────────────────────────────────────
// Usage:
//   ./gradlew coverageReport  — run every module's tests, merge coverage, write a
//                               combined HTML dashboard (root total + per-module
//                               breakdown), and print a console summary table.

/** One module/project's (or the merged total's) coverage counters, in percent. */
data class CoverageStat(
    val name: String,
    val lineCoveredPct: Double,
    val linesCovered: Int,
    val linesMissed: Int,
    val branchCoveredPct: Double,
    val branchesCovered: Int,
    val branchesMissed: Int,
)

/** Parses a Kover-generated, JaCoCo-compatible XML report and returns its top-level LINE/BRANCH counters. */
@Suppress("UNCHECKED_CAST")
fun parseKoverXmlCounters(name: String, xmlFile: java.io.File): CoverageStat? {
    if (!xmlFile.exists()) return null
    return try {
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            // The JaCoCo XML report references a DTD by URL; disable fetching/validating it.
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isValidating = false
        }.newDocumentBuilder().parse(xmlFile)

        var linesCovered = 0
        var linesMissed = 0
        var branchesCovered = 0
        var branchesMissed = 0

        val counters = doc.documentElement.childNodes
        for (i in 0 until counters.length) {
            val node = counters.item(i)
            if (node.nodeName != "counter") continue
            val attrs = node.attributes
            val type = attrs.getNamedItem("type")?.textContent ?: continue
            val missed = attrs.getNamedItem("missed")?.textContent?.toIntOrNull() ?: 0
            val covered = attrs.getNamedItem("covered")?.textContent?.toIntOrNull() ?: 0
            when (type) {
                "LINE" -> { linesMissed = missed; linesCovered = covered }
                "BRANCH" -> { branchesMissed = missed; branchesCovered = covered }
            }
        }

        fun pct(covered: Int, missed: Int) = if (covered + missed == 0) 0.0 else (covered * 100.0) / (covered + missed)

        CoverageStat(
            name = name,
            lineCoveredPct = pct(linesCovered, linesMissed),
            linesCovered = linesCovered,
            linesMissed = linesMissed,
            branchCoveredPct = pct(branchesCovered, branchesMissed),
            branchesCovered = branchesCovered,
            branchesMissed = branchesMissed,
        )
    } catch (e: Exception) {
        logger.warn("Could not parse Kover XML ${xmlFile.path}: ${e.message}")
        null
    }
}

/** Builds a single-page HTML coverage dashboard from the merged total plus each module's own stat. */
fun buildCoverageHtml(total: CoverageStat?, perModule: List<CoverageStat>): String {
    fun barColor(pct: Double) = when {
        pct >= 80.0 -> "#2e7d32"
        pct >= 50.0 -> "#b8860b"
        else -> "#cc0000"
    }

    fun row(stat: CoverageStat) = """
        <tr style="border-top:1px solid #ddd">
            <td style="padding:6px 8px;font-weight:bold">${stat.name}</td>
            <td style="padding:6px 8px;text-align:center;color:${barColor(stat.lineCoveredPct)};font-weight:bold">
                ${"%.1f".format(stat.lineCoveredPct)}%
            </td>
            <td style="padding:6px 8px;text-align:center">${stat.linesCovered} / ${stat.linesCovered + stat.linesMissed}</td>
            <td style="padding:6px 8px;text-align:center;color:${barColor(stat.branchCoveredPct)};font-weight:bold">
                ${"%.1f".format(stat.branchCoveredPct)}%
            </td>
            <td style="padding:6px 8px;text-align:center">${stat.branchesCovered} / ${stat.branchesCovered + stat.branchesMissed}</td>
        </tr>
    """.trimIndent()

    val totalBlock = total?.let {
        """
        <div style="display:flex;gap:24px;margin:16px 0">
          <div style="padding:16px 24px;background:#f0f0f0;border-radius:8px;text-align:center">
            <div style="font-size:36px;font-weight:bold;color:${barColor(it.lineCoveredPct)}">${"%.1f".format(it.lineCoveredPct)}%</div>
            <div style="color:#666">Line coverage (total)</div>
          </div>
          <div style="padding:16px 24px;background:#f0f0f0;border-radius:8px;text-align:center">
            <div style="font-size:36px;font-weight:bold;color:${barColor(it.branchCoveredPct)}">${"%.1f".format(it.branchCoveredPct)}%</div>
            <div style="color:#666">Branch coverage (total)</div>
          </div>
        </div>
        """.trimIndent()
    } ?: "<p style='color:#cc0000'>No merged report found — run <code>./gradlew koverXmlReport</code> first.</p>"

    return """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<title>Coverage Report</title>
<style>body{font-family:sans-serif;max-width:1200px;margin:0 auto;padding:24px}
table{width:100%;border-collapse:collapse;margin:16px 0}
th{text-align:left;padding:6px 8px;background:#f0f0f0}</style>
</head><body>
<h1>Askimo Coverage Report</h1>
<p>Generated: ${java.time.LocalDateTime.now()}</p>
$totalBlock
<h2>Per-module breakdown</h2>
<table>
<tr><th>Module</th><th style="text-align:center">Line %</th><th style="text-align:center">Lines</th>
<th style="text-align:center">Branch %</th><th style="text-align:center">Branches</th></tr>
${perModule.sortedBy { it.name }.joinToString("\n") { row(it) }}
</table>
</body></html>"""
}

tasks.register("coverageReport") {
    group = "verification"
    description = "Runs tests + Kover on every module, merges coverage, and writes a combined HTML dashboard."

    dependsOn("koverXmlReport")
    dependsOn(subprojects.mapNotNull { sub -> sub.tasks.findByName("koverXmlReport") })
    outputs.upToDateWhen { false }

    doLast {
        val reportDir = layout.buildDirectory.dir("reports/kover").get().asFile.also { it.mkdirs() }

        // ── 1. Root-level merged "total" report (Kover-native, spans all modules) ──
        val totalXml = layout.buildDirectory.file("reports/kover/report.xml").get().asFile
        val total = parseKoverXmlCounters("TOTAL (merged)", totalXml)

        // ── 2. Each module's own per-module report, for the breakdown table ────────
        val perModule = subprojects.mapNotNull { sub ->
            val xml = sub.layout.buildDirectory.file("reports/kover/report.xml").get().asFile
            parseKoverXmlCounters(sub.name, xml)
        }

        // ── 3. Combined HTML dashboard ──────────────────────────────────────────────
        val htmlFile = reportDir.resolve("coverage-summary.html")
        htmlFile.writeText(buildCoverageHtml(total, perModule))

        // ── 4. Console summary ──────────────────────────────────────────────────────
        println("\n======================================================")
        println("  Coverage Report Summary")
        println("======================================================")
        if (total != null) {
            println("  TOTAL line coverage   : ${"%.1f".format(total.lineCoveredPct)}% (${total.linesCovered}/${total.linesCovered + total.linesMissed})")
            println("  TOTAL branch coverage : ${"%.1f".format(total.branchCoveredPct)}% (${total.branchesCovered}/${total.branchesCovered + total.branchesMissed})")
        } else {
            println("  No merged report found.")
        }
        println("------------------------------------------------------")
        perModule.sortedBy { it.name }.forEach { s ->
            println("  %-18s line %6.1f%%   branch %6.1f%%".format(s.name, s.lineCoveredPct, s.branchCoveredPct))
        }
        println("======================================================\n")
        println("Report: file://${htmlFile.absolutePath}")
    }
}

// ── Coverage non-regression ratchet ────────────────────────────────────────
// gradle/coverage-baseline.json records the last-known-good line-coverage % for the
// merged total and each module. `checkCoverageRegression` re-reads the same XML reports
// coverageReport just produced and fails only if a number *drops* below baseline - tolerance
// — it never fails for an improvement, and it never ratchets itself (that's a separate,
// explicit, human-reviewed step via `updateCoverageBaseline`).
//
// Usage:
//   ./gradlew checkCoverageRegression   — fails the build if coverage dropped
//   ./gradlew updateCoverageBaseline    — overwrite the baseline with today's numbers
//                                         (run this deliberately after reviewing a genuine
//                                         improvement; never just to silence a failure)

val coverageBaselineFile = rootProject.file("gradle/coverage-baseline.json")

@Suppress("UNCHECKED_CAST")
fun readCoverageBaseline(): Pair<Double, Map<String, Double>> {
    if (!coverageBaselineFile.exists()) return 0.0 to emptyMap()
    val json = groovy.json.JsonSlurper().parse(coverageBaselineFile) as Map<String, Any>
    val total = ((json["total"] as? Map<String, Any>)?.get("linePct") as? Number)?.toDouble() ?: 0.0
    val modules = (json["modules"] as? Map<String, Any>)?.mapNotNull { (name, v) ->
        val pct = ((v as? Map<String, Any>)?.get("linePct") as? Number)?.toDouble() ?: return@mapNotNull null
        name to pct
    }?.toMap() ?: emptyMap()
    return total to modules
}

fun coverageTolerance(): Double {
    if (!coverageBaselineFile.exists()) return 1.0
    @Suppress("UNCHECKED_CAST")
    val json = groovy.json.JsonSlurper().parse(coverageBaselineFile) as Map<String, Any>
    return (json["toleranceLinePct"] as? Number)?.toDouble() ?: 1.0
}

tasks.register("checkCoverageRegression") {
    group = "verification"
    description = "Fails if merged or per-module line coverage dropped below gradle/coverage-baseline.json (minus tolerance)."
    dependsOn("coverageReport")
    outputs.upToDateWhen { false }

    doLast {
        val totalXml = layout.buildDirectory.file("reports/kover/report.xml").get().asFile
        val total = parseKoverXmlCounters("TOTAL (merged)", totalXml)
        val perModule = subprojects.mapNotNull { sub ->
            parseKoverXmlCounters(sub.name, sub.layout.buildDirectory.file("reports/kover/report.xml").get().asFile)
        }.associateBy { it.name }

        val (baselineTotal, baselineModules) = readCoverageBaseline()
        val tolerance = coverageTolerance()
        val regressions = mutableListOf<String>()

        if (total != null && total.lineCoveredPct < baselineTotal - tolerance) {
            regressions += "TOTAL: ${"%.1f".format(total.lineCoveredPct)}% < baseline ${"%.1f".format(baselineTotal)}% (-$tolerance tolerance)"
        }
        baselineModules.forEach { (name, baselinePct) ->
            val current = perModule[name]?.lineCoveredPct
            if (current != null && current < baselinePct - tolerance) {
                regressions += "$name: ${"%.1f".format(current)}% < baseline ${"%.1f".format(baselinePct)}% (-$tolerance tolerance)"
            }
        }

        if (regressions.isNotEmpty()) {
            println("\nCoverage regressions detected:")
            regressions.forEach { println("  - $it") }
            throw GradleException(
                "Line coverage dropped below baseline (gradle/coverage-baseline.json) for " +
                    "${regressions.size} target(s). See above. If this drop is expected/accepted, " +
                    "run ./gradlew updateCoverageBaseline after review.",
            )
        }
        println("Coverage regression check passed (no module dropped below its baseline).")
    }
}

tasks.register("updateCoverageBaseline") {
    group = "verification"
    description = "Overwrites gradle/coverage-baseline.json with the current coverageReport numbers. Run deliberately, not automatically."
    dependsOn("coverageReport")
    outputs.upToDateWhen { false }

    doLast {
        val totalXml = layout.buildDirectory.file("reports/kover/report.xml").get().asFile
        val total = parseKoverXmlCounters("TOTAL (merged)", totalXml)
        val perModule = subprojects.mapNotNull { sub ->
            parseKoverXmlCounters(sub.name, sub.layout.buildDirectory.file("reports/kover/report.xml").get().asFile)
        }

        val tolerance = coverageTolerance()
        val modulesJson = perModule.sortedBy { it.name }
            .joinToString(",\n") { """    "${it.name}": { "linePct": ${"%.1f".format(it.lineCoveredPct)} }""" }

        val json = """
            {
              "_comment": "Non-regression floor for ./gradlew coverageReport's checkCoverageRegression task. Line-coverage percentages only (branch is reported but not ratcheted, since Kover's branch counting can shift slightly across versions without any real code change). Update via `./gradlew updateCoverageBaseline` after a deliberate, reviewed improvement — never lower a number just to make the build pass.",
              "toleranceLinePct": $tolerance,
              "total": {
                "linePct": ${"%.1f".format(total?.lineCoveredPct ?: 0.0)}
              },
              "modules": {
            $modulesJson
              }
            }
        """.trimIndent() + "\n"

        coverageBaselineFile.writeText(json)
        println("Updated ${coverageBaselineFile.path}")
    }
}

