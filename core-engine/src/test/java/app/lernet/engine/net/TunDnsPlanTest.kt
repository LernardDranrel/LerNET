package app.lernet.engine.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TunDnsPlanTest {
    @Test
    fun emptyLibboxDerivesFromTunAddress() {
        val planned = TunDnsPlan.plan(
            fromLibbox = emptyList(),
            hasIpv6TunAddress = false,
            derivedFromTun = "172.19.0.2",
        )
        assertThat(planned.servers).containsExactly("172.19.0.2")
        assertThat(planned.notes.joinToString()).contains("derived")
        assertThat(planned.servers).doesNotContain("1.1.1.1")
    }

    @Test
    fun emptyLibboxWithoutTunStaysEmpty() {
        val planned = TunDnsPlan.plan(fromLibbox = emptyList(), hasIpv6TunAddress = false)
        assertThat(planned.servers).isEmpty()
        assertThat(planned.notes.joinToString()).contains("empty")
    }

    @Test
    fun keepsHijackStubFromLibbox() {
        val planned = TunDnsPlan.plan(
            fromLibbox = listOf("172.19.0.2"),
            hasIpv6TunAddress = false,
            derivedFromTun = "172.19.0.2",
        )
        assertThat(planned.servers).containsExactly("172.19.0.2")
        assertThat(planned.notes).isEmpty()
    }

    @Test
    fun dropsIpv6DnsWhenTunHasNoIpv6() {
        val planned = TunDnsPlan.plan(
            fromLibbox = listOf("172.19.0.2", "fdfe:dcba:9876::2"),
            hasIpv6TunAddress = false,
        )
        assertThat(planned.servers).containsExactly("172.19.0.2")
    }

    @Test
    fun disabledModeAddsNothing() {
        val planned = TunDnsPlan.plan(
            fromLibbox = listOf("172.19.0.2"),
            hasIpv6TunAddress = false,
            dnsMode = TunRoutePlan.DNS_MODE_DISABLED,
        )
        assertThat(planned.servers).isEmpty()
    }
}
