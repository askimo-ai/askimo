/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.skills.SkillImporter
import io.askimo.core.skills.SkillRepository
import io.askimo.core.skills.agent.ExternalAgentLoader
import io.askimo.core.skills.domain.SkillDefinition
import io.askimo.core.skills.domain.SkillTreeNode
import io.askimo.core.util.AskimoHome
import io.askimo.ui.common.components.dangerButton
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.preferences.ApplicationPreferences
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppComponents.appOutlinedTextField
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.revealingMarkdownText
import io.askimo.ui.common.ui.themedTooltip
import io.askimo.ui.common.ui.util.FileDialogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Cursor
import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

@Composable
fun skillsSettingsSection() {
    val skillRepository = remember { SkillRepository() }
    var refreshKey by remember { mutableStateOf(0) }
    val tree by remember(refreshKey) { mutableStateOf(skillRepository.getTree()) }
    fun refresh() {
        refreshKey++
    }
    var selectedSkill by remember { mutableStateOf<SkillDefinition?>(null) }
    // Currently selected file node (for non-skill files)
    var selectedLeaf by remember { mutableStateOf<SkillTreeNode.Leaf?>(null) }

    var isPanelExpanded by remember { mutableStateOf(ApplicationPreferences.getSkillsSidePanelExpanded()) }
    var panelWidth by remember { mutableStateOf(ApplicationPreferences.getSkillsSidePanelWidth().dp) }

    LaunchedEffect(isPanelExpanded) {
        ApplicationPreferences.setSkillsSidePanelExpanded(isPanelExpanded)
    }

    val animatedPanelWidth by animateDpAsState(
        targetValue = if (isPanelExpanded) panelWidth else 40.dp,
        animationSpec = tween(durationMillis = 250),
    )

    // New folder / pack dialog state
    var newItemParentPath by remember { mutableStateOf("") } // context path for in-folder creation
    var showNewSkillInFolderDialog by remember { mutableStateOf(false) }
    var showImportGitHubDialog by remember { mutableStateOf(false) }
    var showImportZipDialog by remember { mutableStateOf(false) }
    var showAddFileDialog by remember { mutableStateOf(false) }
    var showAddFolderDialog by remember { mutableStateOf(false) }
    var addItemParentPath by remember { mutableStateOf("") }

    Row(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            skillsMainContent(
                selectedSkill = selectedSkill,
                selectedLeaf = selectedLeaf,
                onSaveSkill = { relativePath, content ->
                    val saved = skillRepository.save(relativePath, content)
                    refresh()
                    selectedSkill = saved
                },
                onSaveFile = { absolutePath, content ->
                    Files.writeString(absolutePath, content)
                    refresh()
                },
                hasSkills = tree.isNotEmpty(),
            )
        }

        if (isPanelExpanded) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant)
                    .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val newWidth = panelWidth - dragAmount.x.toDp()
                                panelWidth = newWidth.coerceIn(220.dp, 500.dp)
                            },
                            onDragEnd = {
                                ApplicationPreferences.setSkillsSidePanelWidth(panelWidth.value.toInt())
                            },
                        )
                    },
            )
        }

        Card(
            modifier = Modifier.width(animatedPanelWidth).fillMaxHeight(),
            shape = RectangleShape,
            colors = CardDefaults.cardColors(
                containerColor = AppComponents.sidebarSurfaceColor(),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            if (isPanelExpanded) {
                skillsTreePanel(
                    tree = tree,
                    selectedSkill = selectedSkill,
                    onSelectSkill = { skill ->
                        selectedSkill = skill
                        selectedLeaf = null
                    },
                    onSelectFile = { leaf ->
                        if (leaf.isSkillEntry && leaf.skill != null) {
                            selectedSkill = leaf.skill
                            selectedLeaf = null
                        } else {
                            selectedLeaf = leaf
                            selectedSkill = null
                        }
                    },
                    onDeleteSkill = { skill ->
                        skillRepository.delete(skill.relativePath)
                        if (selectedSkill?.relativePath == skill.relativePath) {
                            selectedSkill = null
                            selectedLeaf = null
                        }
                        refresh()
                    },
                    onDeleteFile = { relativePath ->
                        skillRepository.deleteFile(relativePath)
                        if (selectedLeaf?.path == relativePath) selectedLeaf = null
                        refresh()
                    },
                    onDeleteFolder = { folderRelativePath ->
                        skillRepository.deleteFolder(folderRelativePath)
                        if (selectedSkill?.relativePath?.startsWith(folderRelativePath) == true) selectedSkill = null
                        if (selectedLeaf?.path?.startsWith(folderRelativePath) == true) selectedLeaf = null
                        refresh()
                    },
                    onCollapse = { isPanelExpanded = false },
                    onNewSkill = {
                        val folderName = "new-skill-${System.currentTimeMillis()}"
                        val blankContent = "---\nname: New Skill\ndescription: \ntags: []\n---\n\nYou are a helpful assistant.\n"
                        val saved = skillRepository.save("$folderName/skill.md", blankContent)
                        refresh()
                        selectedSkill = saved
                        selectedLeaf = null
                    },
                    onNewSkillInFolder = { parentPath ->
                        newItemParentPath = parentPath
                        showNewSkillInFolderDialog = true
                    },
                    onImportFromGitHub = { showImportGitHubDialog = true },
                    onImportFromZip = { showImportZipDialog = true },
                    onAddFileInFolder = { parentPath ->
                        addItemParentPath = parentPath
                        showAddFileDialog = true
                    },
                    onAddFolderInFolder = { parentPath ->
                        addItemParentPath = parentPath
                        showAddFolderDialog = true
                    },
                    onDeleteAll = {
                        skillRepository.deleteAll()
                        selectedSkill = null
                        selectedLeaf = null
                        refresh()
                    },
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top,
                ) {
                    IconButton(
                        onClick = { isPanelExpanded = true },
                        modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            Icons.Default.ChevronLeft,
                            contentDescription = "Expand skills panel",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }

    // Dialogs
    // New skill inside a specific folder
    if (showNewSkillInFolderDialog) {
        newSkillInFolderDialog(
            parentPath = newItemParentPath,
            onDismiss = { showNewSkillInFolderDialog = false },
            onConfirm = { skillFolderName ->
                if (skillFolderName.isNotBlank()) {
                    val relativePath = "$newItemParentPath/$skillFolderName/skill.md"
                    val blankContent = "---\nname: ${skillFolderName.replace('-', ' ').replaceFirstChar { it.uppercase() }}\ndescription: \ntags: []\n---\n\nYou are a helpful assistant.\n"
                    val saved = skillRepository.save(relativePath, blankContent)
                    refresh()
                    selectedSkill = saved
                    selectedLeaf = null
                }
                showNewSkillInFolderDialog = false
            },
        )
    }

    // Add file inside a folder
    if (showAddFileDialog) {
        addFileInFolderDialog(
            parentPath = addItemParentPath,
            onDismiss = { showAddFileDialog = false },
            onConfirm = { fileName ->
                if (fileName.isNotBlank()) {
                    val absoluteDir = AskimoHome.skillsDir().resolve(addItemParentPath)
                    val absoluteFile = absoluteDir.resolve(fileName)
                    Files.createDirectories(absoluteDir)
                    if (!Files.exists(absoluteFile)) Files.createFile(absoluteFile)
                    refresh()
                    // Select the newly created leaf
                    val relativePath = "$addItemParentPath/$fileName"
                    val leaf = SkillTreeNode.Leaf(
                        name = fileName,
                        path = relativePath,
                        absolutePath = absoluteFile,
                        skill = null,
                        isSkillEntry = false,
                    )
                    selectedLeaf = leaf
                    selectedSkill = null
                }
                showAddFileDialog = false
            },
        )
    }

    // Add sub-folder inside a folder
    if (showAddFolderDialog) {
        addFolderInFolderDialog(
            parentPath = addItemParentPath,
            onDismiss = { showAddFolderDialog = false },
            onConfirm = { folderName ->
                if (folderName.isNotBlank()) {
                    val absoluteDir = AskimoHome.skillsDir().resolve(addItemParentPath).resolve(folderName)
                    Files.createDirectories(absoluteDir)
                    refresh()
                }
                showAddFolderDialog = false
            },
        )
    }

    // Import from GitHub
    if (showImportGitHubDialog) {
        var importSuccessMessage by remember { mutableStateOf<String?>(null) }

        if (importSuccessMessage != null) {
            importSuccessDialog(
                message = importSuccessMessage!!,
                onDismiss = {
                    importSuccessMessage = null
                    showImportGitHubDialog = false
                },
            )
        } else {
            importFromGitHubDialog(
                onDismiss = { showImportGitHubDialog = false },
                onImported = { message ->
                    importSuccessMessage = message
                    refresh()
                },
            )
        }
    }

    // Import from ZIP
    if (showImportZipDialog) {
        var importSuccessMessage by remember { mutableStateOf<String?>(null) }

        if (importSuccessMessage != null) {
            importSuccessDialog(
                message = importSuccessMessage!!,
                onDismiss = {
                    importSuccessMessage = null
                    showImportZipDialog = false
                },
            )
        } else {
            importFromZipDialog(
                onDismiss = { showImportZipDialog = false },
                onImported = { message ->
                    importSuccessMessage = message
                    refresh()
                },
            )
        }
    }
}

// ── Main content ──────────────────────────────────────────────────────────────

@Composable
private fun skillsMainContent(
    selectedSkill: SkillDefinition?,
    selectedLeaf: SkillTreeNode.Leaf?,
    onSaveSkill: (relativePath: String, content: String) -> Unit,
    onSaveFile: (absolutePath: Path, content: String) -> Unit,
    hasSkills: Boolean,
) {
    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = ThemePreferences.CONTENT_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(start = Spacing.extraLarge, top = Spacing.extraLarge, bottom = Spacing.extraLarge, end = 36.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.large),
            ) {
                // Header row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource("settings.skills"),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        val runtimes = ExternalAgentLoader.displayNames()
                        val runtimesLabel = runtimes.mapIndexed { i, r ->
                            if (i == runtimes.lastIndex) "or $r" else r
                        }.joinToString(", ")
                        Spacer(Modifier.height(Spacing.extraSmall))
                        Text(
                            text = stringResource("settings.skills.description", runtimesLabel),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.height(Spacing.small))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        ) {
                            Text(
                                text = stringResource("settings.skills.runtimes"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            runtimes.forEach { runtime ->
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                ) {
                                    Text(
                                        text = runtime,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = Spacing.small, vertical = 3.dp),
                                    )
                                }
                            }
                        }
                    }
                    themedTooltip(text = stringResource("skills.view.docs.tooltip")) {
                        IconButton(
                            onClick = { runCatching { Desktop.getDesktop().browse(URI("https://$DOMAIN/docs/desktop/skills/")) } },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = stringResource("skills.view.docs.tooltip"),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                HorizontalDivider()

                if (selectedSkill == null && selectedLeaf == null) {
                    if (!hasSkills) {
                        // Directory info card — only shown on empty state
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Row(
                                modifier = Modifier.padding(Spacing.large),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Column {
                                        Text(
                                            text = stringResource("settings.skills.directory"),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                        )
                                        Text(
                                            text = AskimoHome.skillsDir().toString(),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                                TextButton(
                                    onClick = { openSkillsFolder(AskimoHome.skillsDir()) },
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                ) {
                                    Text(stringResource("settings.skills.open.folder"))
                                }
                            }
                        }
                        skillsMainEmptyState()
                    } else {
                        skillsMainSelectPrompt()
                    }
                } else if (selectedSkill != null) {
                    skillEditorContent(
                        skill = selectedSkill,
                        onSave = onSaveSkill,
                    )
                } else if (selectedLeaf != null) {
                    fileEditorContent(
                        leaf = selectedLeaf,
                        onSave = onSaveFile,
                    )
                }
            } // end inner Column (widthIn)
        } // end outer Column (scroll)

        VerticalScrollbar(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            adapter = rememberScrollbarAdapter(scrollState),
            style = AppComponents.scrollbarStyle(),
        )
    }
}

@Composable
private fun skillsMainSelectPrompt() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Icon(
                Icons.Default.Extension,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            )
            Text(
                text = stringResource("settings.skills.select.prompt"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun skillsMainEmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Icon(
                Icons.Default.Extension,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            )
            Text(
                text = stringResource("settings.skills.empty"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            Text(
                text = stringResource("settings.skills.empty.hint"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            )
        }
    }
}

// ── Skill editor ──────────────────────────────────────────────────────────────

@Composable
private fun skillEditorContent(
    skill: SkillDefinition,
    onSave: (relativePath: String, content: String) -> Unit,
) {
    // Derive the actual skill.md path.
    val skillMdRelativePath = remember(skill.relativePath) {
        if (skill.relativePath.endsWith("/skill.md", ignoreCase = true)) {
            skill.relativePath
        } else {
            val withoutExt = skill.relativePath.removeSuffix(".md")
            "$withoutExt/skill.md"
        }
    }

    // Read the raw skill.md body (not the merged content used for AI execution)
    val rawSkillMdBody = remember(skill.relativePath) {
        runCatching {
            val absolute = AskimoHome.skillsDir().resolve(skillMdRelativePath)
            val raw = Files.readString(absolute)
            val lines = raw.lines()
            if (lines.firstOrNull()?.trim() == "---") {
                val closingIdx = lines.drop(1).indexOfFirst { it.trim() == "---" }
                if (closingIdx >= 0) lines.drop(closingIdx + 2).joinToString("\n") else raw
            } else {
                raw
            }
        }.getOrDefault(skill.content)
    }

    var name by remember(skill.relativePath) { mutableStateOf(skill.name) }
    var description by remember(skill.relativePath) { mutableStateOf(skill.description) }
    var body by remember(skill.relativePath) { mutableStateOf(rawSkillMdBody) }

    // Inline edit state for name
    var isEditingName by remember(skill.relativePath) { mutableStateOf(false) }
    var nameInput by remember(skill.relativePath) { mutableStateOf(skill.name) }

    // Inline edit state for description
    var isEditingDesc by remember(skill.relativePath) { mutableStateOf(false) }
    var descInput by remember(skill.relativePath) { mutableStateOf(skill.description) }

    fun buildMarkdown(): String = buildString {
        appendLine("---")
        appendLine("name: $name")
        if (description.isNotBlank()) appendLine("description: $description")
        appendLine("---")
        append(body)
    }

    fun autoSave() {
        onSave(skillMdRelativePath, buildMarkdown())
    }

    fun commitName() {
        val trimmed = nameInput.trim()
        if (trimmed.isNotBlank()) {
            name = trimmed
        } else {
            nameInput = name
        }
        isEditingName = false
        autoSave()
    }

    fun commitDesc() {
        description = descInput.trim()
        isEditingDesc = false
        autoSave()
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.large)) {
        // ── Header: name (inline edit) · description (inline edit) · path ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Name + description column
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // ── Name row ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isEditingName) {
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            colors = AppComponents.outlinedTextFieldColors(),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { commitName() },
                            modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Confirm", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(
                            onClick = {
                                nameInput = name
                                isEditingName = false
                            },
                            modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Text(
                            text = name.ifBlank { stringResource("settings.skills.new.skill") },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                nameInput = name
                                isEditingName = true
                            },
                            modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit name", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                    }
                }
                // ── Description row ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isEditingDesc) {
                        OutlinedTextField(
                            value = descInput,
                            onValueChange = { descInput = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            colors = AppComponents.outlinedTextFieldColors(),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { commitDesc() },
                            modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Confirm", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(
                            onClick = {
                                descInput = description
                                isEditingDesc = false
                            },
                            modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Text(
                            text = description.ifBlank { stringResource("settings.skills.editor.description") },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (description.isBlank()) {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                descInput = description
                                isEditingDesc = true
                            },
                            modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit description", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }

        // ── System prompt editor (auto-save on focus loss) ────────────────────
        var isPreviewMode by remember(skill.relativePath) { mutableStateOf(true) }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = stringResource("settings.skills.editor.system.prompt"),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    )
                    Text(
                        text = stringResource("settings.skills.editor.system.prompt.hint"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                }
                // Read / Edit segmented toggle
                readEditToggle(isPreviewMode = isPreviewMode, onToggle = { isPreviewMode = it })
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 260.dp),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Box(modifier = Modifier.padding(16.dp)) {
                    if (isPreviewMode) {
                        revealingMarkdownText(
                            markdown = body.ifBlank { "*${stringResource("settings.skills.editor.system.prompt.placeholder")}*" },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        BasicTextField(
                            value = body,
                            onValueChange = { body = it },
                            modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) autoSave() },
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                lineHeight = 21.sp,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { innerTextField ->
                                if (body.isEmpty()) {
                                    Text(
                                        text = stringResource("settings.skills.editor.system.prompt.placeholder"),
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                        ),
                                    )
                                }
                                innerTextField()
                            },
                        )
                    }
                } // end Box padding
            } // end Surface
        }
    }
}

