package app.lernet.engine.net.observation

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class ObservationGuideTest {
    private val ethernet = ObservedAdapter("ethernet", 4, "Ethernet", "Intel Ethernet", false,
        addresses = listOf("172.16.43.20/24"))
    private fun route(prefix: String) = ObservedRoute(prefix, "0.0.0.0", ethernet.id, ethernet.index, 5)
    private fun snapshot(routes: List<ObservedRoute>) = NetworkSnapshot(platform = "Windows", startedAt = 1,
        adapters = listOf(ethernet), routes = routes)

    @Test fun `routine records on disconnected interface do not accuse internet routes`() {
        val service = listOf("255.255.255.255/32", "224.0.0.0/4", "127.0.0.0/8", "169.254.0.0/16",
            "ff00::/8", "fe80::/64", "::1/128", "172.16.43.20/32", "172.16.43.255/32")
        assertThat(NetworkObservationAnalysis.analyze(snapshot(service.map(::route)))).isEmpty()
        assertThat(ObservationGuide.isServiceRoute(route("172.16.43.21/32"), ethernet)).isFalse()
        assertThat(ObservationGuide.isServiceRoute(route("0.0.0.0/0"), ethernet)).isFalse()
        assertThat(ObservationGuide.isServiceRoute(route("10.20.30.0/24"), ethernet)).isFalse()
    }

    @Test fun `warning names actual interface and keeps actionable paths separate from service records`() {
        val state = snapshot(listOf(route("0.0.0.0/0").copy(nextHop = "172.16.43.1"), route("224.0.0.0/4")))
        val finding = NetworkObservationAnalysis.analyze(state).single()
        assertThat(finding.code).isEqualTo("inactive-route")
        assertThat(finding.evidence.single()).contains("Ethernet")
        assertThat(finding.evidence.single()).doesNotContain("224.0.0.0")
        assertThat(finding.relatedItems.single().title).contains("Ethernet")
        assertThat(finding.relatedItems.single().fields.values.joinToString()).contains("172.16.43.1")
        assertThat(finding.reason).isNotEmpty()
        assertThat(finding.impact).contains("не")
        assertThat(finding.nextSteps).isNotEmpty()
    }

    @Test fun `on link gateway is explained without labeling zero as a dead server`() {
        val state = snapshot(emptyList()).copy(adapters = listOf(ethernet.copy(up = true)))
        val row = EvidenceRow("route", "10.0.0.0/8", mapOf("DestinationPrefix" to "10.0.0.0/8",
            "NextHop" to "0.0.0.0", "InterfaceIndex" to "4"))
        val reading = ObservationGuide.row("routes", row, state)
        assertThat(reading.summary).contains("Ethernet")
        assertThat(reading.summary).contains("без отдельного шлюза")
        assertThat(reading.interpretation).contains("не проверялись")
    }

    @Test fun `broadcast detection respects subnet size and point to point addresses`() {
        assertThat(ObservationGuide.isServiceRoute(route("172.16.43.255/32"), ethernet.copy(addresses = listOf("172.16.42.20/23")))).isTrue()
        assertThat(ObservationGuide.isServiceRoute(route("172.16.42.255/32"), ethernet.copy(addresses = listOf("172.16.42.20/23")))).isFalse()
        assertThat(ObservationGuide.isServiceRoute(route("172.16.43.21/32"), ethernet.copy(addresses = listOf("172.16.43.20/31")))).isFalse()
    }

    @Test fun `all finding families explain rule limits and next action even in old exports`() {
        val codes = listOf("inactive-route", "proxy-port-occupied", "loopback-proxy-no-listener", "windows-block-event",
            "split-default", "virtual-active", "partial-snapshot", "endpoint-route", "endpoint-other-virtual",
            "endpoint-hostname", "endpoint-route-unavailable", "android-default", "android-unvalidated")
        codes.forEach { code ->
            val old = NetworkFinding(code, code, "", FindingKind.FACT, emptyList())
            val reading = ObservationFindingGuide.explain(snapshot(emptyList()), old)
            assertThat(reading.reason).isNotEmpty()
            assertThat(reading.impact).isNotEmpty()
            assertThat(reading.nextSteps).isNotEmpty()
        }
    }

    @Test fun `Android guidance does not apply Windows route metrics or miniport ownership`() {
        assertThat(ObservationGuide.source("routes", "Android").limits).contains("не раскрывает")
        assertThat(ObservationGuide.source("routes", "Android").inspect).doesNotContain("Windows")
        assertThat(ObservationGuide.source("adapters", "Android").limits).contains("другой сети")
        val row = EvidenceRow("r", "10.0.0.0/8", mapOf("Действие" to "Продолжить поиск в другой таблице"))
        assertThat(ObservationGuide.row("routes", row, snapshot(emptyList()).copy(platform = "Android")).interpretation).contains("другой таблице")
    }

    @Test fun `old report findings decode and explained findings survive export`() {
        val old = Json.decodeFromString<NetworkFinding>("""{"code":"inactive-route","title":"test","explanation":"test","kind":"POTENTIAL_CONFLICT","sourceIds":["routes"]}""")
        assertThat(old.reason).isEmpty()
        val explained = NetworkObservationAnalysis.analyze(snapshot(listOf(route("0.0.0.0/0")))).single()
        assertThat(Json.decodeFromString<NetworkFinding>(Json.encodeToString(explained))).isEqualTo(explained)
    }

    @Test fun `collection failures and unsupported components are not presented as connection failure`() {
        val missing = ObservationSource("wfp", "WFP", "", SourceState.ERROR, detail = "Unexpected EOF")
        assertThat(ObservationGuide.availability(missing)).contains("Состояние сети")
        val finding = NetworkObservationAnalysis.analyze(snapshot(emptyList()).copy(sources = listOf(missing))).single()
        assertThat(finding.kind).isEqualTo(FindingKind.INSUFFICIENT_DATA)
        assertThat(finding.relatedItems.single().fields.values).contains("Unexpected EOF")
        assertThat(ObservationGuide.field("UnknownDriverFlag").meaning).contains("отдельное значение")
    }
}
