package app.lernet.engine.policy

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class BlockingDetectorTest {
    private fun frozen(id: String, up: Long, down: Long = 16_384) = FlowSample(id, proxied = true, uplink = up, downlink = down)

    @Test
    fun twoConnectionsFrozenAt16KbAreFlagged() {
        val d = BlockingDetector()
        assertNull(d.onFlows(0, listOf(frozen("a", 900, 5_000), frozen("b", 900, 4_000))))
        assertNull(d.onFlows(1_000, listOf(frozen("a", 1_000), frozen("b", 1_000, 15_800))))
        assertNull(d.onFlows(6_000, listOf(frozen("a", 1_400), frozen("b", 1_400, 15_800))))
        assertEquals(BlockingSign.FROZEN_AFTER_16KB, d.onFlows(12_000, listOf(frozen("a", 1_800), frozen("b", 1_800, 15_800))))
        // Reported once.
        assertNull(d.onFlows(30_000, listOf(frozen("a", 2_000), frozen("b", 2_000, 15_800))))
    }

    @Test
    fun idleKeepAliveConnectionsAreNotFrozen() {
        val d = BlockingDetector()
        // Got 16 KB, then we sent nothing more: an idle page, not a filter.
        for (t in 0L..120_000L step 2_000L) {
            assertNull(d.onFlows(t, listOf(frozen("a", 900), frozen("b", 900))))
        }
    }

    @Test
    fun largeDownloadsAndDirectTrafficAreIgnored() {
        val d = BlockingDetector()
        for (t in 0L..60_000L step 2_000L) {
            val rows = listOf(
                FlowSample("big", true, uplink = t, downlink = 2_000_000),
                FlowSample("direct1", false, uplink = t, downlink = 16_000),
                FlowSample("direct2", false, uplink = t, downlink = 16_000),
            )
            assertNull(d.onFlows(t, rows))
        }
    }

    @Test
    fun oneFrozenConnectionIsNotEnough() {
        val d = BlockingDetector()
        for (t in 0L..60_000L step 2_000L) assertNull(d.onFlows(t, listOf(frozen("a", 1_000 + t))))
    }

    @Test
    fun repeatedHandshakeResetsAreFlagged() {
        val d = BlockingDetector()
        val line = "ERROR outbound/vless[proxy]: dial: tls handshake: read tcp 10.0.0.2:4431->203.0.113.5:443: read: connection reset by peer"
        assertNull(d.onLogLine(0, line))
        assertNull(d.onLogLine(5_000, line))
        assertEquals(BlockingSign.RESET_AFTER_HELLO, d.onLogLine(9_000, line))
    }

    @Test
    fun deadServerDoesNotLookLikeAFilter() {
        val d = BlockingDetector()
        listOf(
            "ERROR outbound/vless[proxy]: dial tcp 203.0.113.5:443: i/o timeout",
            "ERROR outbound/vless[proxy]: dial tcp 203.0.113.5:443: connect: connection refused",
            "ERROR outbound/vless[proxy]: reality verification failed",
        ).forEachIndexed { i, line -> repeat(3) { assertNull(d.onLogLine(i * 1_000L + it, line)) } }
    }

    @Test
    fun resetsSpreadOverTimeDoNotAddUp() {
        val d = BlockingDetector()
        val line = "outbound/vless[proxy]: reality handshake: EOF"
        assertTrue(BlockingDetector.isHandshakeReset(line))
        assertNull(d.onLogLine(0, line))
        assertNull(d.onLogLine(70_000, line))
        assertNull(d.onLogLine(140_000, line))
    }

    @Test
    fun messagesAreRegionNeutral() {
        BlockingSign.entries.forEach { sign ->
            val text = BlockingDetector.message(sign)
            assertTrue("Переключаемся на другой конфиг" in text)
            listOf("РФ", "Росси", "РКН", "ТСПУ").forEach { assertTrue(it !in text) }
        }
    }
}

class ProxiedTrafficTest {
    @Test
    fun countsOnlyProxiedGrowthAndSurvivesClosedConnections() {
        val t = ProxiedTraffic()
        t.observe(listOf(FlowSample("a", true, 100, 0), FlowSample("d", false, 9_000, 9_000)))
        assertEquals(100L, t.uplink)
        assertEquals(0L, t.replies)
        t.observe(listOf(FlowSample("a", true, 300, 50)))
        assertEquals(300L, t.uplink)
        assertEquals(1L, t.replies)
        // "a" closed and vanished; a new one starts from zero: the counters never go back.
        t.observe(listOf(FlowSample("b", true, 40, 0)))
        assertEquals(340L, t.uplink)
        assertEquals(1L, t.replies)
    }
}
