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

    @Test
    fun hintChecksSoonButRespectsTheCooldownAfterASuccess() {
        val s = schedule()
        s.onStart(0)
        s.takeDue(5_000)
        s.onResult(5_000, ok = true)
        s.onHint(10_000)
        assertNull("cooldown after a passed check", s.takeDue(10_000))
        s.onHint(70_000)
        assertEquals(TunnelProbeSchedule.Reason.HINT, s.takeDue(70_000))
        // While a check runs, more hints add nothing.
        s.onHint(70_500)
        assertNull(s.takeDue(70_500))
    }

    @Test
    fun aHoldWaitsItsPauseAndSilenceDoesNotCutItShort() {
        val s = schedule()
        s.onStart(0)
        s.takeDue(5_000)
        s.onResult(5_000, ok = false)
        s.takeDue(9_000)
        assertEquals(TunnelProbeSchedule.Verdict.FAILED, s.onResult(9_000, ok = false))
        s.planHold(9_000, 120_000)
        var up = 0L
        for (t in 10_000L until 129_000L step 1_000L) {
            up += 5_000
            s.onTraffic(t, up, 0)
            s.onHint(t)
            assertNull("held at $t", s.takeDue(t))
        }
        assertEquals(TunnelProbeSchedule.Reason.HOLD, s.takeDue(129_000))
    }

    @Test
    fun aNetworkChangeStillChecksSoonDuringAHold() {
        val s = schedule()
        s.onStart(0)
        s.takeDue(5_000)
        s.onResult(5_000, ok = true)
        s.planHold(6_000, 600_000)
        s.onNetworkChanged(7_000)
        assertEquals(TunnelProbeSchedule.Reason.NETWORK, s.takeDue(12_000))
    }

    @Test
    fun aFailedRoundStartsACooldownSoSilenceDoesNotRetestAtOnce() {
        val s = schedule()
        s.onStart(0)
        s.takeDue(5_000)
        s.onResult(5_000, ok = false)
        s.takeDue(9_000)
        s.onResult(9_000, ok = false)
        var up = 0L
        for (t in 10_000L until 69_000L step 1_000L) {
            up += 5_000
            s.onTraffic(t, up, 0)
            assertNull("cooldown at $t", s.takeDue(t))
        }
    }

    @Test
    fun aCheckBlockedByTheBudgetIsMovedNotDropped() {
        val s = schedule()
        s.onStart(0)
        val reason = s.takeDue(5_000)!!
        s.defer(reason, 40_000)
        assertNull(s.takeDue(39_999))
        assertEquals(TunnelProbeSchedule.Reason.START, s.takeDue(40_000))
    }

    @Test
    fun checkLimitsAllowAJammedHandshake() {
        assertEquals(15_000L, TunnelProbeSchedule.CHECK_TIMEOUT_MS)
        assertEquals(20_000L, TunnelProbeSchedule.CALL_TIMEOUT_MS)
        val s = TunnelProbeSchedule()
        s.onStart(0)
        s.takeDue(s.nextCheckAt()!!)
        s.onResult(10_000, ok = false)
        val confirmAt = s.nextCheckAt()!! - 10_000
        assert(confirmAt in 10_000L..20_000L) { "confirm after $confirmAt" }
    }
}
