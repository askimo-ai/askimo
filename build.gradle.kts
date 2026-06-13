plugins {
    alias(libs.plugins.spotless)
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.graalvm.native) apply false
    alias(libs.plugins.detekt) apply false
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

subprojects {
    apply(plugin = "com.diffplug.spotless")

    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "io.gitlab.arturbosch.detekt")

        configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
            buildUponDefaultConfig = true
            config.setFrom(rootProject.file("detekt.yml"))
            dependencies {
                "detektPlugins"(project(":detekt-rules"))
            }
        }

        tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
            reports {
                xml.required.set(true)
                html.required.set(true)
                txt.required.set(false)
                sarif.required.set(false)
            }
            // Always re-analyse — never serve a cached report
            outputs.upToDateWhen { false }
            // Always succeed so XML is written even when findings exist.
            // The root-level `detekt` task reads the XML and decides pass/fail.
            ignoreFailures = true
        }

        configure<com.diffplug.gradle.spotless.SpotlessExtension> {
            kotlin {
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
}

// ── Detekt aggregated reports ─────────────────────────────────────────────
// Usage:
//   ./gradlew detekt            — analyse all modules, write merged XML + HTML, fail if errors
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

/** Parses a Checkstyle-format XML file produced by detekt and returns a list of finding maps. */
fun parseDetektXml(xmlFile: java.io.File): List<Map<String, String>> {
    if (!xmlFile.exists()) return emptyList()
    return try {
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder().parse(xmlFile)
        val nodes = doc.getElementsByTagName("error")
        (0 until nodes.length).map { i ->
            val node = nodes.item(i)
            val attrs = node.attributes
            val filePath = node.parentNode?.attributes?.getNamedItem("name")?.nodeValue ?: "unknown"
            mapOf(
                "severity" to (attrs.getNamedItem("severity")?.nodeValue ?: "unknown"),
                "rule"     to (attrs.getNamedItem("source")?.nodeValue?.substringAfterLast(".") ?: "unknown"),
                "file"     to filePath.substringAfter("/kotlin/").ifBlank { filePath },
                "line"     to (attrs.getNamedItem("line")?.nodeValue ?: "?"),
                "message"  to (attrs.getNamedItem("message")?.nodeValue ?: ""),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

// Root-level `detekt` task — single command that runs analysis on every subproject,
// merges all XML reports into one, generates a single HTML, prints a summary, and fails if errors.
// Usage: ./gradlew detekt
tasks.register("detekt") {
    group = "verification"
    description = "Runs detekt on all subprojects, generates a merged report, and fails if errors exist."

    dependsOn(subprojects.mapNotNull { sub -> sub.tasks.findByName("detekt") })
    outputs.upToDateWhen { false }

    doLast {
        // ── 1. Collect + merge all subproject XML reports ──────────────────
        val mergedXmlDir = layout.buildDirectory.dir("reports/detekt").get().asFile.also { it.mkdirs() }
        val mergedXmlFile = mergedXmlDir.resolve("detekt.xml")

        val fileBlocks = StringBuilder()
        subprojects.forEach { sub ->
            val xml = sub.layout.buildDirectory.file("reports/detekt/detekt.xml").get().asFile
            if (!xml.exists()) return@forEach
            try {
                val content = xml.readText()
                val fileRegex = Regex("<file[^/].*?</file>", RegexOption.DOT_MATCHES_ALL)
                fileRegex.findAll(content).forEach { fileBlocks.append(it.value).append("\n") }
            } catch (e: Exception) {
                logger.warn("Could not read ${xml.path}: ${e.message}")
            }
        }
        mergedXmlFile.writeText(
            """<?xml version="1.0" encoding="utf-8"?><checkstyle version="4.3">$fileBlocks</checkstyle>""",
        )

        // ── 2. Parse merged XML ────────────────────────────────────────────
        val findings = parseDetektXml(mergedXmlFile)
        val errors   = findings.filter { it["severity"] == "error" }
        val warnings = findings.filter { it["severity"] == "warning" }

        // ── 3. Generate single merged HTML report ──────────────────────────
        val htmlFile = mergedXmlDir.resolve("detekt.html")
        htmlFile.writeText(buildDetektHtml(findings, errors.size, warnings.size))

        // ── 4. Print console summary ───────────────────────────────────────
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

