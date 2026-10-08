package app.lernet.engine.policy

import app.lernet.routing.policy.NetworkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class ExpertTunnelHealthTest {
    private val stopped = ExpertRuntimeState(saved = NetworkPolicy())
    private val running = stopped.copy(phase = ExpertSessionPhase.RUNNING, tun = TunIdentity("acknowledged"), appliedRevision = 0)

    @Test fun `no direct families is a failure only for an acknowledged direct default`() {
        val facts = ExpertDirectNetworkFacts(
            DirectFamilyAvailability.UNAVAILABLE, DirectFamilyAvailability.UNAVAILABLE, "windows_routes", "Ethernet", 0, 100,
        )
        val state = running.copy(appliedPolicy = NetworkPolicy(), directNetwork = facts)
        assertEquals(ExpertTunnelHealth.ERROR, state.tunnelHealth)
        assertEquals(
            ExpertTunnelHealth.HEALTHY,
            state.copy(directNetwork = facts.copy(ipv4 = DirectFamilyAvailability.AVAILABLE)).tunnelHealth,
        )
        assertEquals(
            ExpertTunnelHealth.HEALTHY,
            state.copy(directNetwork = facts.copy(ipv4 = DirectFamilyAvailability.UNKNOWN)).tunnelHealth,
        )
        val proxy = NetworkPolicy().let {
            it.copy(device = it.device.copy(defaultTarget = app.lernet.routing.policy.PolicyTarget.Profile("a")))
        }
        assertEquals(ExpertTunnelHealth.HEALTHY, state.copy(appliedPolicy = proxy).tunnelHealth)
    }

    @Test fun `lamp is unlit when stopped even with old exit failures`() {
        assertEquals(ExpertTunnelHealth.OFF, stopped.withExit(ExitPhase.FAILED).tunnelHealth)
    }

    @Test fun `green requires native tunnel and applied revision acknowledgement`() {
        assertEquals(ExpertTunnelHealth.HEALTHY, running.tunnelHealth)
        assertEquals(ExpertTunnelHealth.PENDING, running.copy(tun = null).tunnelHealth)
        assertEquals(ExpertTunnelHealth.PENDING, running.copy(appliedRevision = null).tunnelHealth)
    }

    @Test fun `starting stopping and applying never claim success`() {
        assertEquals(ExpertTunnelHealth.PENDING, running.copy(phase = ExpertSessionPhase.STARTING).tunnelHealth)
        assertEquals(ExpertTunnelHealth.PENDING, running.copy(phase = ExpertSessionPhase.STOPPING).tunnelHealth)
        assertEquals(ExpertTunnelHealth.PENDING, running.copy(applying = true).tunnelHealth)
    }

    @Test fun `current channel failure turns lamp red and recovery restores green`() {
        assertEquals(ExpertTunnelHealth.ERROR, running.withExit(ExitPhase.FAILED).tunnelHealth)
        assertEquals(ExpertTunnelHealth.ERROR, running.withExit(ExitPhase.DEGRADED).tunnelHealth)
        assertEquals(ExpertTunnelHealth.HEALTHY, running.withExit(ExitPhase.READY).tunnelHealth)
    }

    @Test fun `cold sleeping waking and draining exits are normal lifecycle states`() {
        for (phase in listOf(ExitPhase.SLEEPING, ExitPhase.STARTING, ExitPhase.DRAINING)) {
            assertEquals(ExpertTunnelHealth.HEALTHY, running.withExit(phase).tunnelHealth)
        }
    }

    @Test fun `tunnel failure and unfinished retired cleanup remain red`() {
        assertEquals(ExpertTunnelHealth.ERROR, running.copy(phase = ExpertSessionPhase.FAILED).tunnelHealth)
        assertEquals(
            ExpertTunnelHealth.ERROR,
            stopped.copy(retiredCleanupFailures = listOf(ExpertRetiredCleanupFailure(0, "exit_stop_failed"))).tunnelHealth,
        )
    }

    @Test fun `invalid draft and storage errors do not mislabel a working tunnel`() {
        assertEquals(
            ExpertTunnelHealth.HEALTHY,
            running.copy(errors = listOf("invalid draft"), draftErrors = listOf("invalid draft"), draftPersistenceError = "disk full")
                .tunnelHealth,
        )
    }

    @Test fun `temporary physical network loss waits without claiming success or switching off`() {
        val offline = running.copy(desiredEnabled = true, networkReason = "underlay missing", networkRecovering = true)
        assertEquals(ExpertTunnelHealth.PENDING, offline.tunnelHealth)
        assertEquals(true, offline.desiredEnabled)
        assertEquals(ExpertTunnelHealth.HEALTHY, offline.copy(networkReason = null, networkRecovering = false).tunnelHealth)
    }

    @Test fun `unconfirmed native status and real tunnel failure remain errors while offline`() {
        assertEquals(ExpertTunnelHealth.ERROR, running.copy(networkReason = "status unavailable").tunnelHealth)
        assertEquals(ExpertTunnelHealth.ERROR, running.copy(phase = ExpertSessionPhase.FAILED,
            networkReason = "underlay missing", networkRecovering = true).tunnelHealth)
    }

    private fun ExpertRuntimeState.withExit(phase: ExitPhase) = copy(exits = listOf(ExpertExitState(ExpertExitKey("profile"), phase)))
}
