package app.lernet.desktop.observation

import app.lernet.engine.net.observation.*
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WindowsNetworkObservationTest {
    @org.junit.Test fun `every Windows source has a reading guide`() {
        val missing = WindowsNetworkObservation.definitions.map { it.id }.filterNot {
            it in app.lernet.engine.net.observation.ObservationGuide.sourceIds
        }
        org.junit.Assert.assertEquals(emptyList<String>(), missing)
    }

    private val definition = WindowsObservationDefinition("adapters", "Адаптеры", "Пояснение")

    @Test fun parsesUnicodeAndStableAdapterGuid() {
        val source = WindowsNetworkObservation.parseSource(definition, ObservationCommandResult(0,
            """{"state":"AVAILABLE","rows":[{"InterfaceGuid":"guid-1","ifIndex":5,"Name":"Домашняя сеть","Status":"Up","Virtual":false}],"detail":""}"""))
        assertThat(source.state).isEqualTo(SourceState.AVAILABLE)
        assertThat(source.rows.single().id).isEqualTo("adapters:guid-1")
        assertThat(source.rows.single().title).isEqualTo("Домашняя сеть")
        assertThat(source.rows.single().fields["Virtual"]).isEqualTo("false")
    }

    @Test fun brokenOrClippedJsonIsExplicitFailureWithoutPrivateBody() {
        val clipped = WindowsNetworkObservation.parseSource(definition,
            ObservationCommandResult(0, "{}", outputTruncated = true))
        assertThat(clipped.state).isEqualTo(SourceState.ERROR)
        assertThat(clipped.complete).isFalse()
        assertThat(clipped.detail).contains("4 МБ")
        val malformed = WindowsNetworkObservation.parseSource(definition,
            ObservationCommandResult(0, "{\"private\":\"fixture-private-marker"))
        assertThat(malformed.state).isEqualTo(SourceState.ERROR)
        assertThat(malformed.detail).contains("JSON")
        assertThat(malformed.detail).doesNotContain("fixture-private-marker")
    }

    @Test fun timeoutAndDeniedAreDistinctFromEmpty() {
        assertThat(WindowsNetworkObservation.parseSource(definition, ObservationCommandResult(null, "", true)).state).isEqualTo(SourceState.TIMEOUT)
        assertThat(WindowsNetworkObservation.parseSource(definition, ObservationCommandResult(0,
            """{"state":"ACCESS_DENIED","rows":[],"detail":"Запрещено"}""")).state).isEqualTo(SourceState.ACCESS_DENIED)
        assertThat(WindowsNetworkObservation.parseSource(definition, ObservationCommandResult(0,
            """{"state":"AVAILABLE","rows":[],"detail":""}""")).state).isEqualTo(SourceState.EMPTY)
    }

    @Test fun assemblesRouteWithFamilySpecificInterfaceMetricAndRealProcessOwner() {
        fun s(id: String, vararg rows: Map<String, String>) = ObservationSource(id, id, "", rows = rows.mapIndexed { index, fields -> EvidenceRow("$id:$index", "", fields) })
        val snapshot = WindowsNetworkObservation.assemble(listOf(
            s("adapters", mapOf("InterfaceGuid" to "stable-guid", "ifIndex" to "5", "Name" to "VPN", "Status" to "Up", "Virtual" to "True")),
            s("interfaces", mapOf("InterfaceIndex" to "5", "AddressFamily" to "2", "InterfaceMetric" to "10", "CompartmentId" to "1", "NlMtu" to "1400"),
                mapOf("InterfaceIndex" to "5", "AddressFamily" to "23", "InterfaceMetric" to "50", "CompartmentId" to "1")),
            s("routes", mapOf("DestinationPrefix" to "::/0", "NextHop" to "::", "InterfaceIndex" to "5", "RouteMetric" to "5", "CompartmentId" to "1", "PolicyStore" to "ActiveStore")),
            s("listeners", mapOf("LocalAddress" to "127.0.0.1", "LocalPort" to "2080", "OwningProcess" to "123")),
            s("processes", mapOf("ProcessId" to "123", "Name" to "ForeignVPN.exe")),
        ), 0, true)
        assertThat(snapshot.routes.single().adapterId).isEqualTo("stable-guid")
        assertThat(snapshot.routes.single().interfaceMetric).isEqualTo(50)
        assertThat(snapshot.adapters.single().mtu).isEqualTo(1400)
        assertThat(snapshot.listeners.single().process).isEqualTo("ForeignVPN.exe")
    }

    @Test fun scriptHasReadOnlyCommandsAndNoProfileSecrets() {
        val script = requireNotNull(javaClass.getResourceAsStream("/observation.ps1")).bufferedReader().use { it.readText() }
        assertThat(script).doesNotContain("CommandLine")
        assertThat(script).doesNotContain("Get-VpnConnection -Name")
        assertThat(script).doesNotContain("netsh.exe\" winsock reset")
        assertThat(script).contains("Get-DnsClientNrptPolicy -Effective")
        assertThat(script).contains("Get-NetRoute -IncludeAllCompartments -PolicyStore ActiveStore")
    }
    @Test fun absentRouteMetricDoesNotBecomeKnownZeroCostRoute() {
        fun source(id: String, fields: Map<String, String>) = ObservationSource(id, id, "", rows = listOf(EvidenceRow(id, id, fields)))
        val snapshot = WindowsNetworkObservation.assemble(listOf(
            source("interfaces", mapOf("InterfaceIndex" to "5", "AddressFamily" to "IPv4", "InterfaceMetric" to "10", "CompartmentId" to "1")),
            source("routes", mapOf("DestinationPrefix" to "0.0.0.0/0", "NextHop" to "192.0.2.1", "InterfaceIndex" to "5", "CompartmentId" to "1", "PolicyStore" to "ActiveStore")),
        ), 0, true)
        assertThat(snapshot.routes.single().metricsKnown).isFalse()
    }
}
