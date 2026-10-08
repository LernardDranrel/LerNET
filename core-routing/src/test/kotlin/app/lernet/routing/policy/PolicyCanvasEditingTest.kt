package app.lernet.routing.policy

import app.lernet.routing.ConditionBlock
import app.lernet.routing.ConditionKind
import app.lernet.routing.RuleConditions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyCanvasEditingTest {
    @Test fun `drag patches current rules not the old graph snapshot`() {
        val initial = NetworkPolicy()
        val conditions = RuleConditions(blocks = listOf(ConditionBlock(ConditionKind.GEOIP, listOf("ru"))))
        val node = PolicyNode("new", conditions = conditions)
        val current = initial.copy(device = PolicyBranchEditing.putNode(initial.device, node))
        val edited = PolicyCanvasEditing.update(current, PolicyScope.Device, mapOf("new" to PolicyCanvasPoint(42f, 83f)))
        assertEquals(current.device.nodes, edited.device.nodes)
        assertEquals(current.device.defaultTarget, edited.device.defaultTarget)
        assertEquals(PolicyCanvasPoint(42f, 83f), edited.device.positions["new"])
        assertEquals(current.copy(device = current.device.copy(positions = edited.device.positions)), edited)
    }

    @Test fun `late deleted node drag never resurrects node or orphan position`() {
        val current = NetworkPolicy(device = PolicyTree(PolicyScope.Device, positions = mapOf("removed" to PolicyCanvasPoint(1f, 2f))))
        val edited = PolicyCanvasEditing.update(current, PolicyScope.Device, mapOf("removed" to PolicyCanvasPoint(5f, 6f)))
        assertTrue(edited.device.nodes.isEmpty())
        assertTrue(edited.device.positions.isEmpty())
    }

    @Test fun `layout reset retains latest nodes exits and other scopes`() {
        val node = PolicyNode("new", target = PolicyTarget.Block)
        val tree = PolicyTree(PolicyScope.Profile("vpn"), defaultTarget = PolicyTarget.CurrentExit)
        val device = PolicyTree(PolicyScope.Device, listOf(node), positions = mapOf("new" to PolicyCanvasPoint(1f, 2f)))
        val current = NetworkPolicy(device = device, trees = listOf(tree))
        val edited = PolicyCanvasEditing.update(current, PolicyScope.Device, emptyMap(), clear = true)
        assertEquals(current.device.nodes, edited.device.nodes)
        assertEquals(current.trees, edited.trees)
        assertTrue(edited.device.positions.isEmpty())
        assertEquals(current, PolicyCanvasEditing.update(current, PolicyScope.Profile("deleted"), emptyMap(), clear = true))
    }

    @Test fun `synthetic otherwise becomes stored only when its valid position is moved`() {
        val current = NetworkPolicy(device = PolicyTree(PolicyScope.Device, defaultTarget = PolicyTarget.Block))
        val fallback = PolicyBranchEditing.displayTree(current.device).nodes.single()
        val point = PolicyCanvasPoint(1f, 2f)
        val edited = PolicyCanvasEditing.update(current, PolicyScope.Device, mapOf(fallback.id to point))
        assertEquals(fallback, edited.device.nodes.single())
        assertEquals(PolicyTarget.Block, edited.device.defaultTarget)
        assertEquals(point, edited.device.positions[fallback.id])
        val invalid = mapOf(fallback.id to PolicyCanvasPoint(Float.NaN, 2f))
        assertEquals(current, PolicyCanvasEditing.update(current, PolicyScope.Device, invalid))
    }
}
