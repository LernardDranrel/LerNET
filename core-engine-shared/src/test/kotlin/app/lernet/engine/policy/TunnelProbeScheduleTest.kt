package app.lernet.engine.policy

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class TunnelProbeScheduleTest {
    private fun schedule() = TunnelProbeSchedule(firstMs = { 5_000L }, confirmMs = { 4_000L }, cooldownMs = { 60_000L })

    @Test
    fun onlyOneCheckAfterStartAndNoneWhileTrafficIsAnswered() {
        val s = schedule()
        s.onStart(0)
        assertNull(s.takeDue(4_999))
        assertEquals(TunnelProbeSchedule.Reason.START, s.takeDue(5_000))
        assertEquals(TunnelProbeSchedule.Verdict.HEALTHY, s.onResult(5_200, ok = true))
        var up = 0L
        var replies = 0L
        for (t in 6_000L..600_000L step 1_000L) {
            up += 2_000; replies += 1
            s.onTraffic(t, up, replies)
            assertNull("no check at $t while replies keep coming", s.takeDue(t))
        }
    }

    @Test
    fun unansweredTrafficTriggersACheckAfterSilenceAndCooldown() {
        val s = schedule()
        s.onStart(0)
        s.takeDue(5_000); s.onResult(5_000, ok = true)
        s.onTraffic(6_000, 10_000, 3)
        for (t in 7_000L..64_000L step 1_000L) {
            s.onTraffic(t, 10_000 + t, 3)
            assertNull("cooldown holds at $t", s.takeDue(t))
        }
        s.onTraffic(65_000, 80_000, 3)
        assertEquals(TunnelProbeSchedule.Reason.SILENCE, s.takeDue(65_000))
    }

    @Test
    fun idleTunnelIsNeverChecked() {
        val s = schedule()
        s.onStart(0)
        s.takeDue(5_000); s.onResult(5_000, ok = true)
        for (t in 6_000L..3_600_000L step 5_000L) {
            s.onTraffic(t, 1_000, 2)
            assertNull(s.takeDue(t))
        }
    }

    @Test
    fun failureIsConfirmedOnceAfterJitterNotTwoSeconds() {
        val s = schedule()
        s.onStart(0)
        s.takeDue(5_000)
        assertEquals(TunnelProbeSchedule.Verdict.CONFIRM, s.onResult(5_500, ok = false))
        assertNull(s.takeDue(7_500))
        assertEquals(TunnelProbeSchedule.Reason.CONFIRM, s.takeDue(9_500))
        assertEquals(TunnelProbeSchedule.Verdict.FAILED, s.onResult(9_700, ok = false))
        assertNull(s.takeDue(20_000))
    }

    @Test
    fun networkChangeSkipsCooldown() {
        val s = schedule()
        s.onStart(0)
        s.takeDue(5_000); s.onResult(5_000, ok = true)
        s.onNetworkChanged(10_000)
        assertEquals(TunnelProbeSchedule.Reason.NETWORK, s.takeDue(15_000))
    }

    @Test
    fun defaultTimingsAreRandomWithinBounds() {
        val s = TunnelProbeSchedule()
        repeat(50) {
            s.onStart(0)
            val at = s.nextCheckAt()!!
            assert(at in 3_000L..8_000L) { "first check at $at" }
        }
    }
}
