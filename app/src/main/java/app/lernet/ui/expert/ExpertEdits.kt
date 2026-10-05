package app.lernet.ui.expert

import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyCanvasKeys
import app.lernet.routing.policy.PolicyCanvasPoint
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.PolicyTree

/** Canvas coordinates are presentation only; these operations preserve semantic sibling order. */
internal object ExpertEdits {
    fun tree(policy: NetworkPolicy, scope: PolicyScope): PolicyTree =
        if (scope == PolicyScope.Device) policy.device else policy.trees.first { it.scope == scope }

    fun replaceTree(policy: NetworkPolicy, tree: PolicyTree): NetworkPolicy =
        if (tree.scope == PolicyScope.Device) {
            policy.copy(device = tree)
        } else {
            policy.copy(trees = policy.trees.map { if (it.scope == tree.scope) tree else it })
        }

    fun putNode(policy: NetworkPolicy, scope: PolicyScope, node: PolicyNode): NetworkPolicy {
        val tree = tree(policy, scope)
        val exists = tree.nodes.any { it.id == node.id }
        return replaceTree(
            policy,
            tree.copy(nodes = if (exists) tree.nodes.map { if (it.id == node.id) node else it } else tree.nodes + node),
        )
    }

    fun removeNode(policy: NetworkPolicy, scope: PolicyScope, id: String): NetworkPolicy {
        val tree = tree(policy, scope)
        val doomed = mutableSetOf(id)
        var expanded: Boolean
        do {
            expanded = doomed.addAll(tree.nodes.filter { it.parentId in doomed }.map { it.id })
        } while (expanded)
        val doomedPoints = doomed.map(PolicyCanvasKeys::node).toSet()
        return replaceTree(
            policy,
            tree.copy(
                nodes = tree.nodes.filterNot { it.id in doomed },
                positions = tree.positions.filterKeys { it !in doomedPoints }
            )
        )
    }

    fun position(policy: NetworkPolicy, scope: PolicyScope, key: String, point: PolicyCanvasPoint): NetworkPolicy {
        require(point.isValid())
        val tree = tree(policy, scope)
        return replaceTree(policy, tree.copy(positions = tree.positions + (key to point)))
    }

    fun align(policy: NetworkPolicy, scope: PolicyScope): NetworkPolicy =
        replaceTree(policy, tree(policy, scope).copy(positions = emptyMap()))

    fun removeChannel(policy: NetworkPolicy, id: String): NetworkPolicy {
        fun clean(tree: PolicyTree) = tree.copy(positions = tree.positions - PolicyCanvasKeys.channel(id))
        return policy.copy(
            channels = policy.channels.filterNot { it.id == id },
            device = clean(policy.device), trees = policy.trees.map(::clean),
        )
    }

    fun move(policy: NetworkPolicy, scope: PolicyScope, id: String, delta: Int): NetworkPolicy {
        val tree = tree(policy, scope)
        val node = tree.nodes.firstOrNull { it.id == id } ?: return policy
        val siblings = tree.nodes.filter { it.parentId == node.parentId }
            .sortedWith(compareBy<PolicyNode> { it.sortIndex }.thenBy { it.id })
        val index = siblings.indexOfFirst { it.id == id }
        val destination = (index + delta).coerceIn(0, siblings.lastIndex)
        if (index == destination) return policy
        val ordered = siblings.toMutableList().apply { add(destination, removeAt(index)) }
        val indices = ordered.mapIndexed { position, item -> item.id to position }.toMap()
        return replaceTree(
            policy,
            tree.copy(
                nodes = tree.nodes.map { item ->
                    indices[item.id]?.let { item.copy(sortIndex = it) } ?: item
                }
            )
        )
    }

    fun canAddChild(target: PolicyTarget): Boolean = when (target) {
        PolicyTarget.Direct, PolicyTarget.Block, PolicyTarget.CurrentExit -> true
        is PolicyTarget.Profile, is PolicyTarget.Folder, is PolicyTarget.Channel -> false
    }

    fun ordered(tree: PolicyTree): List<Pair<PolicyNode, Int>> {
        val result = mutableListOf<Pair<PolicyNode, Int>>()
        val byParent = tree.nodes.groupBy { it.parentId }
        val visited = mutableSetOf<String>()
        fun visit(node: PolicyNode, depth: Int) {
            if (!visited.add(node.id)) return
            result += node to depth
            byParent[node.id].orEmpty().sortedWith(compareBy<PolicyNode> { it.sortIndex }.thenBy { it.id }).forEach { visit(it, depth + 1) }
        }
        byParent[null].orEmpty().sortedWith(compareBy<PolicyNode> { it.sortIndex }.thenBy { it.id }).forEach { visit(it, 0) }
        tree.nodes.filter { it.id !in visited }.forEach { visit(it, 0) }
        return result
    }
}
