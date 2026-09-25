package app.lernet.routing

/** Geometry shared by the Android and Windows route diagrams. Units belong to the caller. */
data class RouteLayoutNode(val id: String, val parentId: String?)
data class RouteLayoutPoint(val x: Float, val y: Float)
data class RouteVerticalLayout(val root: RouteLayoutPoint, val nodes: Map<String, RouteLayoutPoint>)

object RouteTreeLayout {
    fun vertical(
        nodes: List<RouteLayoutNode>,
        column: Float,
        row: Float,
        left: Float = 40f,
        top: Float = 40f,
    ): RouteVerticalLayout {
        val ids = nodes.map { it.id }.toSet()
        val children = nodes.groupBy { node ->
            node.parentId?.takeIf { it in ids && it != node.id }
        }
        val centers = mutableMapOf<String, Float>()
        val depths = mutableMapOf<String, Int>()
        val visiting = mutableSetOf<String>()
        var leaf = 0f

        fun place(id: String, depth: Int): Float {
            centers[id]?.let { return it }
            if (!visiting.add(id)) return leaf++
            val childCenters = children[id].orEmpty().filter { it.id !in visiting }
                .map { place(it.id, depth + 1) }
            val center = if (childCenters.isEmpty()) leaf++ else (childCenters.first() + childCenters.last()) / 2f
            centers[id] = center
            depths[id] = depth
            visiting.remove(id)
            return center
        }

        children[null].orEmpty().forEach { place(it.id, 1) }
        nodes.forEach { if (it.id !in centers) place(it.id, 1) }
        val topCenters = children[null].orEmpty().mapNotNull { centers[it.id] }
        val rootCenter = if (topCenters.isEmpty()) 0f else (topCenters.first() + topCenters.last()) / 2f
        return RouteVerticalLayout(
            root = RouteLayoutPoint(left + rootCenter * column, top),
            nodes = centers.mapValues { (id, center) ->
                RouteLayoutPoint(left + center * column, top + depths.getValue(id) * row)
            },
        )
    }
}
