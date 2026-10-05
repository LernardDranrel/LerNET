package app.lernet.engine.policy

import app.lernet.routing.policy.ExitLifecyclePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitLifecycleTest {
    private val policy = ExitLifecyclePolicy(coldStart = true, idleTimeoutMs = 1_000, firstFlowTimeoutMs = 2_000, maxPendingFlows = 100)

    @Test fun `one hundred simultaneous arrivals trigger exactly one wake`() {
        val life = ExitLifecycle(policy)
        val requests = (1..100).map { life.request("flow-$it", 0) }
        assertEquals(1, requests.count { it is FlowAdmission.Wake })
        assertEquals(99, requests.count { it is FlowAdmission.Wait })
        assertEquals(FlowAdmission.QueueFull, life.request("overflow", 1))
        val ticket = (requests.first() as FlowAdmission.Wake).ticket
        assertEquals(100, life.complete(ticket, true, 10).admittedFlowIds.size)
        assertEquals(ExitPhase.READY, life.phase)
    }

    @Test fun `first flow times out while late flow may still use successful wake`() {
        val life = ExitLifecycle(policy)
        val ticket = (life.request("early", 0) as FlowAdmission.Wake).ticket
        life.request("late", 1_000)
        val completion = life.complete(ticket, true, 2_000)
        assertEquals(setOf("early"), completion.rejectedFlowIds)
        assertEquals(setOf("late"), completion.admittedFlowIds)
    }

    @Test fun `health probes cannot wake sleep or keep unused exit alive`() {
        val life = ExitLifecycle(policy)
        life.health(true, 100)
        assertEquals(ExitPhase.SLEEPING, life.phase)
        val ticket = life.warm(100)!!
        life.complete(ticket, true, 200)
        life.health(true, 1_000)
        val stop = life.tick(1_100).stopTicket!!
        assertEquals(ExitPhase.DRAINING, life.phase)
        life.stopped(stop, 1_101)
        assertEquals(ExitPhase.SLEEPING, life.phase)
    }

    @Test fun `quiet active TCP session prevents idle sleep`() {
        val life = ExitLifecycle(policy)
        val ticket = (life.request("tcp", 0) as FlowAdmission.Wake).ticket
        life.complete(ticket, true, 1)
        assertNull(life.tick(50_000).stopTicket)
        life.closeFlow("tcp", 50_001)
        assertNull(life.tick(51_000).stopTicket)
        assertTrue(life.tick(51_001).stopTicket != null)
    }

    @Test fun `stale wake cannot resurrect stopped or newer exit`() {
        val life = ExitLifecycle(policy)
        val old = life.warm(0)!!
        life.complete(old, false, 1)
        life.resetFailed(2)
        val next = life.warm(2)!!
        assertEquals(WakeCompletion(), life.complete(old, true, 3))
        assertEquals(ExitPhase.STARTING, life.phase)
        life.complete(next, true, 4)
        assertEquals(ExitPhase.READY, life.phase)
    }

    @Test fun `failed startup releases all waiting flows`() {
        val life = ExitLifecycle(policy)
        val ticket = (life.request("a", 0) as FlowAdmission.Wake).ticket
        life.request("b", 1)
        assertEquals(setOf("a", "b"), life.complete(ticket, false, 2).rejectedFlowIds)
        assertEquals(FlowAdmission.Unavailable, life.request("c", 3))
    }

    @Test fun `hung wake has a global deadline and stale success cannot revive it`() {
        val life = ExitLifecycle(policy.copy(startupTimeoutMs = 3_000))
        val ticket = (life.request("first", 0) as FlowAdmission.Wake).ticket
        life.request("late", 2_500)
        val timeout = life.tick(3_000)
        assertEquals(setOf("first", "late"), timeout.expiredFlowIds)
        assertEquals(ticket, timeout.cancelWakeTicket)
        assertEquals(ExitPhase.FAILED, life.phase)
        assertEquals(WakeCompletion(), life.complete(ticket, true, 3_001))
    }

    @Test fun `failed stop does not pretend that worker is sleeping`() {
        val life = ExitLifecycle(policy)
        val wake = life.warm(0)!!
        life.complete(wake, true, 1)
        val stop = life.tick(1_000).stopTicket!!
        life.stopped(stop, 1_001, success = false)
        assertEquals(ExitPhase.FAILED, life.phase)
    }

    @Test fun `late success is rejected even before timer callback runs`() {
        val life = ExitLifecycle(policy.copy(startupTimeoutMs = 3_000, firstFlowTimeoutMs = 5_000))
        val ticket = (life.request("waiting", 0) as FlowAdmission.Wake).ticket
        val result = life.complete(ticket, true, 3_000)
        assertTrue(result.admittedFlowIds.isEmpty())
        assertEquals(setOf("waiting"), result.rejectedFlowIds)
        assertEquals(ExitPhase.FAILED, life.phase)
    }
}
