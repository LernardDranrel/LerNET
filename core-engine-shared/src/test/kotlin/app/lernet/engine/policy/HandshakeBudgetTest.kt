package app.lernet.engine.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeBudgetTest {
    private val key = HandshakeKey("1.2.3.4", 443, "www.example.com")

    @Test fun threeNewConnectionsPerMinuteThenWait() {
        val budget = HandshakeBudget()
        assertTrue(budget.tryTake(key, 0))
        assertTrue(budget.tryTake(key, 1_000))
        assertTrue(budget.tryTake(key, 2_000))
        assertFalse(budget.tryTake(key, 3_000))
        assertEquals(57_000L, budget.waitMs(key, 3_000))
        assertTrue(budget.tryTake(key, 60_000))
    }

    @Test fun serversAndSnisCountSeparately() {
        val budget = HandshakeBudget()
        repeat(3) { budget.record(key, 0) }
        assertEquals(0L, budget.waitMs(key.copy(sni = "other.example.com"), 0))
        assertEquals(0L, budget.waitMs(key.copy(port = 8443), 0))
        assertTrue(budget.waitMs(key, 0) > 0)
    }

    @Test fun forcedStartsAndChecksShareOneBudget() {
        val budget = HandshakeBudget()
        budget.record(key, 0) // core start
        budget.record(key, 0) // candidate check
        assertTrue(budget.tryTake(key, 0)) // tunnel check
        assertEquals(3, budget.used(key, 0))
        assertFalse(budget.tryTake(key, 10_000))
    }

    @Test fun keyFromOutboundUsesServerPortAndSniWithHostFallback() {
        val reality = HandshakeKey.fromOutboundJson(
            """{"type":"vless","server":"Srv.Example","server_port":8443,"tls":{"enabled":true,"server_name":"WWW.Apple.com"}}""",
        )
        assertEquals(HandshakeKey("srv.example", 8443, "www.apple.com"), reality)
        val plain = HandshakeKey.fromOutboundJson("""{"type":"trojan","server":"h.example"}""")
        assertEquals(HandshakeKey("h.example", 443, "h.example"), plain)
        assertNull(HandshakeKey.fromOutboundJson("""{"type":"direct"}"""))
        assertNull(HandshakeKey.fromOutboundJson("not json"))
    }
}
