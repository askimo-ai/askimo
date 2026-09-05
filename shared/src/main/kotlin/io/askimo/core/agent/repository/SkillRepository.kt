/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent.repository

import io.askimo.core.agent.SkillMarkdownParser
import io.askimo.core.agent.domain.SkillDefinition
import io.askimo.core.agent.domain.SkillTreeNode
import io.askimo.core.logging.logger
import io.askimo.core.util.AskimoHome
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

/**
 * Filesystem-backed repository for user skills.
 *
 * ## Storage layout
 * A **skill** is always a folder that contains a `skill.md` entry point file
 * (matched case-insensitively, so `SKILL.md`, `Skill.md`, and `skill.md` all work).
 * The folder name is the skill name unless a `name:` frontmatter field overrides it.
 *
 * * All files and sub-folders inside a skill folder are **supplemental** to that skill.
 * `.md` sibling files (excluding reserved names) are merged into the skill content.
 * Non-`.md` files are preserved on import but not read as content.
 *
 * Plain folders without `skill.md` are transparent **category** containers.
 * Sub-folders of a skill folder are NOT treated as separate skills.
 *
 * ```
 * ~/.askimo/personal/skills/
 * ├── coding/                      ← plain category folder
 * │   └── reviewer/                ← skill folder (has skill.md)
 * │       ├── skill.md             ← entry point
 * │       ├── examples.md          ← supplemental context (merged)
 * │       ├── Helper.java          ← supplemental file (copied, not merged)
 * │       └── CLAUDE.md            ← reserved, ignored
 * └── formatter/                   ← skill folder (has skill.md)
 *     └── skill.md
 * ```
 *
 * ## No caching
 * Skills are always read fresh from disk so that edits made outside Askimo are
 * picked up without a restart.
 *
 * ## Thread safety
 * All public methods are safe to call from any thread. File I/O is performed
 * synchronously on the calling thread.
 */
class SkillRepository {

    private val log = logger<SkillRepository>()

