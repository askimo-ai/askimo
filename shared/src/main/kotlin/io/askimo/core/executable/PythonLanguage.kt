/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.executable

import io.askimo.core.util.AskimoHome
import io.askimo.core.util.ProcessBuilderExt
import java.io.File

/**
 * Runnable language implementation for Python scripts.
 *
 * Uses a single Askimo-managed virtualenv at [ASKIMO_VENV_DIR] (~/.askimo/venv)
 * to avoid PEP 668 restrictions on system/Homebrew Python. The venv is created
 * automatically on first use. Third-party imports detected in the script are
 * installed into the venv before execution.
 *
 * Future: support user-configured environments (pyenv, conda, project venv)
 * as an opt-in preference.
 */
object PythonLanguage : RunnableLanguage(setOf("python", "python3", "py"), "python3") {

    /** Askimo-managed virtualenv directory. */
    private val ASKIMO_VENV_DIR get() = AskimoHome.rootBase().resolve("venv").toFile()

    /** Persistent requirements file — packages accumulate here across runs. */
    private val REQUIREMENTS_FILE get() = AskimoHome.rootBase().resolve("requirements.txt").toFile()

    /** Shared workspace directory — scripts write outputs here for chaining across runs. */
    private val ASKIMO_WORKSPACE_DIR get() = AskimoHome.rootBase().resolve("workspace").toFile()

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    /** Standard-library module names that must not be pip-installed. */
    private val STDLIB = setOf(
        "os", "sys", "re", "json", "math", "time", "datetime", "random", "io",
        "collections", "itertools", "functools", "pathlib", "typing", "abc",
        "copy", "enum", "logging", "threading", "subprocess", "hashlib",
        "base64", "urllib", "http", "socket", "struct", "csv", "string",
        "argparse", "unittest", "dataclasses", "contextlib", "traceback",
        "inspect", "operator", "shutil", "tempfile", "textwrap", "warnings",
        "weakref", "gc", "platform", "signal", "stat", "glob", "fnmatch",
    )

    override fun buildTerminalCommand(code: String): String {
        // Ensure workspace dir exists so scripts can write to it immediately
        if (!ASKIMO_WORKSPACE_DIR.exists()) ASKIMO_WORKSPACE_DIR.mkdirs()

        val tmpFile = File.createTempFile("askimo_", ".py").apply {
            writeText(code)
            deleteOnExit()
        }

        val thirdParty = extractThirdPartyImports(code)

        // Append any new third-party imports to the persistent requirements file
        if (thirdParty.isNotEmpty()) {
            val existing = if (REQUIREMENTS_FILE.exists()) {
                REQUIREMENTS_FILE.readLines().map { it.trim() }.toSet()
            } else {
                REQUIREMENTS_FILE.parentFile?.mkdirs()
                emptySet()
            }
            val newPackages = thirdParty.filter { it !in existing }
            if (newPackages.isNotEmpty()) {
                REQUIREMENTS_FILE.appendText(newPackages.joinToString("\n", postfix = "\n"))
            }
        }

        // Bootstrap the venv on first use
        val bootstrapCmd = if (!ASKIMO_VENV_DIR.exists()) {
            val systemPython = ProcessBuilderExt.resolveCommand(listOf("python3")).first()
            "$systemPython -m venv ${ASKIMO_VENV_DIR.absolutePath} && "
        } else {
            ""
        }

        // Always run pip install -r so new entries are picked up; already-installed packages are skipped
        val installCmd = if (REQUIREMENTS_FILE.exists() && REQUIREMENTS_FILE.readText().isNotBlank()) {
            "${venvPip()} install -r ${REQUIREMENTS_FILE.absolutePath} --quiet --disable-pip-version-check && "
        } else {
            ""
        }

        // Inject ASKIMO_WORK_DIR so scripts always know where to read/write shared artifacts.
        // Since the terminal already starts in the workspace dir, scripts using relative paths
        // also land in the right place — ASKIMO_WORK_DIR makes it explicit and reliable.
        val env = "ASKIMO_WORK_DIR=${ASKIMO_WORKSPACE_DIR.absolutePath}"
        return "$bootstrapCmd$installCmd$env ${venvPython()} ${tmpFile.absolutePath}"
    }

    private fun venvPython(): String = File(ASKIMO_VENV_DIR, if (isWindows) "Scripts/python.exe" else "bin/python3").absolutePath

    private fun venvPip(): String = File(ASKIMO_VENV_DIR, if (isWindows) "Scripts/pip.exe" else "bin/pip").absolutePath

    /**
     * Scans [code] for import statements and returns the third-party module
     * names (i.e. those not in [STDLIB]).
     */
    private fun extractThirdPartyImports(code: String): List<String> = code.lines()
        .mapNotNull { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("import ") ->
                    trimmed.removePrefix("import ")
                        .substringBefore(" ")
                        .substringBefore(".")
                        .substringBefore(",")

                trimmed.startsWith("from ") ->
                    trimmed.removePrefix("from ")
                        .substringBefore(" ")
                        .substringBefore(".")

                else -> null
            }
        }
        .filter { it.isNotBlank() && !it.startsWith("_") && it !in STDLIB }
        .distinct()
}
