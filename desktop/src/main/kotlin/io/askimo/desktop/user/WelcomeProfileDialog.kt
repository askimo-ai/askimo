/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.user

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.askimo.core.user.domain.UserProfile
import io.askimo.ui.common.components.linkButton
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.service.AvatarService
import kotlinx.coroutines.launch

/**
 * Welcome dialog shown on first run to collect basic user profile information.
 * This is a simplified version of the full profile dialog focusing on essential fields.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun welcomeProfileDialog(
    onComplete: (UserProfile) -> Unit,
    onSkip: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var occupation by remember { mutableStateOf("") }
    var selectedInterests by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Avatar handling
    val scope = rememberCoroutineScope()
    val avatarService = remember { AvatarService() }
    var avatarPath by remember { mutableStateOf<String?>(null) }
    var avatarImage by remember { mutableStateOf<ImageBitmap?>(null) }

    Dialog(onDismissRequest = { /* Prevent dismissal - user must choose */ }) {
        Surface(
            modifier = Modifier.width(550.dp).height(650.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
            ) {
                // Welcome Header
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "👋 " + stringResource("welcome.title"),
                        style = AppTextStyles.pageTitle,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(Spacing.small))

                    Text(
                        text = stringResource("welcome.subtitle"),
                        style = AppTextStyles.bodySecondary,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(Spacing.extraLarge))

                // Scrollable content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.large),
                ) {
                    // Avatar section (optional)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                .clickable {
                                    scope.launch {
                                        val (path, bitmap) = avatarService.pickAndSaveUserAvatar() ?: return@launch
                                        avatarPath = path
                                        avatarImage = bitmap
                                    }
                                }
                                .pointerHoverIcon(PointerIcon.Hand),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (avatarImage != null) {
                                Image(
                                    bitmap = avatarImage!!,
                                    contentDescription = stringResource("user.profile.avatar"),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                val initials = name.split(" ")
                                    .mapNotNull { it.firstOrNull()?.uppercase() }
                                    .take(2)
                                    .joinToString("")
                                    .ifBlank { "?" }

                                Text(
                                    text = initials,
                                    style = AppTextStyles.emptyStateEmoji,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }

                        Spacer(Modifier.height(Spacing.extraSmall))

                        Text(
                            text = stringResource("welcome.avatar.optional"),
                            style = AppTextStyles.caption,
                        )
                    }

                    HorizontalDivider()

                    // Name field (required)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = {
                            Text(stringResource("welcome.name") + " *")
                        },
                        placeholder = { Text(stringResource("welcome.name.placeholder")) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = AppColors.outlinedTextFieldColors(),
                        singleLine = true,
                    )

                    // Occupation field (optional)
                    OutlinedTextField(
                        value = occupation,
                        onValueChange = { occupation = it },
                        label = { Text(stringResource("welcome.occupation")) },
                        placeholder = { Text(stringResource("welcome.occupation.placeholder")) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = AppColors.outlinedTextFieldColors(),
                        singleLine = true,
                    )

                    // Interests
                    Text(
                        text = stringResource("welcome.interests"),
                        style = AppTextStyles.itemTitle,
                    )

                    Text(
                        text = stringResource("welcome.interests.description"),
                        style = AppTextStyles.sectionDescription,
                    )

                    // Top interest chips (show 8 most common)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        listOf(
                            UserInterestCategory.TECHNOLOGY,
                            UserInterestCategory.SCIENCE,
                            UserInterestCategory.BUSINESS,
                            UserInterestCategory.HEALTH,
                            UserInterestCategory.ARTS,
                            UserInterestCategory.SPORTS,
                            UserInterestCategory.MUSIC,
                            UserInterestCategory.TRAVEL,
                        ).forEach { category ->
                            FilterChip(
                                selected = category.key in selectedInterests,
                                onClick = {
                                    selectedInterests = if (category.key in selectedInterests) {
                                        selectedInterests - category.key
                                    } else {
                                        selectedInterests + category.key
                                    }
                                },
                                label = { Text(stringResource(category.displayKey)) },
                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.extraLarge))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    linkButton(
                        onClick = onSkip,
                    ) {
                        Text(stringResource("welcome.skip"))
                    }

                    primaryButton(
                        onClick = {
                            val updatedPreferences = mutableMapOf<String, String>()
                            avatarPath?.let { updatedPreferences["avatarPath"] = it }

                            val profile = UserProfile(
                                id = "default", // Use default profile ID
                                name = name.ifBlank { null },
                                occupation = occupation.ifBlank { null },
                                interests = selectedInterests.toList(),
                                preferences = updatedPreferences,
                            )
                            onComplete(profile)
                        },
                        enabled = name.isNotBlank(), // Require at least name
                    ) {
                        Text(stringResource("welcome.get_started"))
                    }
                }

                // Required field note
                Text(
                    text = stringResource("welcome.required_note"),
                    style = AppTextStyles.caption,
                    modifier = Modifier.padding(top = Spacing.small),
                )
            }
        }
    }
}
