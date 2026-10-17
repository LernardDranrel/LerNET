package app.lernet.engine.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderStrategyTest {
    @Test fun `fast switch needs both 40 ms and 25 percent`() {
        assertTrue(FastSwitchRule.shouldSwitch(200, 150, null, 0))     // 50 ms, 25 %
        assertFalse(FastSwitchRule.shouldSwitch(400, 350, null, 0))    // 50 ms but 12.5 %
        assertFalse(FastSwitchRule.shouldSwitch(100, 70, null, 0))     // 30 %, only 30 ms
        assertFalse(FastSwitchRule.shouldSwitch(100, 0, null, 0))
    }

    @Test fun `fast switch at most once per ten minutes`() {
        assertFalse(FastSwitchRule.shouldSwitch(300, 100, lastSwitchAtMs = 0, nowMs = 9 * 60_000))
        assertTrue(FastSwitchRule.shouldSwitch(300, 100, lastSwitchAtMs = 0, nowMs = 10 * 60_000))
    }

    @Test fun `stable rank prefers a clean record over speed`() {
        val now = 1_000_000L
        val samples = listOf(
            ExitHealthSample("fast", now - 1_000, 40), ExitHealthSample("fast", now - 2_000, null),
            ExitHealthSample("fast", now - 3_000, null),
            ExitHealthSample("calm", now - 1_000, 180), ExitHealthSample("calm", now - 2_000, 190),
        )
        assertEquals(listOf("calm", "new", "fast"), StableRank.order(listOf("fast", "new", "calm"), samples, now))
    }

    @Test fun `rotation checks backups one by one and skips the active one`() {
        val rotation = BackupRotation()
        val members = listOf("a", "b", "c")
        assertEquals("b", rotation.next(members, "a"))
        assertEquals("c", rotation.next(members, "a"))
        assertEquals("b", rotation.next(members, "a"))
        assertNull(rotation.next(listOf("a"), "a"))
    }

    @Test fun `failover tries verified backups first and failed ones last`() {
        val now = 10 * 60_000L
        val health = mapOf(
            "dead" to BackupHealth(false, null, now - 1_000, true),
            "ok" to BackupHealth(true, 80, now - 1_000, true),
            "old" to BackupHealth(true, 50, now - BackupHealth.FRESH_MS - 1, true),
        )
        assertEquals(listOf("ok", "old", "x", "dead"), BackupHealth.order(listOf("dead", "old", "x", "ok"), health, now))
    }

    @Test fun `button gap and background pace stay in range`() {
        repeat(200) {
            assertTrue(BackupRotation.buttonGapMs() in 300..900)
            assertTrue(BackupRotation.nextDelayMs() in 45_000..300_000)
        }
    }

    @Test fun `backups are checked through the tunnel when it is up`() {
        assertEquals(ReachRoute.TUNNEL, BackupReach.route(tunnelUp = true, tcp = true, userAsked = false))
        assertEquals(ReachRoute.SKIP, BackupReach.route(tunnelUp = true, tcp = false, userAsked = false))
        assertEquals(ReachRoute.DIRECT, BackupReach.route(tunnelUp = true, tcp = false, userAsked = true))
        assertEquals(ReachRoute.DIRECT, BackupReach.route(tunnelUp = false, tcp = true, userAsked = true))
    }
}
