package app.lernet.desktop.observation

import app.lernet.engine.net.observation.*
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ClientObservationContextTest {
    private fun snapshot(elevated: Boolean = false) = NetworkSnapshot(platform = "Windows", startedAt = 0, elevated = elevated,
        adapters = listOf(ObservedAdapter("wifi", 1, "Wi-Fi", up = true), ObservedAdapter("vpn", 2, "Other VPN", "Virtual adapter", true, true)),
        routes = listOf(ObservedRoute("0.0.0.0/0", "192.168.1.1", "wifi", 1, 1), ObservedRoute("0.0.0.0/1", "0.0.0.0", "vpn", 2, 1000)))

    @Test fun proxyChoiceDoesNotClaimMissingAdminRights() {
        val current = withClientContext(snapshot(), ClientObservationContext(requestedMode = "PROXY"))
        assertThat(current.findings.map { it.code }).doesNotContain("admin-required")
    }

    @Test fun requestedVpnAndNonElevatedProcessShowsOnlyPotentialConflict() {
        val current = withClientContext(snapshot(), ClientObservationContext(requestedMode = "FULL_VPN"))
        assertThat(current.findings.single { it.code == "admin-required" }.kind).isEqualTo(FindingKind.POTENTIAL_CONFLICT)
        assertThat(withClientContext(snapshot(true), ClientObservationContext()).findings.map { it.code }).doesNotContain("admin-required")
    }

    @Test fun endpointViaOtherVirtualAdapterIsExplainedWithoutDeclaringFailure() {
        val current = withClientContext(snapshot(true), ClientObservationContext("Selected", "1.1.1.1", "vless"))
        assertThat(current.findings.map { it.code }).containsAtLeast("endpoint-route", "endpoint-other-virtual")
        assertThat(current.findings.single { it.code == "endpoint-other-virtual" }.kind).isEqualTo(FindingKind.POTENTIAL_CONFLICT)
        assertThat(current.sources.last().rows.single().fields.keys).containsExactly("Профиль", "Сервер", "Протокол", "Запрошенный режим")
    }

    @Test fun hostnameIsNotResolvedByPassiveAnalysis() {
        val current = withClientContext(snapshot(true), ClientObservationContext("Selected", "vpn.example.org", "vless"))
        assertThat(current.findings.map { it.code }).contains("endpoint-hostname")
        assertThat(current.findings.map { it.code }).doesNotContain("endpoint-route")
    }
    @Test fun hexLookingHostnameIsNotMistakenForNumericIp() {
        val current = withClientContext(snapshot(true), ClientObservationContext("Selected", "dead.beef", "vless"))
        assertThat(current.findings.map { it.code }).contains("endpoint-hostname")
    }

    @Test fun numericEndpointWithUnavailableRoutesShowsMissingEvidence() {
        val current = withClientContext(NetworkSnapshot(platform = "Windows", startedAt = 0, elevated = true), ClientObservationContext("Selected", "192.0.2.1", "vless"))
        assertThat(current.findings.single { it.code == "endpoint-route-unavailable" }.kind).isEqualTo(FindingKind.INSUFFICIENT_DATA)
    }
}