// ── Generic file editor ──────────────────────────────────────────────────────

@Composable
private fun fileEditorContent(
    leaf: SkillTreeNode.Leaf,
    onSave: (absolutePath: Path, content: String) -> Unit,
) {
    val initialContent = remember(leaf.path) {
        runCatching { Files.readString(leaf.absolutePath) }.getOrDefault("")
    }
    var body by remember(leaf.path) { mutableStateOf(initialContent) }
    val isMarkdown = leaf.name.endsWith(".md")
    // Start in edit mode for new/empty files; preview mode only for markdown with existing content
    var isPreviewMode by remember(leaf.path) { mutableStateOf(false) }
    val lineCount = body.lines().size

    // Inline rename state
    var isRenaming by remember(leaf.path) { mutableStateOf(false) }
    var renameInput by remember(leaf.path) { mutableStateOf(leaf.name) }
    // Track current display name (updated after successful rename)
    var currentName by remember(leaf.path) { mutableStateOf(leaf.name) }

    val fileEmoji = when {
        currentName.endsWith(".md") -> "📄"
        currentName.endsWith(".java") || currentName.endsWith(".kt") -> "☕"
        currentName.endsWith(".json") -> "🔧"
        currentName.endsWith(".txt") -> "📃"
        else -> "📎"
    }

    // Track the actual current absolute path (updates after rename)
    var currentAbsolutePath by remember(leaf.path) { mutableStateOf(leaf.absolutePath) }

    fun saveContent() {
        runCatching { Files.writeString(currentAbsolutePath, body) }
        onSave(currentAbsolutePath, body)
    }

    fun commitRename() {
        val newName = renameInput.trim()
        if (newName.isNotBlank() && newName != currentName) {
            runCatching {
                val newAbsolute = leaf.absolutePath.parent.resolve(newName)
                Files.writeString(newAbsolute, body)
                Files.deleteIfExists(currentAbsolutePath)
                currentName = newName
                currentAbsolutePath = newAbsolute
                onSave(newAbsolute, body)
            }
        }
        isRenaming = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.large)) {
        // ── Header: emoji · filename (with inline rename)  |  Read/Edit toggle ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: file icon + filename or rename input (takes all available space)
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
                modifier = if (isRenaming) Modifier.fillMaxWidth() else Modifier.weight(1f),
            ) {
                themedTooltip(text = leaf.path) {
                    Text(fileEmoji, style = MaterialTheme.typography.titleLarge)
                }
                if (isRenaming) {
                    OutlinedTextField(
                        value = renameInput,
                        onValueChange = { renameInput = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        colors = AppComponents.outlinedTextFieldColors(),
                        modifier = Modifier.weight(1f),
                    )
                    // OK button — commit rename
                    IconButton(
                        onClick = { commitRename() },
                        modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Confirm rename",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    // Cancel rename — discard without saving
                    IconButton(
                        onClick = {
                            renameInput = currentName
                            isRenaming = false
                        },
                        modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Cancel rename",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        text = currentName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // Rename is not allowed for skill.md entry files
                    if (!leaf.isSkillEntry) {
                        IconButton(
                            onClick = {
                                renameInput = currentName
                                isRenaming = true
                            },
                            modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Rename file",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }

            // Right: Read/Edit toggle (md only) — hidden while renaming
            if (isMarkdown && !isRenaming) {
                readEditToggle(isPreviewMode = isPreviewMode, onToggle = { isPreviewMode = it })
            }
        }

        // ── Editor / preview (auto-save on focus loss) ────────────────────────
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp),
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Box(modifier = Modifier.padding(16.dp).fillMaxSize()) {
                if (isMarkdown && isPreviewMode) {
                    revealingMarkdownText(
                        markdown = body.ifBlank { "*${stringResource("settings.skills.editor.empty.file")}*" },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    BasicTextField(
                        value = body,
                        onValueChange = { body = it },
                        modifier = Modifier.fillMaxSize().onFocusChanged { if (!it.isFocused) saveContent() },
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 21.sp,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    )
                }
            } // end Box padding
        } // end Surface

        // ── Footer: line count ────────────────────────────────────────────────
        Text(
            text = if (lineCount == 1) {
                stringResource("settings.skills.editor.line.count").replace("{0}", "$lineCount")
            } else {
                stringResource("settings.skills.editor.line.count.plural").replace("{0}", "$lineCount")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
        )
    }
}

// ── Skills tree panel ─────────────────────────────────────────────────────────

@Composable
private fun skillsTreePanel(
    tree: List<SkillTreeNode>,
    selectedSkill: SkillDefinition?,
    onSelectSkill: (SkillDefinition) -> Unit,
    onSelectFile: (SkillTreeNode.Leaf) -> Unit,
    onDeleteSkill: (SkillDefinition) -> Unit,
    onDeleteFile: (relativePath: String) -> Unit = {},
    onDeleteFolder: (folderRelativePath: String) -> Unit = {},
    onCollapse: () -> Unit,
    onNewSkill: () -> Unit,
    onNewSkillInFolder: (parentPath: String) -> Unit = {},
    onImportFromGitHub: () -> Unit = {},
    onImportFromZip: () -> Unit = {},
    onAddFileInFolder: (folderRelativePath: String) -> Unit = {},
    onAddFolderInFolder: (folderRelativePath: String) -> Unit = {},
    onDeleteAll: () -> Unit = {},
) {
    val scrollState = rememberScrollState()
    var showAddMenu by remember { mutableStateOf(false) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val filteredTree = remember(tree, searchQuery) {
        filterSkillTree(tree, searchQuery)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Panel header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource("settings.skills"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                // + dropdown button
                Box {
                    IconButton(
                        onClick = { showAddMenu = true },
                        modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(18.dp))
                    }
                    AppComponents.dropdownMenu(
                        expanded = showAddMenu,
                        onDismissRequest = { showAddMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource("settings.skills.new.skill"), style = MaterialTheme.typography.bodyMedium) },
                            onClick = {
                                showAddMenu = false
                                onNewSkill()
                            },
                            leadingIcon = { Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource("settings.skills.import.github"), style = MaterialTheme.typography.bodyMedium) },
                            onClick = {
                                showAddMenu = false
                                onImportFromGitHub()
                            },
                            leadingIcon = { Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource("settings.skills.import.zip"), style = MaterialTheme.typography.bodyMedium) },
                            onClick = {
                                showAddMenu = false
                                onImportFromZip()
                            },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        )
                    }
                }
                // Delete all button
                IconButton(
                    onClick = { showDeleteAllConfirm = true },
                    modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete all skills", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                }
                // Collapse button
                IconButton(
                    onClick = onCollapse,
                    modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Collapse", modifier = Modifier.size(18.dp))
                }
            }
        }
        HorizontalDivider()

        appOutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(stringResource("skills.view.search.placeholder")) },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
            },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(
                        onClick = { searchQuery = "" },
                        modifier = Modifier.size(20.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = stringResource("skills.view.search.clear"), modifier = Modifier.size(14.dp))
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

        if (showDeleteAllConfirm) {
            deleteAllSkillsDialog(
                onDismiss = { showDeleteAllConfirm = false },
                onConfirm = {
                    showDeleteAllConfirm = false
                    onDeleteAll()
                },
            )
        }

        // Tree content
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (filteredTree.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (searchQuery.isBlank()) {
                                stringResource("settings.skills.empty")
                            } else {
                                stringResource("skills.view.empty.search")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        )
                    }
                } else {
                    filteredTree.forEach { node ->
                        skillTreeNodeItem(
                            node = node,
                            selectedSkill = selectedSkill,
                            onSelectSkill = onSelectSkill,
                            onSelectFile = onSelectFile,
                            onDeleteSkill = onDeleteSkill,
                            onDeleteFile = onDeleteFile,
                            onDeleteFolder = onDeleteFolder,
                            onNewSkillInFolder = onNewSkillInFolder,
                            onAddFileInFolder = onAddFileInFolder,
                            onAddFolderInFolder = onAddFolderInFolder,
                            forceExpanded = searchQuery.isNotBlank(),
                        )
                    }
                }
            }

            VerticalScrollbar(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                adapter = rememberScrollbarAdapter(scrollState),
                style = AppComponents.scrollbarStyle(),
            )
        }
    }
}

