package app.lernet.routing.policy

/** Three-way merge of UI edits. Only changed fields are written; conflicting edits are rejected. */
object PolicyDraftMerge {
    fun merge(base: NetworkPolicy, edited: NetworkPolicy, current: NetworkPolicy): NetworkPolicy {
        fun folders(policy: NetworkPolicy) = policy.folderPolicies + edited.folderPolicies
            .filter { next -> policy.folderPolicies.none { it.folderId == next.folderId } }
            .map { FolderPolicy(it.folderId) }
        val channels = entries(base.channels, edited.channels, current.channels, PolicyChannel::id)
        val removedChannels = base.channels.map { it.id }.toSet() - channels.map { it.id }.toSet()
        fun tree(before: PolicyTree, next: PolicyTree, now: PolicyTree): PolicyTree {
            val nodes = entries(before.nodes, next.nodes, now.nodes, PolicyNode::id)
            val removed = before.nodes.map { it.id }.toSet() - nodes.map { it.id }.toSet()
            require(nodes.none { it.parentId in removed }) {
                "В удаляемой ветке появились новые правила. Откройте её заново."
            }
            val removedKeys = removed.map(PolicyCanvasKeys::node).toSet() + removedChannels.map(PolicyCanvasKeys::channel)
            val positions = now.positions.toMutableMap()
            (before.positions.keys + next.positions.keys).forEach { key ->
                if (key !in removedKeys && before.positions[key] != next.positions[key]) {
                    val point = field(before.positions[key], next.positions[key], now.positions[key])
                    if (point == null) positions.remove(key) else positions[key] = point
                }
            }
            removedKeys.forEach(positions::remove)
            return now.copy(
                nodes = nodes,
                defaultTarget = field(before.defaultTarget, next.defaultTarget, now.defaultTarget),
                positions = positions,
            )
        }
        return current.copy(
            device = tree(base.device, edited.device, current.device),
            trees = entries(base.trees, edited.trees, current.trees, PolicyTree::scope, ::tree),
            channels = channels,
            folderPolicies = entries(folders(base), edited.folderPolicies, folders(current), FolderPolicy::folderId) { before, next, now ->
                now.copy(
                    selection = field(before.selection, next.selection, now.selection),
                    preferredProfileId = field(before.preferredProfileId, next.preferredProfileId, now.preferredProfileId),
                    autoSwap = field(before.autoSwap, next.autoSwap, now.autoSwap),
                    freshnessMs = field(before.freshnessMs, next.freshnessMs, now.freshnessMs),
                    cooldownMs = field(before.cooldownMs, next.cooldownMs, now.cooldownMs),
                    lifecycle = field(before.lifecycle, next.lifecycle, now.lifecycle),
                )
            },
            profilePolicies = entries(base.profilePolicies, edited.profilePolicies, current.profilePolicies, ProfileExitPolicy::profileId),
            dns = field(base.dns, edited.dns, current.dns),
            health = field(base.health, edited.health, current.health),
        )
    }

    private fun <T> field(base: T, edited: T, current: T): T {
        if (base == edited) return current
        require(current == base || current == edited) { "Схема уже изменена. Откройте свойства заново, чтобы сохранить свои изменения." }
        return edited
    }

    private fun <T, K> entries(
        base: List<T>, edited: List<T>, current: List<T>, key: (T) -> K,
        merge: ((T, T, T) -> T)? = null,
    ): List<T> {
        val before = base.associateBy(key)
        val after = edited.associateBy(key)
        require(before.size == base.size && after.size == edited.size)
        val changed = (before.keys + after.keys).filter { before[it] != after[it] }
        val result = current.associateByTo(linkedMapOf(), key)
        changed.forEach { id ->
            val old = before[id]
            val next = after[id]
            val now = result[id]
            val value = if (old != null && next != null && now != null && merge != null) {
                merge(old, next, now)
            } else field(old, next, now)
            if (value == null) result.remove(id) else result[id] = value
        }
        return result.values.toList()
    }
}
