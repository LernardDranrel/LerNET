package app.lernet.engine.policy

import app.lernet.engine.RunMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TunHoldPolicyTest {
    @Test
    fun failedVpnRestartKeepsTheClosedInterface() {
        assertThat(TunHoldPolicy.holdOnStartFailure(RunMode.FULL_VPN, stopRequested = false, vpnServiceUp = true)).isTrue()
    }

    @Test
    fun userStopAlwaysReleases() {
        assertThat(TunHoldPolicy.holdOnStartFailure(RunMode.FULL_VPN, stopRequested = true, vpnServiceUp = true)).isFalse()
    }

    @Test
    fun nothingToHoldWithoutVpnService() {
        assertThat(TunHoldPolicy.holdOnStartFailure(RunMode.FULL_VPN, stopRequested = false, vpnServiceUp = false)).isFalse()
    }

    @Test
    fun proxyModeHasNoTunnelToHold() {
        assertThat(TunHoldPolicy.holdOnStartFailure(RunMode.PROXY, stopRequested = false, vpnServiceUp = true)).isFalse()
    }
}
