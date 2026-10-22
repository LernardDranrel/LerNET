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
        assertThat(BASIC_BLOCK_EXPERT_ONLY).startsWith("Это правило запрета создано в прошлой версии и продолжает действовать")
        // The old wording contradicted itself («works only in Expert … still works as before»).
        assertThat(BASIC_BLOCK_EXPERT_ONLY).doesNotContain("работает только")
    }
}
