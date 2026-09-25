package app.lernet.engine.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TunRoutePlanTest {
    @Test
    fun api33EmptyRoutesGetsDefaultWhenAddressExists() {
        val planned = TunRoutePlan.plan(
            OpenTunInput(
                autoRoute = true,
                api33 = true,
                dnsMode = "hijack",
                inet4Address = listOf(TunRoutePlan.IPV4_LOCAL),
            ),
        )
        assertThat(planned.addresses).containsExactly(TunRoutePlan.IPV4_LOCAL)
        assertThat(planned.routes).containsExactly(TunRoutePlan.IPV4_DEFAULT)
        assertThat(planned.notes.joinToString()).contains("0.0.0.0/0")
        assertThat(planned.dnsServers).containsExactly("172.19.0.2")
    }

    @Test
    fun api33CustomRoutesAreNotOverlaidWithDefault() {
        val custom = CidrPrefix("1.2.3.0", 24)
        val planned = TunRoutePlan.plan(
            OpenTunInput(
                autoRoute = true,
                api33 = true,
                dnsMode = "hijack",
                inet4Address = listOf(TunRoutePlan.IPV4_LOCAL),
                inet4RouteAddress = listOf(custom),
                dnsServers = listOf("172.19.0.2"),
            ),
        )
        assertThat(planned.routes).containsExactly(custom)
        assertThat(planned.routes).doesNotContain(TunRoutePlan.IPV4_DEFAULT)
        assertThat(planned.dnsServers).containsExactly("172.19.0.2")
    }

    @Test
    fun pre33UsesRouteRangeOnly() {
        val range = listOf(CidrPrefix("0.0.0.0", 1), CidrPrefix("128.0.0.0", 1))
        val planned = TunRoutePlan.plan(
            OpenTunInput(
                autoRoute = true,
                api33 = false,
                dnsMode = "hijack",
                inet4Address = listOf(TunRoutePlan.IPV4_LOCAL),
                inet4RouteRange = range,
            ),
        )
        assertThat(planned.routes).isEqualTo(range)
        assertThat(planned.excludes).isEmpty()
    }

    @Test
    fun autoRouteFalseInstallsNoRoutesOrDns() {
        val planned = TunRoutePlan.plan(
            OpenTunInput(
                autoRoute = false,
                api33 = true,
                dnsMode = "hijack",
                inet4Address = listOf(TunRoutePlan.IPV4_LOCAL),
            ),
        )
        assertThat(planned.routes).isEmpty()
        assertThat(planned.dnsServers).isEmpty()
        assertThat(planned.notes.joinToString()).contains("autoRoute=false")
    }

    @Test
    fun ipv6DefaultOnlyWhenInet6AddressPresent() {
        val planned = TunRoutePlan.plan(
            OpenTunInput(
                autoRoute = true,
                api33 = true,
                dnsMode = "hijack",
                inet4Address = listOf(TunRoutePlan.IPV4_LOCAL),
                inet6Address = listOf(TunRoutePlan.IPV6_ULA),
            ),
        )
        assertThat(planned.routes).contains(TunRoutePlan.IPV4_DEFAULT)
        assertThat(planned.routes).contains(TunRoutePlan.IPV6_DEFAULT)
    }

    @Test
    fun nextIpv4Follows114DnsAddressRule() {
        assertThat(TunRoutePlan.nextIpv4(TunRoutePlan.IPV4_LOCAL)).isEqualTo("172.19.0.2")
    }
}
