package app.lernet.ui.home

import app.lernet.engine.policy.BudgetNotice
import app.lernet.engine.policy.LinkHold
import app.lernet.engine.policy.LinkJudge
import app.lernet.engine.text.HomeWords
import app.lernet.engine.text.StatusWords
import app.lernet.engine.text.TrafficUnits
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** Android keeps these words in resources, Windows in shared code; they must stay the same. */
class PlatformTextParityTest {
    private val strings: Map<String, String> by lazy {
        val dir = listOf("src/main/res/values", "app/src/main/res/values").map(::File).first { it.isDirectory }
        dir.listFiles { f -> f.extension == "xml" }.orEmpty().flatMap { file ->
            Regex("""<string name="([^"]+)">([^<]*)</string>""").findAll(file.readText())
                .map { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("&amp;", "&") }.toList()
        }.toMap()
    }

    private fun same(resource: String, shared: String) =
        assertEquals("R.string.$resource differs from the Windows text", shared, strings.getValue(resource))

    /** Every word of the plain home: the config line, «Связь», the hold report, the error card. */
    @Test
    fun homeWordsMatchWindows() {
        same("home_tap_connect", HomeWords.TAP_CONNECT)
        same("home_tap_disconnect", HomeWords.TAP_DISCONNECT)
        same("home_profile_rules", HomeWords.RULES)
        same("home_change_profile", HomeWords.CHANGE)
        same("home_profile_single", HomeWords.NO_FOLDER)
        same("home_profile_autoswap", HomeWords.AUTOSWAP)
        same("home_profile_proxy", HomeWords.PROXY_MODE)
        assertEquals(HomeWords.folder("Работа"), strings.getValue("home_profile_folder").replace("%1\$s", "Работа"))
        same("home_link_title", HomeWords.LINK_TITLE)
        same("home_link_check", HomeWords.CHECK)
        same("home_latency_checking", HomeWords.CHECKING_BUTTON)
        same("home_link_internet", HomeWords.INTERNET)
        same("home_link_server", HomeWords.SERVER)
        same("home_link_speed", HomeWords.SPEED)
        same("home_link_total", HomeWords.TOTAL)
        same("home_link_path", HomeWords.PATH)
        same("home_link_checking", HomeWords.CHECKING)
        same("home_link_unchecked", HomeWords.UNCHECKED)
        same("home_link_no_answer", HomeWords.NO_ANSWER)
        same("home_link_when_connected", HomeWords.WHEN_CONNECTED)
        same("home_link_path_check", HomeWords.PATH_CHECK)
        assertEquals(HomeWords.works(142), strings.getValue("home_link_works").replace("%1\$d", "142"))
        assertEquals(HomeWords.answers(38), strings.getValue("home_link_answers").replace("%1\$d", "38"))
        assertEquals(HomeWords.pathValue("7 узлов", "NL"), strings.getValue("home_link_path_value").replace("%1\$s", "7 узлов · NL"))
        assertEquals(HomeWords.pair("1 КБ/с", "2 КБ/с"), strings.getValue("traffic_rates").replace("%1\$s", "1 КБ/с").replace("%2\$s", "2 КБ/с"))
        same("home_journal_row", HomeWords.JOURNAL_ROW)
        same("home_attention_less", HomeWords.ATTENTION_LESS)
        assertEquals(HomeWords.attentionMore(2), strings.getValue("home_attention_more").replace("%1\$d", "2"))
        same("state_holding_no_config", HomeWords.HOLDING)
        same("state_failed", HomeWords.FAILED)
        same("failover_other_folder", HomeWords.OTHER_FOLDER)
        same("failover_turn_off", HomeWords.TURN_OFF)
        same("failover_turn_off_note", HomeWords.TURN_OFF_NOTE)
        same("retry", HomeWords.RETRY)
        same("to_profiles", HomeWords.OTHER_CONFIG)
        same("details", HomeWords.DETAILS)
        same("hide_details", HomeWords.HIDE_DETAILS)
        same("home_proxy_mode_note", HomeWords.PROXY_NOTE)
    }

    @Test
    fun statusStripMatchesWindows() {
        same("status_connected", StatusWords.CONNECTED)
        assertEquals(StatusWords.connected("Нидерланды", dnsOk = false),
            strings.getValue("status_connected_profile").replace("%1\$s", "Нидерланды"))
        same("status_connecting", StatusWords.CONNECTING)
        same("status_reconnecting", StatusWords.RECONNECTING)
        same("status_off", StatusWords.OFF)
        same("status_failed", StatusWords.FAILED)
        same("status_no_link_held", StatusWords.NO_LINK_HELD)
        same("status_hold", StatusWords.HOLD)
        same("status_jammed", StatusWords.JAMMED)
        same("status_no_reply", StatusWords.NO_REPLY)
    }

    @Test
    fun trafficUnitsAreTheSame() {
        assertEquals("0 Б", TrafficUnits.bytes(0))
        assertEquals("1,5 КБ", TrafficUnits.bytes(1536))
        assertEquals("2 МБ", TrafficUnits.bytes(2L * 1024 * 1024))
        assertEquals("1,2 ГБ", TrafficUnits.bytes((1.2 * (1L shl 30)).toLong()))
        assertEquals("1,5 КБ/с", app.lernet.engine.TrafficDisplay.formatRate(1536))
    }

    @Test
    fun budgetWaitMatchesWindows() {
        assertEquals(BudgetNotice.short(7_000L), strings.getValue("probe_budget_wait_short").replace("%1\$d", "7"))
        assertEquals(BudgetNotice.full(7_000L, expert = false), strings.getValue("probe_budget_wait").replace("%1\$d", "7"))
    }

    @Test
    fun holdStatusMatchesWindows() {
        assertEquals(LinkJudge.holdMessage(LinkHold.JAMMED), strings.getValue("state_hold_jammed"))
        assertEquals(LinkJudge.holdMessage(LinkHold.ALL_SILENT), strings.getValue("state_hold_all_silent"))
        assertEquals(
            LinkJudge.holdMessage(LinkHold.ALL_SILENT, "2 мин"),
            strings.getValue("state_hold_all_silent_next").replace("%1\$s", "2\u00A0мин"),
        )
    }
}
