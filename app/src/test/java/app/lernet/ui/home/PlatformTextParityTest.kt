package app.lernet.ui.home

import app.lernet.engine.policy.BudgetNotice
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

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
}
