package app.lernet.engine.policy

import app.lernet.config.net.AppTraffic
import app.lernet.config.net.LocalProxyAccess
import app.lernet.engine.ConnectionState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppTrafficRouteTest {
    private val access = LocalProxyAccess(2081, "app", "secret")
    private val config = """{"inbounds":[{"type":"tun","tag":"tun-in"},""" +
        """{"type":"mixed","tag":"app-in","listen":"127.0.0.1","listen_port":2081,""" +
        """"users":[{"username":"app","password":"secret"}]}]}"""

    @Test
    fun connectedGoesThroughTheInternalListener() {
        val route = AppTrafficRoute.of(ConnectionState.CONNECTED, config)
        assertThat((route as AppTraffic.Route.Through).access).isEqualTo(access)
    }

    @Test
    fun startingAndRecoveringRefuseInsteadOfGoingDirect() {
        assertThat(AppTrafficRoute.of(ConnectionState.CONNECTING, config)).isEqualTo(AppTraffic.Route.Closed)
        assertThat(AppTrafficRoute.of(ConnectionState.RECONNECTING, config)).isEqualTo(AppTraffic.Route.Closed)
    }

    @Test
    fun connectedWithoutInternalListenerStaysClosed() {
        assertThat(AppTrafficRoute.of(ConnectionState.CONNECTED, """{"inbounds":[{"type":"tun"}]}"""))
            .isEqualTo(AppTraffic.Route.Closed)
        assertThat(AppTrafficRoute.of(ConnectionState.CONNECTED, null)).isEqualTo(AppTraffic.Route.Closed)
    }

    @Test
    fun offOrFailedIsDirect() {
        assertThat(AppTrafficRoute.of(ConnectionState.DISCONNECTED, config)).isEqualTo(AppTraffic.Route.Direct)
        assertThat(AppTrafficRoute.of(ConnectionState.FAILED, config)).isEqualTo(AppTraffic.Route.Direct)
    }
}
