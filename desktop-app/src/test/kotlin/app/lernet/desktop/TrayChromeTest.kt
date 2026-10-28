package app.lernet.desktop

import app.lernet.desktop.expert.ExpertPhase
import app.lernet.engine.text.StatusWords
import org.junit.Assert.assertEquals
import org.junit.Test

class TrayChromeTest {
    @Test
    fun vpnRunningLooksOnEvenBeforeHealthCheck() {
        val chrome = trayChrome(null, false, TunnelStatus.RUNNING, healthFailures = 0)
        assertEquals("lernet-icon-running.png", chrome.icon)
        assertEquals(StatusWords.chrome(StatusWords.CONNECTED), chrome.tooltip)
    }

    @Test
    fun vpnRunningWithHealthFailuresLooksFailed() {
        val chrome = trayChrome(null, false, TunnelStatus.RUNNING, healthFailures = 2)
        assertEquals("lernet-icon-failed.png", chrome.icon)
        assertEquals(StatusWords.chrome(StatusWords.NO_REPLY), chrome.tooltip)
    }

    @Test
    fun vpnStartingAndReconnectingLookOn() {
        assertEquals("lernet-icon-running.png", trayChrome(null, false, TunnelStatus.STARTING, 0).icon)
        assertEquals(StatusWords.chrome(StatusWords.CONNECTING), trayChrome(null, false, TunnelStatus.STARTING, 0).tooltip)
        assertEquals("lernet-icon-running.png", trayChrome(null, false, TunnelStatus.RECONNECTING, 0).icon)
        assertEquals(StatusWords.chrome(StatusWords.RECONNECTING), trayChrome(null, false, TunnelStatus.RECONNECTING, 0).tooltip)
    }

    @Test
    fun offAndFailed() {
        assertEquals("lernet-icon.png", trayChrome(null, false, TunnelStatus.STOPPED, 0).icon)
        assertEquals(StatusWords.chrome(StatusWords.OFF), trayChrome(null, false, TunnelStatus.STOPPED, 0).tooltip)
        assertEquals("lernet-icon-failed.png", trayChrome(null, false, TunnelStatus.FAILED, 0).icon)
        assertEquals(StatusWords.chrome(StatusWords.FAILED), trayChrome(null, false, TunnelStatus.FAILED, 0).tooltip)
    }

    @Test
    fun expertOnUsesExpertLabelAndRunningIcon() {
        for (phase in listOf(ExpertPhase.STARTING, ExpertPhase.RUNNING, ExpertPhase.APPLYING, ExpertPhase.STOPPING)) {
            val chrome = trayChrome(phase, expertTunnelError = false, TunnelStatus.STOPPED, 0)
            assertEquals(phase.name, "lernet-icon-running.png", chrome.icon)
            assertEquals(phase.name, StatusWords.chrome(StatusWords.EXPERT), chrome.tooltip)
        }
    }

    @Test
    fun expertFailedOrExitErrorLooksFailed() {
        assertEquals("lernet-icon-failed.png", trayChrome(ExpertPhase.FAILED, false, TunnelStatus.STOPPED, 0).icon)
        assertEquals(StatusWords.chrome(StatusWords.FAILED), trayChrome(ExpertPhase.FAILED, false, TunnelStatus.STOPPED, 0).tooltip)
        val error = trayChrome(ExpertPhase.RUNNING, expertTunnelError = true, TunnelStatus.STOPPED, 0)
        assertEquals("lernet-icon-failed.png", error.icon)
        assertEquals(StatusWords.chrome(StatusWords.NO_REPLY), error.tooltip)
    }

    @Test
    fun expertOwnsOverVpn() {
        val chrome = trayChrome(ExpertPhase.RUNNING, false, TunnelStatus.RUNNING, healthFailures = 2)
        assertEquals("lernet-icon-running.png", chrome.icon)
        assertEquals(StatusWords.chrome(StatusWords.EXPERT), chrome.tooltip)
    }
}
