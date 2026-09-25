package app.lernet.engine.compile

import app.lernet.config.model.NormalizedOutbound
import app.lernet.engine.RunMode
import app.lernet.routing.CompiledRoute
import app.lernet.routing.CompiledRule
import app.lernet.routing.MatchKind
import app.lernet.routing.RouteAction
import app.lernet.routing.RouteCompiler
import app.lernet.routing.RuleMatch
import app.lernet.routing.RuleNode
import app.lernet.routing.Specificity
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class ConfigAssemblerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun globalDefaultsAffectOnlyMissingValuesInFinalConfig() {
        val defaults = EngineDefaults(tunMtu = 1400, xmuxConcurrency = "8-8", directDnsServer = "9.9.9.9")
        val assembled = ConfigAssembler.assemble(
            xhttpOutbound(), catchAllProxy(), RunMode.FULL_VPN,
            "info", defaults = defaults
        )
        assertThat(assembled.isValid).isTrue()
        val root = json.parseToJsonElement(assembled.json).jsonObject
        assertThat(root["inbounds"]!!.jsonArray.single().jsonObject["mtu"]!!.jsonPrimitive.content)
            .isEqualTo("1400")
        val mux = root["outbounds"]!!.jsonArray.first().jsonObject["transport"]!!.jsonObject["xmux"]!!.jsonObject
        assertThat(mux["max_concurrency"]!!.jsonPrimitive.content).isEqualTo("8-8")
        val direct = root["dns"]!!.jsonObject["servers"]!!.jsonArray.first().jsonObject
        assertThat(direct["server"]!!.jsonPrimitive.content).isEqualTo("9.9.9.9")
    }

    @Test
    fun vpnConfigHasTunDnsAndSingleProxyOutbound() {
        val assembled = ConfigAssembler.assemble(sampleOutbound(), catchAllProxy(), RunMode.FULL_VPN, "warn")
        assertThat(assembled.isValid).isTrue()
        val root = json.parseToJsonElement(assembled.json).jsonObject
        val inbound = root["inbounds"]!!.jsonArray.single().jsonObject
        assertThat(inbound["type"]?.jsonPrimitive?.content).isEqualTo("tun")
        assertThat(root["dns"]!!.jsonObject["servers"]!!.jsonArray).isNotEmpty()
        assertNoLegacyDns(assembled.json)
        val outboundTypes = root["outbounds"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertThat(outboundTypes).containsExactly("vless", "direct").inOrder()
        assertThat(root["route"]!!.jsonObject["auto_detect_interface"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(inbound["auto_route"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(inbound["strict_route"]?.jsonPrimitive?.content).isEqualTo("false")
        assertThat(inbound["stack"]?.jsonPrimitive?.content).isEqualTo("gvisor")
        assertThat(inbound.containsKey("dns_mode")).isFalse()
        assertThat(inbound.containsKey("interface_name")).isFalse()
        assertThat(inbound["mtu"]?.jsonPrimitive?.content).isEqualTo(ConfigAssembler.TUN_MTU.toString())
        val tunAddrs = inbound["address"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertThat(tunAddrs).containsExactly(ConfigAssembler.TUN_ADDRESS)
    }

    @Test
    fun preservesXhttpModeAndNotesIt() {
        val outbound = NormalizedOutbound(
            id = "out-1",
            tag = "proxy",
            type = "vless",
            singBoxJson = """
                {"type":"vless","tag":"proxy","server":"example.com","server_port":443,
                "uuid":"11111111-1111-1111-1111-111111111111",
                "transport":{"type":"xhttp","path":"/","mode":"auto"}}
            """.trimIndent().replace("\n", ""),
        )
        val assembled = ConfigAssembler.assemble(outbound, catchAllProxy(), RunMode.FULL_VPN, "info")
        val transport = json.parseToJsonElement(assembled.json)
            .jsonObject["outbounds"]!!.jsonArray.first().jsonObject["transport"]!!.jsonObject
        assertThat(transport["mode"]?.jsonPrimitive?.content).isEqualTo("auto")
        assertThat(transport["xmux"]!!.jsonObject["max_concurrency"]?.jsonPrimitive?.content)
            .isEqualTo(XhttpMode.MUX_CONCURRENCY)
        assertThat(assembled.notes.joinToString()).contains("xhttp mode auto preserved")
    }

    @Test
    fun proxyModeUsesMixedInboundAndRejectsBlockViaAction() {
        val assembled = ConfigAssembler.assemble(
            sampleOutbound(),
            CompiledRoute(
                rules = listOf(
                    CompiledRule(
                        nodeId = "ads",
                        specificity = Specificity(MatchKind.EXACT_DOMAIN, 0, 0),
                        match = RuleMatch(domains = listOf("ads.example")),
                        action = RouteAction.BLOCK,
                    ),
                ),
                finalAction = RouteAction.PROXY,
                errors = emptyList(),
            ),
            RunMode.PROXY,
            "info",
        )
        val root = json.parseToJsonElement(assembled.json).jsonObject
        val inbound = root["inbounds"]!!.jsonArray.single().jsonObject
        assertThat(inbound["type"]?.jsonPrimitive?.content).isEqualTo("mixed")
        assertThat(inbound["listen"]?.jsonPrimitive?.content).isEqualTo("127.0.0.1")
        assertThat(inbound["listen_port"]?.jsonPrimitive?.content).isEqualTo("2080")
        val rules = root["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        assertThat(rules.any { it["action"]?.jsonPrimitive?.content == "reject" }).isTrue()
        assertThat(rules.any { it["domain"] != null && it["action"]?.jsonPrimitive?.content == "reject" }).isTrue()
        val types = root["outbounds"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertThat(types).doesNotContain("block")
        assertThat(types).doesNotContain("dns")
        assertNoLegacy114(assembled.json)
    }

    @Test
    fun inboundUsesRouteActionsInsteadOfLegacySniff() {
        val vpn = ConfigAssembler.assemble(sampleOutbound(), catchAllProxy(), RunMode.FULL_VPN, "warn")
        val proxy = ConfigAssembler.assemble(sampleOutbound(), catchAllProxy(), RunMode.PROXY, "warn")
        listOf(vpn, proxy).forEach { assembled ->
            val inbound = json.parseToJsonElement(assembled.json).jsonObject["inbounds"]!!.jsonArray.single().jsonObject
            assertThat(inbound.containsKey("sniff")).isFalse()
            assertThat(inbound.containsKey("sniff_timeout")).isFalse()
            assertThat(inbound.containsKey("domain_strategy")).isFalse()
            val rules = json.parseToJsonElement(assembled.json).jsonObject["route"]!!.jsonObject["rules"]!!.jsonArray
            val actions = rules.mapNotNull { it.jsonObject["action"]?.jsonPrimitive?.content }
            assertThat(actions).contains("sniff")
            assertThat(actions).contains("hijack-dns")
            val sniff = rules.map { it.jsonObject }.first { it["action"]?.jsonPrimitive?.content == "sniff" }
            assertThat(sniff["timeout"]?.jsonPrimitive?.content).isEqualTo(ConfigAssembler.SNIFF_TIMEOUT)
            assertThat(sniff.containsKey("inbound")).isFalse()
            val hijacks = rules.map { it.jsonObject }.filter { it["action"]?.jsonPrimitive?.content == "hijack-dns" }
            assertThat(hijacks).hasSize(2)
            assertThat(hijacks[0]["port"]?.jsonPrimitive?.content).isEqualTo("53")
            assertThat(hijacks[1]["protocol"]?.jsonPrimitive?.content).isEqualTo("dns")
            hijacks.forEach { assertThat(it.containsKey("type")).isFalse() }
            assertNoLegacy114(assembled.json)
        }
        val vpnRules = json.parseToJsonElement(vpn.json).jsonObject["route"]!!.jsonObject["rules"]!!.jsonArray
        assertThat(vpnRules.any { it.jsonObject["ip_is_private"]?.jsonPrimitive?.content == "true" }).isTrue()
    }

    @Test
    fun userShapedModernConfigStays114Legal() {
        val fixture = fixture("user-114-shape.json")
        val root = json.parseToJsonElement(fixture).jsonObject
        val outbound = NormalizedOutbound(
            id = "out-1",
            tag = "proxy",
            type = "vless",
            singBoxJson = root["outbounds"]!!.jsonArray.first().toString(),
        )
        val assembled = ConfigAssembler.assemble(
            outbound,
            catchAllProxy(),
            RunMode.FULL_VPN,
            "warn",
            dnsJson = root["dns"]!!.toString(),
        )
        assertThat(assembled.isValid).isTrue()
        val compiled = json.parseToJsonElement(assembled.json).jsonObject
        val transport = compiled["outbounds"]!!.jsonArray.first().jsonObject["transport"]!!.jsonObject
        assertThat(transport["type"]?.jsonPrimitive?.content).isEqualTo("xhttp")
        val dnsTypes = compiled["dns"]!!.jsonObject["servers"]!!.jsonArray.map {
            it.jsonObject["type"]!!.jsonPrimitive.content
        }
        assertThat(dnsTypes).containsAtLeast("local", "udp")
        assertThat(dnsTypes).doesNotContain("https")
        assertUnderlayDns(assembled.json, assembled.notes)
        val inbound = compiled["inbounds"]!!.jsonArray.single().jsonObject
        assertThat(inbound["address"]).isNotNull()
        assertThat(inbound.containsKey("inet4_address")).isFalse()
        val actions = compiled["route"]!!.jsonObject["rules"]!!.jsonArray.mapNotNull {
            it.jsonObject["action"]?.jsonPrimitive?.content
        }
        assertThat(actions).contains("sniff")
        assertThat(actions).contains("hijack-dns")
        assertNoLegacy114(assembled.json)
        assertNoDanglingDns(assembled.json)
        File("build/lernet-assembled-114.json").writeText(assembled.json)
    }

    @Test
    fun defaultDnsServersUse114TypedForm() {
        val assembled = ConfigAssembler.assemble(sampleOutbound(), catchAllProxy(), RunMode.FULL_VPN, "warn")
        val servers = json.parseToJsonElement(assembled.json).jsonObject["dns"]!!.jsonObject["servers"]!!.jsonArray
        val types = servers.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertThat(types).containsExactly("udp", "local").inOrder()
        assertUnderlayDns(assembled.json, assembled.notes)
        val route = json.parseToJsonElement(assembled.json).jsonObject["route"]!!.jsonObject
        assertThat(route["default_domain_resolver"]?.jsonPrimitive?.content).isEqualTo(DnsBlock.DIRECT_TAG)
        assertThat(
            json.parseToJsonElement(assembled.json).jsonObject["dns"]!!.jsonObject["strategy"]
                ?.jsonPrimitive?.content,
        ).isEqualTo("ipv4_only")
        servers.forEach { server ->
            assertThat(server.jsonObject.containsKey("address")).isFalse()
            assertThat(server.jsonObject["type"]?.jsonPrimitive?.content).isNotEmpty()
        }
        assertNoLegacyDns(assembled.json)
        assertNoEmptyDirectDetour(assembled.json)
    }

    @Test
    fun assembledDnsDetourNeverTargetsMissingOrEmptyDirect() {
        listOf(RunMode.FULL_VPN, RunMode.PROXY).forEach { mode ->
            val assembled = ConfigAssembler.assemble(sampleOutbound(), catchAllProxy(), mode, "info")
            assertThat(assembled.isValid).isTrue()
            assertThat(assembled.errors).isEmpty()
            assertNoEmptyDirectDetour(assembled.json)
            val root = json.parseToJsonElement(assembled.json).jsonObject
            assertThat(assembled.errors).isEqualTo(DnsDependency.dangling(root))
        }
    }

    @Test
    fun profileDetourDirectIsStrippedBeforeBoot() {
        val dns = """
            {"servers":[
              {"type":"udp","tag":"dns-direct","server":"1.1.1.1","detour":"direct","domain_resolver":"local"},
              {"type":"local","tag":"local","detour":"direct"},
              {"type":"tcp","tag":"dns-remote","server":"8.8.8.8","detour":"proxy","domain_resolver":"dns-direct"}
            ],"final":"dns-remote"}
        """.trimIndent()
        val assembled = ConfigAssembler.assemble(
            sampleOutbound(),
            catchAllProxy(),
            RunMode.FULL_VPN,
            "info",
            dnsJson = dns,
        )
        assertThat(assembled.isValid).isTrue()
        assertThat(assembled.notes.joinToString()).contains("dropped detour=direct")
        val servers = json.parseToJsonElement(assembled.json).jsonObject["dns"]!!.jsonObject["servers"]!!.jsonArray
        servers.forEach { server ->
            assertThat(server.jsonObject["detour"]?.jsonPrimitive?.content).isNotEqualTo("direct")
        }
        assertThat(servers.map { it.jsonObject["tag"]?.jsonPrimitive?.content }).doesNotContain("dns-remote")
        assertUnderlayDns(assembled.json, assembled.notes)
        assertNoEmptyDirectDetour(assembled.json)
    }

    @Test
    fun overlaysModernTypedDnsFromProfile() {
        val modern = """
            {"servers":[
              {"type":"tls","tag":"remote","server":"1.1.1.1","domain_resolver":"local"},
              {"type":"local","tag":"local"}
            ],"final":"remote"}
        """.trimIndent()
        val assembled = ConfigAssembler.assemble(
            sampleOutbound(),
            catchAllProxy(),
            RunMode.FULL_VPN,
            "warn",
            dnsJson = modern,
        )
        assertUnderlayDns(assembled.json, assembled.notes)
        assertThat(assembled.json).doesNotContain("\"type\":\"tls\"")
        assertThat(assembled.notes.filter { it.startsWith("dns added") }.joinToString()).doesNotContain("detour=")
        assertNoLegacyDns(assembled.json)
    }

    @Test
    fun compiledProxiedRemoteIsStampedToUnderlayBeforeEngineStart() {
        val compiled = """
            {"dns":{"servers":[
              {"type":"https","tag":"dns-remote","server":"1.1.1.1","path":"/dns-query","detour":"proxy","domain_resolver":"dns-direct"},
              {"type":"udp","tag":"dns-direct","server":"1.1.1.1","domain_resolver":"local"},
              {"type":"local","tag":"local"}
            ],"final":"dns-remote","strategy":"ipv4_only"},
            "route":{"final":"proxy"}}
        """.trimIndent()
        val stamped = DnsBlock.enforceCompiledRemote(compiled, "proxy")
        assertUnderlayDns(stamped.json, stamped.notes)
        assertThat(stamped.json).doesNotContain("\"detour\":\"proxy\"")
        assertThat(stamped.json).doesNotContain("/dns-query")
        val again = DnsBlock.enforceCompiledRemote(stamped.json, "proxy")
        assertUnderlayDns(again.json, again.notes)
        assertThat(
            json.parseToJsonElement(again.json).jsonObject["route"]!!.jsonObject["final"]
                ?.jsonPrimitive?.content
        ).isEqualTo("proxy")
    }

    @Test
    fun proxiedProfileRemoteIsDroppedForUnderlayFinal() {
        listOf(
            """
            {"servers":[
              {"type":"tcp","tag":"dns-remote","server":"1.1.1.1","detour":"proxy"}
            ],"final":"dns-remote"}
            """.trimIndent(),
            """
            {"servers":[
              {"type":"https","tag":"dns-remote","server":"1.1.1.1","path":"/dns-query","detour":"proxy"}
            ],"final":"dns-remote"}
            """.trimIndent(),
            """
            {"servers":[
              {"type":"https","tag":"dns-remote","server":"https://1.1.1.1/dns-query","detour":"proxy"}
            ],"final":"dns-remote"}
            """.trimIndent(),
            """
            {"servers":[
              {"tag":"dns-remote","address":"https://1.1.1.1/dns-query"}
            ],"final":"dns-remote"}
            """.trimIndent(),
        ).forEach { dns ->
            val assembled = ConfigAssembler.assemble(
                sampleOutbound(),
                catchAllProxy(),
                RunMode.FULL_VPN,
                "info",
                dnsJson = dns,
            )
            assertThat(assembled.isValid).isTrue()
            assertUnderlayDns(assembled.json, assembled.notes)
            assertThat(assembled.notes.filter { it.startsWith("dns added") }.joinToString()).doesNotContain("detour=")
        }
    }

    @Test
    fun migratesLegacyAddressDnsToTypedServers() {
        val legacy = """
            {"servers":[
              {"tag":"local","address":"local"},
              {"tag":"bootstrap","address":"1.1.1.1"}
            ],"final":"local"}
        """.trimIndent()
        val assembled = ConfigAssembler.assemble(
            sampleOutbound(),
            catchAllProxy(),
            RunMode.PROXY,
            "warn",
            dnsJson = legacy,
        )
        val servers = json.parseToJsonElement(assembled.json).jsonObject["dns"]!!.jsonObject["servers"]!!.jsonArray
        assertThat(servers[0].jsonObject["type"]?.jsonPrimitive?.content).isEqualTo("local")
        assertThat(servers[1].jsonObject["type"]?.jsonPrimitive?.content).isEqualTo("udp")
        assertThat(servers[1].jsonObject["server"]?.jsonPrimitive?.content).isEqualTo("1.1.1.1")
        servers.forEach { assertThat(it.jsonObject.containsKey("address")).isFalse() }
        assertUnderlayDns(assembled.json, assembled.notes)
        assertNoLegacyDns(assembled.json)
    }

    @Test
    fun dropsTlsDetourThroughProxyInsteadOfRemappingTransport() {
        val dns = """
            {"servers":[
              {"type":"tls","tag":"remote","server":"1.1.1.1","detour":"proxy","domain_resolver":"local"},
              {"type":"local","tag":"local"},
              {"type":"udp","tag":"bootstrap","server":"1.1.1.1"}
            ],"final":"remote"}
        """.trimIndent()
        val assembled = ConfigAssembler.assemble(
            sampleOutbound(),
            catchAllProxy(),
            RunMode.FULL_VPN,
            "info",
            dnsJson = dns,
        )
        val servers = json.parseToJsonElement(assembled.json).jsonObject["dns"]!!.jsonObject["servers"]!!.jsonArray
        assertThat(servers.map { it.jsonObject["tag"]?.jsonPrimitive?.content }).doesNotContain("remote")
        assertThat(servers.any { it.jsonObject["tag"]?.jsonPrimitive?.content == "bootstrap" }).isTrue()
        assertUnderlayDns(assembled.json, assembled.notes)
        assertThat(assembled.json).doesNotContain("\"type\":\"tls\"")
        assertNoDanglingDns(assembled.json)
    }

    @Test
    fun userDnsRemoteWithoutLocalDoesNotDangleLocal() {
        val fixture = fixture("user-dns-remote-no-local.json")
        val dnsJson = json.parseToJsonElement(fixture).jsonObject["dns"]!!.toString()
        val assembled = ConfigAssembler.assemble(
            xhttpOutbound(),
            catchAllProxy(),
            RunMode.FULL_VPN,
            "warn",
            dnsJson = dnsJson,
        )
        assertThat(assembled.isValid).isTrue()
        val root = json.parseToJsonElement(assembled.json).jsonObject
        val dangling = DnsDependency.dangling(root)
        assertThat(dangling).isEmpty()
        assertThat(dangling).doesNotContain("dependency[local] not found for server[dns-remote]")
        val dns = root["dns"]!!.jsonObject
        val servers = dns["servers"]!!.jsonArray.map { it.jsonObject }
        val tags = servers.map { it["tag"]!!.jsonPrimitive.content }
        assertThat(tags).contains("local")
        assertThat(servers.any { it["type"]?.jsonPrimitive?.content == "local" && it["tag"]?.jsonPrimitive?.content == "local" }).isTrue()
        assertThat(tags).doesNotContain("dns-remote")
        assertUnderlayDns(assembled.json, assembled.notes)
        assertThat(assembled.notes.filter { it.startsWith("dns added") }.joinToString()).doesNotContain("detour=")
        assertThat(dns["strategy"]?.jsonPrimitive?.content).isEqualTo("ipv4_only")
        val tunAddrs = root["inbounds"]!!.jsonArray.single().jsonObject["address"]!!.jsonArray.map {
            it.jsonPrimitive.content
        }
        assertThat(tunAddrs).containsExactly("172.19.0.1/30")
        assertNoLegacyDns(assembled.json)
        File("build/lernet-assembled-dns-remote.json").writeText(dns.toString())
    }

    @Test
    fun typicalVlessXhttpDnsHasNoDanglingTags() {
        val assembled = ConfigAssembler.assemble(xhttpOutbound(), catchAllProxy(), RunMode.FULL_VPN, "warn")
        val root = json.parseToJsonElement(assembled.json).jsonObject
        assertThat(DnsDependency.dangling(root)).isEmpty()
        val dns = root["dns"]!!.jsonObject
        val types = dns["servers"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertThat(types).contains("udp")
        assertThat(types).contains("local")
        assertThat(types).doesNotContain("https")
        File("build/lernet-assembled-dns.json").writeText(dns.toString())
    }

    @Test
    fun everyAssembledFixtureHasResolvableDnsDependencies() {
        listOf(
            null,
            fixture("user-114-shape.json").let { raw ->
                json.parseToJsonElement(raw).jsonObject["dns"]!!.toString()
            },
            fixture("user-dns-remote-no-local.json").let { raw ->
                json.parseToJsonElement(raw).jsonObject["dns"]!!.toString()
            }
        ).forEach { dnsJson ->
            val assembled = ConfigAssembler.assemble(
                xhttpOutbound(),
                catchAllProxy(),
                RunMode.FULL_VPN,
                "info",
                dnsJson = dnsJson,
            )
            assertNoDanglingDns(assembled.json)
        }
    }

    private fun assertUnderlayCrumb(notes: List<String>) {
        val crumbs = notes.filter { it.startsWith(DnsBlock.UNDERLAY_CRUMB_PREFIX) }
        assertThat(crumbs).isNotEmpty()
        crumbs.forEach { crumb ->
            assertThat(crumb).contains("tag=${DnsBlock.DIRECT_TAG}")
            assertThat(crumb).contains("type=udp")
            assertThat(crumb).contains("server=${DnsBlock.DIRECT_SERVER}")
            assertThat(crumb).contains("detour=none")
            assertThat(crumb).doesNotContain("type=https")
            assertThat(crumb).doesNotContain("type=tcp")
        }
    }

    private fun assertUnderlayDns(compiled: String, notes: List<String>) {
        val root = json.parseToJsonElement(compiled).jsonObject
        val dns = root["dns"]!!.jsonObject
        assertThat(dns["final"]?.jsonPrimitive?.content).isEqualTo(DnsBlock.DIRECT_TAG)
        val servers = dns["servers"]!!.jsonArray.map { it.jsonObject }
        servers.forEach { server ->
            assertThat(server["detour"]?.jsonPrimitive?.content).isNotEqualTo("proxy")
            assertThat(server["tag"]?.jsonPrimitive?.content).isNotEqualTo(DnsBlock.REMOTE_TAG)
            val type = server["type"]?.jsonPrimitive?.content
            assertThat(type).isNotEqualTo("https")
            assertThat(type).isNotEqualTo("tcp")
            assertThat(type).isNotEqualTo("tls")
        }
        val routeFinal = root["route"]?.jsonObject?.get("final")?.jsonPrimitive?.content
        if (routeFinal != null) {
            assertThat(routeFinal).isEqualTo("proxy")
        }
        assertUnderlayCrumb(notes)
        assertNoEmptyDirectDetour(compiled)
    }

    private fun assertNoDanglingDns(compiled: String) {
        val root = json.parseToJsonElement(compiled).jsonObject
        assertThat(DnsDependency.dangling(root)).isEmpty()
    }

    private fun assertNoEmptyDirectDetour(compiled: String) {
        val root = json.parseToJsonElement(compiled).jsonObject
        val outbounds = (root["outbounds"] as? JsonArray)
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { outbound ->
                outbound["tag"]?.jsonPrimitive?.content?.let { tag -> tag to outbound }
            }
            .toMap()
        val servers = root["dns"]!!.jsonObject["servers"] as JsonArray
        servers.map { it.jsonObject }.forEach { server ->
            val detour = server["detour"]?.jsonPrimitive?.content
            if (detour.isNullOrBlank()) return@forEach
            val outbound = outbounds[detour]
            assertThat(outbound).isNotNull()
            assertThat(DnsDependency.isEmptyDirect(checkNotNull(outbound))).isFalse()
        }
        assertThat(compiled).doesNotContain("\"type\":\"tls\"")
    }

    private fun assertNoLegacyDns(compiled: String) {
        assertThat(DnsBlock.legacyProblems(compiled)).isEmpty()
    }

    private fun assertNoLegacy114(compiled: String) {
        assertThat(SingBox114Guard.legacyProblems(compiled)).isEmpty()
    }

    @Test
    fun countryRuleUsesLocalGeoipRuleSet() {
        val compiled = countryRoute(listOf("ru", "!us"))
        val assembled = ConfigAssembler.assemble(
            sampleOutbound(),
            compiled,
            RunMode.FULL_VPN,
            "warn",
            ruleSetDirectory = "/data/rule-set",
        )
        assertThat(assembled.isValid).isTrue()
        assertThat(SingBox114Guard.legacyProblems(assembled.json)).isEmpty()
        val route = json.parseToJsonElement(assembled.json).jsonObject["route"]!!.jsonObject
        val sets = route["rule_set"]!!.jsonArray.map { it.jsonObject }
        assertThat(sets.map { it["tag"]!!.jsonPrimitive.content }).containsExactly("geoip-ru", "geoip-us").inOrder()
        sets.forEach { set ->
            assertThat(set["type"]!!.jsonPrimitive.content).isEqualTo("local")
            assertThat(set["format"]!!.jsonPrimitive.content).isEqualTo("binary")
        }
        assertThat(sets.map { it["path"]!!.jsonPrimitive.content })
            .containsExactly("/data/rule-set/geoip-ru.srs", "/data/rule-set/geoip-us.srs")
        assertThat(assembled.json).doesNotContain("\"geoip\"")
        val body = route["rules"]!!.toString()
        assertThat(body).contains("\"rule_set\":[\"geoip-ru\"]")
        assertThat(body).contains("\"rule_set\":\"geoip-us\"")
        assertThat(body).contains("\"invert\":true")
        assertThat(body).doesNotContain("!us")
    }

    @Test
    fun countryRuleWithoutDirectoryFailsClosed() {
        val assembled = ConfigAssembler.assemble(
            sampleOutbound(),
            countryRoute(listOf("ru")),
            RunMode.FULL_VPN,
            "warn",
        )
        assertThat(assembled.isValid).isFalse()
        assertThat(assembled.errors.joinToString()).contains("Каталог")
    }

    private fun countryRoute(codes: List<String>): CompiledRoute {
        val geo = RuleNode(
            id = "g",
            parentId = null,
            enabled = true,
            sortIndex = 0,
            match = RuleMatch(geoip = codes),
            action = RouteAction.DIRECT,
        )
        val tail = RuleNode(
            id = "else",
            parentId = null,
            enabled = true,
            sortIndex = 1,
            match = RuleMatch(),
            action = RouteAction.PROXY,
        )
        return RouteCompiler.compile(listOf(geo, tail))
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")).bufferedReader().readText()

    private fun sampleOutbound(): NormalizedOutbound =
        NormalizedOutbound(
            id = "out-1",
            tag = "proxy",
            type = "vless",
            singBoxJson = """
                {"type":"vless","tag":"proxy","server":"example.com","server_port":443,
                "uuid":"11111111-1111-1111-1111-111111111111"}
            """.trimIndent().replace("\n", ""),
        )

    private fun xhttpOutbound(): NormalizedOutbound =
        NormalizedOutbound(
            id = "out-1",
            tag = "proxy",
            type = "vless",
            singBoxJson = """
                {"type":"vless","tag":"proxy","server":"node.example.com","server_port":443,
                "uuid":"11111111-1111-1111-1111-111111111111",
                "transport":{"type":"xhttp","path":"/xhttp","mode":"auto"}}
            """.trimIndent().replace("\n", ""),
        )

    private fun catchAllProxy(): CompiledRoute =
        CompiledRoute(rules = emptyList(), finalAction = RouteAction.PROXY, errors = emptyList())
}
