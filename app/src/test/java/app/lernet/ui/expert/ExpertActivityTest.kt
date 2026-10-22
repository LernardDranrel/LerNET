package app.lernet.ui.expert

import app.lernet.engine.policy.ExpertConnectionObservation
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExpertActivityTest {
    @Test
    fun stateFilterUsesNullableNativeFactsAndSearchDoesNotGuessFromDecisionText() {
        val active = ExpertConnectionObservation("a", "Telegram", "example.org", "tcp", decision = "blocked-owner", active = true)
        val closed = active.copy(id = "c", active = false)
        val unknown = active.copy(id = "u", application = null, active = null)
        val all = listOf(active, closed, unknown)
        assertThat(filterExpertConnections(all, "tele", ExpertActivityFilter.ACTIVE)).containsExactly(active)
        assertThat(filterExpertConnections(all, "EXAMPLE", ExpertActivityFilter.CLOSED)).containsExactly(closed)
        // UI v3: «Длительные» replaced the old «Неизвестно» chip, the same set as Windows.
        val long = active.copy(id = "l", startedAtMs = 0)
        assertThat(filterExpertConnections(all + long, "", ExpertActivityFilter.LONG, nowMs = 60_000)).containsExactly(long)
        assertThat(filterExpertConnections(all + long, "", ExpertActivityFilter.ALL, nowMs = 60_000)).contains(unknown)
        assertThat(filterExpertConnections(all, "blocked", ExpertActivityFilter.ALL)).isEmpty()
    }
}
