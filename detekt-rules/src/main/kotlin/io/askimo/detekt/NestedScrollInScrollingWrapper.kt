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
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList

/**
 * Flags `verticalScroll` or `horizontalScroll` modifier calls that appear inside a lambda
 * argument of a known app-level scrolling wrapper composable (e.g. `scaffoldDialog` or
 * `scaffoldDialogLazyColumn`).
 *
 * Those wrappers already manage their own scroll state internally.  Adding a second
 * `verticalScroll` (or `horizontalScroll`) without a bounded height constraint causes:
 *
 * > `IllegalStateException: Vertically scrollable component was measured with an infinity
 * > maximum height constraints`
 *
 * **Wrong:**
 * ```kotlin
 * AppComponents.scaffoldDialog(onDismissRequest = {}, actions = {}) {
 *     Column(modifier = Modifier.verticalScroll(rememberScrollState())) { … }
 * }
 * ```
 *
 * **Correct** — give the inner container an explicit max-height so it is not unbounded:
 * ```kotlin
 * AppComponents.scaffoldDialog(onDismissRequest = {}, actions = {}) {
 *     Column(modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) { … }
 * }
 * ```
 */
class NestedScrollInScrollingWrapper(config: Config = Config.empty) :
    Rule(
        config,
        "`verticalScroll` or `horizontalScroll` was used directly inside a " +
            "scrolling-wrapper composable (e.g. `scaffoldDialog`, `scaffoldDialogLazyColumn`). " +
            "Those wrappers already provide their own scroll container. " +
            "Add an explicit `Modifier.heightIn(max = …)` constraint before the scroll modifier, " +
            "or remove the redundant scroll modifier entirely.",
    ) {

    private companion object {
        /** Scroll modifiers that must not appear unbounded inside a wrapper. */
        val SCROLL_MODIFIERS = setOf("verticalScroll", "horizontalScroll")

        /**
         * Composable names (simple, unqualified) whose content lambdas already manage
         * scrolling and therefore must not receive a raw scroll modifier from the caller.
         */
        val SCROLLING_WRAPPERS = setOf(
            "scaffoldDialog",
            "scaffoldDialogLazyColumn",
        )
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val calleeName = expression.calleeExpression?.text ?: return
        if (calleeName !in SCROLL_MODIFIERS) return

        // Walk up the PSI tree looking for an enclosing scrolling-wrapper lambda.
        // Stop at a named-function boundary — a scroll inside a separately defined
        // composable called from within the wrapper is not our concern.
        var node = expression.parent
        while (node != null) {
            if (node is KtNamedFunction) break

            if (node is KtLambdaExpression) {
                val wrapperName = enclosingCallName(node)
                if (wrapperName in SCROLLING_WRAPPERS) {
                    report(
                        Finding(
                            entity = Entity.from(expression),
                            message = "`$calleeName` appears inside the content lambda of " +
                                "`$wrapperName`, which already provides its own scroll container. " +
                                "Add `Modifier.heightIn(max = …)` before `$calleeName` to bound " +
                                "the inner scroll, or remove the `$calleeName` modifier entirely.",
                        ),
                    )
                    return
                }
            }

            node = node.parent
        }
    }

    /**
     * Returns the simple name of the call expression that immediately owns [lambda] as an
     * argument (trailing lambda or positional lambda argument), or `null` if none.
     */
    private fun enclosingCallName(lambda: KtLambdaExpression): String? = when (val parent = lambda.parent) {
        // Trailing lambda: `foo { … }`
        is KtLambdaArgument -> (parent.parent as? KtCallExpression)?.calleeExpression?.text

        // Named / positional lambda: `foo(content = { … })`
        is KtValueArgument -> {
            val argList = parent.parent as? KtValueArgumentList
            (argList?.parent as? KtCallExpression)?.calleeExpression?.text
        }

        else -> null
    }
}
