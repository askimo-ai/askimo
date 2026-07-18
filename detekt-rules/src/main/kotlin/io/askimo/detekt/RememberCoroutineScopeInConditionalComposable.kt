/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Flags `rememberCoroutineScope()` calls that appear inside a conditionally rendered
 * Composable block (i.e. inside the `then` or `else` branch of an `if` expression
 * within a `@Composable` function).
 *
 * When the condition becomes `false` the composable is removed from the composition
 * and its scope is immediately cancelled. Any coroutines launched in that scope —
 * such as file-dialog or download operations — are killed before they can complete.
 *
 * **Wrong:**
 * ```kotlin
 * @Composable
 * fun downloadButton(isVisible: Boolean, onDownload: suspend () -> Unit) {
 *     if (isVisible) {
 *         val scope = rememberCoroutineScope()   // cancelled when isVisible turns false
 *         Button(onClick = { scope.launch { onDownload() } }) { … }
 *     }
 * }
 * ```
 *
 * **Correct:** hoist the scope to the parent composable so it lives as long as the
 * parent, independent of the visibility condition.
 * ```kotlin
 * @Composable
 * fun downloadButton(isVisible: Boolean, onClick: () -> Unit) {
 *     if (isVisible) {
 *         Button(onClick = onClick) { … }   // scope owned by caller
 *     }
 * }
 * ```
 */
class RememberCoroutineScopeInConditionalComposable(config: Config = Config.empty) :
    Rule(
        config,
        "`rememberCoroutineScope()` is called inside a conditional block. " +
            "When the condition becomes false the composable is removed and its scope is " +
            "cancelled, killing any in-flight coroutines (e.g. open file dialogs or downloads). " +
            "Hoist `rememberCoroutineScope()` to the parent composable and pass a plain " +
            "lambda instead of a `suspend` lambda.",
    ) {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        if (expression.calleeExpression?.text != "rememberCoroutineScope") return

        // Walk up to find if we are inside an if-branch
        val ifExpression = expression.getParentOfType<KtIfExpression>(strict = true) ?: return

        // Make sure the containing function is a @Composable
        val containingFunction = expression.getParentOfType<KtNamedFunction>(strict = true) ?: return
        val isComposable = containingFunction.annotationEntries.any { it.shortName?.asString() == "Composable" }
        if (!isComposable) return

        // Confirm this call is inside the then/else body of the if, not just anywhere above it
        val thenBranch = ifExpression.then
        val elseBranch = ifExpression.`else`
        val isInsideBranch = (thenBranch != null && thenBranch.textRange.contains(expression.textRange)) ||
            (elseBranch != null && elseBranch.textRange.contains(expression.textRange))

        if (isInsideBranch) {
            report(
                Finding(
                    entity = Entity.from(expression),
                    message = "`rememberCoroutineScope()` inside an `if` branch in " +
                        "`${containingFunction.name}`. The scope will be cancelled when the " +
                        "condition becomes false. Hoist the scope to the parent composable.",
                ),
            )
        }
    }
}
