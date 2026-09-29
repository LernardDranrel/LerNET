package app.lernet.ui.network

import app.lernet.engine.net.observation.*
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class AndroidNetworkObservationTest {
    private fun network(vpn: Boolean = true, self: Boolean = false, validated: Boolean = true) = AndroidNetworkRecord(
        "handle-15", "tun0", if (vpn) listOf("VPN") else listOf("Wi-Fi"), true, true, validated, vpn, self,
        listOf("10.0.0.2/32", "fd00::2/128"), listOf("1.1.1.1"),
        listOf("0.0.0.0/0" to "на интерфейсе", "::/0" to "на интерфейсе"), 1500,
        "Не указан", "Активен",
    )

    @Test fun foreignVpnNeverGetsLerNetOwnershipOrInventedProtocol() {
        val snapshot = AndroidNetworkCollector { listOf(network()) }.collect { 150 }
        val vpn = snapshot.sources.single { it.id == "vpn" }.rows.single()
        assertThat(vpn.fields["Владелец"]).isEqualTo("Android не раскрыл владельца")
        assertThat(vpn.fields["Протокол"]).isEqualTo("Не передан Android")
        assertThat(snapshot.adapters.single().virtual).isTrue()
        assertThat(snapshot.routes.map { it.prefix }).containsExactly("0.0.0.0/0", "::/0")
        assertThat(snapshot.sources.single { it.id == "platform-limits" }.state).isEqualTo(SourceState.UNSUPPORTED)
    }

    @Test fun selfOwnershipRequiresPositiveUidEvidence() {
        val snapshot = AndroidNetworkCollector { listOf(network(self = true)) }.collect()
        assertThat(snapshot.sources.single { it.id == "vpn" }.rows.single().fields["Владелец"])
            .isEqualTo("LerNET · UID совпадает с приложением")
    }

    @Test fun permissionFailureIsNotAnEmptyOrHealthyDevice() {
        val snapshot = AndroidNetworkCollector { throw SecurityException("private internals") }.collect()
        assertThat(snapshot.sources.filter { it.id != "platform-limits" }.all { it.state == SourceState.ACCESS_DENIED }).isTrue()
        assertThat(snapshot.sources.flatMap { it.rows }).isEmpty()
        assertThat(snapshot.findings.any { it.kind == FindingKind.INSUFFICIENT_DATA }).isTrue()
        assertThat(snapshot.sources.joinToString()).doesNotContain("private internals")
    }

    @Test fun anOfflineSnapshotHasNoInventedVpnAndNoDeadTunnelClaim() {
        val empty = AndroidNetworkCollector { emptyList() }.collect()
        assertThat(empty.sources.single { it.id == "vpn" }.state).isEqualTo(SourceState.EMPTY)
        assertThat(empty.adapters).isEmpty()
        val unvalidated = AndroidNetworkCollector { listOf(network(validated = false)) }.collect()
        assertThat(unvalidated.findings.single { it.code == "android-unvalidated" }.kind).isEqualTo(FindingKind.INSUFFICIENT_DATA)
    }

    @Test fun activeNetworkWithHiddenPropertiesRemainsExplicitlyPartial() {
        val snapshot = AndroidNetworkCollector { listOf(network().copy(propertiesAvailable = false, addresses = emptyList())) }.collect()
        val row = snapshot.sources.single { it.id == "adapters" }.rows.single()
        assertThat(row.fields["Доступ к данным"]).isEqualTo("Часть свойств недоступна")
        assertThat(row.fields["Адреса устройства"]).isEqualTo("Не переданы системой")
    }

    @Test fun deniedSourceDoesNotTurnItsMissingRowsIntoRemovedSettings() {
        val previous = AndroidNetworkCollector { listOf(network()) }.collect()
        val denied = AndroidNetworkCollector { throw SecurityException() }.collect()
        val unavailable = observationChanges(previous, denied)
        assertThat(unavailable).isNotEmpty()
        assertThat(unavailable.map { it.after }.any { it == "Больше нет" }).isFalse()
        val changed = previous.copy(sources = previous.sources.map { source -> if (source.id == "dns") source.copy(rows = source.rows.map { it.copy(fields = it.fields + ("DNS" to "9.9.9.9")) }) else source })
        assertThat(observationChanges(previous, changed).map { it.sourceId }).containsExactly("dns")
    }

    @Test fun androidTenNeverCallsAndroidElevenOwnerGetter() {
        var called = false
        assertThat(isOwnNetwork(29, 42) { called = true; 42 }).isFalse()
        assertThat(called).isFalse()
        assertThat(isOwnNetwork(30, 42) { 42 }).isTrue()
        assertThat(isOwnNetwork(36, 42) { -1 }).isFalse()
    }

    @Test fun incompleteLinkPropertiesDoNotReportDeletedDnsOrRoutes() {
        val previous = AndroidNetworkCollector { listOf(network()) }.collect()
        val hidden = AndroidNetworkCollector { listOf(network().copy(propertiesAvailable = false, dns = emptyList(), routes = emptyList())) }.collect()
        assertThat(hidden.sources.filter { it.id in setOf("adapters", "dns", "routes") }.all { !it.complete }).isTrue()
        assertThat(observationChanges(previous, hidden).filter { it.sourceId == "dns" || it.sourceId == "routes" }.all { it.after.contains("не сравнивалось") }).isTrue()
    }

    @Test fun unreachableAndThrowRoutesAreEvidenceNotForwardingPaths() {
        val record = network().copy(routeTypes = mapOf("0.0.0.0/0:на интерфейсе" to 7, "::/0:на интерфейсе" to 9))
        val snapshot = AndroidNetworkCollector { listOf(record) }.collect()
        assertThat(snapshot.routes).isEmpty()
        val actions = snapshot.sources.single { it.id == "routes" }.rows.map { it.fields["Действие"] }
        assertThat(actions).containsExactly("Недоступное назначение · трафик отклоняется", "Продолжить поиск в другой таблице")
        assertThat(AndroidNetworkCollector { listOf(network()) }.collect().routes.all { !it.metricsKnown }).isTrue()
    }

    @Test fun unchangedSnapshotDoesNotInventChangesFromUnsupportedSources() {
        val snapshot = AndroidNetworkCollector { listOf(network()) }.collect()
        assertThat(observationChanges(snapshot, snapshot)).isEmpty()
    }

    @Test fun ipObserverResponseIsBoundedAndOnlyAcceptsLiterals() {
        assertThat(readExternalIp(ByteArrayInputStream("198.51.100.1\n".toByteArray()))).isEqualTo("198.51.100.1")
        assertThat(readExternalIp(ByteArrayInputStream("2001:db8::1".toByteArray()))).isEqualTo("2001:db8::1")
        listOf("example.com", "999.1.1.1", "...", "abc", "x".repeat(49)).forEach { text ->
            assertThrows(IllegalArgumentException::class.java) { readExternalIp(ByteArrayInputStream(text.toByteArray())) }
        }
    }
}
