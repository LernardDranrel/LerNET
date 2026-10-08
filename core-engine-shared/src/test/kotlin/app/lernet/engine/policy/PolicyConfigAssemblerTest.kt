package app.lernet.engine.policy

import app.lernet.config.policy.ExternalExitKind
import app.lernet.config.policy.ExternalExitProfiles
import app.lernet.config.policy.ExternalExitRequest
import app.lernet.config.policy.PolicyMigration
import app.lernet.config.policy.PolicyWorkspace
import app.lernet.config.policy.VerifiedInterfaceBinding
import app.lernet.config.transfer.TransferBundle
import app.lernet.config.transfer.TransferGroup
import app.lernet.config.transfer.TransferOutbound
import app.lernet.config.transfer.TransferProfile
import app.lernet.engine.compile.EnginePlatform
import app.lernet.routing.ConditionBlock
import app.lernet.routing.ConditionKind
import app.lernet.routing.RuleConditions
import app.lernet.routing.policy.DestinationRedirect
import app.lernet.routing.policy.ExitLifecyclePolicy
import app.lernet.routing.policy.FolderPolicy
import app.lernet.routing.policy.FolderSelection
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyBranchEditing
import app.lernet.routing.policy.PolicyChannel
import app.lernet.routing.policy.PolicyHealthSettings
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.PolicyTree
import app.lernet.routing.policy.ProfileExitPolicy
import app.lernet.routing.policy.UnavailableFallback
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyConfigAssemblerTest {
    @Test
    fun `visible otherwise keeps empty Direct transparent on both platforms`() {
        val policy = NetworkPolicy(device = PolicyBranchEditing.displayTree(PolicyTree(PolicyScope.Device)))
        EnginePlatform.entries.forEach { platform ->
            val config = PolicyConfigAssembler.assemble(workspace(policy), platform)
            assertTrue(config.errors.toString(), config.isValid)
            assertFalse(rules(config).any { it["action"]?.jsonPrimitive?.content == "sniff" })
            assertTrue(rules(config).filter { it.containsKey("outbound") }.all { it["outbound"]?.jsonPrimitive?.content == "direct" })
        }
    }

    @Test
    fun `nested otherwise emits the same ordered routing trace and preserved physical exit on both platforms`() {
        var tree = PolicyBranchEditing.displayTree(PolicyTree(PolicyScope.Device, defaultTarget = PolicyTarget.Profile("de")))
        val outer = tree.nodes.single()
        tree = PolicyBranchEditing.putNode(tree, PolicyNode("local", outer.id, conditions = domain("local.example")))
        val remainder = tree.nodes.single { it.otherwise && it.parentId == outer.id }
        EnginePlatform.entries.forEach { platform ->
            val config = PolicyConfigAssembler.assemble(workspace(NetworkPolicy(device = tree)), platform)
            assertTrue(config.errors.toString(), config.isValid)
            val traced = rules(config).filter { (it["lernet_node_ids"] as? JsonArray)?.isNotEmpty() == true }
            assertEquals(2, traced.size)
            assertEquals(JsonArray(listOf(JsonPrimitive(outer.id), JsonPrimitive("local"))), traced[0]["lernet_node_ids"])
            assertEquals("direct", traced[0]["outbound"]?.jsonPrimitive?.content)
            assertEquals(JsonArray(listOf(JsonPrimitive(outer.id), JsonPrimitive(remainder.id))), traced[1]["lernet_node_ids"])
            assertEquals(config.exits.single().tag, traced[1]["outbound"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `Expert system DNS ignores legacy public default on both platforms`() {
        EnginePlatform.entries.forEach { platform ->
            val config = PolicyConfigAssembler.assemble(
                workspace(NetworkPolicy()), platform,
                defaults = app.lernet.engine.compile.EngineDefaults(directDnsServer = "9.9.9.9")
            )
            assertTrue(config.errors.toString(), config.isValid)
            val dns = root(config.policyJson).getValue("dns").jsonObject
            val servers = (dns.getValue("servers") as JsonArray).map { it.jsonObject }
            assertEquals(setOf("local"), servers.map { it.getValue("type").jsonPrimitive.content }.toSet())
            assertFalse(servers.any { it.containsKey("server") })
            assertEquals(PolicyConfigAssembler.DIRECT_DNS_TAG, dns.getValue("final").jsonPrimitive.content)
            assertFalse(config.policyJson.contains("9.9.9.9"))
            val inbound = (root(config.ingressJson).getValue("inbounds") as JsonArray).single().jsonObject
            if (platform == EnginePlatform.WINDOWS) {
                assertEquals("disabled", inbound.getValue("dns_mode").jsonPrimitive.content)
                assertTrue(servers.all { it["lernet_preserve_destination"]?.jsonPrimitive?.content == "true" })
                assertEquals("true", dns.getValue("disable_cache").jsonPrimitive.content)
            } else {
                assertFalse(config.policyJson.contains("lernet_preserve_destination"))
            }
        }
    }

    @Test
    fun `custom business DNS is explicit and never changes service bootstrap DNS`() {
        val policy = NetworkPolicy(
            dns = app.lernet.routing.policy.PolicyDnsSettings(
                app.lernet.routing.policy.PolicyDnsMode.CUSTOM, "10.0.0.53"
            )
        )
        EnginePlatform.entries.forEach { platform ->
            val config = PolicyConfigAssembler.assemble(workspace(policy), platform)
            assertTrue(config.errors.toString(), config.isValid)
            val servers = (root(config.policyJson).getValue("dns").jsonObject.getValue("servers") as JsonArray)
                .map { it.jsonObject }.associateBy { it.getValue("tag").jsonPrimitive.content }
            assertEquals("10.0.0.53", servers.getValue(PolicyConfigAssembler.DIRECT_DNS_TAG).getValue("server").jsonPrimitive.content)
            assertEquals("local", servers.getValue(PolicyConfigAssembler.BOOTSTRAP_DNS_TAG).getValue("type").jsonPrimitive.content)
            assertFalse(servers.getValue(PolicyConfigAssembler.BOOTSTRAP_DNS_TAG).containsKey("server"))
        }
    }

    @Test
    fun `custom direct DNS cannot become the resolver for a protected VPN branch`() {
        val policy = NetworkPolicy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(
                    PolicyNode(
                        "protected", conditions = domain("private.example"),
                        target = PolicyTarget.Profile("de"), protected = true
                    )
                )
            ),
            dns = app.lernet.routing.policy.PolicyDnsSettings(app.lernet.routing.policy.PolicyDnsMode.CUSTOM, "10.0.0.53"),
        )
        EnginePlatform.entries.forEach { platform ->
            val config = PolicyConfigAssembler.assemble(workspace(policy), platform)
            assertTrue(config.errors.toString(), config.isValid)
            val dns = root(config.policyJson).getValue("dns").jsonObject
            val protectedRule = (dns.getValue("rules") as JsonArray).first().jsonObject
            val selectedTag = protectedRule.getValue("server").jsonPrimitive.content
            assertNotEquals(PolicyConfigAssembler.DIRECT_DNS_TAG, selectedTag)
            assertNotEquals(PolicyConfigAssembler.BOOTSTRAP_DNS_TAG, selectedTag)
            val selected = (dns.getValue("servers") as JsonArray).map { it.jsonObject }
                .single { it["tag"]?.jsonPrimitive?.content == selectedTag }
            assertTrue(selected.containsKey("detour"))
        }
    }

    private fun profile(id: String) = TransferProfile(
        id, id, "JSON",
        listOf(
            TransferOutbound(
                "$id-out", "proxy", "socks",
                """
        {"type":"socks","tag":"proxy","server":"$id.example.invalid","server_port":1080,"password":"private-value"}
                """.trimIndent()
            )
        ),
        "$id-out",
    )
    private val legacy = TransferBundle(
        scope = "all", groups = listOf(TransferGroup("eu", "Europe", listOf("de", "nl"))),
        profiles = listOf(profile("de"), profile("nl")), rules = emptyList(),
    )
    private fun domain(value: String) = RuleConditions(blocks = listOf(ConditionBlock(ConditionKind.DOMAIN, listOf(value))))
    private fun workspace(policy: NetworkPolicy): PolicyWorkspace = PolicyMigration.migrate(legacy).copy(saved = policy, draft = policy)
    private fun root(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun rules(config: PolicyAssembledConfig) = (root(config.policyJson).getValue("route").jsonObject.getValue("rules") as JsonArray)
        .map { it.jsonObject }

    @Test
    fun `empty policy captures IPv4 and IPv6 but direct remains default on both platforms`() {
        EnginePlatform.entries.forEach { platform ->
            val config = PolicyConfigAssembler.assemble(workspace(NetworkPolicy()), platform)
            assertTrue(config.errors.toString(), config.isValid)
            assertFalse(root(config.policyJson).containsKey("inbounds"))
            assertFalse(root(config.policyJson).containsKey("experimental"))
            val inbound = (root(config.ingressJson).getValue("inbounds") as JsonArray).single().jsonObject
            assertEquals(2, (inbound.getValue("address") as JsonArray).size)
            assertEquals("direct", rules(config).last().getValue("outbound").jsonPrimitive.content)
            assertFalse(rules(config).any { it["action"]?.jsonPrimitive?.content == "sniff" })
            assertFalse(rules(config).any { it.containsKey("ip_is_private") })
            assertEquals(platform == EnginePlatform.WINDOWS, inbound.getValue("strict_route").jsonPrimitive.content.toBoolean())
        }
    }

    @Test
    fun `profile tags do not collide and diagnostics never include credentials`() {
        val nodes = listOf(
            PolicyNode("one", conditions = domain("one.example"), target = PolicyTarget.Profile("de")),
            PolicyNode("two", sortIndex = 1, conditions = domain("two.example"), target = PolicyTarget.Profile("nl")),
        )
        val config = PolicyConfigAssembler.assemble(
            workspace(NetworkPolicy(device = PolicyTree(PolicyScope.Device, nodes))), EnginePlatform.WINDOWS
        )
        assertTrue(config.errors.toString(), config.isValid)
        assertTrue(rules(config).any { it["action"]?.jsonPrimitive?.content == "sniff" })
        assertEquals(2, config.exits.size)
        assertNotEquals(config.exits[0].tag, config.exits[1].tag)
        assertTrue(config.policyJson.contains("private-value"))
        assertFalse(config.toString().contains("private-value"))
        assertFalse(config.exitManifestJson.contains("private-value"))
    }

    @Test
    fun `shared channel uses same physical outbound while another identity isolates it`() {
        val nodes = listOf(
            PolicyNode("a", conditions = domain("a.example"), target = PolicyTarget.Channel("first")),
            PolicyNode("b", sortIndex = 1, conditions = domain("b.example"), target = PolicyTarget.Channel("first")),
            PolicyNode("c", sortIndex = 2, conditions = domain("c.example"), target = PolicyTarget.Channel("second")),
        )
        val first = PolicyChannel(
            "first", "Same name", PolicyScope.Device,
            PolicyTarget.Profile("de"), ExitLifecyclePolicy(coldStart = true)
        )
        val config = PolicyConfigAssembler.assemble(
            workspace(NetworkPolicy(device = PolicyTree(PolicyScope.Device, nodes), channels = listOf(first, first.copy(id = "second")))),
            EnginePlatform.ANDROID,
        )
        assertTrue(config.errors.toString(), config.isValid)
        assertEquals(2, config.exits.size)
        val routed = rules(config).filter { it["domain"] != null }
        assertEquals(routed[0]["outbound"], routed[1]["outbound"])
        assertNotEquals(routed[0]["outbound"], routed[2]["outbound"])
        assertTrue(config.exits.all { it.lifecycle.coldStart })
    }

    @Test
    fun `ancestor channels converge into one final channel exit and lifecycle on both platforms`() {
        val finalLifecycle = ExitLifecyclePolicy(coldStart = true, idleTimeoutMs = 42_000)
        val nodes = listOf(
            PolicyNode("a", conditions = domain("a.example"), target = PolicyTarget.Channel("ancestor-a")),
            PolicyNode("b", sortIndex = 1, conditions = domain("b.example"), target = PolicyTarget.Channel("ancestor-b")),
        )
        val shared = PolicyChannel("shared", "Shared", PolicyScope.Device, PolicyTarget.Profile("de"), finalLifecycle)
        val channels = listOf(
            PolicyChannel("ancestor-a", "First", PolicyScope.Device, PolicyTarget.Channel(shared.id)),
            PolicyChannel("ancestor-b", "Second", PolicyScope.Device, PolicyTarget.Channel(shared.id)),
            shared,
        )
        val policy = NetworkPolicy(device = PolicyTree(PolicyScope.Device, nodes), channels = channels)
        EnginePlatform.entries.forEach { platform ->
            val config = PolicyConfigAssembler.assemble(workspace(policy), platform)
            assertTrue(config.errors.toString(), config.isValid)
            val exit = config.exits.single()
            assertEquals(listOf(shared.id), exit.channelPath)
            assertEquals(finalLifecycle, exit.lifecycle)
            assertEquals(ExpertExitKey("de", shared.id), exit.key)
            val routed = rules(config).filter { (it["lernet_node_ids"] as? JsonArray)?.isNotEmpty() == true }
            assertEquals(2, routed.size)
            assertEquals(
                setOf(exit.tag),
                routed.map { it.getValue("outbound").jsonPrimitive.content }.toSet()
            )
            assertEquals(
                listOf(listOf("ancestor-a", shared.id), listOf("ancestor-b", shared.id)),
                config.program.rules.filter { it.nodeIds.isNotEmpty() }.map { it.target.channelPath }
            )
            val manifestExit = (root(config.exitManifestJson).getValue("exits") as JsonArray).single().jsonObject
            assertEquals(JsonArray(listOf(JsonPrimitive(shared.id))), manifestExit.getValue("channel_path"))
            assertEquals(
                PolicyConfigAssembler.physicalTag("de", listOf("ancestor-a", shared.id)),
                PolicyConfigAssembler.physicalTag("de", listOf("ancestor-b", shared.id))
            )
        }
    }

    @Test
    fun `same channel name in distinct owners remains distinct physical exits`() {
        val scope = PolicyScope.Profile("de")
        val policy = NetworkPolicy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(
                    PolicyNode("device", conditions = domain("a.example"), target = PolicyTarget.Channel("device-channel")),
                    PolicyNode("profile", sortIndex = 1, conditions = domain("b.example"), target = PolicyTarget.Profile("de", scope))
                )
            ),
            trees = listOf(PolicyTree(scope, defaultTarget = PolicyTarget.Channel("profile-channel"))),
            channels = listOf(
                PolicyChannel("device-channel", "Shared", PolicyScope.Device, PolicyTarget.Profile("de")),
                PolicyChannel("profile-channel", "Shared", scope, PolicyTarget.CurrentExit)
            ),
        )
        val config = PolicyConfigAssembler.assemble(workspace(policy), EnginePlatform.WINDOWS)
        assertTrue(config.errors.toString(), config.isValid)
        assertEquals(2, config.exits.size)
        assertEquals(2, config.exits.map { it.tag }.distinct().size)
        assertEquals(setOf("device-channel", "profile-channel"), config.exits.map { it.key.channelId }.toSet())
    }

    @Test
    fun `shared current exit retains terminal profile and folder identity`() {
        val scope = PolicyScope.Folder("eu")
        val nodes = listOf(
            PolicyNode("de", conditions = domain("de.example"), target = PolicyTarget.Profile("de", scope)),
            PolicyNode("nl", sortIndex = 1, conditions = domain("nl.example"), target = PolicyTarget.Profile("nl", scope)),
            PolicyNode("folder", sortIndex = 2, conditions = domain("folder.example"), target = PolicyTarget.Folder("eu", scope)),
        )
        val shared = PolicyChannel("shared", "Shared", scope, lifecycle = ExitLifecyclePolicy(coldStart = true))
        val policy = NetworkPolicy(
            device = PolicyTree(PolicyScope.Device, nodes),
            trees = listOf(PolicyTree(scope, defaultTarget = PolicyTarget.Channel(shared.id))),
            channels = listOf(shared),
        )
        val config = PolicyConfigAssembler.assemble(workspace(policy), EnginePlatform.WINDOWS)
        assertTrue(config.errors.toString(), config.isValid)
        assertEquals(4, config.exits.size)
        assertEquals(4, config.exits.map { it.tag }.distinct().size)
        assertTrue(config.exits.all { it.channelPath == listOf(shared.id) && it.lifecycle == shared.lifecycle })
        assertEquals(
            setOf(
                ExpertExitKey("de", shared.id), ExpertExitKey("nl", shared.id),
                ExpertExitKey("de", shared.id, folderId = "eu"), ExpertExitKey("nl", shared.id, folderId = "eu")
            ),
            config.exitTags.keys
        )
        assertEquals(listOf(shared.id), config.folders.single().channelPath)
        assertNotEquals(
            PolicyConfigAssembler.physicalTag("de", listOf(shared.id), "eu"),
            PolicyConfigAssembler.physicalTag("de", listOf(shared.id), "another-folder")
        )
    }

    @Test
    fun `folder first flow gate retains all candidates and real selection policy`() {
        val tree = PolicyTree(PolicyScope.Folder("eu"), defaultTarget = PolicyTarget.CurrentExit)
        val policy = NetworkPolicy(
            device = PolicyTree(PolicyScope.Device, listOf(PolicyNode("folder", target = PolicyTarget.Folder("eu")))),
            trees = listOf(tree),
            folderPolicies = listOf(FolderPolicy("eu", FolderSelection.LOWEST_LATENCY, "nl", autoSwap = true)),
        )
        val config = PolicyConfigAssembler.assemble(workspace(policy), EnginePlatform.WINDOWS)
        assertTrue(config.errors.toString(), config.isValid)
        assertEquals(2, config.exits.size)
        val folder = config.folders.single()
        assertEquals(2, folder.candidateTags.size)
        val manifest = (root(config.exitManifestJson).getValue("folders") as JsonArray).single().jsonObject
        assertEquals("fastest", manifest.getValue("selection").jsonPrimitive.content)
        assertEquals("true", manifest.getValue("auto_swap").jsonPrimitive.content)
        assertTrue(rules(config).any { it["outbound"]?.jsonPrimitive?.content == folder.tag })
    }

    @Test
    fun `protected DNS resolver detours through exit and unavailable target rejects`() {
        val node = PolicyNode("secret", conditions = domain("secret.example"), target = PolicyTarget.Profile("de"), protected = true)
        val policy = NetworkPolicy(device = PolicyTree(PolicyScope.Device, listOf(node)))
        val config = PolicyConfigAssembler.assemble(workspace(policy), EnginePlatform.ANDROID)
        assertTrue(config.errors.toString(), config.isValid)
        val dns = root(config.policyJson).getValue("dns").jsonObject
        val dnsRule = (dns.getValue("rules") as JsonArray).first().jsonObject
        val serverTag = dnsRule.getValue("server").jsonPrimitive.content
        val server = (dns.getValue("servers") as JsonArray).map { it.jsonObject }.single { it["tag"]?.jsonPrimitive?.content == serverTag }
        assertEquals(config.exits.single().tag, server.getValue("detour").jsonPrimitive.content)
        val unavailable = PolicyConfigAssembler.assemble(workspace(policy), EnginePlatform.ANDROID, unavailableProfileIds = setOf("de"))
        assertTrue(unavailable.errors.toString(), unavailable.isValid)
        assertEquals("reject", rules(unavailable).first { it["domain"] != null }.getValue("action").jsonPrimitive.content)
        assertFalse(unavailable.exitManifestJson.contains("private-value"))
    }

    @Test
    fun `explicit direct fallback is emitted only for unprotected routing and redirect belongs to rule`() {
        val node = PolicyNode(
            "redirect", conditions = domain("old.example"), target = PolicyTarget.Profile("de", fallback = UnavailableFallback.DIRECT),
            redirect = DestinationRedirect("new.example", 8443),
        )
        val config = PolicyConfigAssembler.assemble(
            workspace(NetworkPolicy(device = PolicyTree(PolicyScope.Device, listOf(node)))), EnginePlatform.WINDOWS
        )
        assertTrue(config.errors.toString(), config.isValid)
        val rule = rules(config).first { it["domain"] != null }
        assertEquals("direct", rule.getValue("lernet_fallback").jsonPrimitive.content)
        assertEquals("new.example", rule.getValue("override_address").jsonPrimitive.content)
        assertEquals("8443", rule.getValue("override_port").jsonPrimitive.content)
    }

    @Test
    fun `physical underlay defaults are dynamic and explicit profile interface stays bound`() {
        val policy = NetworkPolicy(device = PolicyTree(PolicyScope.Device, defaultTarget = PolicyTarget.Profile("de")))
        val config = PolicyConfigAssembler.assemble(workspace(policy), EnginePlatform.WINDOWS, underlayInterface = "Wi-Fi")
        assertTrue(config.errors.toString(), config.isValid)
        val policyRoot = root(config.policyJson)
        assertTrue((policyRoot.getValue("outbounds") as JsonArray).all { !it.jsonObject.containsKey("bind_interface") })
        assertTrue(
            (policyRoot.getValue("dns").jsonObject.getValue("servers") as JsonArray).all {
                !it.jsonObject.containsKey("bind_interface")
            }
        )
        assertEquals("false", policyRoot.getValue("route").jsonObject.getValue("auto_detect_interface").jsonPrimitive.content)
        assertEquals("Wi-Fi", policyRoot.getValue("route").jsonObject.getValue("default_interface").jsonPrimitive.content)
        val ingressRoute = root(config.ingressJson).getValue("route").jsonObject
        assertEquals("Wi-Fi", ingressRoute.getValue("default_interface").jsonPrimitive.content)
        assertEquals("false", ingressRoute.getValue("auto_detect_interface").jsonPrimitive.content)
        val source = profile("de").let { profile ->
            profile.copy(
                outbounds = profile.outbounds.map { outbound ->
                    outbound.copy(
                        singBoxJson = JsonObject(
                            root(outbound.singBoxJson) +
                                ("bind_interface" to JsonPrimitive("Corporate VPN"))
                        ).toString()
                    )
                }
            )
        }
        val corporateWorkspace = workspace(policy).let { it.copy(legacy = it.legacy.copy(profiles = listOf(source, profile("nl")))) }
        val corporate = PolicyConfigAssembler.assemble(corporateWorkspace, EnginePlatform.WINDOWS, underlayInterface = "Wi-Fi")
        assertTrue(corporate.errors.toString(), corporate.isValid)
        val proxy = (root(corporate.policyJson).getValue("outbounds") as JsonArray).first {
            it.jsonObject["type"]?.jsonPrimitive?.content == "socks"
        }
        assertEquals("Corporate VPN", proxy.jsonObject.getValue("bind_interface").jsonPrimitive.content)
    }

    @Test
    fun `Windows ordinary direct preserves original destination routes without changing DNS bootstrap or profile sockets`() {
        val policy = NetworkPolicy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(PolicyNode("profile", conditions = domain("one.example"), target = PolicyTarget.Profile("de")))
            )
        )
        val windows = PolicyConfigAssembler.assemble(workspace(policy), EnginePlatform.WINDOWS, underlayInterface = "Wi-Fi")
        assertTrue(windows.errors.toString(), windows.isValid)
        val outbounds = (root(windows.policyJson).getValue("outbounds") as JsonArray).map { it.jsonObject }
        assertEquals(
            listOf("direct"),
            outbounds.filter { it["lernet_system_route"]?.jsonPrimitive?.content == "true" }
                .map { it.getValue("tag").jsonPrimitive.content }
        )
        val bootstrap = (root(windows.policyJson).getValue("dns").jsonObject.getValue("servers") as JsonArray).first {
            it.jsonObject["tag"]?.jsonPrimitive?.content == PolicyConfigAssembler.BOOTSTRAP_DNS_TAG
        }.jsonObject
        assertFalse(bootstrap.containsKey("detour"))
        assertEquals("local", bootstrap.getValue("type").jsonPrimitive.content)
        assertFalse(bootstrap.containsKey("server"))
        val android = PolicyConfigAssembler.assemble(workspace(policy), EnginePlatform.ANDROID)
        assertTrue(android.errors.toString(), android.isValid)
        assertFalse(android.policyJson.contains("lernet_system_route"))
    }

    @Test
    fun `negated exact DNS predicates preserve logical inversion`() {
        val condition = Json.parseToJsonElement("""{"type":"logical","mode":"and","rules":[{"domain":["one.example"]}],"invert":true}""")
            .jsonObject
        val projected = PolicyDnsProjection.project(condition)
        assertFalse(projected.inexact)
        assertEquals(condition, projected.condition)
    }

    @Test
    fun `protected app policy allows only safe unknown owner DNS and rejects unknown non DNS`() {
        val conditions = RuleConditions(blocks = listOf(ConditionBlock(ConditionKind.APP, listOf("org.telegram.messenger"))))
        val node = PolicyNode("app", conditions = conditions, target = PolicyTarget.Profile("de"), protected = true)
        val config = PolicyConfigAssembler.assemble(
            workspace(NetworkPolicy(device = PolicyTree(PolicyScope.Device, listOf(node)))),
            EnginePlatform.ANDROID
        )
        assertTrue(config.errors.toString(), config.isValid)
        val route = root(config.policyJson).getValue("route").jsonObject
        assertEquals("package", route.getValue("lernet_owner_guard").jsonPrimitive.content)
        val guard = rules(config).indexOfFirst { it.containsKey("package_name_regex") }
        val hijack = rules(config).indexOfFirst { it["action"]?.jsonPrimitive?.content == "hijack-dns" }
        assertTrue(guard > hijack)
        assertEquals("reject", rules(config)[guard].getValue("action").jsonPrimitive.content)
        assertEquals("true", route.getValue("lernet_unknown_owner_dns_safe").jsonPrimitive.content)
        assertEquals("false", route.getValue("auto_detect_interface").jsonPrimitive.content)
        assertEquals("false", root(config.ingressJson).getValue("route").jsonObject.getValue("auto_detect_interface").jsonPrimitive.content)
        val dns = root(config.policyJson).getValue("dns").jsonObject
        val dnsRules = (dns.getValue("rules") as JsonArray).map { it.jsonObject }
        val catchall = dnsRules.indexOfFirst { "package_name_regex" in it && it["action"]?.jsonPrimitive?.content == "reject" }
        assertTrue(catchall > 0)
        val servers = (dns.getValue("servers") as JsonArray).map { it.jsonObject }.associateBy { it.getValue("tag").jsonPrimitive.content }
        dnsRules.take(catchall).filter { "server" in it }.forEach { rule ->
            val server = servers.getValue(rule.getValue("server").jsonPrimitive.content)
            assertTrue(server.getValue("detour").jsonPrimitive.content in config.exits.map { it.tag })
        }
    }

    @Test
    fun `explicit app block denies DNS even without protected checkbox and explains broad impact`() {
        val app = RuleConditions(blocks = listOf(ConditionBlock(ConditionKind.APP, listOf("blocked.app"))))
        val policy = NetworkPolicy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(
                    PolicyNode(
                        "deny", conditions = app,
                        target = PolicyTarget.Block
                    )
                )
            )
        )
        val config = PolicyConfigAssembler.assemble(workspace(policy), EnginePlatform.ANDROID)
        assertTrue(config.errors.toString(), config.isValid)
        val dnsRules = root(config.policyJson).getValue("dns").jsonObject.getValue("rules") as JsonArray
        assertTrue(dnsRules.any { it.jsonObject["action"]?.jsonPrimitive?.content == "reject" && it.jsonObject.keys == setOf("action") })
        assertTrue(config.notes.any { "другие программы" in it })
        assertEquals("package", root(config.policyJson).getValue("route").jsonObject.getValue("lernet_owner_guard").jsonPrimitive.content)
    }

    @Test
    fun `HTTP protected DNS defaults to TCP and explicit UDP is rejected before publication`() {
        val http = ExternalExitProfiles.build(ExternalExitRequest(ExternalExitKind.HTTP, "HTTP", "proxy.invalid", 8080), "de", "de-out")
        val policy = NetworkPolicy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(PolicyNode("secure", target = PolicyTarget.Profile("de"), protected = true))
            )
        )
        val source = workspace(policy).let { it.copy(legacy = it.legacy.copy(profiles = listOf(http, profile("nl")))) }
        val config = PolicyConfigAssembler.assemble(source, EnginePlatform.WINDOWS)
        assertTrue(config.errors.toString(), config.isValid)
        val protectedDns = (root(config.policyJson).getValue("dns").jsonObject.getValue("servers") as JsonArray)
            .map { it.jsonObject }.filter { "detour" in it }
        assertTrue(protectedDns.isNotEmpty())
        assertTrue(protectedDns.all { it.getValue("type").jsonPrimitive.content == "tcp" })
        val explicit = http.copy(
            dnsPolicy = "PROFILE",
            dnsJson = """
            {"servers":[{"type":"udp","tag":"remote","server":"1.1.1.1","detour":"proxy"}],"final":"remote"}
            """.trimIndent()
        )
        val invalid = PolicyConfigAssembler.assemble(
            source.copy(legacy = source.legacy.copy(profiles = listOf(explicit, profile("nl")))),
            EnginePlatform.WINDOWS
        )
        assertFalse(invalid.isValid)
        assertTrue(invalid.dnsSafetyErrors.single().contains("только TCP"))
        assertTrue(invalid.policyJson.isEmpty())
    }

    @Test
    fun `Windows system profile DNS is supported but explicit DHCP binding remains rejected`() {
        val policy = NetworkPolicy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(PolicyNode("profile", target = PolicyTarget.Profile("de")))
            )
        )
        listOf(
            """{"servers":[{"type":"local","tag":"system"}],"final":"system"}""",
            """{"servers":[{"type":"udp","tag":"remote","server":"1.1.1.1"},{"type":"dhcp","tag":"system","interface":"Wi-Fi"}],
                "rules":[{"domain_suffix":["company.example"],"server":"system"}],"final":"remote"}""",
            """{"servers":[{"type":"https","tag":"remote","server":"dns.example","domain_resolver":"system"},
                {"type":"local","tag":"system"}],"final":"remote"}""",
        ).forEach { raw ->
            val source = workspace(policy).let {
                it.copy(
                    legacy = it.legacy.copy(
                        profiles = listOf(
                            profile("de").copy(
                                dnsPolicy = "PROFILE", dnsJson = raw
                            ),
                            profile("nl")
                        )
                    )
                )
            }
            val config = PolicyConfigAssembler.assemble(source, EnginePlatform.WINDOWS)
            if (raw.contains("dhcp")) {
                assertFalse(config.isValid)
                assertTrue(config.dnsSafetyErrors.single().contains("DHCP"))
                assertTrue(config.policyJson.isEmpty())
            } else {
                assertTrue(config.errors.toString(), config.isValid)
                assertTrue(config.policyJson.contains("lernet_preserve_destination"))
                val ingress = (root(config.ingressJson).getValue("inbounds") as JsonArray).single().jsonObject
                assertEquals("disabled", ingress.getValue("dns_mode").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `Windows profile DNS helpers use original network without public substitution`() {
        val policy = NetworkPolicy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(PolicyNode("profile", target = PolicyTarget.Profile("de")))
            )
        )
        listOf(
            """{"servers":[{"type":"https","tag":"remote","server":"dns.example"}],"final":"remote"}""",
            """{"servers":[{"type":"udp","tag":"remote","server":"1.1.1.1"},{"type":"local","tag":"local"}],"final":"remote"}""",
        ).forEach { raw ->
            val source = workspace(policy).let {
                it.copy(
                    legacy = it.legacy.copy(
                        profiles = listOf(
                            profile("de").copy(
                                dnsPolicy = "PROFILE", dnsJson = raw
                            ),
                            profile("nl")
                        )
                    )
                )
            }
            val config = PolicyConfigAssembler.assemble(source, EnginePlatform.WINDOWS)
            assertTrue(config.errors.toString(), config.isValid)
            val dns = root(config.policyJson).getValue("dns").jsonObject
            val servers = (dns.getValue("servers") as JsonArray).map { it.jsonObject }
            assertFalse(servers.any { it["type"]?.jsonPrimitive?.content == "dhcp" })
            val remote = servers.first {
                it["server"]?.jsonPrimitive?.content in setOf("dns.example", "1.1.1.1") &&
                    it.containsKey("domain_resolver")
            }
            val helper = servers.single { it["tag"] == remote["domain_resolver"] }
            assertEquals("local", helper.getValue("type").jsonPrimitive.content)
            assertFalse(helper.containsKey("server"))
            assertEquals("true", helper.getValue("lernet_preserve_destination").jsonPrimitive.content)
        }
    }

    @Test
    fun `ordinary DNS groups remap ordered member tags and preserve selection settings`() {
        val policy = NetworkPolicy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(PolicyNode("profile", target = PolicyTarget.Profile("de")))
            )
        )
        val dns = """{
            "servers":[{"type":"tcp","tag":"first","server":"1.1.1.1"},
                {"type":"tcp","tag":"second","server":"8.8.8.8"},
                {"type":"group","tag":"team","servers":["second","first"],"mode":"fastest","error_ttl":"2m","win_ttl":"5m"}],
            "final":"team"
        }"""
        val source = workspace(policy).let {
            it.copy(
                legacy = it.legacy.copy(
                    profiles = listOf(
                        profile("de").copy(dnsPolicy = "PROFILE", dnsJson = dns), profile("nl")
                    )
                )
            )
        }
        val config = PolicyConfigAssembler.assemble(source, EnginePlatform.WINDOWS)
        assertTrue(config.errors.toString(), config.isValid)
        val servers = (root(config.policyJson).getValue("dns").jsonObject.getValue("servers") as JsonArray)
            .map { it.jsonObject }.associateBy { it.getValue("tag").jsonPrimitive.content }
        val group = servers.values.single { it["type"]?.jsonPrimitive?.content == "group" }
        val memberAddresses = (group.getValue("servers") as JsonArray).map {
            servers.getValue(it.jsonPrimitive.content).getValue("server").jsonPrimitive.content
        }
        assertEquals(listOf("8.8.8.8", "1.1.1.1"), memberAddresses)
        assertEquals("fastest", group.getValue("mode").jsonPrimitive.content)
        assertEquals("2m", group.getValue("error_ttl").jsonPrimitive.content)
        assertEquals("5m", group.getValue("win_ttl").jsonPrimitive.content)
        assertEquals(dns, source.legacy.profiles.first().dnsJson)
    }

    @Test
    fun `DNS groups reject missing members and mixed resolver cycles before publication`() {
        val policy = NetworkPolicy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(PolicyNode("profile", target = PolicyTarget.Profile("de")))
            )
        )
        listOf(
            """{"servers":[{"type":"group","tag":"team","servers":["missing"]}],"final":"team"}""",
            """{"servers":[{"type":"group","tag":"team","servers":["team"]}],"final":"team"}""",
            """{"servers":[{"type":"group","tag":"team","servers":["remote"]},
                {"type":"https","tag":"remote","server":"dns.example","domain_resolver":"team"}],"final":"team"}""",
        ).forEach { dns ->
            val source = workspace(policy).let {
                it.copy(
                    legacy = it.legacy.copy(
                        profiles = listOf(
                            profile("de").copy(dnsPolicy = "PROFILE", dnsJson = dns), profile("nl")
                        )
                    )
                )
            }
            val config = PolicyConfigAssembler.assemble(source, EnginePlatform.WINDOWS)
            assertFalse(config.isValid)
            assertTrue(config.dnsSafetyErrors.single().contains("групп"))
            assertTrue(config.policyJson.isEmpty())
        }
    }

    @Test
    fun `verified Windows interface is protected while Android retains it and blocks its branch`() {
        val corporate = ExternalExitProfiles.build(
            ExternalExitRequest(
                ExternalExitKind.CORPORATE_INTERFACE, "Corporate",
                binding =
                VerifiedInterfaceBinding("12345678-1234-5678-9abc-1234567890ab", "Corporate VPN", 17)
            ),
            "de", "de-out"
        )
        val policy = NetworkPolicy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(
                    PolicyNode(
                        "corporate", conditions = domain("company.example"),
                        target = PolicyTarget.Profile("de"), protected = true
                    )
                )
            )
        )
        val source = workspace(policy).let { it.copy(legacy = it.legacy.copy(profiles = listOf(corporate, profile("nl")))) }
        val windows = PolicyConfigAssembler.assemble(source, EnginePlatform.WINDOWS, underlayInterface = "Wi-Fi")
        assertTrue(windows.errors.toString(), windows.isValid)
        assertTrue(windows.policyJson.contains("lernet_interface"))
        assertEquals("route", rules(windows).first { "domain" in it }.getValue("action").jsonPrimitive.content)
        val android = PolicyConfigAssembler.assemble(source, EnginePlatform.ANDROID)
        assertTrue(android.errors.toString(), android.isValid)
        assertTrue("corporate" in android.program.inactiveNodeIds)
        assertTrue("de" in android.program.unavailableExitProfileIds)
        assertEquals("reject", rules(android).first { "domain" in it }.getValue("action").jsonPrimitive.content)
        assertFalse(android.policyJson.contains("lernet_interface"))
        assertTrue(android.notes.contains(ExternalExitProfiles.ANDROID_INTERFACE_UNSUPPORTED))
        assertEquals(corporate, source.legacy.profiles.first())
    }

    @Test
    fun `ordinary profile and folder lifecycle settings have separate physical identities`() {
        val standaloneLife = ExitLifecyclePolicy(coldStart = true, idleTimeoutMs = 60_000)
        val folderLife = ExitLifecyclePolicy(coldStart = false, idleTimeoutMs = 120_000)
        val policy = NetworkPolicy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(
                    PolicyNode("standalone", conditions = domain("a.example"), target = PolicyTarget.Profile("de")),
                    PolicyNode(
                        "folder", sortIndex = 1, conditions = domain("b.example"),
                        target = PolicyTarget.Folder("eu", routeScope = null)
                    ),
                )
            ),
            profilePolicies = listOf(ProfileExitPolicy("de", standaloneLife)),
            folderPolicies = listOf(FolderPolicy("eu", lifecycle = folderLife)),
        )
        val schedule = ExpertProbeSchedule(2_000, 6_000, 3_000, 30_000, 3)
        val config = PolicyConfigAssembler.assemble(workspace(policy), EnginePlatform.WINDOWS, probeSchedule = schedule)
        assertTrue(config.errors.toString(), config.isValid)
        val exits = config.exits.filter { it.profileId == "de" }
        assertEquals(2, exits.size)
        assertNotEquals(exits[0].key, exits[1].key)
        assertEquals(standaloneLife, exits.single { it.folderId == null }.lifecycle)
        assertEquals(folderLife, exits.single { it.folderId == "eu" }.lifecycle)
        val folderManifest = (root(config.exitManifestJson).getValue("folders") as JsonArray).single().jsonObject
        assertEquals("3000", folderManifest.getValue("active_probe_timeout_ms").jsonPrimitive.content)
        assertEquals("2000", folderManifest.getValue("probe_min_interval_ms").jsonPrimitive.content)
        assertEquals("3", folderManifest.getValue("failed_checks_before_recovery").jsonPrimitive.content)
    }

    @Test
    fun `saved active health config reaches standalone gates and folders while explicit schedule overrides it`() {
        val health = PolicyHealthSettings(2_000, 5_000, 3_000, 3)
        val policy = NetworkPolicy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(
                    PolicyNode("standalone", conditions = domain("a.example"), target = PolicyTarget.Profile("de")),
                    PolicyNode("folder", sortIndex = 1, conditions = domain("b.example"), target = PolicyTarget.Folder("eu", null))
                )
            ),
            health = health,
        )
        EnginePlatform.entries.forEach { platform ->
            val config = PolicyConfigAssembler.assemble(workspace(policy), platform)
            assertTrue(config.errors.toString(), config.isValid)
            assertTrue(config.exits.any { it.folderId == null })
            val manifest = root(config.exitManifestJson)
            val expected = root(
                """{"probe_min_interval_ms":2000,"probe_max_interval_ms":5000,
                    "active_probe_timeout_ms":3000,"failed_checks_before_recovery":3}
                """.trimIndent()
            )
            assertEquals(expected, manifest.getValue("health"))
            val folder = (manifest.getValue("folders") as JsonArray).single().jsonObject
            expected.forEach { (key, value) -> assertEquals(value, folder.getValue(key)) }
            assertEquals("30000", folder.getValue("probe_timeout_ms").jsonPrimitive.content)
        }
        val override = ExpertProbeSchedule(1_000, 4_000, 2_000, 30_000, 4)
        val config = PolicyConfigAssembler.assemble(workspace(policy), EnginePlatform.WINDOWS, probeSchedule = override)
        assertTrue(config.errors.toString(), config.isValid)
        val manifest = root(config.exitManifestJson)
        val actual = manifest.getValue("health").jsonObject
        assertEquals("1000", actual.getValue("probe_min_interval_ms").jsonPrimitive.content)
        assertEquals("4000", actual.getValue("probe_max_interval_ms").jsonPrimitive.content)
        assertEquals("2000", actual.getValue("active_probe_timeout_ms").jsonPrimitive.content)
        assertEquals("4", actual.getValue("failed_checks_before_recovery").jsonPrimitive.content)
        val folder = (manifest.getValue("folders") as JsonArray).single().jsonObject
        actual.forEach { (key, value) -> assertEquals(value, folder.getValue(key)) }
        assertEquals(health, policy.health)
    }

    @Test
    fun `profile DNS rules keep scoped priority and protected local overrides cannot leak`() {
        val dns = """{"servers":[{"type":"udp","tag":"remote","server":"9.9.9.9","detour":"proxy"},
            {"type":"local","tag":"os"}],"final":"remote",
            "rules":[{"domain":["inside.example"],"server":"os"}]}
        """.trimIndent()
        val bundle = legacy.copy(
            profiles = legacy.profiles.map {
                if (it.id == "de") it.copy(dnsPolicy = "PROFILE", dnsJson = dns) else it
            }
        )
        val policy = NetworkPolicy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(
                    PolicyNode("secret", conditions = domain("*.example"), target = PolicyTarget.Profile("de"), protected = true),
                )
            )
        )
        val config = PolicyConfigAssembler.assemble(workspace(policy).copy(legacy = bundle), EnginePlatform.ANDROID)
        assertTrue(config.errors.toString(), config.isValid)
        val dnsRoot = root(config.policyJson).getValue("dns").jsonObject
        val override = (dnsRoot.getValue("rules") as JsonArray).first().jsonObject
        assertEquals("and", override.getValue("mode").jsonPrimitive.content)
        assertTrue(override.toString().contains("inside.example"))
        val serverTag = override.getValue("server").jsonPrimitive.content
        val server = (dnsRoot.getValue("servers") as JsonArray).map { it.jsonObject }.single {
            it["tag"]?.jsonPrimitive?.content == serverTag
        }
        assertEquals("udp", server.getValue("type").jsonPrimitive.content)
        assertEquals(config.exits.single().tag, server.getValue("detour").jsonPrimitive.content)
    }

    @Test
    fun `dynamic folder does not silently choose different candidate DNS policy`() {
        val dns = """{"servers":[{"type":"udp","tag":"custom","server":"9.9.9.9"}],"final":"custom"}"""
        val bundle = legacy.copy(
            profiles = legacy.profiles.map {
                if (it.id == "de") it.copy(dnsPolicy = "PROFILE", dnsJson = dns) else it
            }
        )
        val policy = NetworkPolicy(device = PolicyTree(PolicyScope.Device, defaultTarget = PolicyTarget.Folder("eu", routeScope = null)))
        val config = PolicyConfigAssembler.assemble(workspace(policy).copy(legacy = bundle), EnginePlatform.WINDOWS)
        assertFalse(config.isValid)
        assertTrue(config.errors.single().startsWith("DNS:"))
        assertTrue(config.policyJson.isEmpty())
    }

    @Test
    fun `protected owner DNS does not rely on the system resolver retaining application identity`() {
        val original = Json.parseToJsonElement("""{"package_name":["org.telegram.messenger"],"domain":["inside.example"]}""").jsonObject
        val projection = PolicyDnsProjection.project(original)
        assertTrue(projection.inexact)
        assertFalse(projection.condition.containsKey("package_name"))
        assertEquals(original["domain"], projection.condition["domain"])
        val onlyOwner = PolicyDnsProjection.project(Json.parseToJsonElement("""{"package_name":["org.telegram.messenger"]}""").jsonObject)
        assertTrue(onlyOwner.condition.isEmpty())
    }

    @Test
    fun `profile selected direct outbound cannot masquerade as a protected tunnel`() {
        val bundle = legacy.copy(
            profiles = legacy.profiles.map { candidate ->
                if (candidate.id == "de") {
                    candidate.copy(
                        outbounds = listOf(
                            TransferOutbound(
                                "de-out", "proxy", "direct",
                                """{"type":"direct","tag":"proxy"}"""
                            )
                        )
                    )
                } else {
                    candidate
                }
            }
        )
        val node = PolicyNode("protected", target = PolicyTarget.Profile("de"), protected = true)
        val policy = NetworkPolicy(device = PolicyTree(PolicyScope.Device, listOf(node)))
        val config = PolicyConfigAssembler.assemble(workspace(policy).copy(legacy = bundle), EnginePlatform.WINDOWS)
        assertFalse(config.isValid)
        assertTrue(config.errors.single().contains("прямой"))
        assertTrue(config.policyJson.isBlank())
    }

    @Test
    fun `custom HTTPS health URL reaches native manifest and credentials in URL are rejected`() {
        val valid = PolicyConfigAssembler.assemble(
            workspace(NetworkPolicy()), EnginePlatform.WINDOWS,
            probeUrl = "https://health.example/status?small=1"
        )
        assertTrue(valid.errors.toString(), valid.isValid)
        assertEquals("https://health.example/status?small=1", root(valid.exitManifestJson).getValue("probe_url").jsonPrimitive.content)
        listOf("http://health.example", "https://user:password@health.example", "https://health.example/#fragment").forEach { url ->
            val invalid = PolicyConfigAssembler.assemble(workspace(NetworkPolicy()), EnginePlatform.WINDOWS, probeUrl = url)
            assertFalse(invalid.isValid)
            assertFalse(invalid.errors.toString().contains("password"))
        }
    }

    @Test
    fun `modern profile DNS query matcher does not inherit legacy ipv4 strategy from normalization`() {
        val dns = """{"servers":[{"type":"udp","tag":"remote","server":"9.9.9.9","detour":"proxy"}],
            "final":"remote","rules":[{"query_type":"AAAA","action":"reject"}]}
        """.trimIndent()
        val bundle = legacy.copy(
            profiles = legacy.profiles.map {
                if (it.id == "de") it.copy(dnsPolicy = "PROFILE", dnsJson = dns) else it
            }
        )
        val policy = NetworkPolicy(device = PolicyTree(PolicyScope.Device, defaultTarget = PolicyTarget.Profile("de")))
        val config = PolicyConfigAssembler.assemble(workspace(policy).copy(legacy = bundle), EnginePlatform.WINDOWS)
        assertTrue(config.errors.toString(), config.isValid)
        val dnsRoot = root(config.policyJson).getValue("dns").jsonObject
        val dnsRules = (dnsRoot.getValue("rules") as JsonArray).map { it.jsonObject }
        assertTrue(dnsRules.any { it["query_type"]?.jsonPrimitive?.content == "AAAA" })
        assertFalse(dnsRules.any { it.containsKey("strategy") })
    }
}
