package app.lernet.engine.compile

import app.lernet.config.model.DnsPolicy
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

class ConfigTruthTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun fullPreviewMatchesEffectiveConfigAndKeepsAccessKeys() {
        val outbound = xhttpOutbound(withMux = false)
        val preview = ConfigTruth.preview(
            outbound, catchAll(), RunMode.FULL_VPN, "warn",
            null, DnsPolicy.UNDERLAY, EngineDefaults(tunMtu = 1400, xmuxConcurrency = "8-8")
        )
        val full = json.parseToJsonElement(preview.fullEngineJson).jsonObject
        assertThat(full["log"]!!.jsonObject["level"]!!.jsonPrimitive.content).isEqualTo("info")
        assertThat(full["inbounds"]!!.jsonArray.single().jsonObject["mtu"]!!.jsonPrimitive.content)
            .isEqualTo("1400")
        assertThat(preview.fullEngineJson).contains("uuid")
        val dns = preview.fields.single { it.id == TruthFieldId.DNS }
        assertThat(dns.source).isEqualTo(FieldSource.GLOBAL)
        assertThat(preview.errors).isEmpty()
    }

    @Test
    fun underlayStampStrikesProfileTlsAndDoh() {
        listOf(TLS_DNS to "tls", DOH_DNS to "https").forEach { (dns, type) ->
            val assembled = assemble(dns, DnsPolicy.UNDERLAY)
            val row = dnsRow(assembled, dns, DnsPolicy.UNDERLAY)
            assertThat(row.overridden).isTrue()
            assertThat(row.source).isEqualTo(FieldSource.SYSTEM)
            assertThat(row.reason).isEqualTo(OverrideReason.DNS_DROPPED_PROXIED)
            assertThat(row.reasonDetail).contains("dns dropped proxied resolver")
            assertThat(row.profileValue).contains(type)
            assertThat(row.effectiveValue).contains("final=${DnsBlock.DIRECT_TAG}")
            assertThat(row.effectiveValue).contains("udp")
            assertThat(row.effectiveValue).doesNotContain("tls")
            assertThat(row.effectiveValue).doesNotContain("https")
            val finalTag = json.parseToJsonElement(assembled.json)
                .jsonObject["dns"]!!.jsonObject["final"]!!.jsonPrimitive.content
            assertThat(finalTag).isEqualTo(DnsBlock.DIRECT_TAG)
        }
    }

    @Test
    fun profilePolicyKeepsProxiedFinal() {
        val dns = TLS_DNS
        val assembled = assemble(dns, DnsPolicy.PROFILE)
        val root = json.parseToJsonElement(assembled.json).jsonObject
        val dnsObj = root["dns"]!!.jsonObject
        assertThat(dnsObj["final"]!!.jsonPrimitive.content).isEqualTo(DnsBlock.REMOTE_TAG)
        assertThat(assembled.notes.joinToString()).doesNotContain(DnsBlock.UNDERLAY_CRUMB_PREFIX)
        assertThat(assembled.notes.joinToString()).contains("${DnsBlock.PROFILE_KEPT_PREFIX} final=${DnsBlock.REMOTE_TAG}")
        val types = dnsObj["servers"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertThat(types).contains("tls")
        val tun = root["inbounds"]!!.jsonArray.first().jsonObject
        assertThat(tun["stack"]!!.jsonPrimitive.content).isEqualTo(ConfigAssembler.TUN_STACK)
        assertThat(root["route"]!!.jsonObject["final"]!!.jsonPrimitive.content).isEqualTo("proxy")
        assertThat(assembled.notes.joinToString()).contains("xmux max_concurrency=${XhttpMode.MUX_CONCURRENCY}")
        val fields = fieldsOf(assembled, dns, DnsPolicy.PROFILE)
        val row = fields.single { it.id == TruthFieldId.DNS }
        assertThat(row.overridden).isFalse()
        assertThat(row.source).isEqualTo(FieldSource.PROFILE)
        assertThat(row.effectiveValue).contains("final=${DnsBlock.REMOTE_TAG}")
        assertThat(row.effectiveValue).contains("tls")
        val mode = fields.single { it.id == TruthFieldId.TRANSPORT_MODE }
        assertThat(mode.overridden).isFalse()
        assertThat(mode.profileValue).isEqualTo("stream-one")
        assertThat(mode.effectiveValue).isEqualTo("stream-one")
        val xmux = fields.single { it.id == TruthFieldId.XMUX }
        assertThat(xmux.overridden).isFalse()
        assertThat(xmux.source).isEqualTo(FieldSource.GLOBAL)
        assertThat(xmux.effectiveValue).isEqualTo(XhttpMode.MUX_CONCURRENCY)
        val log = fields.single { it.id == TruthFieldId.LOG_LEVEL }
        assertThat(log.overridden).isTrue()
        assertThat(log.globalValue).isEqualTo("warn")
        assertThat(log.effectiveValue).isEqualTo("info")
    }

    @Test
    fun profilePolicyStripsEmptyDirectDetourWithoutForcingUnderlayFinal() {
        val dns = """
            {"servers":[
            {"type":"tls","tag":"dns-remote","server":"1.1.1.1","detour":"proxy"},
            {"type":"udp","tag":"extra","server":"9.9.9.9","detour":"direct"}],
            "final":"dns-remote"}
        """.trimIndent().replace("\n", "")
        val assembled = assemble(dns, DnsPolicy.PROFILE)
        val servers = json.parseToJsonElement(assembled.json)
            .jsonObject["dns"]!!.jsonObject["servers"]!!.jsonArray
            .map { it.jsonObject }
        servers.forEach { server ->
            assertThat(server["detour"]?.jsonPrimitive?.content).isNotEqualTo("direct")
        }
        val row = dnsRow(assembled, dns, DnsPolicy.PROFILE)
        assertThat(row.overridden).isTrue()
        assertThat(row.reason).isEqualTo(OverrideReason.DNS_DROPPED_DIRECT_DETOUR)
        assertThat(row.profileValue).contains("detour=direct")
        assertThat(row.effectiveValue).doesNotContain("detour=direct")
        assertThat(row.effectiveValue).contains("final=${DnsBlock.REMOTE_TAG}")
        assertThat(row.effectiveValue).contains("tls")
    }

    @Test
    fun softReloadKeepsProfileDnsUntilPolicyIsUnderlay() {
        val assembled = assemble(TLS_DNS, DnsPolicy.PROFILE)
        val kept = DnsBlock.enforceCompiledRemote(assembled.json, "proxy", DnsPolicy.PROFILE)
        val keptFinal = json.parseToJsonElement(kept.json).jsonObject["dns"]!!.jsonObject["final"]!!.jsonPrimitive.content
        assertThat(keptFinal).isEqualTo(DnsBlock.REMOTE_TAG)
        assertThat(kept.notes.joinToString()).doesNotContain(DnsBlock.UNDERLAY_CRUMB_PREFIX)
        val stamped = DnsBlock.enforceCompiledRemote(assembled.json, "proxy", DnsPolicy.UNDERLAY)
        val stampedFinal = json.parseToJsonElement(stamped.json)
            .jsonObject["dns"]!!.jsonObject["final"]!!.jsonPrimitive.content
        assertThat(stampedFinal).isEqualTo(DnsBlock.DIRECT_TAG)
        assertThat(stamped.notes.joinToString()).contains(DnsBlock.UNDERLAY_CRUMB_PREFIX)
    }

    @Test
    fun ownedXmuxIsNotAnOverride() {
        val outbound = xhttpOutbound(withMux = true)
        val assembled = ConfigAssembler.assemble(
            outbound,
            catchAll(),
            RunMode.FULL_VPN,
            "info",
            dnsJson = null,
            dnsPolicy = DnsPolicy.UNDERLAY,
        )
        val fields = ConfigTruth.fields(
            TruthInput(outbound.singBoxJson, null, DnsPolicy.UNDERLAY, "info", assembled.json, assembled.notes),
        )
        val xmux = fields.single { it.id == TruthFieldId.XMUX }
        assertThat(xmux.overridden).isFalse()
        assertThat(xmux.effectiveValue).isEqualTo(XhttpMode.MUX_CONCURRENCY)
        assertThat(assembled.notes.joinToString()).contains("xmux kept")
    }

    @Test
    fun patchKeepsUnknownOutboundKeys() {
        val raw = """
            {"type":"vless","tag":"proxy","server":"old.example","server_port":443,
            "packet_encoding":"xudp",
            "tls":{"enabled":true,"server_name":"old","reality":{"enabled":true,"public_key":"SECRET"}},
            "transport":{"type":"xhttp","path":"/p","mode":"stream-one","extra":1}}
        """.trimIndent().replace("\n", "")
        val written = OutboundPatch.write(raw, "node.example", 8443, "store.steampowered.com", "/c42", "stream-one")
        val root = json.parseToJsonElement(written).jsonObject
        assertThat(root["server"]!!.jsonPrimitive.content).isEqualTo("node.example")
        assertThat(root["server_port"]!!.jsonPrimitive.content).isEqualTo("8443")
        assertThat(root["packet_encoding"]!!.jsonPrimitive.content).isEqualTo("xudp")
        val tls = root["tls"]!!.jsonObject
        assertThat(tls["server_name"]!!.jsonPrimitive.content).isEqualTo("store.steampowered.com")
        assertThat(tls["reality"]!!.jsonObject["public_key"]!!.jsonPrimitive.content).isEqualTo("SECRET")
        val transport = root["transport"]!!.jsonObject
        assertThat(transport["path"]!!.jsonPrimitive.content).isEqualTo("/c42")
        assertThat(transport["extra"]!!.jsonPrimitive.content).isEqualTo("1")
        val owned = OutboundPatch.read(written)
        assertThat(owned.reality).isEqualTo(OwnedOutbound.REALITY_PRESENT)
    }

    @Test
    fun dnsWriteKeepsDetourAndFinal() {
        val original = TLS_DNS
        val drafts = DnsDraft.read(original).map { it.copy(server = "9.9.9.9") }
        val written = checkNotNull(DnsDraft.write(original, drafts))
        val dns = json.parseToJsonElement(written).jsonObject
        val server = dns["servers"]!!.jsonArray.first().jsonObject
        assertThat(server["server"]!!.jsonPrimitive.content).isEqualTo("9.9.9.9")
        assertThat(server["detour"]!!.jsonPrimitive.content).isEqualTo("proxy")
        assertThat(server["type"]!!.jsonPrimitive.content).isEqualTo("tls")
        assertThat(dns["final"]!!.jsonPrimitive.content).isEqualTo(DnsBlock.REMOTE_TAG)
    }

    @Test
    fun engineSnapshotRedactsSecrets() {
        val raw = """{"uuid":"11111111-1111-1111-1111-111111111111","password":"hunter2"}"""
        val redacted = ConfigTruth.redactedEngineJson(raw)
        assertThat(redacted).doesNotContain("11111111-1111-1111-1111-111111111111")
        assertThat(redacted).doesNotContain("hunter2")
        assertThat(redacted).contains("***")
    }

    private fun dnsRow(assembled: AssembledConfig, dns: String, policy: DnsPolicy): FieldView =
        fieldsOf(assembled, dns, policy).single { it.id == TruthFieldId.DNS }

    private fun fieldsOf(assembled: AssembledConfig, dns: String, policy: DnsPolicy): List<FieldView> =
        ConfigTruth.fields(
            TruthInput(
                outboundJson = xhttpOutbound(withMux = false).singBoxJson,
                dnsJson = dns,
                dnsPolicy = policy,
                logLevel = "warn",
                assembledJson = assembled.json,
                notes = assembled.notes,
            ),
        )

    private fun assemble(dns: String, policy: DnsPolicy): AssembledConfig =
        ConfigAssembler.assemble(
            xhttpOutbound(withMux = false),
            catchAll(),
            RunMode.FULL_VPN,
            "warn",
            dnsJson = dns,
            dnsPolicy = policy,
        )

    private fun xhttpOutbound(withMux: Boolean): NormalizedOutbound {
        val mux = if (withMux) ""","xmux":{"max_concurrency":"16-16"}""" else ""
        return NormalizedOutbound(
            id = "out-1",
            tag = "proxy",
            type = "vless",
            singBoxJson = """
                {"type":"vless","tag":"proxy","server":"node.example","server_port":443,
                "uuid":"11111111-1111-1111-1111-111111111111",
                "transport":{"type":"xhttp","path":"/x","mode":"stream-one"$mux}}
            """.trimIndent().replace("\n", ""),
        )
    }

    private fun catchAll(): CompiledRoute =
        CompiledRoute(rules = emptyList(), finalAction = RouteAction.PROXY, errors = emptyList())

    private companion object {
        const val TLS_DNS =
            """{"servers":[{"type":"tls","tag":"dns-remote","server":"1.1.1.1","detour":"proxy"}],"final":"dns-remote"}"""
        const val DOH_DNS =
            """{"servers":[{"type":"https","tag":"remote","server":"1.1.1.1","detour":"proxy"}],"final":"remote"}"""
    }
}
