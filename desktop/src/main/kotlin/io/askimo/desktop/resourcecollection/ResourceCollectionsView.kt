/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.resourcecollection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing

/**
 * Empty view for Resource Collections.
 *
 * This view displays a placeholder message when no resource collections exist,
 * with a button to create a new one.
 *
 * Future enhancements:
 * - List existing collections with star/delete/manage actions
 * - Search and filter collections
 * - Bulk operations
 */
@Composable
fun resourceCollectionsView(
    onNewCollection: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(Spacing.extraLarge)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Empty state message
            Text(
                text = stringResource("resourcecollections.empty.title"),
                style = AppTextStyles.pageTitle,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Text(
                text = stringResource("resourcecollections.empty.subtitle"),
                style = AppTextStyles.bodySecondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.medium),
            )

            // Create collection button
            Button(
                onClick = onNewCollection,
                modifier = Modifier.padding(top = Spacing.extraLarge),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.padding(end = Spacing.small),
                )
                Text(stringResource("resourcecollection.create"))
            }
        }
    }
}
