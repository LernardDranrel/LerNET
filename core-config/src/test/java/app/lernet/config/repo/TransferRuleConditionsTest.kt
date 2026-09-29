package app.lernet.config.repo

import app.lernet.config.transfer.TransferRule
import app.lernet.routing.ConditionCodec
import app.lernet.routing.MatchJoin
import app.lernet.routing.RuleMatch
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TransferRuleConditionsTest {
    @Test fun desktopFlatRuleKeepsOrBetweenDifferentFieldsOnAndroid() {
        val rule = TransferRule("rule", "profile", sortIndex = 0, action = "DIRECT",
            domains = listOf("example.com"), ipCidrs = listOf("203.0.113.0/24"), join = "OR")
        val stored = rule.androidConditionsJson()
        val restored = ConditionCodec.decode(stored, RuleMatch())
        assertThat(restored.join).isEqualTo(MatchJoin.OR)
        assertThat(restored.blocks).hasSize(2)
    }

    @Test fun elseRuleRemainsWithoutConditions() {
        val rule = TransferRule("else", "profile", sortIndex = 1, action = "PROXY")
        assertThat(rule.androidConditionsJson()).isEmpty()
    }
}
