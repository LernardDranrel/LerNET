package app.lernet.engine.net.observation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ObservationCompletenessTest {
    @Test fun truncatedSourceDoesNotReportAnAppOrRuleAsRemoved() {
        val source = ObservationSource("firewall-rules", "Брандмауэр", "", rows = listOf(EvidenceRow("1", "Rule")))
        val old = NetworkSnapshot(platform = "Windows", startedAt = 0, sources = listOf(source))
        val current = old.copy(sources = listOf(source.copy(rows = emptyList(), complete = false)))
        val change = NetworkObservationAnalysis.compare(old, current).single()
        assertThat(change.after).contains("не сравнивалось")
        assertThat(change.after).doesNotContain("Больше нет")
    }

    @Test fun unknownInterfaceMetricDoesNotPickTheSupposedFastestPath() {
        val snapshot = NetworkSnapshot(platform = "Windows", startedAt = 0, routes = listOf(
            ObservedRoute("0.0.0.0/0", "192.168.1.1", "a", 1, 1, metricsKnown = false),
            ObservedRoute("0.0.0.0/0", "192.168.2.1", "b", 2, 50),
        ))
        assertThat(NetworkRouteSelection.select(snapshot, "1.1.1.1").error).contains("Метрики")
        assertThat(NetworkRouteSelection.select(snapshot, "1.1.1.1").selected).isNull()
    }

    @Test fun truncatedListenerInventoryCannotDeclareAConfiguredProxyMissing() {
        val sources = listOf(
            ObservationSource("user-proxy", "Прокси", "", rows = listOf(EvidenceRow("proxy", "Прокси", mapOf(
                "ProxyEnable" to "1", "ProxyServer" to "127.0.0.1:1080",
            )))),
            ObservationSource("listeners", "Порты", "", complete = false),
        )
        val findings = NetworkObservationAnalysis.analyze(NetworkSnapshot(platform = "Windows", startedAt = 0, sources = sources))
        assertThat(findings.none { it.code == "loopback-proxy-no-listener" }).isTrue()
        val missing = findings.single { it.code == "partial-snapshot" }
        assertThat(missing.sourceIds).containsExactly("listeners")
        assertThat(missing.evidence.single()).contains("неполная")
    }

    @Test fun incompleteOrFailedRouteTableCannotDetermineWinningRouteFromRetainedRows() {
        val snapshot = NetworkSnapshot(platform = "Windows", startedAt = 0,
            routes = listOf(ObservedRoute("0.0.0.0/0", "192.168.1.1", "a", 1, 1)),
        )
        val source = ObservationSource("routes", "Маршруты", "")
        listOf(source.copy(complete = false), source.copy(state = SourceState.ACCESS_DENIED),
            source.copy(state = SourceState.ERROR), source.copy(state = SourceState.TIMEOUT)).forEach { unavailable ->
            val result = NetworkRouteSelection.select(snapshot.copy(sources = listOf(unavailable)), "1.1.1.1")
            assertThat(result.selected).isNull()
            assertThat(result.error).contains("неполному")
        }
    }

    @Test fun oldFirewallEventIsARecordedFactWithNoInventedOwner() {
        val snapshot = NetworkSnapshot(platform = "Windows", startedAt = 0, sources = listOf(
            ObservationSource("events", "События", "", rows = listOf(EvidenceRow("event:1", "Windows · 5157", mapOf("Event ID" to "5157", "Источник" to "Microsoft-Windows-Security-Auditing", "FilterRTID" to "123", "Application" to "App.exe")))),
        ))
        val finding = NetworkObservationAnalysis.analyze(snapshot).single { it.code == "windows-block-event" }
        assertThat(finding.kind).isEqualTo(FindingKind.FACT)
        assertThat(finding.explanation).contains("не обязательно")
        assertThat(finding.sourceIds).containsExactly("events")
    }

    @Test fun eventNumberAloneDoesNotEstablishAWindowsSecurityBlock() {
        val source = ObservationSource("trace-events", "Запись", "", rows = listOf(
            EvidenceRow("tcp-event", "Microsoft-Windows-TCPIP · 5157", mapOf("Event ID" to "5157", "Источник" to "Microsoft-Windows-TCPIP")),
            EvidenceRow("number-in-title", "Строка 15157", mapOf("Event ID" to "12")),
        ))
        val snapshot = NetworkSnapshot(platform = "Windows", startedAt = 0, sources = listOf(source))
        assertThat(NetworkObservationAnalysis.analyze(snapshot).none { it.code == "windows-block-event" }).isTrue()
    }

    @Test fun IPv4OnlyWildcardListenerDoesNotCoverAnExplicitIPv6Proxy() {
        val sources = listOf(
            ObservationSource("user-proxy", "Прокси", "", rows = listOf(EvidenceRow("proxy", "Прокси", mapOf(
                "ProxyEnable" to "1", "ProxyServer" to "[::1]:1080",
            )))),
            ObservationSource("listeners", "Порты", ""),
        )
        val snapshot = NetworkSnapshot(platform = "Windows", startedAt = 0, sources = sources,
            listeners = listOf(ObservedListener("0.0.0.0", 1080, 5)))
        assertThat(NetworkObservationAnalysis.analyze(snapshot).any { it.code == "loopback-proxy-no-listener" }).isTrue()
    }
}
