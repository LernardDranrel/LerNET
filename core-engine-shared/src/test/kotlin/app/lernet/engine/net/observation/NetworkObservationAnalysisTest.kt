package app.lernet.engine.net.observation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NetworkObservationAnalysisTest {
    @Test fun `port warning excludes our process and unrelated bind address`() {
        val state = snapshot().copy(listeners = listOf(
            ObservedListener("127.0.0.1", 2080, 10),
            ObservedListener("192.168.1.1", 2080, 20),
            ObservedListener("0.0.0.0", 2081, 30),
        ))
        assertThat(NetworkObservationAnalysis.analyze(state, ownPids = setOf(10))).isEmpty()
    }

    @Test fun `foreign wildcard and loopback listeners are possible conflicts with evidence`() {
        val state = snapshot().copy(listeners = listOf(ObservedListener("::", 2080, 20, "ExampleVPN.exe")))
        val finding = NetworkObservationAnalysis.analyze(state).single()
        assertThat(finding.code).isEqualTo("proxy-port-occupied")
        assertThat(finding.kind).isEqualTo(FindingKind.POTENTIAL_CONFLICT)
        assertThat(finding.sourceIds).containsExactly("listeners")
        assertThat(finding.evidence.single()).contains("ExampleVPN.exe")
    }

    @Test fun `virtual adapter alone is a fact not a conflict or assigned VPN owner`() {
        val state = snapshot().copy(adapters = listOf(ObservedAdapter("hyperv", 1, "vEthernet", up = true, virtual = true)))
        val finding = NetworkObservationAnalysis.analyze(state).single()
        assertThat(finding.kind).isEqualTo(FindingKind.FACT)
        assertThat(finding.explanation).contains("сам по себе")
    }

    @Test fun `persistent split route does not claim to steer live traffic`() {
        val route = ObservedRoute("128.0.0.0/1", "0.0.0.0", "vpn", 1, 100, store = "PersistentStore")
        assertThat(NetworkObservationAnalysis.analyze(snapshot().copy(routes = listOf(route)))).isEmpty()
        val active = NetworkObservationAnalysis.analyze(snapshot().copy(routes = listOf(route.copy(store = "ActiveStore"))))
        assertThat(active.single().code).isEqualTo("split-default")
        assertThat(active.single().kind).isEqualTo(FindingKind.FACT)
    }

    @Test fun `route through down adapter is tentative not proof of a failure`() {
        val state = snapshot().copy(
            adapters = listOf(ObservedAdapter("old", 1, "VPN", up = false)),
            routes = listOf(ObservedRoute("10.0.0.0/8", "0.0.0.0", "old", 1, 1)),
        )
        val finding = NetworkObservationAnalysis.analyze(state).single()
        assertThat(finding.kind).isEqualTo(FindingKind.POTENTIAL_CONFLICT)
        assertThat(finding.sourceIds).containsExactly("routes", "adapters")
        assertThat(finding.explanation).contains("не доказывает")
    }

    @Test fun `unavailable sources preserve uncertainty while empty available sources do not`() {
        val state = snapshot().copy(sources = listOf(
            source("adapters"), source("events").copy(state = SourceState.EMPTY),
            source("filters").copy(state = SourceState.ACCESS_DENIED, detail = "Доступ запрещён"),
            source("owner").copy(state = SourceState.UNSUPPORTED),
        ))
        val finding = NetworkObservationAnalysis.analyze(state).single()
        assertThat(finding.code).isEqualTo("partial-snapshot")
        assertThat(finding.kind).isEqualTo(FindingKind.INSUFFICIENT_DATA)
        assertThat(finding.sourceIds).containsExactly("filters", "owner")
    }

    @Test fun `reordered rows and reordered map fields are not network changes`() {
        val rowA = EvidenceRow("guid-a", "Ethernet", linkedMapOf("MTU" to "1500", "DNS" to "1.1.1.1"))
        val rowB = EvidenceRow("guid-b", "Wi-Fi", mapOf("MTU" to "1500"))
        val before = snapshot().copy(sources = listOf(source("adapters", listOf(rowA, rowB))))
        val after = snapshot().copy(sources = listOf(source("adapters", listOf(rowB, rowA.copy(fields = linkedMapOf("DNS" to "1.1.1.1", "MTU" to "1500"))))))
        assertThat(NetworkObservationAnalysis.compare(before, after)).isEmpty()
    }

    @Test fun `changed field is one evidence change and not an attribution of authorship`() {
        val before = snapshot().copy(sources = listOf(source("dns", listOf(EvidenceRow("iface-guid", "Ethernet", mapOf("DNS" to "1.1.1.1"))))))
        val after = snapshot().copy(sources = listOf(source("dns", listOf(EvidenceRow("iface-guid", "Ethernet", mapOf("DNS" to "8.8.8.8"))))))
        val change = NetworkObservationAnalysis.compare(before, after).single()
        assertThat(change.before).contains("1.1.1.1")
        assertThat(change.after).contains("8.8.8.8")
        assertThat(change.sourceId).isEqualTo("dns")
    }

    @Test fun `source disappears or loses access remains visible in comparison`() {
        val before = snapshot().copy(sources = listOf(source("dns"), source("routes")))
        val after = snapshot().copy(sources = listOf(source("dns").copy(state = SourceState.TIMEOUT)))
        val changes = NetworkObservationAnalysis.compare(before, after)
        assertThat(changes.map { it.sourceId }).containsExactly("dns", "routes")
    }

    @Test fun `collection failure is not presented as removed settings`() {
        val oldRow = EvidenceRow("iface-guid", "Ethernet", mapOf("DNS" to "1.1.1.1"))
        val before = snapshot().copy(sources = listOf(source("dns", listOf(oldRow))))
        listOf(SourceState.ERROR, SourceState.TIMEOUT, SourceState.ACCESS_DENIED, SourceState.UNSUPPORTED).forEach { failure ->
            val after = snapshot().copy(sources = listOf(source("dns").copy(state = failure)))
            val change = NetworkObservationAnalysis.compare(before, after).single()
            assertThat(change.after).doesNotContain("Больше нет")
            assertThat(change.after).contains("не сравнивалось")
            assertThat(change.title).isEqualTo("dns")
        }
    }

    @Test fun `recovered empty source does not establish that settings were removed while unavailable`() {
        val before = snapshot().copy(sources = listOf(source("dns", listOf(EvidenceRow("stale", "Старое значение"))).copy(state = SourceState.ERROR)))
        val after = snapshot().copy(sources = listOf(source("dns").copy(state = SourceState.EMPTY)))
        val change = NetworkObservationAnalysis.compare(before, after).single()
        assertThat(change.before).contains("Не удалось")
        assertThat(change.after).contains("записей нет")
        assertThat(change.after).contains("не сравнивалось")
        assertThat(change.after).doesNotContain("Больше нет")
    }

    @Test fun `recovered populated source is not presented as new settings`() {
        val before = snapshot().copy(sources = listOf(source("dns").copy(state = SourceState.ACCESS_DENIED)))
        val after = snapshot().copy(sources = listOf(source("dns", listOf(EvidenceRow("iface-guid", "Ethernet", mapOf("DNS" to "1.1.1.1"))))))
        val change = NetworkObservationAnalysis.compare(before, after).single()
        assertThat(change.before).doesNotContain("Не было")
        assertThat(change.after).contains("не сравнивалось")
    }

    @Test fun `repeated unavailable sources are not changes but remain insufficient evidence`() {
        listOf(SourceState.ERROR, SourceState.TIMEOUT, SourceState.ACCESS_DENIED, SourceState.UNSUPPORTED).forEach { state ->
            val before = snapshot().copy(sources = listOf(source("dns").copy(state = state, capturedAt = 1)))
            val after = before.copy(id = "new", sources = before.sources.map { it.copy(capturedAt = 2) })
            assertThat(NetworkObservationAnalysis.compare(before, after)).isEmpty()
            assertThat(NetworkObservationAnalysis.analyze(after).single().kind).isEqualTo(FindingKind.INSUFFICIENT_DATA)
        }
    }

    @Test fun `availability details and completeness transitions remain meaningful changes`() {
        val incomplete = source("dns").copy(complete = false, detail = "Ограничено")
        val before = snapshot().copy(sources = listOf(incomplete))
        assertThat(NetworkObservationAnalysis.compare(before, before.copy(id = "new"))).isEmpty()
        listOf(incomplete.copy(detail = "Другая ошибка"), incomplete.copy(state = SourceState.ERROR),
            incomplete.copy(complete = true)).forEach { source ->
            val change = NetworkObservationAnalysis.compare(before, before.copy(sources = listOf(source))).single()
            assertThat(change.after).contains("не сравнивалось")
        }
    }

    @Test fun `successfully collected empty source can establish removal`() {
        val before = snapshot().copy(sources = listOf(source("dns", listOf(EvidenceRow("iface-guid", "Ethernet", mapOf("DNS" to "1.1.1.1"))))))
        val after = snapshot().copy(sources = listOf(source("dns").copy(state = SourceState.EMPTY)))
        val changes = NetworkObservationAnalysis.compare(before, after)
        assertThat(changes.any { it.after == "Больше нет" && it.title == "Ethernet" }).isTrue()
    }

    @Test fun `configured local proxy with no listener is only a potential conflict`() {
        val state = snapshot().copy(sources = listOf(proxySource("http=127.0.0.1:1080;https=127.0.0.1:1080"), source("listeners").copy(state = SourceState.EMPTY)))
        val finding = NetworkObservationAnalysis.analyze(state).single()
        assertThat(finding.code).isEqualTo("loopback-proxy-no-listener")
        assertThat(finding.kind).isEqualTo(FindingKind.POTENTIAL_CONFLICT)
        assertThat(finding.evidence).containsExactly("127.0.0.1:1080")
    }

    @Test fun `disabled remote and listening proxies do not produce stale proxy warning`() {
        val proxies = listOf(proxySource("127.0.0.1:1080", enabled = false), proxySource("10.0.0.1:1080"), proxySource("localhost:1080"))
        proxies.forEach { proxy ->
            val state = snapshot().copy(sources = listOf(proxy, source("listeners")), listeners = listOf(ObservedListener("::1", 1080, 5)))
            assertThat(NetworkObservationAnalysis.analyze(state).any { it.code == "loopback-proxy-no-listener" }).isFalse()
        }
    }

    @Test fun `missing or denied listener inventory cannot prove a stale local proxy`() {
        listOf(emptyList(), listOf(source("listeners").copy(state = SourceState.ACCESS_DENIED))).forEach { listeners ->
            val state = snapshot().copy(sources = listOf(proxySource("127.0.0.1:1080")) + listeners)
            assertThat(NetworkObservationAnalysis.analyze(state).any { it.code == "loopback-proxy-no-listener" }).isFalse()
        }
    }

    private fun snapshot() = NetworkSnapshot(platform = "Windows", startedAt = 1L)
    private fun source(id: String, rows: List<EvidenceRow> = emptyList()) = ObservationSource(id, id, "", rows = rows, capturedAt = 1L)
    private fun proxySource(server: String, enabled: Boolean = true) = source("user-proxy", listOf(
        EvidenceRow("user", "Прокси пользователя", mapOf("ProxyEnable" to if (enabled) "1" else "0", "ProxyServer" to server)),
    ))
}
