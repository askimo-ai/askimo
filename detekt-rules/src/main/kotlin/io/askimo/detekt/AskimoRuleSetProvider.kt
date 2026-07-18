/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.detekt

import dev.detekt.api.Config
import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

class AskimoRuleSetProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("askimo")

    override fun instance(): RuleSet = RuleSet(
        id = ruleSetId,
        rules = mapOf(
            RuleName("NestedScrollInScrollingWrapper") to { config: Config ->
                NestedScrollInScrollingWrapper(config)
            },
            RuleName("RememberCoroutineScopeInConditionalComposable") to { config: Config ->
                RememberCoroutineScopeInConditionalComposable(config)
            },
        ),
    )
}
