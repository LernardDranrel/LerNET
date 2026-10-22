package app.lernet.ui

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/** Item 10 of the audit: basic-mode texts without jargon, without self-contradiction, on «вы». */
class BasicTextsTest {
    private val strings: Map<String, String> by lazy {
        File("src/main/res/values").listFiles { f -> f.name.endsWith(".xml") && !f.name.startsWith("expert_") }!!
            .flatMap { file ->
                Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(file.readText()).map { it.groupValues[1] to it.groupValues[2] }.toList()
            }.toMap()
    }

    @Test fun noJargonLeftInBasicMode() {
        listOf("VPN-канал", "В сироты", "движок", "Ладошка", "Сборка не вышла").forEach { word ->
            assertThat(strings.filterValues { word in it }.keys).isEmpty()
        }
    }

    @Test fun oneButtonWordPerMeaning() {
        assertThat(strings).doesNotContainKey("settings_journal_apply")
        assertThat(strings.getValue("settings_journal_confirm_body")).doesNotContain("обрезан до")
    }

    @Test fun oldBanTextNoLongerContradictsItself() {
        val text = strings.getValue("rule_block_expert_only")
        assertThat(text).doesNotContain("работает только")
        assertThat(text).contains("продолжает действовать")
    }

    @Test fun pathCheckSaysItRunsOnlyByButtonAndPastTheVpn() {
        val text = strings.getValue("home_hop_start_hint")
        assertThat(text).contains("только по кнопке")
        assertThat(text).contains("мимо VPN")
    }

    @Test fun basicModeAddressesTheUserAsVy() {
        val informal = Regex("""(^|[^а-яё])(ты|тебя|тебе|твой|твоя|твоей|твои|твою)([^а-яё]|$)""", RegexOption.IGNORE_CASE)
        assertThat(strings.filterValues { informal.containsMatchIn(it) }.keys).isEmpty()
    }
}
