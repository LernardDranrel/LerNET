package app.lernet.ui.expert

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.lernet.R
import app.lernet.routing.MatchJoin
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyOtherwise
import app.lernet.routing.policy.PolicyTree
import app.lernet.ui.routes.blockTitle
import app.lernet.ui.routes.shownValue

/** Presentation ancestry also covers disconnected imported rules, in the same order as the full canvas. */
internal class ExpertBranchIndex(tree: PolicyTree) {
    private val parents = mutableMapOf<String, String?>()
    private val levels = mutableMapOf<String?, MutableList<PolicyNode>>()
    val byId: Map<String, PolicyNode> = buildMap {
        val trail = mutableListOf<String>()
        for ((node, depth) in ExpertEdits.ordered(tree)) {
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
    PolicyOtherwise.isOtherwise(node) -> stringResource(R.string.expert_otherwise_title)
    node.title.isBlank() -> stringResource(R.string.expert_rule_unnamed)
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
