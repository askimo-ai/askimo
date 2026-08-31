/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent.domain

import java.nio.file.Path

/**
 * A node in the skill folder tree built by [io.askimo.core.agent.repository.SkillRepository].
 *
 * ## Convention
 * A skill is a **folder** containing a `skill.md` entry point (case-insensitive).
 * Everything inside is supplemental. Plain folders are category containers.
 *
 * ```
 * skills/
 * ├── coding/                      ← plain category folder (isSkillFolder=false)
 * │   └── reviewer/                ← skill folder (isSkillFolder=true)
 * │       ├── skill.md             ← entry point  → Leaf.SkillEntry
 * │       ├── examples.md          ← supplemental → Leaf.File
 * │       ├── Helper.java          ← supplemental → Leaf.File
 * │       └── sub/                 ← sub-dir      → Category
 * └── formatter/                   ← skill folder
 *     └── skill.md
 * ```
 */
sealed class SkillTreeNode {

    /**
     * A folder node — either a plain category or a skill folder.
     *
     * @param name          Display name (folder name, or from `skill.md` frontmatter).
     * @param path          Slash-joined path relative to skills root, e.g. `"coding/reviewer"`.
     * @param children      Child nodes (sub-folders and files).
     * @param isSkillFolder `true` when this folder contains a `skill.md` entry point.
     * @param skill         The loaded [SkillDefinition] when [isSkillFolder] is true.
     */
    data class Category(
        val name: String,
        val path: String,
        val children: List<SkillTreeNode> = emptyList(),
        val isSkillFolder: Boolean = false,
        val skill: SkillDefinition? = null,
    ) : SkillTreeNode()

    /**
     * A leaf node representing a single file inside the skills directory.
     * This includes `skill.md` entry points, supplemental `.md` files, and all other files.
     *
     * @param name         File name (e.g. `"examples.md"`, `"Helper.java"`).
     * @param path         Slash-joined path relative to skills root.
     * @param absolutePath Absolute filesystem path for reading/writing.
     * @param skill        Non-null when this file is a `.md` skill/supplement (parsed frontmatter).
     *                     Null for non-markdown files or files that failed parsing.
     * @param isSkillEntry `true` when this is the `skill.md` entry point of its parent folder.
     */
    data class Leaf(
        val name: String,
        val path: String,
        val absolutePath: Path,
        val skill: SkillDefinition? = null,
        val isSkillEntry: Boolean = false,
    ) : SkillTreeNode()
}
