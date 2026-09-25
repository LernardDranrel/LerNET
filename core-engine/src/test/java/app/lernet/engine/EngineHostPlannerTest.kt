package app.lernet.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EngineHostPlannerTest {
    @Test
    fun proxyConnectNeverForegroundStartsUnusedVpn() {
        val commands = EngineHostPlanner.start(
            mode = RunMode.PROXY,
            vpnRunning = false,
            proxyRunning = false,
        )
        assertThat(commands.filter { it.action == HostAction.START_FOREGROUND }.map { it.target })
            .containsExactly(HostTarget.PROXY)
        assertThat(commands.any { it.target == HostTarget.VPN && it.action != HostAction.STOP_SERVICE }).isFalse()
    }

    @Test
    fun proxyReconnectWhileProxyRunningDoesNotForegroundVpn() {
        val commands = EngineHostPlanner.start(
            mode = RunMode.PROXY,
            vpnRunning = false,
            proxyRunning = true,
        )
        assertThat(commands.filter { it.action == HostAction.START_FOREGROUND }.map { it.target })
            .containsExactly(HostTarget.PROXY)
        assertThat(commands.any { it.target == HostTarget.VPN && it.action == HostAction.START_FOREGROUND }).isFalse()
        assertThat(commands.any { it.target == HostTarget.VPN && it.action == HostAction.SIGNAL_STOP }).isFalse()
    }

    @Test
    fun fullVpnReconnectWhileVpnRunningDoesNotForegroundProxy() {
        val commands = EngineHostPlanner.start(
            mode = RunMode.FULL_VPN,
            vpnRunning = true,
            proxyRunning = false,
        )
        assertThat(commands.filter { it.action == HostAction.START_FOREGROUND }.map { it.target })
            .containsExactly(HostTarget.VPN)
        assertThat(commands.any { it.target == HostTarget.PROXY && it.action == HostAction.START_FOREGROUND }).isFalse()
    }

    @Test
    fun fullVpnConnectNeverForegroundStartsUnusedProxy() {
        val commands = EngineHostPlanner.start(
            mode = RunMode.FULL_VPN,
            vpnRunning = false,
            proxyRunning = false,
        )
        assertThat(commands.filter { it.action == HostAction.START_FOREGROUND }.map { it.target })
            .containsExactly(HostTarget.VPN)
        assertThat(commands.any { it.target == HostTarget.PROXY && it.action != HostAction.STOP_SERVICE }).isFalse()
    }

    @Test
    fun stopOfUnusedHostDoesNotStartIt() {
        val commands = EngineHostPlanner.stop(HostTarget.VPN, running = false)
        assertThat(commands).containsExactly(HostCommand(HostTarget.VPN, HostAction.STOP_SERVICE))
        assertThat(commands.any { it.action == HostAction.START_FOREGROUND }).isFalse()
        assertThat(commands.any { it.action == HostAction.SIGNAL_STOP }).isFalse()
    }

    @Test
    fun stopOfRunningHostSignalsThenStopsWithoutNewForegroundStart() {
        val commands = EngineHostPlanner.stopAll(vpnRunning = true, proxyRunning = false)
        assertThat(commands).containsExactly(
            HostCommand(HostTarget.VPN, HostAction.SIGNAL_STOP),
            HostCommand(HostTarget.VPN, HostAction.STOP_SERVICE),
            HostCommand(HostTarget.PROXY, HostAction.STOP_SERVICE),
        ).inOrder()
        assertThat(commands.any { it.action == HostAction.START_FOREGROUND }).isFalse()
    }
}
