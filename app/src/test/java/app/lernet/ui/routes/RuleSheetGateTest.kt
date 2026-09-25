package app.lernet.ui.routes

import app.lernet.routing.ConditionBlock
import app.lernet.routing.ConditionKind
import app.lernet.routing.MatchJoin
import app.lernet.routing.RuleConditions
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RuleSheetGateTest {
    @Test
    fun emptyRulesAndEmptyBlocksAreBlocked() {
        assertThat(RuleSheetGate.errors(RuleConditions(MatchJoin.OR, emptyList())))
            .containsExactly(RuleSheetGate.EMPTY_BLOCKS)
        assertThat(
            RuleSheetGate.errors(
                RuleConditions(MatchJoin.OR, listOf(ConditionBlock(ConditionKind.DOMAIN, listOf("")))),
            ),
        ).containsExactly(RuleSheetGate.emptyBlock(ConditionKind.DOMAIN))
        assertThat(
            RuleSheetGate.errors(
                RuleConditions(MatchJoin.OR, listOf(ConditionBlock(ConditionKind.GEOIP, listOf("ru")))),
            ),
        ).isEmpty()
    }

    @Test
    fun dismissMarksEmptyDraftIncompleteAndSheetOnly() {
        val empty = RuleConditions(MatchJoin.OR, emptyList())
        assertThat(RuleSheetGate.isIncomplete(empty)).isTrue()
        assertThat(RuleSheetGate.isSheetOnlyError(RuleSheetGate.EMPTY_BLOCKS)).isTrue()
        assertThat(RuleSheetGate.isSheetOnlyError("node-1:${RuleSheetGate.EMPTY_BLOCKS}")).isTrue()
        assertThat(RuleSheetGate.isSheetOnlyError(RuleSheetGate.emptyBlock(ConditionKind.APP))).isTrue()
        assertThat(RuleSheetGate.isSheetOnlyError("else: missing")).isFalse()
    }

    @Test
    fun filledRuleIsNotDiscardedOnDismiss() {
        val filled = RuleConditions(
            MatchJoin.OR,
            listOf(ConditionBlock(ConditionKind.DOMAIN, listOf("example.com"))),
        )
        assertThat(RuleSheetGate.isIncomplete(filled)).isFalse()
        assertThat(RuleSheetDismiss.shouldDiscardDraft(filled, isNewDraft = true)).isFalse()
        assertThat(RuleSheetDismiss.shouldDiscardDraft(RuleConditions(MatchJoin.OR, emptyList()), isNewDraft = true)).isTrue()
        assertThat(RuleSheetDismiss.shouldDiscardDraft(RuleConditions(MatchJoin.OR, emptyList()), isNewDraft = false))
            .isFalse()
    }
}
