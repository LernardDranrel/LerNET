package app.lernet.routing.policy

/** Import uses new local IDs. Missing mappings are errors, never a direct route or a new root. */
data class PolicyIdRemap(
    val profiles: Map<String, String>,
    val folders: Map<String, String>,
    val nodes: Map<String, String>,
    val channels: Map<String, String>,
) {
    fun apply(policy: NetworkPolicy): NetworkPolicy {
        listOf(profiles, folders, nodes, channels).forEach { mapping ->
            require(mapping.values.none { it.isBlank() } && mapping.values.distinct().size == mapping.size) {
                "Неоднозначное переназначение идентификаторов"
            }
        }
        fun mapped(mapping: Map<String, String>, id: String): String =
            requireNotNull(mapping[id]) { "Отсутствует соответствие для ссылки при импорте" }
        fun scope(old: PolicyScope): PolicyScope = when (old) {
            PolicyScope.Device -> old
            is PolicyScope.Profile -> PolicyScope.Profile(mapped(profiles, old.id))
            is PolicyScope.Folder -> PolicyScope.Folder(mapped(folders, old.id))
        }
        fun target(old: PolicyTarget): PolicyTarget = when (old) {
            PolicyTarget.Direct, PolicyTarget.Block, PolicyTarget.CurrentExit -> old
            is PolicyTarget.Profile -> old.copy(id = mapped(profiles, old.id), routeScope = old.routeScope?.let(::scope))
            is PolicyTarget.Folder -> old.copy(id = mapped(folders, old.id), routeScope = old.routeScope?.let(::scope))
            is PolicyTarget.Channel -> old.copy(id = mapped(channels, old.id))
        }
        fun tree(old: PolicyTree): PolicyTree = old.copy(
            scope = scope(old.scope), defaultTarget = target(old.defaultTarget),
            nodes = old.nodes.map { node ->
                node.copy(id = mapped(nodes, node.id), parentId = node.parentId?.let { mapped(nodes, it) }, target = target(node.target))
            },
            positions = old.positions.mapKeys { (key, _) ->
                when {
                    key == PolicyCanvasKeys.ROOT -> key
                    key.startsWith("channel:") -> PolicyCanvasKeys.channel(mapped(channels, key.removePrefix("channel:")))
                    else -> PolicyCanvasKeys.node(mapped(nodes, PolicyCanvasKeys.nodeId(key)))
                }
            },
        )
        return policy.copy(
            device = tree(policy.device), trees = policy.trees.map(::tree),
            channels = policy.channels.map { channel ->
                channel.copy(id = mapped(channels, channel.id), owner = scope(channel.owner), target = target(channel.target))
            },
            folderPolicies = policy.folderPolicies.map { folder ->
                folder.copy(
                    folderId = mapped(folders, folder.folderId),
                    preferredProfileId = folder.preferredProfileId?.let {
                        mapped(profiles, it)
                    }
                )
            },
            profilePolicies = policy.profilePolicies.map { profile ->
                profile.copy(profileId = mapped(profiles, profile.profileId))
            },
        )
    }
}
