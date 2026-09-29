package app.lernet.engine.compile

import app.lernet.config.model.NormalizedOutbound
import app.lernet.engine.RunMode
import app.lernet.routing.CompiledRoute
import app.lernet.routing.RouteAction
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Desk simulation of the Android packet path after Hiddify-shaped DNS:
 * DNS UDP underlay (no detour) is dns.final. Sniff → hijack-dns uses that underlay.
 * Public TCP → sniff (timeout) → skip hijack → skip private → route final proxy.
 */
class RoutePacketPathTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun dnsHijackThenPublicTcpHitsFinalProxy() {
        val assembled = ConfigAssembler.assemble(
            xhttpOutbound(),
            CompiledRoute(rules = emptyList(), finalAction = RouteAction.PROXY, errors = emptyList()),
            RunMode.FULL_VPN,
            "info",
        )
        val root = json.parseToJsonElement(assembled.json).jsonObject
        assertThat(DnsDependency.dangling(root)).isEmpty()

        val dns = root["dns"]!!.jsonObject
        val dnsTypes = dns["servers"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertThat(dns["final"]?.jsonPrimitive?.content).isEqualTo(DnsBlock.DIRECT_TAG)
        assertThat(dnsTypes).doesNotContain("https")
        assertThat(dnsTypes).contains("udp")
        assertThat(dnsTypes).contains("local")
        assertThat(dns["servers"]!!.jsonArray.any { it.jsonObject["tag"]?.jsonPrimitive?.content == "local" }).isTrue()

        val rules = root["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
            .filter { it["inbound"] == null }
        assertThat(root["route"]!!.jsonObject["final"]?.jsonPrimitive?.content).isEqualTo("proxy")

        val sniff = rules[0]
        assertThat(sniff["action"]?.jsonPrimitive?.content).isEqualTo("sniff")
        assertThat(sniff["timeout"]?.jsonPrimitive?.content).isEqualTo("300ms")
        assertThat(sniff.containsKey("inbound")).isFalse()

        val portHijack = rules[1]
        assertThat(portHijack["action"]?.jsonPrimitive?.content).isEqualTo("hijack-dns")
        assertThat(portHijack["port"]?.jsonPrimitive?.content).isEqualTo("53")
        assertThat(portHijack.containsKey("type")).isFalse()
        assertThat(portHijack.containsKey("protocol")).isFalse()

        val protocolHijack = rules[2]
        assertThat(protocolHijack["action"]?.jsonPrimitive?.content).isEqualTo("hijack-dns")
        assertThat(protocolHijack["protocol"]?.jsonPrimitive?.content).isEqualTo("dns")
        assertThat(protocolHijack.containsKey("type")).isFalse()
        assertThat(protocolHijack.containsKey("port")).isFalse()

        val inbound = root["inbounds"]!!.jsonArray.first().jsonObject
        assertThat(inbound["stack"]?.jsonPrimitive?.content).isEqualTo("gvisor")
        assertThat(inbound.containsKey("dns_mode")).isFalse()
        assertThat(inbound["mtu"]?.jsonPrimitive?.content).isEqualTo("1500")

        val privateDirect = rules[3]
        assertThat(privateDirect["ip_is_private"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(privateDirect["outbound"]?.jsonPrimitive?.content).isEqualTo("direct")

        rules.forEach { rule ->
            assertThat(rule["action"]?.jsonPrimitive?.content).isNotEqualTo("reject")
            assertThat(rule["outbound"]?.jsonPrimitive?.content).isNotEqualTo("block")
        }

        assertThat(SniffRematch.target(protocol = "dns", port = 53, privateIp = false, finalTag = "proxy"))
            .isEqualTo("hijack-dns")
        assertThat(SniffRematch.target(protocol = null, port = 53, privateIp = false, finalTag = "proxy"))
            .isEqualTo("hijack-dns")
        assertThat(SniffRematch.target(protocol = null, port = 443, privateIp = true, finalTag = "proxy"))
            .isEqualTo("direct")
        assertThat(SniffRematch.target(protocol = "tls", port = 443, privateIp = false, finalTag = "proxy"))
            .isEqualTo("proxy")
        assertThat(SniffRematch.target(protocol = null, port = 443, privateIp = false, finalTag = "proxy"))
            .isEqualTo("proxy")
    }

    private fun xhttpOutbound(): NormalizedOutbound =
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
