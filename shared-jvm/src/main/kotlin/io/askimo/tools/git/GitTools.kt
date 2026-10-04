/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.tools.git

import dev.langchain4j.agent.tool.Tool
import io.askimo.core.util.ProcessBuilderExt
import io.askimo.tools.fs.LocalFsTools
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class GitTools {
    @Tool("Unified diff of staged changes (git diff --cached)")
    fun stagedDiff(args: List<String> = listOf("--no-color", "--unified=0", "--diff-algorithm=minimal")): String {
        val fullDiff = exec(listOf("git", "diff", "--cached") + args)
        return preprocessDiff(fullDiff)
    }

    @Tool("Concise git status (-sb)")
    fun status(): String = exec(listOf("git", "status", "-sb"))

    @Tool("Current branch name")
    fun branch(): String = exec(listOf("git", "rev-parse", "--abbrev-ref", "HEAD")).trim()

    @Tool("Write .git/COMMIT_EDITMSG and run git commit -F -")
    fun commit(
        message: String,
        signoff: Boolean = false,
        noVerify: Boolean = false,
        writeEditmsg: Boolean = true,
    ): String {
        // Optionally save commit message to file (mimics git's default behavior)
        if (writeEditmsg) LocalFsTools.writeFile(".git/COMMIT_EDITMSG", message)

        // Early check: no staged changes = fail fast
        val hasStaged = exec(listOf("git", "diff", "--cached", "--name-only")).isNotBlank()
        require(hasStaged) {
            "No staged changes. Run `git add` first."
        }

        val cmd =
            buildList {
                addAll(listOf("git", "commit"))
                if (noVerify) add("--no-verify")
                if (signoff) add("--signoff")
                addAll(listOf("-F", "-"))
            }

        return execWithStdinOrThrow(cmd, message)
    }

    private fun preprocessDiff(diff: String): String {
        val lines = diff.lines()
        val result = mutableListOf<String>()
        var currentFile: String? = null
        var isNewFile = false
        var isDeletedFile = false
        var addedLines = 0
        var deletedLines = 0
        val contentLines = mutableListOf<String>()

        fun flushCurrentFile() {
            currentFile?.let { file ->
                when {
                    isNewFile -> result.add("new file: $file")

                    isDeletedFile -> result.add("deleted file: $file")

                    else -> {
                        result.add("$file (+$addedLines -$deletedLines)")
                        // For modified files, include some context but limit to 20 lines
                        if (contentLines.size <= 20) {
                            result.addAll(contentLines)
                        } else {
                            result.addAll(contentLines.take(10))
                            result.add("... (${contentLines.size - 20} lines omitted) ...")
                            result.addAll(contentLines.takeLast(10))
                        }
                    }
                }
            }
        }

        for (line in lines) {
            when {
                line.startsWith("diff --git") -> {
                    flushCurrentFile()

                    // Extract filename from "diff --git a/file b/file"
                    currentFile = line.substringAfter("b/").takeIf { it.isNotBlank() }
                    isNewFile = false
                    isDeletedFile = false
                    addedLines = 0
                    deletedLines = 0
                    contentLines.clear()
                    contentLines.add(line)
                }

                line.startsWith("new file mode") -> {
                    isNewFile = true
                }

                line.startsWith("deleted file mode") -> {
                    isDeletedFile = true
                }

                line.startsWith("index ") || line.startsWith("---") || line.startsWith("+++") -> {
                    if (!isNewFile && !isDeletedFile) contentLines.add(line)
                }

                line.startsWith("@@") -> {
                    if (!isNewFile && !isDeletedFile) contentLines.add(line)
                }

                line.startsWith("+") && !line.startsWith("+++") -> {
                    addedLines++
                    if (!isNewFile && !isDeletedFile) contentLines.add(line)
                }

                line.startsWith("-") && !line.startsWith("---") -> {
                    deletedLines++
                    if (!isNewFile && !isDeletedFile) contentLines.add(line)
                }

                line.isNotBlank() -> {
                    if (!isNewFile && !isDeletedFile) contentLines.add(line)
                }
            }
        }

        // Flush the last file
        flushCurrentFile()

        return result.joinToString("\n")
    }

    private fun exec(cmd: List<String>): String {
        val p =
            ProcessBuilderExt(cmd)
                .redirectErrorStream(true)
                .start()
        val out = ByteArrayOutputStream()
        p.inputStream.copyTo(out)
        val code = p.waitFor()
        val text = out.toString(StandardCharsets.UTF_8)
        if (code != 0) {
            error("Command failed: ${cmd.joinToString(" ")} ($code)\n$text")
        }
        return text
    }

    @Suppress("ktlint:standard:max-line-length")
    private fun execWithStdinOrThrow(
        cmd: List<String>,
        input: String,
    ): String {
        val pb = ProcessBuilderExt(cmd).redirectErrorStream(true)
        val p = pb.start()
        p.outputStream.bufferedWriter().use { it.write(input) }
        val out = ByteArrayOutputStream()
        p.inputStream.copyTo(out)
        val code = p.waitFor()
        val text = out.toString(StandardCharsets.UTF_8).trim()

        if (code != 0) {
            val hints =
                buildString {
                    if (text.contains("nothing to commit", ignoreCase = true)) {
                        appendLine("Hint: Nothing to commit (no staged files?). Run `git add -A`.")
                    }
                    if (text.contains("pre-commit", ignoreCase = true)) {
                        appendLine("Hint: A pre-commit hook failed. Try fixing issues or run with noVerify=true.")
                    }
                    if (text.contains("gpg", ignoreCase = true) || text.contains("signing", ignoreCase = true)) {
                        appendLine("Hint: GPG signing failed. Configure GPG or disable signing with `git config commit.gpgsign false`.")
                    }
                    if (text.contains("merge", ignoreCase = true) && text.contains("in progress", ignoreCase = true)) {
                        appendLine(
                            "Hint: Merge/rebase in progress. Resolve conflicts or run `git merge --continue` / `git rebase --continue`.",
                        )
                    }
                    if (text.contains("user.name", ignoreCase = true) || text.contains("user.email", ignoreCase = true)) {
                        appendLine(
                            "Hint: Missing user identity. Run `git config user.name 'Your Name'` and `git config user.email you@example.com`.",
                        )
                    }
                }.trim()

            val msg =
                buildString {
                    appendLine("Command failed ($code): ${cmd.joinToString(" ")}")
                    if (text.isNotBlank()) {
                        appendLine("Output:")
                        appendLine(text)
                    }
                    if (hints.isNotBlank()) {
                        appendLine()
                        appendLine(hints)
                    }
                }.trim()

            error(msg)
        }

        return text
    }
}
