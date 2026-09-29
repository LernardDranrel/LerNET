package app.lernet.engine.compile

import app.lernet.config.model.NormalizedOutbound
import app.lernet.engine.RunMode
import app.lernet.engine.net.CidrPrefix
import app.lernet.engine.net.OpenTunInput
import app.lernet.engine.net.TunRoutePlan
import app.lernet.routing.CompiledRoute
import app.lernet.routing.RouteAction
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * RedTeam canonical assemble: sniff without inbound, sequential hijack
 * port 53 then protocol dns, TUN stack=gvisor. Never mixed, never omit
 * (omit defaults mixed = system TCP + gvisor UDP).
 * Builder routes/DNS stay TunOptions-gated. protect==false / establish==null
 * are [app.lernet.engine.net.VpnGuard], not this assemble test.
 */
class SfaOpenTunGateTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun canonicalSniffNoInboundPort53ThenDnsHijackAndGvisorStack() {
        val assembled = ConfigAssembler.assemble(
            outbound(),
            CompiledRoute(rules = emptyList(), finalAction = RouteAction.PROXY, errors = emptyList()),
            RunMode.FULL_VPN,
            "info",
        )
        val root = json.parseToJsonElement(assembled.json).jsonObject
        val sniff = root["route"]!!.jsonObject["rules"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["action"]?.jsonPrimitive?.content == "sniff" }
        assertThat(sniff.containsKey("inbound")).isFalse()
        assertThat(sniff["action"]?.jsonPrimitive?.content).isEqualTo("sniff")
        assertThat(sniff["timeout"]?.jsonPrimitive?.content).isEqualTo("300ms")

        val hijacks = root["route"]!!.jsonObject["rules"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["action"]?.jsonPrimitive?.content == "hijack-dns" }
        assertThat(hijacks).hasSize(2)
        assertThat(hijacks[0]["port"]?.jsonPrimitive?.content).isEqualTo("53")
        assertThat(hijacks[0].containsKey("protocol")).isFalse()
        assertThat(hijacks[0].containsKey("type")).isFalse()
        assertThat(hijacks[1]["protocol"]?.jsonPrimitive?.content).isEqualTo("dns")
        assertThat(hijacks[1].containsKey("port")).isFalse()
        assertThat(hijacks[1].containsKey("type")).isFalse()

        val tun = root["inbounds"]!!.jsonArray.first().jsonObject
        assertThat(tun["stack"]?.jsonPrimitive?.content).isEqualTo("gvisor")
        assertThat(tun["stack"]?.jsonPrimitive?.content).isNotEqualTo("mixed")
        assertThat(tun["stack"]?.jsonPrimitive?.content).isNotEqualTo("system")
        assertThat(tun.containsKey("dns_mode")).isFalse()
        assertThat(tun["auto_route"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(tun["strict_route"]?.jsonPrimitive?.content).isEqualTo("false")
        assertThat(root["route"]!!.jsonObject["auto_detect_interface"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(root["route"]!!.jsonObject["final"]?.jsonPrimitive?.content).isEqualTo("proxy")
        assertThat(DnsDependency.dangling(root)).isEmpty()
        assertThat(
            root["dns"]!!.jsonObject["servers"]!!.jsonArray.any {
                it.jsonObject["type"]?.jsonPrimitive?.content == "local"
            },
        ).isTrue()
        assertThat(assembled.notes.joinToString()).contains("sniff rematch TCP → outbound=proxy")
        assertThat(assembled.notes.joinToString()).contains("stack=gvisor")
        val summary = AssembledKeys.summarize(assembled.json)
        assertThat(summary).contains("sniff")
        assertThat(summary).doesNotContain("sniff+inbound")
        assertThat(summary).doesNotContain("+logical")
        assertThat(summary).contains("hijack-dns+port")
        assertThat(summary).contains("hijack-dns+protocol")
        assertThat(summary).contains("stack")
        assertThat(summary).doesNotContain("dns_mode")
        File("build").mkdirs()
        File("build/lernet-assembled-alive.json").writeText(
            assembled.json
                .replace(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"), "<uuid>")
                .replace("node.example.com", "<host>"),
        )
    }

    @Test
    fun builderRoutesAndDnsAreGatedByTunOptionsAutoRoute() {
        val off = TunRoutePlan.plan(
            OpenTunInput(
                autoRoute = false,
                api33 = true,
                dnsMode = "hijack",
                inet4Address = listOf(TunRoutePlan.IPV4_LOCAL),
                inet4RouteAddress = listOf(TunRoutePlan.IPV4_DEFAULT),
                dnsServers = listOf("172.19.0.2"),
            ),
        )
        assertThat(off.routes).isEmpty()
        assertThat(off.dnsServers).isEmpty()

        val fromOptions = CidrPrefix("10.0.0.0", 8)
        val on = TunRoutePlan.plan(
            OpenTunInput(
                autoRoute = true,
                api33 = true,
                dnsMode = "hijack",
                inet4Address = listOf(TunRoutePlan.IPV4_LOCAL),
                inet4RouteAddress = listOf(fromOptions),
                dnsServers = listOf("172.19.0.2"),
            ),
        )
        assertThat(on.routes).containsExactly(fromOptions)
        assertThat(on.routes).doesNotContain(TunRoutePlan.IPV4_DEFAULT)
        assertThat(on.dnsServers).containsExactly("172.19.0.2")

        val pre33Range = listOf(CidrPrefix("0.0.0.0", 1), CidrPrefix("128.0.0.0", 1))
        val range = TunRoutePlan.plan(
            OpenTunInput(
                autoRoute = true,
                api33 = false,
                dnsMode = "hijack",
                inet4Address = listOf(TunRoutePlan.IPV4_LOCAL),
                inet4RouteRange = pre33Range,
            ),
        )
        assertThat(range.routes).isEqualTo(pre33Range)
        assertThat(range.routes).doesNotContain(TunRoutePlan.IPV4_DEFAULT)
    }

    private fun outbound(): NormalizedOutbound =
        NormalizedOutbound(
            id = "out-1",
            tag = "proxy",
            type = "vless",
            singBoxJson =
            """{"type":"vless","tag":"proxy","server":"node.example.com","server_port":443,""" +
                """"uuid":"11111111-1111-1111-1111-111111111111",""" +
                """"transport":{"type":"xhttp","path":"/xhttp","mode":"auto"}}""",
        )
}
