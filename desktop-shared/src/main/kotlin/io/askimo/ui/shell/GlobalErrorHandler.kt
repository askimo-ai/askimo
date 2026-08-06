/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.shell
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.askimo.core.event.EventBus
import io.askimo.core.event.error.AppErrorEvent
import io.askimo.core.event.error.IndexingErrorEvent
import io.askimo.core.event.error.IndexingErrorType
import io.askimo.core.event.error.ModelNotAvailableEvent
import io.askimo.core.event.error.SendMessageErrorEvent
import io.askimo.core.event.internal.NavigateToProviderSettingsEvent
import io.askimo.core.exception.AuthenticationException
import io.askimo.core.exception.ExceptionMapper
import io.askimo.core.exception.ModelNotFoundChatException
import io.askimo.core.exception.ProviderNotConfiguredException
import io.askimo.core.i18n.LocalizationManager

/**
 * Holds the state for the global error dialog triggered by application-wide error events.
 */
data class ErrorDialogState(
    val show: Boolean = false,
    val title: String = "",
    val message: String = "",
    val linkText: String? = null,
    val linkUrl: String? = null,
    val details: String? = null,
    /** Label for an optional in-dialog action button (e.g. "Configure Provider"). */
    val actionLabel: String? = null,
    /** Callback invoked when the user clicks the action button. The dialog is dismissed before this runs. */
    val action: (() -> Unit)? = null,
)

/**
 * Listens to [EventBus.errorEvents] and surfaces errors through [ErrorDialogState].
 *
 * Call this composable once at the top-level app scope.
 *
 * For errors the user can fix by reconfiguring their provider
 * ([AuthenticationException], [ProviderNotConfiguredException], [ModelNotFoundChatException]),
 * the resulting [ErrorDialogState] includes an action button that posts
 * [NavigateToProviderSettingsEvent] on the internal event bus.
 * The top-level composable should listen for that event and open the provider wizard.
 *
 * @param onStateChange called whenever a new [ErrorDialogState] should be applied.
 */
@Composable
fun globalErrorHandler(onStateChange: (ErrorDialogState) -> Unit) {
    LaunchedEffect(Unit) {
        EventBus.errorEvents.collect { event ->
            when (event) {
                is IndexingErrorEvent -> {
                    when (event.errorType) {
                        IndexingErrorType.EMBEDDING_MODEL_NOT_FOUND -> {
                            val modelName = event.details["modelName"] ?: "unknown"
                            val provider = event.details["provider"] ?: "AI provider"
                            onStateChange(
                                ErrorDialogState(
                                    show = true,
                                    title = LocalizationManager.getString("error.indexing.model_not_found.title"),
                                    message = LocalizationManager.getString(
                                        "error.indexing.model_not_found.message",
                                        modelName,
                                        provider,
                                    ),
                                ),
                            )
                        }

                        IndexingErrorType.EMBEDDING_INPUT_TOO_LARGE -> {
                            val files = event.details["files"] ?: "unknown"
                            onStateChange(
                                ErrorDialogState(
                                    show = true,
                                    title = LocalizationManager.getString("error.indexing.input_too_large.title"),
                                    message = LocalizationManager.getString(
                                        "error.indexing.input_too_large.message",
                                        files,
                                    ),
                                    details = event.details["message"],
                                ),
                            )
                        }

                        IndexingErrorType.IO_ERROR -> {
                            onStateChange(
                                ErrorDialogState(
                                    show = true,
                                    title = LocalizationManager.getString("error.indexing.io_error.title"),
                                    message = event.details["message"]
                                        ?: LocalizationManager.getString("error.indexing.io_error.message"),
                                ),
                            )
                        }

                        IndexingErrorType.UNKNOWN_ERROR -> {
                            onStateChange(
                                ErrorDialogState(
                                    show = true,
                                    title = LocalizationManager.getString("error.indexing.unknown.title"),
                                    message = event.details["message"]
                                        ?: LocalizationManager.getString("error.indexing.unknown.message"),
                                ),
                            )
                        }
                    }
                }

                is ModelNotAvailableEvent -> {
                    onStateChange(
                        ErrorDialogState(
                            show = true,
                            title = if (event.isEmbedding) {
                                LocalizationManager.getString("error.model.not_available.title.embedding")
                            } else {
                                LocalizationManager.getString("error.model.not_available.title.chat")
                            },
                            message = if (event.isEmbedding) {
                                LocalizationManager.getString(
                                    "error.model.not_available.message.embedding",
                                    event.modelName,
                                )
                            } else {
                                LocalizationManager.getString(
                                    "error.model.not_available.message.chat",
                                    event.modelName,
                                )
                            },
                            linkText = if (event.isEmbedding) {
                                LocalizationManager.getString("error.model.not_available.config_link")
                            } else {
                                null
                            },
                            linkUrl = if (event.isEmbedding) {
                                LocalizationManager.getString("error.model.not_available.config_url")
                            } else {
                                null
                            },
                        ),
                    )
                }

                is SendMessageErrorEvent -> {
                    val mapped = ExceptionMapper.map(event.throwable)
                    val localizedMsg = LocalizationManager.getString(
                        mapped.getMessageKey(),
                        *mapped.getMessageArgs().values.toTypedArray(),
                    )
                    val isProviderConfigError = mapped is AuthenticationException ||
                        mapped is ProviderNotConfiguredException ||
                        mapped is ModelNotFoundChatException

                    onStateChange(
                        ErrorDialogState(
                            show = true,
                            title = LocalizationManager.getString("error.send_message.title"),
                            message = localizedMsg,
                            actionLabel = if (isProviderConfigError) {
                                LocalizationManager.getString("error.action.configure_provider")
                            } else {
                                null
                            },
                            // Posts a navigation event so the UI layer can open the wizard
                            // without this component holding any reference to the UI.
                            action = if (isProviderConfigError) {
                                { EventBus.post(NavigateToProviderSettingsEvent()) }
                            } else {
                                null
                            },
                        ),
                    )
                }

                is AppErrorEvent -> {
                    onStateChange(
                        ErrorDialogState(
                            show = true,
                            title = event.title,
                            message = event.message,
                            details = event.cause?.message,
                        ),
                    )
                }
            }
        }
    }
}
