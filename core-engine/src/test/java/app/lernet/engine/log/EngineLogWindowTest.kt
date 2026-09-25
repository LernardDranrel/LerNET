package app.lernet.engine.log

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EngineLogWindowTest {
    @Test
    fun keepsOnlyLastFiveMinutes() {
        var now = 0L
        val window = EngineLogWindow(windowMs = 5 * 60_000L, nowMs = { now })
        window.add("old")
        now = 5 * 60_000L + 1
        window.add("fresh")
        val text = window.snapshot()
        assertThat(text).doesNotContain("old")
        assertThat(text).contains("fresh")
    }
}
