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
        assertThat(filterExpertConnections(all, "", ExpertActivityFilter.UNKNOWN)).containsExactly(unknown)
        assertThat(filterExpertConnections(all, "blocked", ExpertActivityFilter.ALL)).isEmpty()
    }
}
