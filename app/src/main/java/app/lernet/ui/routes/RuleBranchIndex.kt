package app.lernet.ui.routes

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.lernet.R
import app.lernet.routing.MatchJoin
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyOtherwise

/** Presentation ancestry also covers disconnected imported rules, in the same order as the full canvas. */
internal class RuleBranchIndex(nodes: List<PolicyNode>) {
    private val parents = mutableMapOf<String, String?>()
    private val levels = mutableMapOf<String?, MutableList<PolicyNode>>()
    val byId: Map<String, PolicyNode> = buildMap {
        val trail = mutableListOf<String>()
        for ((node, depth) in orderedBranchNodes(nodes)) {
            while (trail.size > depth) trail.removeAt(trail.lastIndex)
            val parent = trail.lastOrNull()
            parents[node.id] = parent
            levels.getOrPut(parent) { mutableListOf() }.add(node)
            put(node.id, node)
            trail.add(node.id)
        }
    }

    fun children(parentId: String?): List<PolicyNode> = levels[parentId].orEmpty()

    fun parentId(nodeId: String): String? = parents[nodeId]

    fun path(nodeId: String?): List<PolicyNode> {
        val trail = mutableListOf<PolicyNode>()
        var current = nodeId?.let(byId::get)
        while (current != null) {
            trail.add(current)
            current = parents[current.id]?.let(byId::get)
        }
        trail.reverse()
        return trail
    }
}

@Composable
internal fun branchRuleTitle(node: PolicyNode): String = when {
    PolicyOtherwise.isOtherwise(node) -> {
        val label = stringResource(R.string.expert_otherwise_title)
        val name = node.title.trim().takeUnless { it.isEmpty() || it.equals(label, true) || it.equals("ИНАЧЕ", true) }
        if (name == null) label else "$label · $name"
    }
    node.title.isBlank() -> RulePreview.firstToken(node.conditions)?.let { (kind, value) ->
        shownValue(kind, value, stringResource(R.string.field_geo_private))
    } ?: stringResource(R.string.expert_rule_unnamed)
    else -> node.title
}

@Composable
internal fun branchRuleCondition(node: PolicyNode): String {
    if (PolicyOtherwise.isOtherwise(node)) return stringResource(R.string.rule_else_locked_short)
    if (node.conditions.blocks.isEmpty()) return stringResource(R.string.expert_branch_no_condition)
    val privateLabel = stringResource(R.string.field_geo_private)
    val join = stringResource(if (node.conditions.join == MatchJoin.AND) R.string.rule_join_and else R.string.rule_join_or)
    val blocks = node.conditions.blocks.map { block ->
        val values = block.values.map { shownValue(block.kind, it, privateLabel) }
        val type = blockTitle(block.kind)
        if (values.isEmpty() || values.singleOrNull() == type) type else "$type · ${values.joinToString(", ")}"
    }
    return blocks.joinToString(" $join ")
}

internal fun orderedBranchNodes(nodes: List<PolicyNode>): List<Pair<PolicyNode, Int>> {
    val result = mutableListOf<Pair<PolicyNode, Int>>()
    val byParent = nodes.sortedWith(compareBy<PolicyNode> { it.sortIndex }.thenBy { it.id }).groupBy { it.parentId }
    val visited = mutableSetOf<String>()
    val pending = ArrayDeque<Pair<PolicyNode, Int>>()
    fun visit(root: PolicyNode) {
        pending.addLast(root to 0)
        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeLast()
            if (!visited.add(node.id)) continue
            result += node to depth
            byParent[node.id].orEmpty().asReversed().forEach { pending.addLast(it to depth + 1) }
        }
    }
    byParent[null].orEmpty().forEach(::visit)
    nodes.forEach { if (it.id !in visited) visit(it) }
    return result
}
