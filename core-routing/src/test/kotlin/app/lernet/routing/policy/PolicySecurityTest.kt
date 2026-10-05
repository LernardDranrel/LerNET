package app.lernet.routing.policy

import app.lernet.routing.ConditionBlock
import app.lernet.routing.ConditionJson
import app.lernet.routing.ConditionKind
import app.lernet.routing.MatchJoin
import app.lernet.routing.RoutePlatform
import app.lernet.routing.RuleConditions
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicySecurityTest {
    private val inventory = PolicyInventory(setOf("de"), mapOf("eu" to listOf("de")))
    private fun domain(value: String) = RuleConditions(blocks = listOf(ConditionBlock(ConditionKind.DOMAIN, listOf(value))))

    @Test
    fun `nested same channel retains distinct traversal traces while sharing final physical identity`() {
        val scope = PolicyScope.Profile("de")
        val inner = PolicyChannel("inner", "Shared", scope)
        val outer = PolicyChannel("outer", "Outer", PolicyScope.Device, PolicyTarget.Profile("de", scope))
        val policy = NetworkPolicy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(
                    PolicyNode("a", conditions = domain("a.example"), target = PolicyTarget.Channel("outer")),
                    PolicyNode("b", sortIndex = 1, conditions = domain("b.example"), target = PolicyTarget.Channel("outer")),
                    PolicyNode("c", sortIndex = 2, conditions = domain("c.example"), target = PolicyTarget.Channel("another")),
                )
            ),
            trees = listOf(
                PolicyTree(
                    scope,
                    listOf(
                        PolicyNode("nested", conditions = domain("*.example"), target = PolicyTarget.Channel("inner")),
                    ),
                    PolicyTarget.CurrentExit
                )
            ),
            channels = listOf(inner, outer, outer.copy(id = "another")),
        )
        val result = PolicyProgramCompiler.compile(policy, inventory, RoutePlatform.WINDOWS)
        assertTrue(result.errors.toString(), result.isValid)
        val nested = result.rules.filter { "nested" in it.nodeIds }
        assertEquals(listOf("outer", "inner"), nested[0].target.channelPath)
        assertEquals(nested[0].target.channelPath, nested[1].target.channelPath)
        assertEquals(listOf("another", "inner"), nested[2].target.channelPath)
        assertEquals("inner", nested[0].target.channelId)
        assertTrue(nested.all { it.target.physicalChannelPath == listOf("inner") })
    }

    @Test
    fun `protected current exit overrides enclosing profile direct fallback`() {
        val scope = PolicyScope.Profile("de")
        val tree = PolicyTree(
            scope,
            listOf(
                PolicyNode(
                    "protected", conditions = domain("secret.example"),
                    target = PolicyTarget.CurrentExit, protected = true
                )
            ),
            PolicyTarget.CurrentExit
        )
        val policy = NetworkPolicy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(
                    PolicyNode("enter", target = PolicyTarget.Profile("de", scope, UnavailableFallback.DIRECT)),
                )
            ),
            trees = listOf(tree),
        )
        val result = PolicyProgramCompiler.compile(policy, inventory, RoutePlatform.WINDOWS)
        assertTrue(result.errors.toString(), result.isValid)
        val protected = result.rules.first { it.protected }
        assertEquals(UnavailableFallback.BLOCK, (protected.target.target as PolicyTarget.Profile).fallback)
        assertTrue(
            result.rules.any {
                !it.protected && (it.target.target as? PolicyTarget.Profile)?.fallback == UnavailableFallback.DIRECT
            }
        )
    }

    @Test
    fun `protected folder child channel cannot inherit outer direct fallback`() {
        val scope = PolicyScope.Folder("eu")
        val inner = PolicyChannel("inner", "Private", scope)
        val tree = PolicyTree(
            scope,
            listOf(
                PolicyNode(
                    "protected", conditions = domain("secret.example"),
                    target = PolicyTarget.Channel("inner"), protected = true
                )
            ),
            PolicyTarget.CurrentExit
        )
        val policy = NetworkPolicy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(
                    PolicyNode("enter", target = PolicyTarget.Folder("eu", scope, UnavailableFallback.DIRECT)),
                )
            ),
            trees = listOf(tree), channels = listOf(inner),
        )
        val result = PolicyProgramCompiler.compile(policy, inventory, RoutePlatform.WINDOWS)
        assertTrue(result.errors.toString(), result.isValid)
        assertEquals(UnavailableFallback.BLOCK, (result.rules.first { it.protected }.target.target as PolicyTarget.Folder).fallback)
    }

    @Test
    fun `destination host validation rejects URLs authorities injection and malformed literal IPs`() {
        listOf(
            ":::", "host:443", "https:example", "[::1]", "http://host", "a b", "host\nvalue", "fe80::1%wifi", "300.1.2.3", "123",
        ).forEach {
            assertNull(it, PolicyDestinationAddress.normalize(it))
        }
        listOf("::1", "2001:db8::1", "127.0.0.1", "localhost", "example.org", "пример.рф").forEach {
            assertNotNull(it, PolicyDestinationAddress.normalize(it))
        }
        val invalid = PolicyNode("n", redirect = DestinationRedirect("host:443"))
        assertFalse(
            PolicyProgramCompiler.compile(
                NetworkPolicy(device = PolicyTree(PolicyScope.Device, listOf(invalid))), inventory,
                RoutePlatform.WINDOWS
            ).isValid
        )
    }

    @Test
    fun `domain and destination CIDR blocks remain AND in native rule`() {
        val condition = ConditionJson.of(
            RuleConditions(
                join = MatchJoin.AND,
                blocks = listOf(
                    ConditionBlock(ConditionKind.DOMAIN, listOf("one.example")),
                    ConditionBlock(ConditionKind.CIDR, listOf("192.0.2.0/24")),
                )
            )
        )
        assertEquals("logical", condition["type"]?.jsonPrimitive?.content)
        assertEquals("and", condition["mode"]?.jsonPrimitive?.content)
    }

    @Test
    fun `dormant mobile protected tree does not block unrelated Windows device direct policy`() {
        val mobile = RuleConditions(blocks = listOf(ConditionBlock(ConditionKind.APP, listOf("org.telegram.messenger"))))
        val profile = PolicyTree(
            PolicyScope.Profile("de"),
            listOf(
                PolicyNode(
                    "protected-app", conditions = mobile,
                    target = PolicyTarget.CurrentExit, protected = true
                )
            ),
            PolicyTarget.CurrentExit
        )
        val policy = NetworkPolicy(trees = listOf(profile))
        assertTrue(PolicyProgramCompiler.compile(policy, inventory, RoutePlatform.WINDOWS).isValid)
        val referenced = policy.copy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(PolicyNode("enter", target = PolicyTarget.Profile("de", profile.scope)))
            )
        )
        val onWindows = PolicyProgramCompiler.compile(referenced, inventory, RoutePlatform.WINDOWS)
        assertTrue(onWindows.errors.toString(), onWindows.isValid)
        assertTrue("protected-app" in onWindows.inactiveNodeIds)
        assertEquals(RoutePlatform.ANDROID, onWindows.inactiveProtections.single().requiredPlatform)
        assertFalse(onWindows.rules.any { "protected-app" in it.nodeIds })
        val onAndroid = PolicyProgramCompiler.compile(referenced, inventory, RoutePlatform.ANDROID)
        assertTrue(onAndroid.isValid)
        assertTrue(onAndroid.inactiveProtections.isEmpty())
        assertTrue(onAndroid.rules.any { "protected-app" in it.nodeIds && it.protected })
    }

    @Test
    fun `canvas metadata rejects missing owners nonfinite and unbounded points without changing route semantics`() {
        val tree = PolicyTree(
            PolicyScope.Device, listOf(PolicyNode("n", target = PolicyTarget.Profile("de"))),
            positions = mapOf("n" to PolicyCanvasPoint(100f, 200f), "root" to PolicyCanvasPoint(0f, 0f))
        )
        val withPositions = PolicyProgramCompiler.compile(NetworkPolicy(device = tree), inventory, RoutePlatform.WINDOWS)
        val automatic = PolicyProgramCompiler.compile(
            NetworkPolicy(device = tree.copy(positions = emptyMap())), inventory, RoutePlatform.WINDOWS,
        )
        assertEquals(automatic.rules, withPositions.rules)
        listOf(PolicyCanvasPoint(Float.NaN, 1f), PolicyCanvasPoint(0f, Float.POSITIVE_INFINITY), PolicyCanvasPoint(2_000_000f, 0f))
            .forEach { point ->
                assertFalse(
                    PolicyProgramCompiler.compile(
                        NetworkPolicy(device = tree.copy(positions = mapOf("n" to point))),
                        inventory, RoutePlatform.WINDOWS
                    ).isValid
                )
            }
        assertFalse(
            PolicyProgramCompiler.compile(
                NetworkPolicy(device = tree.copy(positions = mapOf("missing" to PolicyCanvasPoint(1f, 2f)))),
                inventory, RoutePlatform.WINDOWS
            ).isValid
        )
    }

    @Test
    fun `manually disabled protection is not labeled as a foreign platform restriction`() {
        val windows = RuleConditions(blocks = listOf(ConditionBlock(ConditionKind.PROCESS, listOf("browser.exe"))))
        val policy = NetworkPolicy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(
                    PolicyNode("disabled", enabled = false, conditions = windows, target = PolicyTarget.Block, protected = true),
                    PolicyNode("child", parentId = "disabled", target = PolicyTarget.Profile("de"), protected = true),
                    PolicyNode("detached", detached = true, target = PolicyTarget.Profile("de"), protected = true),
                )
            )
        )
        val program = PolicyProgramCompiler.compile(policy, inventory, RoutePlatform.WINDOWS)
        assertTrue(program.errors.toString(), program.isValid)
        assertTrue(program.inactiveProtections.isEmpty())
        assertFalse(program.rules.any { it.protected })
    }

    @Test
    fun `canvas node and channel positions remap stable IDs while keeping root coordinates`() {
        val channel = PolicyChannel("c", "Named", PolicyScope.Device, PolicyTarget.Profile("de"))
        val positions = mapOf(
            "n" to PolicyCanvasPoint(100f, 200f), "channel:c" to PolicyCanvasPoint(250f, 400f),
            "root" to PolicyCanvasPoint(0f, -20f)
        )
        val policy = NetworkPolicy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(PolicyNode("n", target = PolicyTarget.Channel("c"))), positions = positions
            ),
            channels = listOf(channel)
        )
        val remapped = PolicyIdRemap(mapOf("de" to "local-de"), emptyMap(), mapOf("n" to "local-n"), mapOf("c" to "local-c")).apply(policy)
        assertEquals(positions["n"], remapped.device.positions["local-n"])
        assertEquals(positions["channel:c"], remapped.device.positions["channel:local-c"])
        assertEquals(positions["root"], remapped.device.positions["root"])
        assertFalse("n" in remapped.device.positions)
    }

    @Test
    fun `arbitrary portable node IDs cannot collide with canvas root or channel keys`() {
        val ids = listOf("root", "channel:c", "node:root", "ordinary")
        val positions = ids.associate { PolicyCanvasKeys.node(it) to PolicyCanvasPoint(100f, 200f) } +
            (PolicyCanvasKeys.ROOT to PolicyCanvasPoint(0f, 0f))
        val policy = NetworkPolicy(
            device = PolicyTree(
                PolicyScope.Device, ids.map { PolicyNode(it, target = PolicyTarget.Block) },
                positions = positions
            )
        )
        assertEquals(5, policy.device.positions.size)
        assertTrue(PolicyProgramCompiler.compile(policy, inventory, RoutePlatform.WINDOWS).isValid)
        val mapping = ids.associateWith { "local-$it" }
        val remapped = PolicyIdRemap(emptyMap(), emptyMap(), mapping, emptyMap()).apply(policy)
        assertEquals(5, remapped.device.positions.size)
        ids.forEach { id ->
            assertEquals(positions[PolicyCanvasKeys.node(id)], remapped.device.positions[PolicyCanvasKeys.node(mapping.getValue(id))])
        }
    }
}
