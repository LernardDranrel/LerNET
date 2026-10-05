package app.lernet.engine.net.observation

import org.junit.Assert.*
import org.junit.Test

class NetworkDependenciesTest {
    private fun snapshot(udp: ObservationSource = ObservationSource("udp-endpoints", "UDP", "", SourceState.EMPTY)) = NetworkSnapshot(
        platform = "Windows", startedAt = 1,
        adapters = listOf(ObservedAdapter("ethernet", 8, "Ethernet 3", up = true, dns = listOf("127.0.0.1", "::1"))),
        sources = listOf(ObservationSource("listeners", "TCP", "", SourceState.EMPTY), udp))

    @Test fun loopbackDnsWithoutTcpAndUdpListenersExplainsDependency() {
        val finding = NetworkDependencies.localDnsFindings(snapshot()).single()
        assertEquals(FindingKind.POTENTIAL_CONFLICT, finding.kind)
        assertEquals(listOf("127.0.0.1", "::1"), finding.evidence)
        assertTrue(finding.impact.contains("Фильтры Windows"))
        assertTrue(finding.relatedItems.single().title.contains("Ethernet"))
    }

    @Test fun missingUdpEvidenceCannotProveNoResolver() {
        val finding = NetworkDependencies.localDnsFindings(snapshot().copy(sources = emptyList())).single()
        assertEquals(FindingKind.INSUFFICIENT_DATA, finding.kind)
        assertEquals(FindingKind.INSUFFICIENT_DATA, NetworkDependencies.localDnsFindings(snapshot(
            ObservationSource("udp-endpoints", "UDP", "", complete = false))).single().kind)
    }

    @Test fun resolverOnIpv4CannotExplainIpv6LoopbackButUdpListenerDoes() {
        val udp = ObservationSource("udp-endpoints", "UDP", "", rows = listOf(EvidenceRow("dns", "Resolver", mapOf("LocalAddress" to "::1", "LocalPort" to "53"))))
        val state = snapshot(udp).copy(listeners = listOf(ObservedListener("0.0.0.0", 53, 42)))
        assertTrue(NetworkDependencies.localDnsFindings(state).isEmpty())
        assertEquals(listOf("::1"), NetworkDependencies.localDnsFindings(snapshot().copy(listeners = state.listeners)).single().evidence)
    }

    @Test fun disconnectedAdaptersAndPublicDnsDoNotRaiseLoopbackWarnings() {
        val state = snapshot()
        assertTrue(NetworkDependencies.localDnsFindings(state.copy(adapters = state.adapters.map { it.copy(up = false) })).isEmpty())
        assertTrue(NetworkDependencies.localDnsFindings(state.copy(adapters = state.adapters.map { it.copy(dns = listOf("1.1.1.1")) })).isEmpty())
    }
}
