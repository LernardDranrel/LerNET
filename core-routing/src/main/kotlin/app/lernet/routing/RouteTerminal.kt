package app.lernet.routing

/**
 * Any node, including «Иначе», becomes a structural fork as soon as it has children.
 * Its saved action is only a leaf default; children determine a fork's outcome.
 * Named pipes must be moved to a terminal child before a node becomes a fork.
 */
object RouteTerminal {
    const val MESSAGE =
        "Отдельный VPN-канал должен принадлежать конечному правилу."

    enum class ProxyMode {
        AUTO,
        NAMED,
        FORK,
    }

    fun proxyMode(action: RouteAction, pipeName: String, childCount: Int): ProxyMode? = when (action) {
        RouteAction.PROXY -> when {
            childCount > 0 -> ProxyMode.FORK
            pipeName.isNotBlank() -> ProxyMode.NAMED
            else -> ProxyMode.AUTO
        }
        RouteAction.DIRECT -> null
        RouteAction.BLOCK -> null
    }

    /** The node's condition, not its stored action, determines whether it can branch. */
    fun acceptsChildren(@Suppress("UNUSED_PARAMETER") action: RouteAction, pipeName: String, childCount: Int): Boolean =
        childCount > 0 && pipeName.isBlank()

    /**
     * A named pipe must be explicitly moved to the new level's «Иначе» first.
     */
    fun canAdoptChild(@Suppress("UNUSED_PARAMETER") action: RouteAction, pipeName: String): Boolean = pipeName.isBlank()

    fun childErrors(nodes: List<RuleNode>): List<FieldError> {
        val byId = nodes.associateBy(RuleNode::id)
        val counts = nodes.groupingBy { it.parentId }.eachCount()
        return nodes.mapNotNull { node ->
            val parent = byId[node.parentId] ?: return@mapNotNull null
            val kids = counts[parent.id] ?: 0
            when {
                parent.pipeName.isNotBlank() ->
                    FieldError(node.id, "parent", RouteElse.PIPE_AND_FORK)
                acceptsChildren(parent.action, parent.pipeName, kids) -> null
                else -> FieldError(node.id, "parent", MESSAGE)
            }
        }
    }
}
