package app.lernet.routing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RouteTerminalTest {
    @Test
    fun everyConditionalActionCanBecomeAForkWithoutANamedPipe() {
        assertThat(RouteTerminal.acceptsChildren(RouteAction.PROXY, "", 0)).isFalse()
        assertThat(RouteTerminal.acceptsChildren(RouteAction.PROXY, "video", 0)).isFalse()
        assertThat(RouteTerminal.acceptsChildren(RouteAction.PROXY, "", 2)).isTrue()
        assertThat(RouteTerminal.acceptsChildren(RouteAction.PROXY, "video", 2)).isFalse()
        assertThat(RouteTerminal.acceptsChildren(RouteAction.DIRECT, "", 0)).isFalse()
        assertThat(RouteTerminal.acceptsChildren(RouteAction.DIRECT, "", 1)).isTrue()
        assertThat(RouteTerminal.acceptsChildren(RouteAction.BLOCK, "", 1)).isTrue()
        assertThat(RouteTerminal.canAdoptChild(RouteAction.PROXY, "")).isTrue()
        assertThat(RouteTerminal.canAdoptChild(RouteAction.PROXY, "video")).isFalse()
        assertThat(RouteTerminal.canAdoptChild(RouteAction.DIRECT, "")).isTrue()
        assertThat(RouteTerminal.canAdoptChild(RouteAction.BLOCK, "")).isTrue()
    }

    @Test
    fun proxyModeIsExclusive() {
        assertThat(RouteTerminal.proxyMode(RouteAction.PROXY, "", 0)).isEqualTo(RouteTerminal.ProxyMode.AUTO)
        assertThat(RouteTerminal.proxyMode(RouteAction.PROXY, "video", 0)).isEqualTo(RouteTerminal.ProxyMode.NAMED)
        assertThat(RouteTerminal.proxyMode(RouteAction.PROXY, "", 1)).isEqualTo(RouteTerminal.ProxyMode.FORK)
        assertThat(RouteTerminal.proxyMode(RouteAction.DIRECT, "", 0)).isNull()
    }
}
