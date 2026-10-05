package app.lernet.engine.policy

import app.lernet.routing.RoutePlatform
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyInventory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyApplySessionTest {
    private val inventory = PolicyInventory(emptySet(), emptyMap())
    private val supported = PolicyControlCapabilities(true, true, true)
    private val tun = TunIdentity("tun-1")

    @Test fun `save and apply are separate and persisted failure cannot claim saved revision`() {
        val session = PolicyApplySession(NetworkPolicy(), RoutePlatform.WINDOWS)
        assertThrows(IllegalStateException::class.java) { session.save(inventory) { error("disk full") } }
        assertEquals(0L, session.state.savedRevision)
        assertNull(session.state.appliedRevision)
        assertTrue(session.save(inventory) {}.isEmpty())
        assertEquals(1L, session.state.savedRevision)
        assertNull(session.state.appliedRevision)
        val request = session.requestApply(inventory, supported, tun) as PolicyApplyRequest.Ready
        assertTrue(session.acknowledge(request.ticket, tun))
        assertEquals(1L, session.state.appliedRevision)
    }

    @Test fun `legacy reload capability cannot be mistaken for live atomic apply`() {
        RoutePlatform.entries.forEach { platform ->
            val session = PolicyApplySession(NetworkPolicy(), platform)
            assertTrue(session.requestApply(inventory, supported.copy(preservesTun = false), tun) is PolicyApplyRequest.Rejected)
            assertEquals(PolicyApplyPhase.IDLE, session.state.phase)
        }
    }

    @Test fun `failed atomic apply retains old active revision and old callback cannot win`() {
        val session = PolicyApplySession(NetworkPolicy(), RoutePlatform.ANDROID)
        val initial = session.requestApply(inventory, supported, tun) as PolicyApplyRequest.Ready
        session.acknowledge(initial.ticket, tun)
        session.save(inventory) {}
        val next = session.requestApply(inventory, supported, tun) as PolicyApplyRequest.Ready
        assertFalse(session.acknowledge(initial.ticket, tun))
        session.acknowledge(next.ticket, tun, "candidate rejected")
        assertEquals(0L, session.state.appliedRevision)
        assertEquals(1L, session.state.savedRevision)
        assertEquals(PolicyApplyPhase.FAILED, session.state.phase)
    }

    @Test fun `different TUN identity invalidates active revision even when backend reports success`() {
        val session = PolicyApplySession(NetworkPolicy(), RoutePlatform.WINDOWS)
        val request = session.requestApply(inventory, supported, tun) as PolicyApplyRequest.Ready
        session.acknowledge(request.ticket, TunIdentity("replacement"))
        assertNull(session.state.appliedRevision)
        assertEquals(PolicyApplyPhase.FAILED, session.state.phase)
    }

    @Test fun `stop cancels pending transaction and stale success cannot claim applied`() {
        val session = PolicyApplySession(NetworkPolicy(), RoutePlatform.ANDROID)
        val request = session.requestApply(inventory, supported, tun) as PolicyApplyRequest.Ready
        session.tunnelStopped()
        assertFalse(session.acknowledge(request.ticket, tun))
        assertNull(session.state.appliedRevision)
    }
}
