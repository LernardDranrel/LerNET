package app.lernet.routing

enum class RoutePlatform { ANDROID, WINDOWS }

/** Availability is derived for this device; stored rules and their enabled flags stay portable. */
object RoutePlatformRules {
    fun unsupported(match: RuleMatch, platform: RoutePlatform): Boolean = when (platform) {
        RoutePlatform.ANDROID -> match.processes.isNotEmpty()
        RoutePlatform.WINDOWS -> match.apps.isNotEmpty()
    }

    /** Every descendant inherits the restriction, including an otherwise branch. */
    fun inactiveNodeIds(nodes: List<RuleNode>, platform: RoutePlatform): Set<String> {
        val children = nodes.groupBy { it.parentId }
        val inactive = mutableSetOf<String>()
        val pending = ArrayDeque<String>()
        val kind = if (platform == RoutePlatform.WINDOWS) ConditionKind.APP else ConditionKind.PROCESS
        nodes.filter { node ->
            node.conditions?.blocks?.any { it.kind == kind } ?: unsupported(node.match, platform)
        }
            .forEach { pending.add(it.id) }
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (inactive.add(id)) children[id].orEmpty().forEach { pending.add(it.id) }
        }
        return inactive
    }
}
