package app.lernet.routing.policy

import app.lernet.routing.ConditionBlock
import app.lernet.routing.ConditionKind
import app.lernet.routing.RoutePlatform
import app.lernet.routing.RuleConditions
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyOtherwiseTest {
    private val inventory = PolicyInventory(setOf("vpn"), emptyMap())
    private fun domain(value: String) = RuleConditions(blocks = listOf(ConditionBlock(ConditionKind.DOMAIN, listOf(value))))
    private fun preview(tree: PolicyTree, destination: String, platform: RoutePlatform = RoutePlatform.WINDOWS): PolicyRoutePreview {
        val program = PolicyProgramCompiler.compile(NetworkPolicy(device = tree), inventory, platform)
        assertTrue(program.errors.toString(), program.isValid)
        return PolicyFlowMatcher.preview(program, PolicySimulationInput(domain = destination))
    }

    @Test fun `viewing default adds a visible otherwise without changing stored tree or route`() {
        val tree = PolicyTree(PolicyScope.Device, defaultTarget = PolicyTarget.Block)
        val display = PolicyBranchEditing.displayTree(tree)
        assertTrue(tree.nodes.isEmpty())
        assertEquals(display, PolicyBranchEditing.displayTree(tree))
        assertEquals(display, PolicyBranchEditing.displayTree(display))
        assertTrue(display.nodes.single().otherwise)
        assertEquals(PolicyTarget.Block, preview(display, "unknown.example").target?.target)
        assertEquals(preview(tree, "unknown.example").target?.target, preview(display, "unknown.example").target?.target)
    }

    @Test fun `scope defaults have different stable identities and legacy catch all is reused`() {
        val device = PolicyBranchEditing.displayTree(PolicyTree(PolicyScope.Device))
        val profile = PolicyBranchEditing.displayTree(PolicyTree(PolicyScope.Profile("vpn"), defaultTarget = PolicyTarget.CurrentExit))
        assertFalse(device.nodes.single().id == profile.nodes.single().id)
        val old = PolicyNode("legacy", sortIndex = Int.MAX_VALUE, target = PolicyTarget.Block)
        val tree = PolicyTree(PolicyScope.Device, listOf(old))
        val display = PolicyBranchEditing.displayTree(tree)
        assertEquals(1, display.nodes.size)
        assertEquals("legacy", display.nodes.single().id)
        assertEquals(PolicyTarget.Block, preview(display, "unknown.example").target?.target)
        assertFalse(tree.nodes.single().otherwise)
    }

    @Test fun `regular matches win and all remaining traffic reaches editable otherwise on both platforms`() {
        var tree = PolicyTree(PolicyScope.Device, defaultTarget = PolicyTarget.Block)
        tree = PolicyBranchEditing.putNode(tree, PolicyNode("local", conditions = domain("local.example")))
        val otherwise = tree.nodes.single { it.otherwise }
        tree = PolicyBranchEditing.putNode(tree, otherwise.copy(target = PolicyTarget.Profile("vpn")))
        RoutePlatform.entries.forEach { platform ->
            assertEquals(PolicyTarget.Direct, preview(tree, "local.example", platform).target?.target)
            val result = preview(tree, "else.example", platform)
            assertEquals(PolicyTarget.Profile("vpn"), result.target?.target)
            assertEquals(listOf(otherwise.id), result.nodeIds)
        }
    }

    @Test fun `otherwise can branch again preserving the old physical exit for its remainder`() {
        var tree = PolicyTree(PolicyScope.Device, defaultTarget = PolicyTarget.Profile("vpn"))
        tree = PolicyBranchEditing.displayTree(tree)
        val outer = tree.nodes.single()
        tree = PolicyBranchEditing.putNode(
            tree,
            PolicyNode("inside", parentId = outer.id, conditions = domain("blocked.example"), target = PolicyTarget.Block),
        )
        val inner = tree.nodes.single { it.otherwise && it.parentId == outer.id }
        assertEquals(PolicyTarget.Profile("vpn"), inner.target)
        assertEquals(PolicyTarget.Direct, tree.nodes.first { it.id == outer.id }.target)
        RoutePlatform.entries.forEach { platform ->
            assertEquals(PolicyTarget.Block, preview(tree, "blocked.example", platform).target?.target)
            val result = preview(tree, "other.example", platform)
            assertEquals(PolicyTarget.Profile("vpn"), result.target?.target)
            assertEquals(listOf(outer.id, inner.id), result.nodeIds)
        }
    }

    @Test fun `nested otherwise remains constrained by parent and never bypasses its condition`() {
        var tree = PolicyBranchEditing.putNode(
            PolicyTree(PolicyScope.Device),
            PolicyNode("parent", conditions = domain("*.example"), target = PolicyTarget.Block),
        )
        tree = PolicyBranchEditing.putNode(tree, PolicyNode("child", "parent", conditions = domain("allowed.example")))
        assertEquals(PolicyTarget.Direct, preview(tree, "allowed.example").target?.target)
        assertEquals(PolicyTarget.Block, preview(tree, "blocked.example").target?.target)
        assertEquals(PolicyTarget.Direct, preview(tree, "outside.test").target?.target)
        val remainder = tree.nodes.single { it.otherwise && it.parentId == "parent" }
        tree = PolicyBranchEditing.putNode(
            tree, PolicyNode("nested", remainder.id, conditions = domain("vpn.example"), target = PolicyTarget.Profile("vpn")),
        )
        assertEquals(PolicyTarget.Profile("vpn"), preview(tree, "vpn.example").target?.target)
        assertEquals(PolicyTarget.Block, preview(tree, "blocked.example").target?.target)
        assertEquals(PolicyTarget.Direct, preview(tree, "outside.test").target?.target)
    }

    @Test fun `otherwise cannot move ahead of rules or be deleted and regular insertion stays before it`() {
        var tree = PolicyBranchEditing.putNode(PolicyTree(PolicyScope.Device), PolicyNode("one", conditions = domain("one.example")))
        val otherwise = tree.nodes.single { it.otherwise }
        tree = PolicyBranchEditing.putNode(tree, PolicyNode("two", sortIndex = Int.MAX_VALUE, conditions = domain("two.example")))
        assertEquals(listOf("one", "two", otherwise.id), tree.nodes.sortedBy { it.sortIndex }.map { it.id })
        assertEquals(tree, PolicyBranchEditing.moveNode(tree, otherwise.id, -1))
        assertEquals(tree, PolicyBranchEditing.moveNode(tree, "two", 1))
        assertEquals(tree, PolicyBranchEditing.removeNode(tree, otherwise.id))
        val changed = PolicyBranchEditing.putNode(
            tree, otherwise.copy(enabled = false, conditions = domain("bad.example"), parentId = "one"),
        )
        val saved = changed.nodes.single { it.id == otherwise.id }
        assertTrue(saved.enabled)
        assertEquals(null, saved.parentId)
        assertEquals(RuleConditions(), saved.conditions)
    }

    @Test fun `invalid explicit otherwise is rejected before compilation`() {
        val otherwise = PolicyNode("else", otherwise = true)
        listOf(
            listOf(otherwise, PolicyNode("late", sortIndex = 1)),
            listOf(otherwise, otherwise.copy(id = "another", sortIndex = 1)),
            listOf(otherwise.copy(enabled = false)),
            listOf(otherwise.copy(conditions = domain("bad.example"))),
        ).forEach { nodes ->
            val result = PolicyProgramCompiler.compile(
                NetworkPolicy(device = PolicyTree(PolicyScope.Device, nodes)), inventory, RoutePlatform.WINDOWS,
            )
            assertFalse(result.isValid)
            assertTrue(result.errors.any { it.field == "otherwise" })
        }
    }

    @Test fun `adding a sibling inside protected legacy branch preserves blocking remainder`() {
        val tree = PolicyTree(
            PolicyScope.Device,
            listOf(
                PolicyNode("guard", conditions = domain("*.example"), protected = true),
                PolicyNode("vpn", "guard", conditions = domain("secret.example"), target = PolicyTarget.Profile("vpn")),
            )
        )
        val changed = PolicyBranchEditing.putNode(
            tree, PolicyNode("new", "guard", conditions = domain("new.example"), target = PolicyTarget.Profile("vpn")),
        )
        assertEquals(PolicyTarget.Block, changed.nodes.single { it.otherwise && it.parentId == "guard" }.target)
        assertEquals(PolicyTarget.Block, preview(changed, "other.example").target?.target)
        assertEquals(PolicyTarget.Direct, preview(changed, "outside.test").target?.target)
    }

    @Test fun `portable marker round trips and old policy lacking it still loads`() {
        val tree = PolicyBranchEditing.putNode(PolicyTree(PolicyScope.Device), PolicyNode("rule", conditions = domain("one.example")))
        val policy = NetworkPolicy(device = tree)
        val encoded = Json.encodeToString(policy)
        assertEquals(policy, Json.decodeFromString<NetworkPolicy>(encoded))
        val old = Json.decodeFromString<NetworkPolicy>("""{"device":{"scope":{"type":"device"},"nodes":[{"id":"old"}]}}""")
        assertFalse(old.device.nodes.single().otherwise)
        // Marker is presentation/validation only. Ordered paths remain executable by the same compiler.
        val unmarked = tree.copy(nodes = tree.nodes.map { it.copy(otherwise = false) })
        listOf("one.example", "unknown.example").forEach { destination ->
            assertEquals(preview(tree, destination).target?.target, preview(unmarked, destination).target?.target)
        }
    }

    @Test fun `editing a legacy fork retains continuation to later outer siblings`() {
        val tree = PolicyTree(
            PolicyScope.Device,
            listOf(
                PolicyNode("parent", conditions = domain("*.example")),
                PolicyNode("child", "parent", conditions = domain("foo.example")),
                PolicyNode("later", sortIndex = 1, conditions = domain("bar.example"), target = PolicyTarget.Profile("vpn")),
            ),
            defaultTarget = PolicyTarget.Block
        )
        val edited = PolicyBranchEditing.putNode(tree, tree.nodes.first { it.id == "child" }.copy(title = "Edited"))
        val added = PolicyBranchEditing.putNode(edited, PolicyNode("new", "parent", conditions = domain("new.example")))
        listOf(tree, edited, added).forEach {
            assertEquals(PolicyTarget.Profile("vpn"), preview(it, "bar.example").target?.target)
            assertEquals(PolicyTarget.Block, preview(it, "other.example").target?.target)
        }
        assertFalse(added.nodes.any { it.parentId == "parent" && it.otherwise })
    }

    @Test fun `first live child under physical otherwise preserves disabled children and former exit`() {
        val parent = PolicyNode("else", otherwise = true, target = PolicyTarget.Profile("vpn"))
        val disabled = PolicyNode("off", parentId = parent.id, enabled = false)
        val tree = PolicyTree(PolicyScope.Device, listOf(parent, disabled), PolicyTarget.Profile("vpn"))
        val edited = PolicyBranchEditing.putNode(tree, PolicyNode("child", parent.id, conditions = domain("one.example")))
        assertFalse(edited.nodes.first { it.id == disabled.id }.enabled)
        assertEquals(PolicyTarget.Direct, preview(edited, "one.example").target?.target)
        assertEquals(PolicyTarget.Profile("vpn"), preview(edited, "other.example").target?.target)
    }

    @Test fun `editing equal priorities preserves the original sibling order`() {
        val tree = PolicyTree(
            PolicyScope.Device,
            listOf(
                PolicyNode("a", conditions = domain("a.example")),
                PolicyNode("b", conditions = domain("b.example")),
            )
        )
        val edited = PolicyBranchEditing.putNode(tree, tree.nodes.last().copy(title = "Edited"))
        assertEquals(listOf("a", "b"), edited.nodes.filterNot { it.otherwise }.sortedBy { it.sortIndex }.map { it.id })
    }
}
