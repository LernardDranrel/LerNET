package app.lernet.routing.policy

import app.lernet.routing.ConditionCodec
import app.lernet.routing.ConditionJson
import app.lernet.routing.RoutePlatform
import app.lernet.routing.RoutePlatformRules
import kotlinx.serialization.json.JsonObject

internal data class PolicyPath(
    val scope: PolicyScope,
    val nodeIds: List<String>,
    val condition: JsonObject,
    val target: PolicyTarget,
    val protected: Boolean,
    val redirect: DestinationRedirect?,
)

internal data class PolicyProjection(
    val paths: List<PolicyPath>,
    val inactiveNodeIds: Set<String>,
    /** Only owner predicates for the other platform, including their reachable descendants. */
    val foreignOwnerInactiveNodeIds: Set<String>,
)

/** Tree order is semantic; platform restrictions propagate to descendants without editing saved data. */
internal object PolicyPaths {
    fun project(tree: PolicyTree, platform: RoutePlatform): PolicyProjection {
        val byParent = tree.nodes.filter { !it.detached && it.enabled }.groupBy { it.parentId }
        val inactive = mutableSetOf<String>()
        val paths = mutableListOf<PolicyPath>()
        fun walk(node: PolicyNode, ancestors: List<PolicyNode>, unavailable: Boolean) {
            val unsupported = unavailable || RoutePlatformRules.unsupported(ConditionCodec.project(node.conditions), platform)
            val branch = ancestors + node
            if (unsupported) inactive += node.id
            val children = byParent[node.id].orEmpty().sortedWith(compareBy<PolicyNode> { it.sortIndex }.thenBy { it.id })
            if (!node.enabled) return
            if (children.isNotEmpty()) {
                children.forEach { walk(it, branch, unsupported) }
                // Descendant conditions may not match. The protected parent's remainder must not
                // fall through to the device's direct default, including after platform filtering.
                if (!unsupported && branch.any { it.protected }) {
                    val constraints = branch.map { ConditionJson.of(it.conditions) }.filter { it.isNotEmpty() }
                    paths += PolicyPath(
                        tree.scope, branch.map { it.id }, ConditionJson.andAll(constraints),
                        PolicyTarget.Block, true, null
                    )
                }
            } else if (!unsupported) {
                val constraints = branch.map { ConditionJson.of(it.conditions) }.filter { it.isNotEmpty() }
                paths += PolicyPath(
                    tree.scope, branch.map { it.id }, ConditionJson.andAll(constraints), node.target,
                    branch.any { it.protected },
                    branch.mapNotNull { it.redirect }
                        .fold<DestinationRedirect, DestinationRedirect?>(null) { previous, current -> current.over(previous) },
                )
            }
        }
        byParent[null].orEmpty().sortedWith(compareBy<PolicyNode> { it.sortIndex }.thenBy { it.id })
            .forEach { walk(it, emptyList(), false) }
        // Currently platform owner predicates are the only projected inactive cause. Keep the
        // reason explicit so future disabled/unreachable annotations cannot become foreign warnings.
        return PolicyProjection(paths, inactive, inactive.toSet())
    }
}
