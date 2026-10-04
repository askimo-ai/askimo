/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util
/**
 * Personal edition implementation of [AskimoHomeBase].
 * All data resolves under ~/.askimo/personal/.
 *
 * Used by both the desktop-personal and CLI modules.
 * Registered at startup via [AskimoHome.register].
 */
object PersonalAskimoHome : AskimoHomeBase {
    override val profileDirName: String = "personal"
}
