package app.lernet.ui.routes

import app.lernet.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BasicOutcomeChoicesTest {
    @Test
    fun newRulesOfferOnlyVpnAndDirect() {
        assertThat(basicOutcomeChoices(false).map { it.first }).containsExactly("proxy", "direct").inOrder()
    }

    @Test
    fun oldBanRuleKeepsItsChoice() {
        assertThat(basicOutcomeChoices(true).map { it.first }).containsExactly("proxy", "direct", "block").inOrder()
        assertThat(basicOutcomeChoices(true).last().second).isEqualTo(R.string.action_block)
    }
}
