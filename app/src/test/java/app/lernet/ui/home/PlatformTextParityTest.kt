package app.lernet.ui.home

import app.lernet.engine.policy.BudgetNotice
import app.lernet.engine.policy.LinkHold
import app.lernet.engine.policy.LinkJudge
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** Android keeps these words in resources, Windows in shared code; they must stay the same. */
class PlatformTextParityTest {
    private val strings: Map<String, String> by lazy {
        val file = listOf("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml")
            .map(::File).first { it.isFile }
        Regex("""<string name="([^"]+)">([^<]*)</string>""").findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
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
            strings.getValue("state_hold_all_silent_next").replace("%1\$s", "2 мин"),
        )
    }
}
