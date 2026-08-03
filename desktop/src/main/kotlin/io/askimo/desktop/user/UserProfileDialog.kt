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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.askimo.core.user.domain.UserProfile
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.service.AvatarService
import kotlinx.coroutines.launch
import java.io.File
import org.jetbrains.skia.Image as SkiaImage

/**
 * User interest categories that users can select
 */
enum class UserInterestCategory(val key: String, val displayKey: String) {
    TECHNOLOGY("technology", "user.interest.technology"),
    SCIENCE("science", "user.interest.science"),
    TRAVEL("travel", "user.interest.travel"),
    POLITICS("politics", "user.interest.politics"),
    BUSINESS("business", "user.interest.business"),
    HEALTH("health", "user.interest.health"),
    ARTS("arts", "user.interest.arts"),
    SPORTS("sports", "user.interest.sports"),
    COOKING("cooking", "user.interest.cooking"),
    LITERATURE("literature", "user.interest.literature"),
    MUSIC("music", "user.interest.music"),
    MOVIES("movies", "user.interest.movies"),
    GAMING("gaming", "user.interest.gaming"),
    FINANCE("finance", "user.interest.finance"),
    EDUCATION("education", "user.interest.education"),
    ENVIRONMENT("environment", "user.interest.environment"),
}

/**
 * User profile dialog for viewing and editing user information
 */
@Composable
fun userProfileDialog(
    profile: UserProfile,
    onDismiss: () -> Unit,
    onSave: (UserProfile) -> Unit,
) {
    var name by remember { mutableStateOf(profile.name ?: "") }
    var email by remember { mutableStateOf(profile.email ?: "") }
    var occupation by remember { mutableStateOf(profile.occupation ?: "") }
    var location by remember { mutableStateOf(profile.location ?: "") }
    var bio by remember { mutableStateOf(profile.bio ?: "") }

    // Parse existing interests into predefined and custom
    val existingInterests = profile.interests
    val predefinedInterests = UserInterestCategory.entries.map { it.key }.toSet()
    var selectedInterests by remember {
        mutableStateOf(
            existingInterests.filter { it in predefinedInterests }.toSet(),
        )
    }
    var customInterests by remember {
        mutableStateOf(
            existingInterests.filter { it !in predefinedInterests }.joinToString(", "),
        )
    }

    // Avatar handling
    val scope = rememberCoroutineScope()
    val avatarService = remember { AvatarService() }
    var avatarPath by remember { mutableStateOf(profile.preferences["avatarPath"]) }
    var avatarImage by remember { mutableStateOf<ImageBitmap?>(null) }

    // Load existing avatar if available
    remember(avatarPath) {
        avatarPath?.let { path ->
            try {
                val file = File(path)
                if (file.exists()) {
                    val bytes = file.readBytes()
                    avatarImage = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                }
            } catch (_: Exception) {
                // Failed to load avatar, use default
                avatarImage = null
            }
        }
    }

    AppComponents.scaffoldDialog(
        onDismissRequest = onDismiss,
        onCloseRequest = onDismiss,
        width = 720.dp,
        title = {
            Text(
                text = stringResource("user.profile.title"),
                style = AppTextStyles.pageTitle,
            )
        },
        actions = {
            secondaryButton(onClick = onDismiss) {
                Text(stringResource("action.cancel"))
            }
            Spacer(Modifier.width(Spacing.small))
            primaryButton(
                onClick = {
                    val allInterests = selectedInterests.toMutableSet()
                    customInterests.split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .forEach { allInterests.add(it) }

                    val updatedPreferences = profile.preferences.toMutableMap()
                    avatarPath?.let { updatedPreferences["avatarPath"] = it }

                    onSave(
                        profile.copy(
                            name = name.ifBlank { null },
                            email = email.ifBlank { null },
                            occupation = occupation.ifBlank { null },
                            location = location.ifBlank { null },
                            bio = bio.ifBlank { null },
                            interests = allInterests.toList(),
                            preferences = updatedPreferences,
                        ),
                    )
                },
            ) {
                Text(stringResource("action.save"))
            }
        },
        stickyHeader = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
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
                            modifier = Modifier.size(96.dp),
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
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Text(
                    text = stringResource("user.profile.avatar.click_to_change"),
                    style = AppTextStyles.caption,
                )
            }
        },
        showSectionDividers = true,
        content = {
            // ── Basic Information ─────────────────────────────────────────────
            Text(
                text = stringResource("user.profile.basic_info"),
                style = AppTextStyles.sectionTitle,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource("user.profile.name")) },
                colors = AppComponents.outlinedTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(stringResource("user.profile.email")) },
                placeholder = { Text(stringResource("user.profile.email.placeholder")) },
                colors = AppComponents.outlinedTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = occupation,
                onValueChange = { occupation = it },
                label = { Text(stringResource("user.profile.occupation")) },
                placeholder = { Text(stringResource("user.profile.occupation.placeholder")) },
                colors = AppComponents.outlinedTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                label = { Text(stringResource("user.profile.location")) },
                placeholder = { Text(stringResource("user.profile.location.placeholder")) },
                colors = AppComponents.outlinedTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = bio,
                onValueChange = { bio = it },
                label = { Text(stringResource("user.profile.bio")) },
                placeholder = { Text(stringResource("user.profile.bio.placeholder")) },
                colors = AppComponents.outlinedTextFieldColors(),
                modifier = Modifier.fillMaxWidth().height(100.dp),
                maxLines = 4,
            )

            HorizontalDivider()

            // ── Interests ─────────────────────────────────────────────────────
            Text(
                text = stringResource("user.profile.interests"),
                style = AppTextStyles.sectionTitle,
            )
            Text(
                text = stringResource("user.profile.interests.description"),
                style = AppTextStyles.caption,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                UserInterestCategory.entries.forEach { category ->
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
            OutlinedTextField(
                value = customInterests,
                onValueChange = { customInterests = it },
                label = { Text(stringResource("user.profile.interests.custom")) },
                placeholder = { Text(stringResource("user.profile.interests.custom.placeholder")) },
                colors = AppComponents.outlinedTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text(
                        text = stringResource("user.profile.interests.custom.hint"),
                        style = AppTextStyles.caption,
                    )
                },
            )
        },
    )
}
