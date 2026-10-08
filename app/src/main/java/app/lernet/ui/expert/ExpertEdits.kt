package app.lernet.ui.expert

import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyBranchEditing
import app.lernet.routing.policy.PolicyCanvasEditing
import app.lernet.routing.policy.PolicyCanvasKeys
import app.lernet.routing.policy.PolicyCanvasPoint
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyOtherwise
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.PolicyTree

/** Canvas coordinates are presentation only; these operations preserve semantic sibling order. */
internal object ExpertEdits {
    fun tree(policy: NetworkPolicy, scope: PolicyScope): PolicyTree =
        if (scope == PolicyScope.Device) {
            policy.device
        } else {
            policy.trees.firstOrNull { it.scope == scope } ?: PolicyTree(scope, defaultTarget = PolicyTarget.CurrentExit)
        }

    fun replaceTree(policy: NetworkPolicy, original: PolicyTree): NetworkPolicy {
        val tree = PolicyBranchEditing.synchronizeDefault(original)
        return if (tree.scope == PolicyScope.Device) {
            policy.copy(device = tree)
        } else {
            policy.copy(trees = policy.trees.filterNot { it.scope == tree.scope } + tree)
        }
    }

    fun putNode(policy: NetworkPolicy, scope: PolicyScope, node: PolicyNode): NetworkPolicy =
        ensureTargetTree(replaceTree(policy, PolicyBranchEditing.putNode(tree(policy, scope), node)), node.target)

    private fun ensureTargetTree(policy: NetworkPolicy, target: PolicyTarget): NetworkPolicy {
        val scope = when (target) {
            is PolicyTarget.Profile -> target.routeScope
            is PolicyTarget.Folder -> target.routeScope
            PolicyTarget.Direct, PolicyTarget.Block, PolicyTarget.CurrentExit, is PolicyTarget.Channel -> null
        } ?: return policy
        return if (scope == PolicyScope.Device || policy.trees.any { it.scope == scope }) {
            policy
        } else {
            replaceTree(policy, tree(policy, scope))
        }
    }

    fun removeNode(policy: NetworkPolicy, scope: PolicyScope, id: String): NetworkPolicy =
        replaceTree(policy, PolicyBranchEditing.removeNode(tree(policy, scope), id))

    fun position(policy: NetworkPolicy, scope: PolicyScope, key: String, point: PolicyCanvasPoint): NetworkPolicy {
        require(point.isValid())
        return PolicyCanvasEditing.update(policy, scope, mapOf(key to point))
    }

    fun align(policy: NetworkPolicy, scope: PolicyScope): NetworkPolicy =
        PolicyCanvasEditing.update(policy, scope, emptyMap(), clear = true)

    fun removeChannel(policy: NetworkPolicy, id: String): NetworkPolicy {
        fun clean(tree: PolicyTree) = tree.copy(positions = tree.positions - PolicyCanvasKeys.channel(id))
        return policy.copy(
            channels = policy.channels.filterNot { it.id == id },
            device = clean(policy.device), trees = policy.trees.map(::clean),
        )
    }

    fun move(policy: NetworkPolicy, scope: PolicyScope, id: String, delta: Int): NetworkPolicy =
        replaceTree(policy, PolicyBranchEditing.moveNode(tree(policy, scope), id, delta))

    fun canAddChild(node: PolicyNode): Boolean = PolicyOtherwise.isOtherwise(node) || canAddChild(node.target)

    fun canAddChild(target: PolicyTarget): Boolean = when (target) {
        PolicyTarget.Direct, PolicyTarget.Block, PolicyTarget.CurrentExit -> true
        is PolicyTarget.Profile, is PolicyTarget.Folder, is PolicyTarget.Channel -> false
    }

    fun ordered(tree: PolicyTree): List<Pair<PolicyNode, Int>> {
        val result = mutableListOf<Pair<PolicyNode, Int>>()
        val displayed = PolicyBranchEditing.displayTree(tree)
        val byParent = displayed.nodes.groupBy { it.parentId }
        val visited = mutableSetOf<String>()
        fun visit(node: PolicyNode, depth: Int) {
            if (!visited.add(node.id)) return
            result += node to depth
            byParent[node.id].orEmpty().sortedWith(compareBy<PolicyNode> { it.sortIndex }.thenBy { it.id }).forEach { visit(it, depth + 1) }
        }
        byParent[null].orEmpty().sortedWith(compareBy<PolicyNode> { it.sortIndex }.thenBy { it.id }).forEach { visit(it, 0) }
        displayed.nodes.filter { it.id !in visited }.forEach { visit(it, 0) }
        return result
    }
}
