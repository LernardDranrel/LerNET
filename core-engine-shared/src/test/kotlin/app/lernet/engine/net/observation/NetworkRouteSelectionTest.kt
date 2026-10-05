package app.lernet.engine.net.observation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NetworkRouteSelectionTest {
    @Test fun `more specific route wins even with a higher metric`() {
        val routes = listOf(route("0.0.0.0/0", "physical", 1), route("128.0.0.0/1", "vpn", 500))
        assertThat(select(routes, "198.51.100.33").selected?.adapterId).isEqualTo("vpn")
        assertThat(select(routes, "8.8.8.8").selected?.adapterId).isEqualTo("physical")
    }

    @Test fun `both route and interface metric participate after prefix priority`() {
        val routes = listOf(route("0.0.0.0/0", "a", 1, 100), route("0.0.0.0/0", "b", 50, 1))
        assertThat(select(routes, "1.1.1.1").selected?.adapterId).isEqualTo("b")
    }

    @Test fun `IPv6 is selected independently and partial byte prefixes match`() {
        val routes = listOf(
            route("0.0.0.0/0", "ipv4", 0), route("::/0", "ipv6", 1),
            route("2001:db8:8000::/33", "specific", 200),
        )
        assertThat(select(routes, "2001:db8:abcd::1").selected?.adapterId).isEqualTo("specific")
        assertThat(select(routes, "2001:db8:1234::1").selected?.adapterId).isEqualTo("ipv6")
    }

    @Test fun `persistent and foreign compartment routes do not affect selection`() {
        val routes = listOf(
            route("0.0.0.0/0", "current", 20),
            route("1.1.1.1/32", "saved", 0).copy(store = "PersistentStore"),
            route("1.1.1.1/32", "foreign", 0).copy(compartment = "42"),
        )
        assertThat(select(routes, "1.1.1.1").selected?.adapterId).isEqualTo("current")
        assertThat(NetworkRouteSelection.select(snapshot(routes), "1.1.1.1", "42").selected?.adapterId).isEqualTo("foreign")
    }

    @Test fun `known disconnected adapters do not win and missing adapters are not invented`() {
        val state = snapshot(listOf(route("0.0.0.0/0", "up", 50), route("1.1.1.1/32", "down", 1)))
            .copy(adapters = listOf(ObservedAdapter("up", 1, "Ethernet", up = true), ObservedAdapter("down", 2, "VPN", up = false)))
        assertThat(NetworkRouteSelection.select(state, "1.1.1.1").selected?.adapterId).isEqualTo("up")
        assertThat(select(listOf(route("1.1.1.1/32", "unknown", 1)), "1.1.1.1").selected?.adapterId).isEqualTo("unknown")
    }

    @Test fun `equal cost alternatives stay visible rather than claiming an OS winner`() {
        val result = select(listOf(route("0.0.0.0/0", "b", 1), route("0.0.0.0/0", "a", 1)), "1.1.1.1")
        assertThat(result.candidates.map { it.adapterId }).containsExactly("a", "b").inOrder()
    }

    @Test fun `numeric input is strict and never falls back to DNS`() {
        listOf("example.com", "localhost", "2130706433", "127.1", "01.2.3.4", "256.1.1.1", "https://1.1.1.1", "1.1.1.1;exit", "::1%7", "1::2::3", "1:2:3:4:5:6:7:8:9", " 1.1.1.1").forEach { input ->
            val result = select(listOf(route("0.0.0.0/0", "a", 1)), input)
            assertThat(result.selected).isNull()
            assertThat(result.error).isNotEmpty()
        }
    }

    @Test fun `IPv4 mapped IPv6 peers use IPv4 routing while remaining valid numeric input`() {
        val routes = listOf(route("::/0", "ipv6", 1), route("0.0.0.0/0", "ipv4", 2), route("127.0.0.0/8", "mapped", 3))
        assertThat(NetworkRouteSelection.isNumericAddress("::ffff:127.0.0.1")).isTrue()
        assertThat(select(routes, "::ffff:127.0.0.1").selected?.adapterId).isEqualTo("mapped")
        assertThat(select(routes, "0:0:0:0:0:ffff:7f00:1").selected?.adapterId).isEqualTo("mapped")
    }

    @Test fun `invalid prefixes cannot match and no route is not proof of unreachable host`() {
        val result = select(listOf(route("0.0.0.0/99", "a", 1), route("host.example/0", "b", 1)), "1.1.1.1")
        assertThat(result.selected).isNull()
        assertThat(result.error).contains("не подтверждает")
    }

    @Test fun `metric addition does not overflow Int`() {
        val routes = listOf(route("0.0.0.0/0", "overflow", Int.MAX_VALUE, Int.MAX_VALUE), route("0.0.0.0/0", "normal", 10))
        assertThat(select(routes, "1.1.1.1").selected?.adapterId).isEqualTo("normal")
    }

    private fun route(prefix: String, adapter: String, metric: Int, interfaceMetric: Int = 0) =
        ObservedRoute(prefix, "0.0.0.0", adapter, 1, metric, interfaceMetric, compartment = "1")

    private fun snapshot(routes: List<ObservedRoute>) = NetworkSnapshot(platform = "Windows", startedAt = 1L, routes = routes)
    private fun select(routes: List<ObservedRoute>, destination: String) = NetworkRouteSelection.select(snapshot(routes), destination)
}
