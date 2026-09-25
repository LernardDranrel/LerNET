package app.lernet.vpn

import app.lernet.engine.ConnectionSnapshot
import app.lernet.engine.ConnectionState
import app.lernet.engine.RunMode
import app.lernet.engine.live.ChannelHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionNotificationStateTest {
    @Test
    fun healthyConnectionUsesNormalIcon() {
        val state = notificationState(
            ConnectionSnapshot.idle().copy(state = ConnectionState.CONNECTED, channel = ChannelHealth.HOP_UP),
            RunMode.FULL_VPN,
        )
        assertEquals(ConnectionState.CONNECTED, state.state)
        assertFalse(state.needsAttention)
    }

    @Test
    fun silentTunnelUsesAlertIcon() {
        val state = notificationState(
            ConnectionSnapshot.idle().copy(state = ConnectionState.CONNECTED, channel = ChannelHealth.TUNNEL_DEAD),
            RunMode.FULL_VPN,
        )
        assertTrue(state.needsAttention)
    }

    @Test
    fun reconnectingAndFailedUseAlertIcon() {
        for (condition in listOf(ConnectionState.RECONNECTING, ConnectionState.FAILED)) {
            val state = notificationState(ConnectionSnapshot.idle().copy(state = condition), RunMode.FULL_VPN)
            assertTrue(state.needsAttention)
        }
    }

    @Test
    fun anotherServiceModeDoesNotShowItsStatus() {
        val state = notificationState(
            ConnectionSnapshot.idle(RunMode.PROXY).copy(state = ConnectionState.FAILED),
            RunMode.FULL_VPN,
        )
        assertEquals(ConnectionState.DISCONNECTED, state.state)
        assertFalse(state.needsAttention)
    }
}
