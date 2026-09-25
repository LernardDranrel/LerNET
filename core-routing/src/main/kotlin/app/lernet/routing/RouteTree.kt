package app.lernet.routing

object RouteTree {
    /** Parent id for nodes that are not in the compiled tree. */
    const val ORPHAN = "orphan"

    fun <T> keepAttached(nodes: List<T>, idOf: (T) -> String, parentOf: (T) -> String?): List<T> {
        val byId = nodes.associateBy(idOf)
        fun rooted(id: String, seen: MutableSet<String>): Boolean {
            if (!seen.add(id)) return false
            val node = byId[id] ?: return false
            val parent = parentOf(node) ?: return true
            return if (parent == ORPHAN) false else rooted(parent, seen)
        }
        return nodes.filter { rooted(idOf(it), HashSet()) }
    }

    fun keepAttached(nodes: List<RuleNode>): List<RuleNode> =
        keepAttached(nodes, RuleNode::id, RuleNode::parentId)

    fun wouldCycle(parentOf: Map<String, String?>, nodeId: String, newParentId: String?): Boolean {
        if (newParentId == null) return false
        var cursor: String? = newParentId
        val seen = HashSet<String>()
        var cycle = false
        while (cursor != null && !cycle) {
            cycle = cursor == nodeId || !seen.add(cursor)
            cursor = if (cycle) null else parentOf[cursor]
        }
        return cycle
    }

    fun filledKinds(match: RuleMatch): List<MatchKind> = buildList {
        if (match.domains.isNotEmpty()) add(MatchKind.EXACT_DOMAIN)
        if (match.domainSuffixes.isNotEmpty()) add(MatchKind.DOMAIN_SUFFIX)
        if (match.ipCidrs.isNotEmpty()) add(MatchKind.IP_CIDR)
        if (match.geoip.isNotEmpty()) add(MatchKind.GEOIP)
        if (match.apps.isNotEmpty()) add(MatchKind.APP)
    }

    fun leavesInOrder(nodes: List<RuleNode>): List<String> {
        val parents = nodes.mapNotNull { it.parentId }.toSet()
        val order = RouteCompiler.assignTreeOrder(nodes)
        return nodes
            .filter { it.id !in parents }
            .sortedBy { order[it.id] ?: Int.MAX_VALUE }
            .map { it.id }
    }
}
