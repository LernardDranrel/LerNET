package app.lernet.routing.policy

import org.junit.Test
import org.junit.Assert.*

class PolicyDraftMergeTest {
    @Test fun rapidFolderSelectionAndSwapDoNotOverwriteEachOther() {
        val base = NetworkPolicy()
        val selection = base.copy(folderPolicies = listOf(FolderPolicy("f", selection = FolderSelection.LOWEST_LATENCY)))
        val swap = base.copy(folderPolicies = listOf(FolderPolicy("f", autoSwap = true)))
        val merged = PolicyDraftMerge.merge(base, swap, PolicyDraftMerge.merge(base, selection, base))
        assertEquals(FolderSelection.LOWEST_LATENCY, merged.folderPolicies.single().selection)
        assertTrue(merged.folderPolicies.single().autoSwap)
    }

    @Test fun ruleEditPreservesConcurrentLayoutAndUnrelatedRule() {
        val a = PolicyNode("a", title = "old")
        val base = NetworkPolicy(device = PolicyTree(PolicyScope.Device, nodes = listOf(a)))
        val edited = base.copy(device = base.device.copy(nodes = listOf(a.copy(title = "new"))))
        val current = base.copy(device = base.device.copy(nodes = listOf(a, PolicyNode("b")), positions = mapOf("a" to PolicyCanvasPoint(400f, 100f))))
        val result = PolicyDraftMerge.merge(base, edited, current)
        assertEquals("new", result.device.nodes.first().title)
        assertEquals("b", result.device.nodes.last().id)
        assertEquals(current.device.positions, result.device.positions)
    }

    @Test(expected = IllegalArgumentException::class) fun sameRuleConflictRejectsOldEditor() {
        val a = PolicyNode("a", title = "old")
        val base = NetworkPolicy(device = PolicyTree(PolicyScope.Device, nodes = listOf(a)))
        PolicyDraftMerge.merge(base, base.copy(device = base.device.copy(nodes = listOf(a.copy(title = "mine")))),
            base.copy(device = base.device.copy(nodes = listOf(a.copy(title = "newer")))))
    }

    @Test(expected = IllegalArgumentException::class) fun deletedRuleIsNotResurrectedByStaleEditor() {
        val a = PolicyNode("a")
        val base = NetworkPolicy(device = PolicyTree(PolicyScope.Device, nodes = listOf(a)))
        PolicyDraftMerge.merge(base, base.copy(device = base.device.copy(nodes = listOf(a.copy(title = "changed")))),
            base.copy(device = base.device.copy(nodes = emptyList())))
    }

    @Test(expected = IllegalArgumentException::class) fun parentRemovalRejectsConcurrentNewChild() {
        val parent = PolicyNode("parent")
        val base = NetworkPolicy(device = PolicyTree(PolicyScope.Device, nodes = listOf(parent)))
        val edited = base.copy(device = base.device.copy(nodes = emptyList()))
        val current = base.copy(device = base.device.copy(nodes = listOf(parent, PolicyNode("child", parentId = "parent"))))
        PolicyDraftMerge.merge(base, edited, current)
    }

    @Test fun removedRuleCannotLeaveConcurrentCanvasPositionAndOtherPositionsSurvive() {
        val a = PolicyNode("a")
        val b = PolicyNode("b")
        val base = NetworkPolicy(device = PolicyTree(PolicyScope.Device, nodes = listOf(a, b)))
        val edited = base.copy(device = base.device.copy(nodes = listOf(b)))
        val current = base.copy(device = base.device.copy(positions = mapOf(
            "a" to PolicyCanvasPoint(20f, 50f), "b" to PolicyCanvasPoint(200f, 60f),
        )))
        val result = PolicyDraftMerge.merge(base, edited, current)
        assertEquals(listOf(b), result.device.nodes)
        assertEquals(mapOf("b" to PolicyCanvasPoint(200f, 60f)), result.device.positions)
    }

    @Test fun persistenceRetryWithTheSameValueIsIdempotent() {
        val base = NetworkPolicy()
        val next = base.copy(dns = PolicyDnsSettings(PolicyDnsMode.CUSTOM, "9.9.9.9"))
        assertEquals(next, PolicyDraftMerge.merge(base, next, next))
    }
}
