package app.lernet.engine.net.observation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTableTest {
    private fun snapshot(mtu: Int = 1500, proxy: String = "Не указан для этой сети", foreignVpn: Boolean = false) = NetworkSnapshot(
        platform = "Android", startedAt = 0,
        adapters = listOf(
            ObservedAdapter("w", 30, "wlan0", "Wi-Fi", up = true, addresses = listOf("192.168.1.23/24", "fe80::1/64"), dns = listOf("192.168.1.1"), mtu = mtu),
        ) + if (foreignVpn) listOf(ObservedAdapter("v", 40, "tun0", "VPN", up = true, virtual = true)) else emptyList(),
        routes = listOf(ObservedRoute("0.0.0.0/0", "192.168.1.1", "w", 30, 0)),
        sources = listOf(
            ObservationSource("dns", "DNS", "", SourceState.AVAILABLE, rows = listOf(EvidenceRow("w", "wlan0", mapOf("Частный DNS" to "dns.example", "Прокси" to proxy)))),
            ObservationSource("vpn", "VPN", "", SourceState.AVAILABLE, rows = if (foreignVpn) listOf(EvidenceRow("v", "tun0", mapOf("Владелец" to "Android не раскрыл владельца"))) else emptyList()),
        ),
    )

    @Test
    fun ordinaryNetworkHasNothingUnusual() {
        val sections = NetworkTable.build(snapshot())
        assertEquals(0, NetworkTable.unusualCount(sections))
        assertTrue(sections.any { it.title == "Wi-Fi и мобильная сеть" && it.rows.single().collected == Collected.NOT_YET })
    }

    @Test
    fun wifiSectionReadsTheRadioSourceAndMarksHiddenValues() {
        val radio = ObservationSource("wlan", "Wi-Fi и мобильная сеть", "", SourceState.AVAILABLE, rows = listOf(
            EvidenceRow("wifi", "Wi-Fi", linkedMapOf("Имя сети" to "Скрыто. Android выдаёт имя сети…", "Сигнал" to "-60 дБм · хороший")),
        ))
        val base = snapshot()
        val rows = NetworkTable.build(base.copy(sources = base.sources + radio)).first { it.title == "Wi-Fi и мобильная сеть" }.rows
        assertEquals(listOf("Имя сети", "Сигнал"), rows.map { it.name })
        assertEquals(Collected.HIDDEN, rows[0].collected)
        assertEquals(Collected.YES, rows[1].collected)
        assertFalse(rows.any { "пока не собирает" in it.value })
    }

    @Test
    fun windowsRawWlanTextStaysOffTheTable() {
        val raw = ObservationSource("wlan", "Wi-Fi", "", SourceState.AVAILABLE, rows = listOf(EvidenceRow("0", "Wi-Fi", mapOf("Interfaces" to "Name : Wi-Fi"))))
        val windows = snapshot().copy(platform = "Windows", sources = listOf(raw))
        assertFalse(NetworkTable.build(windows).any { it.title.startsWith("Wi-Fi") })
    }

    @Test
    fun lowMtuProxyAndForeignVpnAreUnusual() {
        val sections = NetworkTable.build(snapshot(mtu = 1400, proxy = "192.168.1.10:3128", foreignVpn = true))
        assertEquals(3, NetworkTable.unusualCount(sections))
        val text = NetworkTable.copyText(snapshot(), sections, "Анализ сети · Android")
        assertTrue("MTU: 1400 байт (обычно: 1500) — необычно" in text)
        assertTrue("Необычных значений: 3" in text)
    }

    @Test
    fun freeFieldsAreNeverJudged() {
        val sections = NetworkTable.build(snapshot())
        val dns = sections.first { it.title == "DNS" }.rows
        assertFalse(dns.any { it.unusual })
    }
}
