package app.lernet.routing.policy

import app.lernet.routing.ConditionBlock
import app.lernet.routing.ConditionKind
import app.lernet.routing.RoutePlatform
import app.lernet.routing.RuleConditions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPolicyTest {
    private val inventory = PolicyInventory(setOf("de", "nl"), mapOf("europe" to listOf("de", "nl")))
    private val profileTree = PolicyTree(PolicyScope.Profile("de"), defaultTarget = PolicyTarget.CurrentExit)
    private fun domain(value: String) = RuleConditions(blocks = listOf(ConditionBlock(ConditionKind.DOMAIN, listOf(value))))
    private fun compile(policy: NetworkPolicy, platform: RoutePlatform = RoutePlatform.WINDOWS) =
        PolicyProgramCompiler.compile(policy, inventory, platform)

    @Test
    fun `disabling last child restores parent terminal action without promoting descendants`() {
        val nodes = listOf(
            PolicyNode("parent", target = PolicyTarget.Profile("de")),
            PolicyNode("off", "parent", enabled = false, target = PolicyTarget.Block),
            PolicyNode("grandchild", "off", target = PolicyTarget.Block),
        )
        val result = compile(NetworkPolicy(device = PolicyTree(PolicyScope.Device, nodes)))
        assertTrue(result.errors.toString(), result.isValid)
        assertEquals(listOf(PolicyTarget.Profile("de"), PolicyTarget.Direct), result.rules.map { it.target.target })
    }

    @Test
    fun `physical destination cannot be silently ignored by local child rules`() {
        val nodes = listOf(
            PolicyNode("parent", target = PolicyTarget.Profile("de")),
            PolicyNode("child", "parent", target = PolicyTarget.Direct),
        )
        val result = compile(NetworkPolicy(device = PolicyTree(PolicyScope.Device, nodes)))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.nodeId == "parent" && it.field == "target" })
    }

    @Test fun `empty expert policy is direct by default on both platforms`() {
        RoutePlatform.entries.forEach { platform ->
            val result = compile(NetworkPolicy(), platform)
            assertTrue(result.errors.toString(), result.isValid)
            assertEquals(PolicyTarget.Direct, result.rules.single().target.target)
        }
    }

    @Test fun `active health settings accept bounds and reject invalid persisted values`() {
        val valid = listOf(
            PolicyHealthSettings(), PolicyHealthSettings(1_000, 1_000, 1_000, 1),
            PolicyHealthSettings(60_000, 60_000, 15_000, 10)
        )
        valid.forEach { health ->
            assertTrue(compile(NetworkPolicy(health = health)).isValid)
        }
        val invalid = listOf(
            PolicyHealthSettings(minimumIntervalMs = 999),
            PolicyHealthSettings(minimumIntervalMs = 60_001, maximumIntervalMs = 60_001),
            PolicyHealthSettings(maximumIntervalMs = 60_001),
            PolicyHealthSettings(minimumIntervalMs = 7_000, maximumIntervalMs = 3_000),
            PolicyHealthSettings(activeTimeoutMs = 999), PolicyHealthSettings(activeTimeoutMs = 15_001),
            PolicyHealthSettings(failedChecksBeforeRecovery = 0), PolicyHealthSettings(failedChecksBeforeRecovery = 11),
            PolicyHealthSettings(minimumIntervalMs = Long.MIN_VALUE),
            PolicyHealthSettings(maximumIntervalMs = Long.MAX_VALUE),
        )
        invalid.forEach { health ->
            val result = compile(NetworkPolicy(health = health))
            assertFalse(health.toString(), result.isValid)
            assertTrue(result.errors.any { it.field == "health" })
        }
    }

    @Test fun `same channel id shares one exit and names do not merge distinct channels`() {
        val first = PolicyChannel("first", "Telegram", PolicyScope.Device, PolicyTarget.Profile("de"))
        val second = first.copy(id = "second")
        val nodes = listOf(
            PolicyNode("a", conditions = domain("one.example"), target = PolicyTarget.Channel("first")),
            PolicyNode("b", sortIndex = 1, conditions = domain("two.example"), target = PolicyTarget.Channel("first")),
            PolicyNode("c", sortIndex = 2, conditions = domain("three.example"), target = PolicyTarget.Channel("second")),
        )
        val result = compile(NetworkPolicy(device = PolicyTree(PolicyScope.Device, nodes), channels = listOf(first, second)))
        assertTrue(result.errors.toString(), result.isValid)
        assertEquals(listOf("first", "first", "second", null), result.rules.map { it.target.channelId })
    }

    @Test fun `cross tree cycle and cross owner channel cannot execute`() {
        val loop = profileTree.copy(defaultTarget = PolicyTarget.Profile("de", profileTree.scope))
        assertFalse(compile(NetworkPolicy(trees = listOf(loop))).isValid)
        val channel = PolicyChannel("x", "x", profileTree.scope)
        val tree = PolicyTree(PolicyScope.Device, listOf(PolicyNode("n", target = PolicyTarget.Channel("x"))))
        assertFalse(compile(NetworkPolicy(device = tree, trees = listOf(profileTree), channels = listOf(channel))).isValid)
    }

    @Test fun `missing reference never silently falls back to direct`() {
        listOf(
            PolicyTarget.Profile("missing"), PolicyTarget.Folder("missing", null), PolicyTarget.Channel("missing"),
            PolicyTarget.CurrentExit
        ).forEach { target ->
            assertFalse(compile(NetworkPolicy(device = PolicyTree(PolicyScope.Device, defaultTarget = target))).isValid)
        }
    }

    @Test fun `protection checks referenced tree defaults and fallback`() {
        val protected = PolicyNode(
            "guard", conditions = domain("bank.example"), protected = true,
            target = PolicyTarget.Profile("de", profileTree.scope)
        )
        val directTree = profileTree.copy(defaultTarget = PolicyTarget.Direct)
        assertFalse(compile(NetworkPolicy(device = PolicyTree(PolicyScope.Device, listOf(protected)), trees = listOf(directTree))).isValid)
        val badFallback = protected.copy(target = PolicyTarget.Profile("de", fallback = UnavailableFallback.DIRECT))
        assertFalse(compile(NetworkPolicy(device = PolicyTree(PolicyScope.Device, listOf(badFallback)))).isValid)
    }

    @Test fun `protected fork blocks unmatched remainder before device direct default`() {
        val nodes = listOf(
            PolicyNode("parent", conditions = domain("*.example"), protected = true),
            PolicyNode("child", "parent", conditions = domain("bank.example"), target = PolicyTarget.Profile("de")),
        )
        val result = compile(NetworkPolicy(device = PolicyTree(PolicyScope.Device, nodes)))
        assertTrue(result.errors.toString(), result.isValid)
        assertEquals(listOf(PolicyTarget.Profile("de"), PolicyTarget.Block, PolicyTarget.Direct), result.rules.map { it.target.target })
    }

    @Test fun `child tree folds parent conditions and retains folder selection context`() {
        val folderScope = PolicyScope.Folder("europe")
        val parent = PolicyNode("parent", conditions = domain("*.example"), target = PolicyTarget.Folder("europe"))
        val child = PolicyNode("child", conditions = domain("bank.example"), target = PolicyTarget.CurrentExit)
        val policy = NetworkPolicy(
            device = PolicyTree(PolicyScope.Device, listOf(parent)),
            trees = listOf(PolicyTree(folderScope, listOf(child), PolicyTarget.CurrentExit))
        )
        val result = compile(policy)
        assertTrue(result.errors.toString(), result.isValid)
        assertEquals(listOf("parent", "child"), result.rules.first().nodeIds)
        assertEquals(PolicyTarget.Folder("europe", null), result.rules.first().target.target)
        assertTrue(result.rules.first().condition.toString().contains("bank.example"))
        assertTrue(result.rules.first().condition.toString().contains("example"))
    }

    @Test fun `unsupported branch and descendants are retained but not emitted`() {
        val android = RuleConditions(blocks = listOf(ConditionBlock(ConditionKind.APP, listOf("org.telegram.messenger"))))
        val nodes = listOf(
            PolicyNode("android", conditions = android),
            PolicyNode("child", "android", target = PolicyTarget.Profile("de"))
        )
        val policy = NetworkPolicy(device = PolicyTree(PolicyScope.Device, nodes))
        val windows = compile(policy)
        assertTrue(windows.isValid)
        assertEquals(setOf("android", "child"), windows.inactiveNodeIds)
        assertEquals(1, windows.rules.size)
        assertEquals(2, policy.device.nodes.size)
        assertEquals(2, compile(policy, RoutePlatform.ANDROID).rules.size)
        val protectedNodes = listOf(nodes.first().copy(protected = true), nodes.last())
        val foreignProtection = compile(policy.copy(device = policy.device.copy(nodes = protectedNodes)))
        assertTrue(foreignProtection.isValid)
        assertEquals("android", foreignProtection.inactiveProtections.single().nodeId)
    }

    @Test fun `tree parent cycle duplicates invalid rewrite and invalid conditions are rejected`() {
        val cases = listOf(
            listOf(PolicyNode("a", "b"), PolicyNode("b", "a")),
            listOf(PolicyNode("a"), PolicyNode("a")),
            listOf(PolicyNode("a", "missing")),
            listOf(PolicyNode("a", redirect = DestinationRedirect(port = 70000))),
            listOf(PolicyNode("a", conditions = domain("bad domain"))),
        )
        cases.forEach { nodes -> assertFalse(compile(NetworkPolicy(device = PolicyTree(PolicyScope.Device, nodes))).isValid) }
    }

    @Test fun `disabled parent and detached node cannot promote children to device root`() {
        val nodes = listOf(
            PolicyNode("off", enabled = false), PolicyNode("child", "off", target = PolicyTarget.Block),
            PolicyNode("detached", detached = true, target = PolicyTarget.Block)
        )
        val targets = compile(NetworkPolicy(device = PolicyTree(PolicyScope.Device, nodes))).rules.map { it.target.target }
        assertEquals(listOf(PolicyTarget.Direct), targets)
    }

    @Test fun `inherited redirect keeps address when child overrides only port`() {
        val nodes = listOf(
            PolicyNode("parent", redirect = DestinationRedirect("localhost")),
            PolicyNode("child", "parent", target = PolicyTarget.Direct, redirect = DestinationRedirect(port = 8443))
        )
        val redirect = compile(NetworkPolicy(device = PolicyTree(PolicyScope.Device, nodes))).rules.first().redirect
        assertEquals(DestinationRedirect("localhost", 8443), redirect)
    }

    @Test fun `tree cannot borrow unrelated profile or folder scope`() {
        val node = PolicyNode("n", target = PolicyTarget.Profile("nl", PolicyScope.Profile("de")))
        assertFalse(compile(NetworkPolicy(device = PolicyTree(PolicyScope.Device, listOf(node)), trees = listOf(profileTree))).isValid)
    }

    @Test fun `import remaps shared channel tree owner and profile references without merging names`() {
        val channel = PolicyChannel("shared", "Telegram", PolicyScope.Device, PolicyTarget.Profile("de", profileTree.scope))
        val tree = PolicyTree(
            PolicyScope.Device,
            listOf(
                PolicyNode("a", conditions = domain("*.example")),
                PolicyNode("b", "a", conditions = domain("one.example"), target = PolicyTarget.Channel("shared")),
                PolicyNode("c", conditions = domain("two.example"), target = PolicyTarget.Channel("shared"))
            )
        )
        val policy = NetworkPolicy(device = tree, trees = listOf(profileTree), channels = listOf(channel))
        val map =
            PolicyIdRemap(
                mapOf("de" to "new-de"), emptyMap(),
                mapOf("a" to "new-a", "b" to "new-b", "c" to "new-c"), mapOf("shared" to "new-channel"),
            )
        val result = map.apply(policy)
        assertEquals("new-a", result.device.nodes[1].parentId)
        assertEquals(PolicyTarget.Channel("new-channel"), result.device.nodes[1].target)
        assertEquals("Telegram", result.channels.single().name)
        assertEquals(PolicyTarget.Profile("new-de", PolicyScope.Profile("new-de")), result.channels.single().target)
        val localInventory = PolicyInventory(setOf("new-de"), emptyMap())
        assertTrue(PolicyProgramCompiler.compile(result, localInventory, RoutePlatform.WINDOWS).isValid)
        assertThrows(IllegalArgumentException::class.java) { map.copy(profiles = emptyMap()).apply(policy) }
        assertThrows(IllegalArgumentException::class.java) { map.copy(nodes = mapOf("a" to "same", "b" to "same")).apply(policy) }
    }
}
