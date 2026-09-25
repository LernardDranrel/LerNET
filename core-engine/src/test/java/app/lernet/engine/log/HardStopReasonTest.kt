package app.lernet.engine.log

import app.lernet.engine.ConnectionCause
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HardStopReasonTest {
    @Test
    fun distinguishesUserFromWatchdogAndDns() {
        assertThat(HardStopReason.tag(ConnectionCause.UserDisconnected)).isEqualTo("user")
        assertThat(HardStopReason.tag(ConnectionCause.WatchdogTimeout(20))).isEqualTo("watchdog")
        assertThat(HardStopReason.tag(ConnectionCause.DnsStalled(8))).isEqualTo("dns")
        assertThat(HardStopReason.tag(ConnectionCause.DnsUnreachable(8))).isEqualTo("dns")
        assertThat(HardStopReason.crumb(ConnectionCause.UserDisconnected))
            .isEqualTo("HARD_STOP reason=user")
        assertThat(HardStopReason.crumb(ConnectionCause.WatchdogTimeout(20)))
            .isEqualTo("HARD_STOP reason=watchdog")
    }
}
