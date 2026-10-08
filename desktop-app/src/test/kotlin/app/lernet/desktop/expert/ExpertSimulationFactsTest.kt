package app.lernet.desktop.expert

import androidx.compose.runtime.saveable.SaverScope
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicySimulationInput
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExpertSimulationFactsTest {
    private val saverScope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    @Test
    fun `history snapshot survives navigation with unknown facts still null`() {
        val original = ExpertConnection(
            "flow", "Браузер", "Подпись назначения", "TCP", "Напрямую", "Наблюдение",
            routeNodeIds = listOf("root", "node:legacy"), destinationPort = 443, policyRevision = 4,
        )
        val saved = with(ExpertConnectionSaver) { saverScope.save(original) }
        assertThat(ExpertConnectionSaver.restore(requireNotNull(saved))).isEqualTo(original)
    }

    @Test
    fun `unfinished example survives navigation with source and destination separate`() {
        val original = PolicySimulationInput(
            domain = "example.org", sourceIp = "192.0.2.1", destinationIp = "203.0.113.2", sourcePort = 50000,
        )
        val saved = with(ExpertInputSaver) { saverScope.save(original) }
        assertThat(ExpertInputSaver.restore(requireNotNull(saved))).isEqualTo(original)
    }

    @Test
    fun `display labels never substitute unknown flow facts`() {
        val flow = ExpertConnection("flow", "Браузер", "example.org:443", "TCP", "Напрямую", "")
        assertThat(flow.simulationInput()).isEqualTo(PolicySimulationInput())
    }

    @Test
    fun `recorded transport and sniffed protocol are distinct facts`() {
        val flow = ExpertConnection(
            "flow", "Браузер", "example.org:443", "TCP", "Напрямую", "",
            domain = "example.org", destinationIp = "203.0.113.2", destinationPort = 443,
            sourceIp = "192.0.2.1", sourcePort = 50000, processName = "browser.exe",
            network = "tcp", sniffedProtocol = "tls", geoCountry = "DE",
        )
        assertThat(flow.simulationInput()).isEqualTo(
            PolicySimulationInput(
                domain = "example.org", destinationIp = "203.0.113.2", destinationPort = 443,
                sourceIp = "192.0.2.1", sourcePort = 50000, processName = "browser.exe",
                network = "tcp", protocol = "tls", geoCountry = "DE",
            ),
        )
    }

    @Test
    fun `recorded path uses applied snapshot and never edited draft sharing its revision`() {
        val saved = NetworkPolicy(revision = 4)
        val applied = NetworkPolicy(revision = 3)
        val draft = saved.copy(device = saved.device.copy(nodes = listOf(PolicyNode("new"))))
        val state = ExpertUiState(saved = saved, draft = draft, applied = applied)
        assertThat(state.recordedPolicy(3)).isEqualTo(applied)
        assertThat(state.recordedPolicy(4)).isEqualTo(saved)
        assertThat(state.recordedPolicy(4)).isNotEqualTo(draft)
    }

    @Test
    fun `unknown and unavailable recorded revisions have no replacement snapshot`() {
        val state = ExpertUiState(saved = NetworkPolicy(revision = 4))
        assertThat(state.recordedPolicy(null)).isNull()
        assertThat(state.recordedPolicy(2)).isNull()
    }
}
