package app.lernet.routing.policy

/** A canvas patch never replaces rules with the snapshot used to render a gesture. */
object PolicyCanvasEditing {
    fun update(
        policy: NetworkPolicy,
        scope: PolicyScope,
        points: Map<String, PolicyCanvasPoint>,
        clear: Boolean = false,
    ): NetworkPolicy {
        val original = if (scope == PolicyScope.Device) {
            policy.device
        } else {
            policy.trees.firstOrNull { it.scope == scope }
                ?: return policy
        }
        val displayed = PolicyBranchEditing.displayTree(original)
        val validPoints = points.filterValues { it.isValid() }
        val syntheticMoved = displayed.nodes.any {
            PolicyCanvasKeys.node(it.id) in validPoints && original.nodes.none { stored -> stored.id == it.id }
        }
        val tree = if (syntheticMoved) displayed else original
        val keys = tree.nodes.map { PolicyCanvasKeys.node(it.id) }.toSet() + PolicyCanvasKeys.ROOT +
            policy.channels.filter { it.owner == scope }.map { PolicyCanvasKeys.channel(it.id) }
        val accepted = validPoints.filterKeys { it in keys }
        val existing = if (clear) emptyMap() else tree.positions.filter { (key, point) -> key in keys && point.isValid() }
        val updated = tree.copy(positions = existing + accepted)
        return if (scope == PolicyScope.Device) {
            policy.copy(device = updated)
        } else {
            policy.copy(trees = policy.trees.map { if (it.scope == scope) updated else it })
        }
    }
}
