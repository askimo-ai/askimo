/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

class AskimoRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "askimo"

    override fun instance(config: Config): RuleSet = RuleSet(
        id = ruleSetId,
        rules = listOf(
            NestedScrollInScrollingWrapper(config),
            RememberCoroutineScopeInConditionalComposable(config),
        ),
    )
}
