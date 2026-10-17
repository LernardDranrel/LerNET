package app.lernet.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BasicBlockRuleTest {
    @Test
    fun newRulesOfferNoBanInBasicMode() {
        assertThat(basicDesktopActions(false)).containsExactly("PROXY", "DIRECT").inOrder()
    }

    @Test
    fun oldBanRuleStaysVisibleWithExpertNote() {
        assertThat(basicDesktopActions(true)).contains("BLOCK")
        assertThat(BASIC_BLOCK_EXPERT_ONLY).startsWith("Запрет работает только в Эксперте")
    }
}