@Composable
private fun skillTreeNodeItem(
    node: SkillTreeNode,
    depth: Int = 0,
    selectedSkill: SkillDefinition?,
    onSelectSkill: (SkillDefinition) -> Unit,
    onSelectFile: (SkillTreeNode.Leaf) -> Unit,
    onDeleteSkill: (SkillDefinition) -> Unit,
    onDeleteFile: (relativePath: String) -> Unit = {},
    onDeleteFolder: (folderRelativePath: String) -> Unit = {},
    onNewSkillInFolder: (parentPath: String) -> Unit = {},
    onAddFileInFolder: (folderRelativePath: String) -> Unit = {},
    onAddFolderInFolder: (folderRelativePath: String) -> Unit = {},
    forceExpanded: Boolean = false,
) {
    when (node) {
        is SkillTreeNode.Category -> {
            var isExpanded by remember { mutableStateOf(true) }
            var showDeleteConfirm by remember { mutableStateOf(false) }
            var showAddMenu by remember { mutableStateOf(false) }
            val interactionSource = remember { MutableInteractionSource() }
            val isHovered by interactionSource.collectIsHoveredAsState()
            val isSkillFolder = node.isSkillFolder && node.skill != null
            val isSelected = isSkillFolder && selectedSkill?.relativePath == node.skill?.relativePath
            val isExpandedNow = if (forceExpanded) true else isExpanded

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent,
                            shape = MaterialTheme.shapes.small,
                        )
                        .hoverable(interactionSource)
                        .clickable {
                            if (isSkillFolder) {
                                onSelectSkill(node.skill!!)
                            } else if (!forceExpanded) {
                                isExpanded = !isExpanded
                            }
                        }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(start = (depth * 12).dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    // Expand/collapse arrow for all folders
                    if (node.children.isNotEmpty()) {
                        Icon(
                            if (isExpandedNow) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = if (isExpandedNow) "Collapse" else "Expand",
                            modifier = Modifier.size(14.dp).clickable {
                                if (!forceExpanded) {
                                    isExpanded = !isExpanded
                                }
                            },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Spacer(Modifier.size(14.dp))
                    }
                    Icon(
                        if (isSkillFolder) Icons.Default.Extension else Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = node.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSkillFolder) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isSkillFolder) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    if (isHovered || showAddMenu) {
                        // + button with dropdown
                        Box {
                            IconButton(
                                onClick = { showAddMenu = true },
                                modifier = Modifier.size(20.dp).pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            AppComponents.dropdownMenu(
                                expanded = showAddMenu,
                                onDismissRequest = { showAddMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource("settings.skills.add.file"), style = MaterialTheme.typography.bodyMedium) },
                                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp)) },
                                    onClick = {
                                        showAddMenu = false
                                        onAddFileInFolder(node.path)
                                    },
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource("settings.skills.add.folder"), style = MaterialTheme.typography.bodyMedium) },
                                    leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(14.dp)) },
                                    onClick = {
                                        showAddMenu = false
                                        onAddFolderInFolder(node.path)
                                    },
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                runCatching {
                                    val absoluteDir = AskimoHome.skillsDir().resolve(node.path).toFile()
                                    Desktop.getDesktop().open(absoluteDir.also { it.mkdirs() })
                                }
                            },
                            modifier = Modifier.size(20.dp).pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "Open folder", modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.size(20.dp).pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (isExpandedNow && node.children.isNotEmpty()) {
                    node.children.forEach { child ->
                        skillTreeNodeItem(
                            node = child,
                            depth = depth + 1,
                            selectedSkill = selectedSkill,
                            onSelectSkill = onSelectSkill,
                            onSelectFile = onSelectFile,
                            onDeleteSkill = onDeleteSkill,
                            onDeleteFile = onDeleteFile,
                            onDeleteFolder = onDeleteFolder,
                            onNewSkillInFolder = onNewSkillInFolder,
                            onAddFileInFolder = onAddFileInFolder,
                            onAddFolderInFolder = onAddFolderInFolder,
                            forceExpanded = forceExpanded,
                        )
                    }
                }
            }

            if (showDeleteConfirm) {
                deleteFolderConfirmDialog(
                    folderName = node.name,
                    onDismiss = { showDeleteConfirm = false },
                    onConfirm = {
                        showDeleteConfirm = false
                        onDeleteFolder(node.path)
                    },
                )
            }
        }

        is SkillTreeNode.Leaf -> {
            val isSelected = selectedSkill?.relativePath == node.path ||
                (selectedSkill?.relativePath == node.skill?.relativePath && node.skill != null)
            val interactionSource = remember { MutableInteractionSource() }
            val isHovered by interactionSource.collectIsHoveredAsState()
            var showDeleteConfirm by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (depth * 12).dp)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent,
                        shape = MaterialTheme.shapes.small,
                    )
                    .hoverable(interactionSource)
                    .clickable { onSelectFile(node) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    // File type indicator
                    Text(
                        text = when {
                            node.isSkillEntry -> "📋"
                            node.name.endsWith(".md") -> "📄"
                            node.name.endsWith(".java") || node.name.endsWith(".kt") -> "☕"
                            node.name.endsWith(".json") -> "🔧"
                            node.name.endsWith(".txt") -> "📃"
                            else -> "📎"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Column {
                        Text(
                            text = if (node.isSkillEntry) node.name else node.name,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isSelected || node.isSkillEntry) FontWeight.SemiBold else FontWeight.Normal,
                            color = when {
                                node.isSkillEntry -> MaterialTheme.colorScheme.onSurface
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                        if (node.skill?.description?.isNotBlank() == true && node.isSkillEntry) {
                            Text(
                                text = node.skill?.description ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (isHovered && !node.isSkillEntry) {
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.size(20.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(11.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (showDeleteConfirm) {
                deleteFileConfirmDialog(
                    fileName = node.name,
                    onDismiss = { showDeleteConfirm = false },
                    onConfirm = {
                        showDeleteConfirm = false
                        val skill = node.skill
                        if (skill != null) onDeleteSkill(skill) else onDeleteFile(node.path)
                    },
                )
            }
        }
    }
}

private fun filterSkillTree(
    tree: List<SkillTreeNode>,
    query: String,
): List<SkillTreeNode> {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank()) return tree

    fun SkillTreeNode.matchesSelf(): Boolean = when (this) {
        is SkillTreeNode.Category -> {
            name.lowercase().contains(normalizedQuery) ||
                path.lowercase().contains(normalizedQuery) ||
                (skill?.name?.lowercase()?.contains(normalizedQuery) == true) ||
                (skill?.description?.lowercase()?.contains(normalizedQuery) == true)
        }

        is SkillTreeNode.Leaf -> {
            name.lowercase().contains(normalizedQuery) ||
                path.lowercase().contains(normalizedQuery) ||
                (skill?.name?.lowercase()?.contains(normalizedQuery) == true) ||
                (skill?.description?.lowercase()?.contains(normalizedQuery) == true)
        }
    }

    fun filterNode(node: SkillTreeNode): SkillTreeNode? = when (node) {
        is SkillTreeNode.Leaf -> if (node.matchesSelf()) node else null

        is SkillTreeNode.Category -> {
            val filteredChildren = node.children.mapNotNull(::filterNode)
            if (node.matchesSelf() || filteredChildren.isNotEmpty()) {
                node.copy(children = filteredChildren)
            } else {
                null
            }
        }
    }

    return tree.mapNotNull(::filterNode)
}

@Composable
private fun readEditToggle(
    isPreviewMode: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
    ) {
        // Read (preview) segment
        previewEditSegmentButton(
            isPreview = true,
            label = stringResource("settings.skills.editor.mode.read"),
            icon = Icons.Default.Visibility,
            isActive = isPreviewMode,
            hasBorderStart = false,
            onToggle = onToggle,
        )
        // Edit segment
        previewEditSegmentButton(
            isPreview = false,
            label = stringResource("settings.skills.editor.mode.edit"),
            icon = Icons.Default.Edit,
            isActive = !isPreviewMode,
            hasBorderStart = true,
            onToggle = onToggle,
        )
    }
}

@Composable
private fun previewEditSegmentButton(
    isPreview: Boolean,
    label: String,
    icon: ImageVector,
    isActive: Boolean,
    hasBorderStart: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Box(
        modifier = Modifier
            .background(
                if (isActive) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                },
            )
            .then(
                if (hasBorderStart) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RectangleShape)
                } else {
                    Modifier
                },
            )
            .clickable { onToggle(isPreview) }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(13.dp),
                tint = if (isActive) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isActive) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

private fun openSkillsFolder(path: Path) {
    runCatching { Desktop.getDesktop().open(path.toFile().also { it.mkdirs() }) }
}

// ── Dialogs ───────────────────────────────────────────────────────────────────

@Composable
private fun importErrorBanner(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun newSkillInFolderDialog(
    parentPath: String,
    onDismiss: () -> Unit,
    onConfirm: (skillFileName: String) -> Unit,
) {
    var fileName by remember { mutableStateOf("") }
    AppComponents.alertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("settings.skills.new.skill")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text(
                    text = "📁 $parentPath/",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it.replace(" ", "-").lowercase() },
                    label = { Text(stringResource("settings.skills.editor.name")) },
                    placeholder = { Text(stringResource("settings.skills.new.skill.placeholder")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            primaryButton(
                onClick = { onConfirm(fileName.trim()) },
                enabled = fileName.isNotBlank(),
            ) { Text(stringResource("action.create")) }
        },
        dismissButton = {
            secondaryButton(onClick = onDismiss) {
                Text(stringResource("action.cancel"))
            }
        },
    )
}

@Composable
private fun importFromGitHubDialog(
    onDismiss: () -> Unit,
    onImported: (message: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val importSuccessTemplate = stringResource("settings.skills.import.success.message")
    var repoUrl by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AppComponents.alertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = { Text(stringResource("settings.skills.import.github")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text(
                    text = stringResource("settings.skills.import.github.description"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                appOutlinedTextField(
                    value = repoUrl,
                    onValueChange = {
                        repoUrl = it
                        errorMessage = null
                    },
                    label = { Text(stringResource("settings.skills.import.github.url.label")) },
                    placeholder = { Text("https://github.com/user/my-skills") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isImporting,
                )

                if (errorMessage != null) {
                    importErrorBanner(errorMessage!!)
                }
            }
        },
        confirmButton = {
            primaryButton(
                onClick = {
                    scope.launch {
                        isImporting = true
                        errorMessage = null
                        val result = withContext(Dispatchers.IO) {
                            SkillImporter.importFromGitHub(repoUrl.trim())
                        }
                        when (result) {
                            is SkillImporter.ImportResult.Success -> {
                                onImported(
                                    importSuccessTemplate
                                        .replace("{0}", result.skillCount.toString())
                                        .replace("{1}", result.targetDir.fileName.toString()),
                                )
                            }

                            is SkillImporter.ImportResult.Failure -> {
                                errorMessage = result.message
                                isImporting = false
                            }
                        }
                    }
                },
                enabled = repoUrl.isNotBlank() && !isImporting,
            ) {
                Text(if (isImporting) stringResource("settings.skills.import.github.importing") else stringResource("settings.skills.import.github.button"))
            }
        },
        dismissButton = {
            secondaryButton(onClick = onDismiss, enabled = !isImporting) {
                Text(stringResource("action.cancel"))
            }
        },
    )
}

@Composable
private fun importFromZipDialog(
    onDismiss: () -> Unit,
    onImported: (message: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val importSuccessTemplate = stringResource("settings.skills.import.success.message")
    val zipPickerTitle = stringResource("settings.skills.import.zip.picker.title")
    var selectedZipPath by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AppComponents.alertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = { Text(stringResource("settings.skills.import.zip")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text(
                    text = stringResource("settings.skills.import.zip.description"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                secondaryButton(
                    onClick = {
                        scope.launch {
                            if (isImporting) return@launch
                            errorMessage = null
                            val path = FileDialogUtils.pickFilePath(
                                title = zipPickerTitle,
                                extensions = listOf("zip"),
                            ) ?: return@launch
                            selectedZipPath = path
                        }
                    },
                    enabled = !isImporting,
                ) {
                    Text(stringResource("settings.skills.import.zip.pick.button"))
                }

                if (selectedZipPath != null) {
                    Text(
                        text = Path.of(selectedZipPath!!).fileName.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }

                if (errorMessage != null) {
                    importErrorBanner(errorMessage!!)
                }
            }
        },
        confirmButton = {
            primaryButton(
                onClick = {
                    scope.launch {
                        val path = selectedZipPath ?: return@launch
                        isImporting = true
                        errorMessage = null
                        val result = withContext(Dispatchers.IO) {
                            SkillImporter.importFromZip(Path.of(path))
                        }
                        when (result) {
                            is SkillImporter.ImportResult.Success -> {
                                onImported(
                                    importSuccessTemplate
                                        .replace("{0}", result.skillCount.toString())
                                        .replace("{1}", result.targetDir.fileName.toString()),
                                )
                            }

                            is SkillImporter.ImportResult.Failure -> {
                                errorMessage = result.message
                                isImporting = false
                            }
                        }
                    }
                },
                enabled = selectedZipPath != null && !isImporting,
            ) {
                Text(
                    if (isImporting) {
                        stringResource("settings.skills.import.zip.importing")
                    } else {
                        stringResource("settings.skills.import.zip.button")
                    },
                )
            }
        },
        dismissButton = {
            secondaryButton(onClick = onDismiss, enabled = !isImporting) {
                Text(stringResource("action.cancel"))
            }
        },
    )
}

@Composable
private fun addFileInFolderDialog(
    parentPath: String,
    onDismiss: () -> Unit,
    onConfirm: (fileName: String) -> Unit,
) {
    var fileName by remember { mutableStateOf("") }
    AppComponents.alertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("settings.skills.add.file")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text(
                    text = "📁 $parentPath/",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text(stringResource("settings.skills.add.file.name")) },
                    placeholder = { Text("notes.md") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            primaryButton(
                onClick = { onConfirm(fileName.trim()) },
                enabled = fileName.isNotBlank(),
            ) { Text(stringResource("action.create")) }
        },
        dismissButton = {
            secondaryButton(onClick = onDismiss) {
                Text(stringResource("action.cancel"))
            }
        },
    )
}

@Composable
private fun addFolderInFolderDialog(
    parentPath: String,
    onDismiss: () -> Unit,
    onConfirm: (folderName: String) -> Unit,
) {
    var folderName by remember { mutableStateOf("") }
    AppComponents.alertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("settings.skills.add.folder")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text(
                    text = "📁 $parentPath/",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it.replace(" ", "-").lowercase() },
                    label = { Text(stringResource("settings.skills.add.folder.name")) },
                    placeholder = { Text("examples") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            primaryButton(
                onClick = { onConfirm(folderName.trim()) },
                enabled = folderName.isNotBlank(),
            ) { Text(stringResource("action.create")) }
        },
        dismissButton = {
            secondaryButton(onClick = onDismiss) {
                Text(stringResource("action.cancel"))
            }
        },
    )
}

@Composable
private fun deleteFileConfirmDialog(
    fileName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AppComponents.alertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("settings.skills.delete.file.title")) },
        text = {
            Text(
                text = stringResource("settings.skills.delete.file.confirm").replace("{0}", fileName),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            dangerButton(onClick = onConfirm) { Text(stringResource("action.delete")) }
        },
        dismissButton = {
            secondaryButton(onClick = onDismiss) { Text(stringResource("action.cancel")) }
        },
    )
}

@Composable
private fun deleteFolderConfirmDialog(
    folderName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AppComponents.alertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("settings.skills.delete.folder.title")) },
        text = {
            Text(
                text = stringResource("settings.skills.delete.folder.confirm").replace("{0}", folderName),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            dangerButton(onClick = onConfirm) { Text(stringResource("action.delete")) }
        },
        dismissButton = {
            secondaryButton(onClick = onDismiss) { Text(stringResource("action.cancel")) }
        },
    )
}

@Composable
private fun deleteAllSkillsDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AppComponents.alertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("settings.skills.delete.all.title")) },
        text = {
            Text(
                text = stringResource("settings.skills.delete.all.confirm"),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            dangerButton(onClick = onConfirm) { Text(stringResource("action.delete")) }
        },
        dismissButton = {
            secondaryButton(onClick = onDismiss) { Text(stringResource("action.cancel")) }
        },
    )
}

@Composable
private fun importSuccessDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    AppComponents.alertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("settings.skills.import.success.title")) },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        confirmButton = {
            primaryButton(
                onClick = onDismiss,
            ) { Text(stringResource("action.ok")) }
        },
    )
}
