package app.lernet.engine.compile

import app.lernet.config.model.DnsPolicy
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class DnsTunnelTest {
    private fun servers(dns: JsonObject) = dns["servers"]!!.jsonArray.map { it.jsonObject }
    private fun tag(o: JsonObject) = o["tag"]?.jsonPrimitive?.content

    @Test
    fun defaultPolicyIsTunnel() {
        assertThat(DnsPolicy.DEFAULT).isEqualTo(DnsPolicy.TUNNEL)
        assertThat(DnsPolicy.fromStorage(null)).isEqualTo(DnsPolicy.TUNNEL)
        assertThat(DnsPolicy.fromStorage("UNDERLAY")).isEqualTo(DnsPolicy.UNDERLAY)
    }

    @Test
    fun siteNamesGoThroughTheTunnelByTcpAndDoh() {
        val dns = DnsBlock.prepare(null, "proxy", DnsPolicy.TUNNEL, "9.9.9.9").dns
        assertThat(dns["final"]!!.jsonPrimitive.content).isEqualTo(DnsBlock.TUNNEL_TAG)
        val byTag = servers(dns).associateBy { tag(it) }
        val group = byTag.getValue(DnsBlock.TUNNEL_TAG)
        assertThat(group["type"]!!.jsonPrimitive.content).isEqualTo("group")
        assertThat(group["servers"]!!.jsonArray.map { it.jsonPrimitive.content })
            .containsExactly(DnsBlock.TUNNEL_TCP_TAG, DnsBlock.TUNNEL_DOH_TAG).inOrder()
        listOf(DnsBlock.TUNNEL_TCP_TAG to "tcp", DnsBlock.TUNNEL_DOH_TAG to "https").forEach { (t, type) ->
            val s = byTag.getValue(t)
            assertThat(s["type"]!!.jsonPrimitive.content).isEqualTo(type)
            assertThat(s["server"]!!.jsonPrimitive.content).isEqualTo("9.9.9.9")
            assertThat(s["detour"]!!.jsonPrimitive.content).isEqualTo("proxy")
        }
        // No UDP over the tunnel and the slow first xhttp stream is waited for.
        assertThat(servers(dns).filter { it["detour"] != null }.map { it["type"]!!.jsonPrimitive.content }).doesNotContain("udp")
        assertThat(dns["timeout"]!!.jsonPrimitive.content).isEqualTo(DnsBlock.TUNNEL_TIMEOUT)
    }

    @Test
    fun networkResolverStaysOnlyForTheServerName() {
        val dns = DnsBlock.prepare(null, "proxy", DnsPolicy.TUNNEL).dns
        val tags = servers(dns).mapNotNull { tag(it) }
        assertThat(tags).containsAtLeast(DnsBlock.DIRECT_TAG, DnsBlock.LOCAL_TAG)
        assertThat(dns["final"]!!.jsonPrimitive.content).isNotEqualTo(DnsBlock.DIRECT_TAG)
    }

    @Test
    fun profileRulesPointingAtDroppedServersFallBackToTunnelNotNetwork() {
        val profile = """{"servers":[{"type":"https","tag":"remote","server":"8.8.8.8","detour":"proxy"},
            {"type":"udp","tag":"dns-direct","server":"1.1.1.1"}],
            "rules":[{"domain_suffix":["example"],"server":"remote"}],"final":"remote"}"""
        val dns = DnsBlock.prepare(profile, "proxy", DnsPolicy.TUNNEL).dns
        val rule = (dns["rules"] as JsonArray).single().jsonObject
        assertThat(rule["server"]!!.jsonPrimitive.content).isEqualTo(DnsBlock.TUNNEL_TAG)
        assertThat(servers(dns).mapNotNull { tag(it) }).doesNotContain("remote")
    }

    @Test
    fun underlayChoiceIsKept() {
        val dns = DnsBlock.prepare(null, "proxy", DnsPolicy.UNDERLAY).dns
        assertThat(dns["final"]!!.jsonPrimitive.content).isEqualTo(DnsBlock.DIRECT_TAG)
        assertThat(servers(dns).mapNotNull { tag(it) }).doesNotContain(DnsBlock.TUNNEL_TAG)
    }
}
