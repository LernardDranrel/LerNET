package app.lernet.config.policy

import app.lernet.config.transfer.TransferBundle
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyCanvasKeys
import app.lernet.routing.policy.PolicyInventory
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.PolicyTree

data class PolicyReconcileResult(val workspace: PolicyWorkspace, val notes: List<String>)

/** Inventory deletion becomes a visible block, never an implicit direct fallback. */
object PolicyWorkspaceReconcile {
    fun reconcile(before: PolicyWorkspace, bundle: TransferBundle): PolicyReconcileResult {
        val canonical = bundle.copy(scope = "all")
        if (before.legacy.copy(scope = "all", selectedProfileId = null) == canonical.copy(selectedProfileId = null)) {
            return PolicyReconcileResult(before.copy(legacy = canonical), emptyList())
        }
        val oldMigration = PolicyMigration.migrate(before.legacy).saved
        val newMigration = PolicyMigration.migrate(bundle).saved
        val inventory = PolicyMigration.inventory(bundle)
        val notes = linkedSetOf<String>()
        val revision = nextRevision(before.saved.revision, before.draft.revision)
        fun reconcile(policy: NetworkPolicy): NetworkPolicy {
            val oldTrees = oldMigration.trees.associateBy { it.scope }
            val migratedTrees = newMigration.trees.associateBy { it.scope }
            val retained = policy.trees.filter { exists(it.scope, inventory) }.map { tree ->
                val previous = oldTrees[tree.scope]
                if (sameRoutes(tree, previous)) {
                    val refreshed = migratedTrees.getValue(tree.scope)
                    refreshed.copy(
                        positions = if (tree.positions ==
                            previous?.positions
                        ) {
                            refreshed.positions
                        } else {
                            refreshed.positions + tree.positions
                        }
                    )
                } else {
                    tree
                }
            }
            val added = newMigration.trees.filter { tree -> retained.none { it.scope == tree.scope } }
            val refreshedScopes = retained.filter { tree -> sameRoutes(tree, migratedTrees[tree.scope]) }.map { it.scope }.toSet()
            val oldChannels = oldMigration.channels.associateBy { it.id }
            val retainedChannels = policy.channels.filter { exists(it.owner, inventory) }.filterNot { channel ->
                channel.owner in refreshedScopes && channel == oldChannels[channel.id]
            }
            val channels = retainedChannels + newMigration.channels.filter { channel ->
                (channel.owner in refreshedScopes || channel.owner in added.map { it.scope }) &&
                    retainedChannels.none { it.id == channel.id }
            }
            val scopes = (retained + added).map { it.scope }.toSet() + PolicyScope.Device
            val channelIds = channels.map { it.id }.toSet()
            fun target(target: PolicyTarget, label: String): PolicyTarget {
                val valid = when (target) {
                    PolicyTarget.Direct, PolicyTarget.Block, PolicyTarget.CurrentExit -> true
                    is PolicyTarget.Channel -> target.id in channelIds
                    is PolicyTarget.Profile ->
                        target.id in inventory.profileIds &&
                            (target.routeScope?.let { it in scopes && owns(target.id, it, inventory) } ?: true)
                    is PolicyTarget.Folder ->
                        target.id in inventory.folderMembers &&
                            (target.routeScope == null || target.routeScope in scopes)
                }
                if (valid) return target
                notes += "Ветка «$label» блокируется: её профиль, папка или канал удалены либо перенесены. Выберите новый выход."
                return PolicyTarget.Block
            }
            fun tree(tree: PolicyTree) = tree.copy(
                defaultTarget = target(tree.defaultTarget, scopeLabel(tree.scope)),
                nodes = tree.nodes.map { node -> node.copy(target = target(node.target, node.title.ifBlank { node.id })) },
                positions = tree.positions.filterKeys { key ->
                    key == PolicyCanvasKeys.ROOT ||
                        tree.nodes.any { PolicyCanvasKeys.node(it.id) == key } ||
                        channels.any { it.owner == tree.scope && key == PolicyCanvasKeys.channel(it.id) }
                },
            )
            val oldFolderPolicies = oldMigration.folderPolicies.associateBy { it.folderId }
            val newFolderPolicies = newMigration.folderPolicies.associateBy { it.folderId }
            val policies = policy.folderPolicies.filter { it.folderId in inventory.folderMembers }.map { folder ->
                val refreshed = if (folder == oldFolderPolicies[folder.folderId]) newFolderPolicies.getValue(folder.folderId) else folder
                refreshed.copy(
                    preferredProfileId = refreshed.preferredProfileId?.takeIf {
                        it in
                            inventory.folderMembers.getValue(folder.folderId)
                    }
                )
            }
            return policy.copy(
                revision = revision,
                device = tree(policy.device),
                trees = (retained + added).map(::tree),
                channels = channels.map { it.copy(target = target(it.target, it.name)) },
                folderPolicies = policies + newMigration.folderPolicies.filter { fresh -> policies.none { it.folderId == fresh.folderId } },
                profilePolicies = policy.profilePolicies.filter { it.profileId in inventory.profileIds },
            )
        }
        val saved = reconcile(before.saved)
        val draft = if (before.draft == before.saved) saved else reconcile(before.draft)
        return PolicyReconcileResult(before.copy(legacy = bundle, saved = saved, draft = draft), notes.toList())
    }

    private fun exists(scope: PolicyScope, inventory: PolicyInventory): Boolean = when (scope) {
        PolicyScope.Device -> true
        is PolicyScope.Profile -> scope.id in inventory.profileIds
        is PolicyScope.Folder -> scope.id in inventory.folderMembers
    }

    private fun sameRoutes(left: PolicyTree, right: PolicyTree?): Boolean =
        right != null && left.copy(positions = emptyMap()) == right.copy(positions = emptyMap())

    private fun owns(profileId: String, scope: PolicyScope, inventory: PolicyInventory): Boolean = when (scope) {
        PolicyScope.Device -> false
        is PolicyScope.Profile -> scope.id == profileId
        is PolicyScope.Folder -> profileId in inventory.folderMembers[scope.id].orEmpty()
    }

    private fun scopeLabel(scope: PolicyScope): String = when (scope) {
        PolicyScope.Device -> "маршрут устройства по умолчанию"
        is PolicyScope.Profile -> "профиль ${scope.id}"
        is PolicyScope.Folder -> "папка ${scope.id}"
    }
}
