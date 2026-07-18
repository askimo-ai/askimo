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
    plugins.apply("com.diffplug.spotless")

    plugins.withId("org.jetbrains.kotlin.jvm") {
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

        // ── 1. Collect findings from every subproject SARIF report ─────────
        val findings = subprojects.flatMap { sub ->
            val sarif = sub.layout.buildDirectory.file("reports/detekt/detekt.sarif").get().asFile
            parseDetektSarif(sarif)
        }
        val errors   = findings.filter { it["severity"] == "error" }
        val warnings = findings.filter { it["severity"] == "warning" }

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

