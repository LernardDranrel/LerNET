package app.lernet.routing.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyEditorProtectionTest {
    @Test fun `nested editor inherits prohibition through unmarked parent`() {
        val tree = PolicyTree(PolicyScope.Device, nodes = listOf(
            PolicyNode("secure", protected = true),
            PolicyNode("child", parentId = "secure"),
        ))
        assertTrue(PolicyBranchEditing.inheritedProtection(tree, "child"))
        assertTrue(PolicyBranchEditing.inheritedProtection(tree, "secure"))
    }

    @Test fun `unrelated protected branch does not lock this editor`() {
        val tree = PolicyTree(PolicyScope.Device, nodes = listOf(
            PolicyNode("secure", protected = true), PolicyNode("other"),
        ))
        assertFalse(PolicyBranchEditing.inheritedProtection(tree, "other"))
        assertFalse(PolicyBranchEditing.inheritedProtection(tree, null))
        assertFalse(PolicyBranchEditing.inheritedProtection(tree, "deleted"))
    }

    @Test fun `malformed draft ancestry terminates without losing reachable prohibition`() {
        val nodes = listOf(PolicyNode("a", parentId = "b"), PolicyNode("b", parentId = "a"))
        assertFalse(PolicyBranchEditing.inheritedProtection(PolicyTree(PolicyScope.Device, nodes = nodes), "a"))
        assertTrue(PolicyBranchEditing.inheritedProtection(
            PolicyTree(PolicyScope.Device, nodes = nodes.map { if (it.id == "b") it.copy(protected = true) else it }), "a"))
    }
}
