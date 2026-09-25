package app.lernet.engine.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UnderlyingNetworkPolicyTest {
    @Test
    fun vpnTransportIsNeverDefaultEgress() {
        assertThat(UnderlyingNetworkPolicy.isUsableDefault("tun0", tunName = null, vpnTransport = true)).isFalse()
        assertThat(UnderlyingNetworkPolicy.shouldExposeToLibbox("tun0", tunName = null, vpnTransport = true)).isFalse()
    }

    @Test
    fun registeredTunNameIsNeverDefaultEgress() {
        assertThat(UnderlyingNetworkPolicy.isUsableDefault("tun0", tunName = "tun0", vpnTransport = false)).isFalse()
    }

    @Test
    fun wifiOrCellIsUsableDefault() {
        assertThat(UnderlyingNetworkPolicy.isUsableDefault("wlan0", tunName = "tun0", vpnTransport = false)).isTrue()
        assertThat(UnderlyingNetworkPolicy.isUsableDefault("rmnet0", tunName = "tun0", vpnTransport = false)).isTrue()
    }

    @Test
    fun blankNameIsRejected() {
        assertThat(UnderlyingNetworkPolicy.isUsableDefault("", tunName = null, vpnTransport = false)).isFalse()
        assertThat(UnderlyingNetworkPolicy.isUsableDefault(null, tunName = null, vpnTransport = false)).isFalse()
    }
}