    companion object {
        /**
         * File names that are reserved — never treated as supplemental skill content.
         * Matched case-sensitively (these are intentionally uppercase agent conventions).
         */
        private val RESERVED_FILENAMES = setOf("CLAUDE.md", "GEMINI.md", "AGENTS.md", "README.md")

        /** The skill entry-point filename — matched case-insensitively. */
        private const val SKILL_ENTRY = "skill.md"

        /** Cap on a rename-on-save folder name — keeps names reasonable across filesystems. */
        private const val MAX_FOLDER_NAME_LENGTH = 160

        /** Cap on rename-on-save disambiguation attempts (`-2`, `-3`, ...) before giving up. */
        private const val MAX_RENAME_SUFFIX_ATTEMPTS = 50

        /**
         * Counts skill folders under [root].
         *
         * A skill is a folder that contains a `skill.md` file (case-insensitive).
         * Sub-folders of a skill folder are NOT counted as separate skills.
         * Hidden directories (`.git`, etc.) are excluded.
         */
        fun countSkills(root: Path): Int {
            val result = mutableListOf<Path>()
            countSkillFolderRoots(root, result)
            return result.size
        }

        private fun countSkillFolderRoots(dir: Path, result: MutableList<Path>) {
            collectSkillFolderRoots(dir, result, skipGit = { it.any { seg -> seg.toString() == ".git" } })
        }

        private fun collectSkillFolderRoots(dir: Path, result: MutableList<Path>, skipGit: (Path) -> Boolean) {
            if (skipGit(dir)) return
            val entries = runCatching { Files.list(dir).toList() }.getOrDefault(emptyList())
            val hasSkillEntry = entries.any {
                Files.isRegularFile(it) && it.fileName.toString().equals(SKILL_ENTRY, ignoreCase = true)
            }
            if (hasSkillEntry) {
                result.add(dir)
                return // do not recurse — sub-folders of a skill folder are not separate skills
            }
            entries
                .filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }
                .forEach { collectSkillFolderRoots(it, result, skipGit) }
        }
    }

    /**
     * Returns all supplemental `.md` files (non-reserved, non-entry-point) across all skill folders.
     * Used internally; for selecting skills use [getSkillsOnly].
     */
    fun getAll(): List<SkillDefinition> {
        val root = AskimoHome.skillsDir()
        if (!Files.isDirectory(root)) return emptyList()
        val skills = mutableListOf<SkillDefinition>()
        Files.walk(root)
            .filter { it.isRegularFile() }
            .filter { path ->
                val name = path.fileName.toString()
                name.endsWith(".md") &&
                    !name.equals(SKILL_ENTRY, ignoreCase = true) &&
                    name !in RESERVED_FILENAMES
            }
            .filter { path ->
                // Exclude files inside hidden directories (.git, .github, etc.)
                val rel = path.relativeTo(root).toString().replace("\\", "/")
                rel.split("/").dropLast(1).none { segment -> segment.startsWith(".") }
            }
            .sorted()
            .forEach { path ->
                runCatching {
                    val relativePath = path.relativeTo(root).toString().replace("\\", "/")
                    val raw = Files.readString(path)
                    val skill = SkillMarkdownParser.parse(raw, relativePath, path)
                    skills += skill
                }.onFailure { e ->
                    log.warn("Skipped file '{}': {}", path.fileName, e.message)
                }
            }
        return skills
    }

    /**
     * Returns only selectable skills for the launcher view.
     * A skill is any folder (at any depth) that contains a `skill.md` entry point (case-insensitive),
     * UNLESS that folder is itself inside another skill folder.
     */
    fun getSkillsOnly(): List<SkillDefinition> {
        val root = AskimoHome.skillsDir()
        if (!Files.isDirectory(root)) return emptyList()
        return findSkillFolderRoots(root, root)
            .sortedBy { it.name }
    }

    /**
     * Recursively finds top-level skill folders starting from [dir].
     * If [dir] itself is a skill folder, it is returned and its subtree is NOT recursed.
     * Hidden directories are skipped.
     *
     * @param dir   Current directory being examined.
     * @param root  The skills root (used to compute relative paths / categories).
     */
    private fun findSkillFolderRoots(dir: Path, root: Path): List<SkillDefinition> {
        val entries = runCatching { Files.list(dir).toList() }.getOrDefault(emptyList())
        val entryFile = entries.firstOrNull {
            Files.isRegularFile(it) && it.fileName.toString().equals(SKILL_ENTRY, ignoreCase = true)
        }
        if (entryFile != null) {
            // This folder IS a skill — load it and stop recursing
            return loadSkillFolder(dir, entryFile, root)?.let { listOf(it) } ?: emptyList()
        }
        // Plain category folder — recurse into non-hidden subdirectories
        return entries
            .filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }
            .flatMap { findSkillFolderRoots(it, root) }
    }

    /**
     * Loads a skill from [skillFolder] using [entryFile] as `skill.md`.
     * Supplemental `.md` siblings (non-reserved, non-entry) are merged into content.
     * Category is derived from the folder's parent path relative to [root].
     */
    private fun loadSkillFolder(skillFolder: Path, entryFile: Path, root: Path): SkillDefinition? = runCatching {
        val mainContent = Files.readString(entryFile)
        val folderName = skillFolder.fileName.toString()
        // Category = parent path relative to skills root (not including the skill folder itself)
        val parentRel = if (skillFolder == root) {
            ""
        } else {
            skillFolder.parent.relativeTo(root).toString().replace("\\", "/")
        }
        // Virtual relative path so categoryPath is computed correctly
        val virtualRelativePath = if (parentRel.isNotEmpty()) "$parentRel/$folderName.md" else "$folderName.md"
        val base = SkillMarkdownParser.parse(mainContent, virtualRelativePath, entryFile)
        val displayName = if (hasExplicitNameField(mainContent)) {
            base.name.takeIf { it.isNotBlank() } ?: folderName
        } else {
            folderName
        }
        // Collect all supplemental files recursively (non-reserved, non-hidden, non-entry)
        // Each entry: Pair(label relative to skillFolder, file body text)
        val supplementalFiles: List<Pair<String, String>> = Files.walk(skillFolder)
            .filter { Files.isRegularFile(it) }
            .filter { file ->
                val name = file.fileName.toString()
                !name.equals(SKILL_ENTRY, ignoreCase = true) &&
                    name !in RESERVED_FILENAMES &&
                    file.relativeTo(skillFolder).none { seg -> seg.toString().startsWith(".") }
            }
            .sorted()
            .toList() // convert Java stream to Kotlin List
            .mapNotNull { file ->
                runCatching {
                    val raw = Files.readString(file)
                    val label = skillFolder.relativize(file).toString().replace("\\", "/")
                    val body = if (file.fileName.toString().endsWith(".md")) {
                        SkillMarkdownParser.parse(raw, label, file).content
                    } else {
                        raw
                    }
                    Pair(label, body)
                }.getOrNull()
            }

        val content = if (supplementalFiles.isEmpty()) {
            base.content
        } else {
            val fileTree = buildString {
                appendLine("## Skill Folder Structure")
                appendLine()
                appendLine("This skill contains the following files:")
                appendLine()
                appendLine("- `skill.md` ← entry point (instructions above)")
                supplementalFiles.forEach { (label, _) -> appendLine("- `$label`") }
            }
            val fileContents = buildString {
                appendLine("## Supplemental Files")
                appendLine()
                appendLine("The following files are part of this skill. When instructions reference a file by name, refer to its content below.")
                supplementalFiles.forEach { (label, body) ->
                    appendLine()
                    appendLine("### `$label`")
                    appendLine()
                    appendLine(body.trim())
                }
            }
            "${base.content}\n\n---\n\n$fileTree\n\n---\n\n$fileContents"
        }
        base.copy(
            name = displayName,
            relativePath = virtualRelativePath,
            content = content,
            systemPrompt = base.content,
            supplementalFileNames = supplementalFiles.map { it.first },
        )
    }.onFailure { e ->
        log.warn("Failed to load skill folder '{}': {}", skillFolder, e.message)
    }.getOrNull()

    /**
     * Builds a tree of [SkillTreeNode] from all skill folders on disk.
     *
     * ## Tree structure
     * - Skill folders are shown with their **direct parent folder** as category.
     *   Any deeper ancestors (grandparent, great-grandparent, …) are invisible.
     * - Root-level skill folders (direct child of skillsDir) have no category wrapper.
     * - Skills are grouped under their immediate parent name. Multiple skills sharing
     *   the same immediate parent name/path appear under one category node.
     *
     * Example:
     * ```
     * a/b/coding/reviewer/skill.md  →  📁 coding  →  🧩 reviewer
     * formatter/skill.md            →  🧩 formatter   (no category)
     * ```
     */
    fun getTree(): List<SkillTreeNode> {
        val root = AskimoHome.skillsDir()
        if (!Files.isDirectory(root)) return emptyList()

        // Collect all skill folders
        val skillFolderPaths = mutableListOf<Path>()
        findSkillFolderRootPaths(root, skillFolderPaths)

        val rootSkills = mutableListOf<SkillTreeNode>()
        // category path (relative) → list of skill dirs
        val byCategory = linkedMapOf<String, MutableList<Path>>()

        skillFolderPaths.forEach { dir ->
            val parent = dir.parent
            if (parent == root) {
                rootSkills += skillFolderCategoryNode(dir, root)
            } else {
                // Use immediate parent as category key (its relative path from root)
                val categoryKey = parent.relativeTo(root).toString().replace("\\", "/")
                byCategory.getOrPut(categoryKey) { mutableListOf() }.add(dir)
            }
        }

        // Build category nodes, using only the immediate parent folder name as display name
        val categoryNodes = byCategory.entries
            .sortedBy { it.key }
            .map { (categoryPath, skillDirs) ->
                val categoryName = categoryPath.substringAfterLast("/")
                val skillNodes = skillDirs.sortedBy { it.fileName.toString() }.map { dir ->
                    skillFolderCategoryNode(dir, root)
                }
                SkillTreeNode.Category(
                    name = categoryName,
                    path = categoryPath,
                    children = skillNodes,
                    isSkillFolder = false,
                    skill = null,
                )
            }

        return (categoryNodes + rootSkills).sortedBy { (it as SkillTreeNode.Category).name }
    }

    /**
     * Builds a [SkillTreeNode.Category] for a skill folder [dir] relative to [root].
     */
    private fun skillFolderCategoryNode(dir: Path, root: Path): SkillTreeNode.Category {
        val entryFile = runCatching { Files.list(dir).toList() }.getOrDefault(emptyList()).firstOrNull {
            Files.isRegularFile(it) && it.fileName.toString().equals(SKILL_ENTRY, ignoreCase = true)
        }
        val skill = entryFile?.let { loadSkillFolder(dir, it, root) }
        val folderName = skill?.name?.takeIf { it.isNotBlank() } ?: dir.fileName.toString()
        val path = dir.relativeTo(root).toString().replace("\\", "/")
        return SkillTreeNode.Category(
            name = folderName,
            path = path,
            children = buildSkillFolderContents(dir, root),
            isSkillFolder = true,
            skill = skill,
        )
    }

    /**
     * Recursively builds the child nodes shown inside a skill folder:
     * skill.md first, then other files sorted, then sub-directories recursed.
     * Hidden dirs are excluded.
     */
    private fun buildSkillFolderContents(dir: Path, root: Path): List<SkillTreeNode> {
        val entries = runCatching { Files.list(dir).toList() }.getOrDefault(emptyList())
        val entryFile = entries.firstOrNull { Files.isRegularFile(it) && it.fileName.toString().equals(SKILL_ENTRY, ignoreCase = true) }
        val otherFiles = entries
            .filter { Files.isRegularFile(it) && it != entryFile }
            .sortedBy { it.fileName.toString() }
        val subDirs = entries
            .filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }
            .sortedBy { it.fileName.toString() }

        val result = mutableListOf<SkillTreeNode>()
        if (entryFile != null) result += fileToLeaf(entryFile, root, isSkillEntry = true)
        result += otherFiles.map { fileToLeaf(it, root) }
        subDirs.forEach { subDir ->
            val subPath = subDir.relativeTo(root).toString().replace("\\", "/")
            val subChildren = buildSkillFolderContents(subDir, root)
            result += SkillTreeNode.Category(
                name = subDir.fileName.toString(),
                path = subPath,
                children = subChildren,
                isSkillFolder = false,
                skill = null,
            )
        }
        return result
    }

    private fun fileToLeaf(file: Path, root: Path, isSkillEntry: Boolean = false): SkillTreeNode.Leaf {
        val rel = file.relativeTo(root).toString().replace("\\", "/")
        val name = file.fileName.toString()
        val skill = if (isSkillEntry || (name.endsWith(".md") && !name.equals(SKILL_ENTRY, ignoreCase = true) && name !in RESERVED_FILENAMES)) {
            runCatching { SkillMarkdownParser.parse(Files.readString(file), rel, file) }.getOrNull()
        } else {
            null
        }
        return SkillTreeNode.Leaf(name = name, path = rel, absolutePath = file, skill = skill, isSkillEntry = isSkillEntry)
    }

    /**
     * Finds a skill by its virtual [SkillDefinition.relativePath].
     */
    fun findByPath(relativePath: String): SkillDefinition? = getSkillsOnly().firstOrNull { it.relativePath == relativePath.replace("\\", "/") }

    /**
     * Writes [content] to the given [relativePath] under [skillsDir].
     * Parent directories are created automatically.
     *
     * If [relativePath] points at the skill entry point (`skill.md`, case-insensitive), the
     * containing folder is also kept in sync with the frontmatter `name:` field — per this
     * repository's storage contract ("the folder name is the skill name unless a `name:`
     * frontmatter field overrides it," see class doc). Without this, a skill created via the
     * "New Skill" flow (folder literally named `new-skill-<timestamp>`) would keep that
     * placeholder folder name forever even after the user renames it in the editor — and since
     * external-agent materialization derives its destination folder name from this same
     * relativePath (see [SkillDefinition.slug]), agents like Claude Code/Codex would list the
     * skill under the placeholder name instead of the human-chosen one.
     *
     * If another skill in the *same* category already occupies the target folder name (e.g.
     * two skills sharing the same `name:`), a numeric suffix (`-2`, `-3`, ...) is appended until
     * an available name is found — the existing folder is never overwritten, but the renaming
     * skill also isn't left stuck under its old placeholder name just because of a collision.
     * Skills in *different* categories never collide in the first place: this rename only ever
     * moves a folder within its own parent directory, and [SkillDefinition.slug] (used for
     * external-agent materialization) prepends the full category path on top of this leaf name.
     *
     * Rename-on-save only applies when the entry file has a containing skill folder (e.g.
     * `"my-skill/skill.md"`); a bare root-level `"skill.md"` is left as-is since there is no
     * skill folder to rename (renaming would otherwise attempt to move `skillsDir()` itself).
     *
     * @param relativePath Path relative to `AskimoHome.skillsDir()`, e.g. `"coding/review/my-skill.md"`.
     * @param content      Full file content (frontmatter + body).
     * @return The parsed [SkillDefinition] after saving (with the post-rename path, if renamed).
     */
    fun save(relativePath: String, content: String): SkillDefinition {
        require(relativePath.endsWith(".md")) { "Skill file path must end with .md: $relativePath" }
        val root = AskimoHome.skillsDir()
        val normalizedRelativePath = relativePath.replace("\\", "/")
        var absolute = root.resolve(normalizedRelativePath)
        Files.createDirectories(absolute.parent)
        Files.writeString(absolute, content)
        log.debug("Saved skill to {}", absolute)

        var effectiveRelativePath = normalizedRelativePath
        if (absolute.fileName.toString().equals(SKILL_ENTRY, ignoreCase = true)) {
            // Only rename based on an *explicit* `name:` frontmatter field — SkillMarkdownParser
            // falls back to a filename-derived name (e.g. "skill" from "skill.md") when no such
            // field is present, which would otherwise cause every nameless skill.md to be
            // spuriously renamed to a folder literally called "skill".
            val hasExplicitName = hasExplicitNameField(content)
            val parsedName = if (hasExplicitName) {
                runCatching { SkillMarkdownParser.parse(content, normalizedRelativePath, absolute).name }.getOrNull()
            } else {
                null
            }
            val targetFolderName = parsedName?.let(::sanitizeFolderName)?.takeIf { it.isNotBlank() }
            val currentFolder = absolute.parent
            val currentFolderName = currentFolder.fileName.toString()

            // Never rename when the entry file sits directly under skillsDir() itself — there's
            // no containing skill folder to rename, and currentFolder would be the skills root.
            if (targetFolderName != null && targetFolderName != currentFolderName && currentFolder != root) {
                val resolvedName = resolveAvailableFolderName(currentFolder.parent, targetFolderName, currentFolderName)
                if (resolvedName != null) {
                    val targetFolder = currentFolder.resolveSibling(resolvedName)
                    Files.move(currentFolder, targetFolder)
                    absolute = targetFolder.resolve(absolute.fileName)
                    effectiveRelativePath = root.relativize(absolute).toString().replace("\\", "/")
                    log.debug("Renamed skill folder '{}' -> '{}' to match frontmatter name", currentFolderName, resolvedName)
                }
            }
        }

        return SkillMarkdownParser.parse(Files.readString(absolute), effectiveRelativePath, absolute)
    }

    /**
     * Finds an available folder name for a rename-on-save, starting from [desiredName] under
     * [parent]. If [desiredName] is free, it's used as-is. Otherwise appends `-2`, `-3`, ... up
     * to [MAX_RENAME_SUFFIX_ATTEMPTS] until a free name is found — never overwrites another
     * skill's folder.
     *
     * Returns `null` if [desiredName] already equals [currentFolderName] (nothing to rename) or,
     * in the extremely unlikely event that every suffix up to the cap is also taken, to leave
     * the folder as-is rather than looping forever.
     */
    private fun resolveAvailableFolderName(parent: Path, desiredName: String, currentFolderName: String): String? {
        if (desiredName == currentFolderName) return null
        if (!Files.exists(parent.resolve(desiredName))) return desiredName
        for (suffix in 2..MAX_RENAME_SUFFIX_ATTEMPTS) {
            val candidate = "$desiredName-$suffix"
            if (candidate == currentFolderName) return null
            if (!Files.exists(parent.resolve(candidate))) return candidate
        }
        log.debug(
            "Could not find an available disambiguated folder name for '{}' after {} attempts — leaving '{}' as-is",
            desiredName,
            MAX_RENAME_SUFFIX_ATTEMPTS,
            currentFolderName,
        )
        return null
    }

    /**
     * Sanitizes a frontmatter `name:` value into a filesystem-safe, lowercase kebab-case folder
     * name segment — the same normalization style as [SkillDefinition.slug], but applied to a
     * single leaf folder name rather than a full category-qualified path.
     */
    private fun sanitizeFolderName(rawName: String): String = rawName
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(MAX_FOLDER_NAME_LENGTH)

    /**
     * Returns `true` if [content] has an explicit `name:` key inside its **leading YAML
     * frontmatter block** only — the same block [SkillMarkdownParser] treats as frontmatter
     * (delimited by a `---` first line and a matching closing `---` line further down).
     *
     * Unlike a naive whole-file regex scan, this can't be fooled by a `name:` line that
     * merely appears in the skill body (non-frontmatter) text, and mirrors
     * [SkillMarkdownParser]'s own key-matching rule (colon split, trimmed key) so this stays
     * consistent with however `SkillMarkdownParser.parse` actually resolves `name`.
     */
    private fun hasExplicitNameField(content: String): Boolean {
        val lines = content.lines()
        if (lines.isEmpty() || lines[0].trim() != "---") return false
        val closingIndex = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (closingIndex == -1) return false
        return lines.subList(1, closingIndex + 1).any { line ->
            val colonIndex = line.indexOf(':')
            colonIndex > 0 && line.substring(0, colonIndex).trim() == "name"
        }
    }

    /**
     * Deletes the skill file at [relativePath].
     *
     * @return `true` if the file existed and was deleted, `false` if it was not found.
     */
    fun delete(relativePath: String): Boolean {
        val file = AskimoHome.skillsDir().resolve(relativePath.replace("\\", "/"))
        return if (Files.deleteIfExists(file)) {
            log.debug("Deleted skill '{}'", relativePath)
            pruneEmptyDirs(file.parent)
            true
        } else {
            false
        }
    }

    /**
     * Deletes a supplemental (non-skill-entry) file at [relativePath] and then
     * prunes any ancestor category folders that become skill-less.
     *
     * @return `true` if the file existed and was deleted, `false` if it was not found.
     */
    fun deleteFile(relativePath: String): Boolean {
        val file = AskimoHome.skillsDir().resolve(relativePath.replace("\\", "/"))
        return if (Files.deleteIfExists(file)) {
            log.debug("Deleted file '{}'", relativePath)
            pruneEmptyDirs(file.parent)
            true
        } else {
            false
        }
    }

    /**
     * Deletes the folder at [relativePath] (and its entire subtree) then prunes any
     * ancestor category folders that become skill-less as a result.
     *
     * @return `true` if the folder existed and was deleted, `false` if it was not found.
     */
    fun deleteFolder(relativePath: String): Boolean {
        val dir = AskimoHome.skillsDir().resolve(relativePath.replace("\\", "/"))
        if (!Files.isDirectory(dir)) return false
        Files.walk(dir)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
        log.debug("Deleted folder '{}'", relativePath)
        pruneEmptyDirs(dir.parent)
        return true
    }

    /**
     * Deletes all top-level entries (files and folders) inside the skills directory,
     * effectively removing all skills.
     *
     * @return Number of top-level entries deleted.
     */
    fun deleteAll(): Int {
        val skillsDir = AskimoHome.skillsDir()
        if (!Files.isDirectory(skillsDir)) return 0
        var count = 0
        Files.list(skillsDir).use { stream ->
            stream.forEach { entry ->
                if (Files.isDirectory(entry)) {
                    Files.walk(entry).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                } else {
                    Files.deleteIfExists(entry)
                }
                count++
            }
        }
        log.debug("Deleted all skills ({} top-level entries)", count)
        return count
    }

    // ── Import ───────────────────────────────────────────────────────────────

    /**
     * Imports skill folders from [sourceDir] into the skills directory under [targetName].
     *
     * Only **skill folders** (directories containing `skill.md`, case-insensitive) are imported.
     * The entire subtree of each skill folder is copied (all files, all sub-folders).
     * `.git` directories and their contents are excluded.
     * Non-skill directories (e.g. `ci/`, `scripts/`) are ignored.
     *
     * @param sourceDir  Root of the directory to import from (e.g. a cloned git repo).
     * @param targetName Sub-directory name under [skillsDir] to import into.
     * @return Number of skill folders imported.
     */
    fun importFromDirectory(sourceDir: Path, targetName: String): Int {
        val targetDir = AskimoHome.skillsDir().resolve(targetName)
        Files.createDirectories(targetDir)

        // Find all top-level skill folder roots (stop recursing once a skill.md is found)
        val skillFolderRoots = mutableListOf<Path>()
        findSkillFolderRootPaths(sourceDir, skillFolderRoots)

        skillFolderRoots.forEach { root ->
            Files.walk(root)
                .filter { Files.isRegularFile(it) && !it.isUnderGit() }
                .forEach { src ->
                    val dest = targetDir.resolve(sourceDir.relativize(src))
                    Files.createDirectories(dest.parent)
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
                }
        }

        log.info("Imported {} skill(s) from {} into {}", skillFolderRoots.size, sourceDir, targetDir)
        return skillFolderRoots.size
    }

    /**
     * Recursively finds top-level skill folder paths in [dir], adding to [result].
     * Stops recursing into a directory once it is identified as a skill folder.
     * Skips hidden directories.
     */
    private fun findSkillFolderRootPaths(dir: Path, result: MutableList<Path>) {
        collectSkillFolderRoots(dir, result, skipGit = { it.isUnderGit() })
    }

    /** True if this path has `.git` anywhere in its name components. */
    private fun Path.isUnderGit(): Boolean = any { it.toString() == ".git" }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Starting from [dir], walks up the directory tree toward [skillsDir] and deletes
     * every directory whose subtree contains no `skill.md` file.
     *
     * Unlike a simple "stop on first non-empty ancestor" approach, this always
     * recurses all the way to the root so that each ancestor is evaluated
     * independently. Example:
     *
     * ```
     * skills/
     *   parent1/
     *     parent2/          ← no skills left → deleted
     *   other-skill/skill.md
     * ```
     * After deleting the last skill under `parent1/parent2`, `parent2` and `parent1`
     * are both pruned even though `skills/` still contains `other-skill`.
     */
    private fun pruneEmptyDirs(dir: Path) {
        val root = AskimoHome.skillsDir()
        pruneUp(dir, root)
    }

    private fun pruneUp(dir: Path, root: Path) {
        if (dir == root || !Files.isDirectory(dir)) return
        if (hasNoSkills(dir)) {
            Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
        pruneUp(dir.parent, root)
    }

    /**
     * Returns `true` if [dir] contains no `skill.md` file anywhere in its subtree.
     */
    private fun hasNoSkills(dir: Path): Boolean = Files.walk(dir)
        .filter { Files.isRegularFile(it) && it.fileName.toString().equals(SKILL_ENTRY, ignoreCase = true) }
        .findAny()
        .isEmpty
}
