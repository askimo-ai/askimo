/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing

/**
 * Banner shown when RAG indexing is unavailable because the active AI provider has no
 * embedding model configured (or doesn't support embeddings at all). Copy/action swap
 * between "configure embedding model" and "switch provider" based on [providerSupportsEmbedding].
 *
 * Reused by the projects, project detail, and resource collections views.
 *
 * @param onConfigureClick Navigates to the AI Provider settings section.
 * @param notConfiguredMessageKey i18n key shown when an embedding model just needs configuring.
 * @param unsupportedProviderMessageKey i18n key shown when the provider can't support embeddings.
 * @param configureActionKey i18n key for the action button in the "not configured" case.
 * @param switchProviderActionKey i18n key for the action button in the "unsupported" case.
 */
@Composable
fun embeddingModelNotConfiguredBanner(
    providerSupportsEmbedding: Boolean,
    onConfigureClick: () -> Unit,
    notConfiguredMessageKey: String = "projects.rag.embedding.not.configured",
    unsupportedProviderMessageKey: String = "projects.rag.embedding.unsupported.provider",
    configureActionKey: String = "projects.rag.embedding.configure",
    switchProviderActionKey: String = "projects.rag.embedding.switch.provider",
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = AppColors.cardColors(AppColors.Elevation.RAISED),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = AppTextStyles.primaryContent,
                )
                SelectionContainer {
                    Text(
                        text = stringResource(
                            if (providerSupportsEmbedding) {
                                notConfiguredMessageKey
                            } else {
                                unsupportedProviderMessageKey
                            },
                        ),
                        style = AppTextStyles.caption.copy(color = AppTextStyles.secondaryContent),
                    )
                }
            }
            linkButton(onClick = onConfigureClick) {
                Text(
                    text = stringResource(
                        if (providerSupportsEmbedding) {
                            configureActionKey
                        } else {
                            switchProviderActionKey
                        },
                    ),
                    style = AppTextStyles.caption,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
