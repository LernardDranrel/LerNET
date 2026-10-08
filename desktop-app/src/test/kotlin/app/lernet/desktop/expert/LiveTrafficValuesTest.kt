package app.lernet.desktop.expert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTrafficValuesTest {
    private fun flow(atMs: Long, bytes: Long, started: Long = 1_000) = ExpertConnection(
        "flow", "app", "example.test", "tcp", "direct", "", uploadedBytes = bytes,
        downloadedBytes = bytes * 2, startedAtMs = started, observedAtMs = atMs, active = true,
    )

    @Test
    fun rateUsesConfirmedSnapshotIntervalAndRejectsCounterReset() {
        val sampler = FlowRateSampler()
        assertTrue(sampler.update(listOf(flow(2_000, 100))).isEmpty())
        assertEquals(FlowRate(200, 400), sampler.update(listOf(flow(4_000, 500)))["flow"])
        assertEquals(FlowRate(200, 400), sampler.update(listOf(flow(4_000, 500)))["flow"])
        assertTrue(sampler.update(listOf(flow(5_000, 10))).isEmpty())
        assertTrue(sampler.update(emptyList()).isEmpty())
    }

    @Test
    fun restartedFlowDoesNotInheritOldRate() {
        val sampler = FlowRateSampler()
        sampler.update(listOf(flow(2_000, 100)))
        assertTrue(sampler.update(listOf(flow(4_000, 500, started = 3_000))).isEmpty())
    }

    @Test
    fun longLivedRequiresKnownStartAndActiveState() {
        val row = flow(31_000, 1)
        assertTrue(isLongLivedFlow(row, 31_000))
        assertFalse(isLongLivedFlow(row.copy(active = false), 31_000))
        assertFalse(isLongLivedFlow(row.copy(startedAtMs = null), 31_000))
        assertFalse(isLongLivedFlow(row, 30_999))
    }

    @Test
    fun staleCountersHaveNoCurrentRate() {
        val row = flow(2_000, 1)
        assertEquals(FlowRate(1, 2), freshFlowRate(row, FlowRate(1, 2), 3_000))
        assertNull(freshFlowRate(row, FlowRate(1, 2), 8_001))
        assertNull(freshFlowRate(row.copy(observedAtMs = null), FlowRate(1, 2), 3_000))
        assertNull(freshFlowRate(row.copy(active = false), FlowRate(1, 2), 3_000))
        assertNull(freshFlowRate(row.copy(active = null), FlowRate(1, 2), 3_000))
    }
}
