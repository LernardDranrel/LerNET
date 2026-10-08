package app.lernet.vpn.expert

import app.lernet.engine.policy.*
import app.lernet.routing.policy.NetworkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class ExpertNotificationStatusTest {
    private val running = ExpertRuntimeState(saved = NetworkPolicy(), phase = ExpertSessionPhase.RUNNING,
        desiredEnabled = true, tun = TunIdentity("tun"), appliedRevision = 0, appliedPolicy = NetworkPolicy())

    @Test fun notificationTracksOutageRecoveryAndManualStopWithoutClaimingActive() {
        assertEquals(ExpertNotificationStatus.ACTIVE, expertNotificationStatus(running, true))
        assertEquals(ExpertNotificationStatus.OFFLINE, expertNotificationStatus(running, false))
        assertEquals(ExpertNotificationStatus.RECOVERING, expertNotificationStatus(running.copy(networkReason = "reset pending"), true))
        assertEquals(ExpertNotificationStatus.ACTIVE, expertNotificationStatus(running, true))
        assertEquals(ExpertNotificationStatus.STOPPING, expertNotificationStatus(running.copy(phase = ExpertSessionPhase.STOPPING), false))
        assertEquals(ExpertNotificationStatus.STOPPED, expertNotificationStatus(running.copy(phase = ExpertSessionPhase.STOPPED), false))
    }

    @Test fun startupIsProgressEvenWhenNetworkIsUnavailableAndFailureIsExplicit() {
        assertEquals(ExpertNotificationStatus.STARTING, expertNotificationStatus(running.copy(phase = ExpertSessionPhase.STARTING), false))
        assertEquals(ExpertNotificationStatus.FAILED, expertNotificationStatus(running.copy(phase = ExpertSessionPhase.FAILED), true))
    }
}
