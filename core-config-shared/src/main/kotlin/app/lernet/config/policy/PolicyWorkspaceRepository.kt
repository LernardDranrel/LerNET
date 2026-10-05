package app.lernet.config.policy

import app.lernet.config.model.DnsPolicy
import app.lernet.config.model.ProfileSource
import app.lernet.config.transfer.TransferBundle
import app.lernet.config.transfer.TransferCodec
import app.lernet.routing.ConditionCodec
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyCanvasKeys
import app.lernet.routing.policy.PolicyIdRemap
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.PolicyTree
import app.lernet.routing.policy.PolicyValidator
import java.util.UUID

/** Local storage owns saved/draft state. Every mutation is durable before a caller receives it. */
class PolicyWorkspaceRepository(
    private val store: PolicyWorkspaceStore,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private var current: PolicyWorkspace? = null
    var lastNotes: List<String> = emptyList()
        private set

    @Synchronized
    fun open(bundle: TransferBundle): PolicyWorkspace {
        TransferCodec.validate(bundle)
        recoverImport(bundle)?.let { return it }
        val existing = current ?: store.load()
        return if (existing == null) {
            commit(PolicyMigration.migrate(bundle))
        } else {
            current = existing
            reconcile(bundle)
        }
    }

    @Synchronized
    fun snapshot(): PolicyWorkspace = requireNotNull(current) { "Рабочая область ещё не открыта" }

    @Synchronized
    fun reconcile(bundle: TransferBundle): PolicyWorkspace {
        TransferCodec.validate(bundle)
        recoverImport(bundle)?.let { return it }
        val before = snapshot()
        if (before.legacy == bundle) return before
        val result = PolicyWorkspaceReconcile.reconcile(before, bundle)
        val next = commit(result.workspace)
        lastNotes = result.notes
        return next
    }

    @Synchronized
    fun updateDraft(policy: NetworkPolicy): PolicyWorkspace = commit(snapshot().copy(draft = policy))

    /** Runtime sessions already assign revisions; this method must not increment them a second time. */
    @Synchronized
    fun persist(saved: NetworkPolicy, draft: NetworkPolicy): PolicyWorkspace = commit(snapshot().copy(saved = saved, draft = draft))

    @Synchronized
    fun saveDraft(): PolicyWorkspace {
        val before = snapshot()
        val errors = PolicyValidator.validate(before.draft, PolicyMigration.inventory(before.legacy))
        require(errors.isEmpty()) { errors.joinToString("; ") { it.message } }
        if (before.saved == before.draft) return before
        val next = before.draft.copy(revision = nextRevision(before.saved.revision, before.draft.revision))
        return commit(before.copy(saved = next, draft = next))
    }

    @Synchronized
    fun discardDraft(): PolicyWorkspace = commit(snapshot().let { it.copy(draft = it.saved) })

    /** Export includes credentials and is only invoked by the explicit export action. */
    @Synchronized
    fun export(): String = PolicyWorkspaceCodec.encode(snapshot())

    @Synchronized
    fun replaceImported(raw: String): PolicyWorkspace = commit(previewImported(raw, replace = true).after)

    /** Prepare once; native/Desktop/Room consumers must use after.legacy IDs without remapping again. */
    @Synchronized
    fun previewImported(raw: String, replace: Boolean = false): PolicyImportPlan {
        require(store.loadPendingImport() == null) { "Сначала восстановите прерванный импорт, обновив список профилей." }
        val imported = PolicyWorkspaceCodec.decode(raw).let { it.copy(legacy = PolicyWorkspaceImport.canonicalLegacy(it.legacy)) }
        val before = snapshot()
        if (replace) {
            val revision = nextRevision(before.saved.revision, before.draft.revision, imported.saved.revision)
            val next = imported.copy(saved = imported.saved.copy(revision = revision), draft = imported.draft.copy(revision = revision))
            return PolicyImportPlan(before, next)
        }
        return PolicyImportPlan(before, appendImported(before, imported))
    }

    /** Profile forms prepare an exact inventory transaction without publishing a partial workspace. */
    @Synchronized
    fun previewInventory(bundle: TransferBundle): PolicyImportPlan {
        require(store.loadPendingImport() == null) { "Сначала восстановите прерванный импорт, обновив список профилей." }
        TransferCodec.validate(bundle)
        val before = snapshot()
        val after = PolicyWorkspaceReconcile.reconcile(before, bundle).workspace
        PolicyWorkspaceCodec.validate(after)
        return PolicyImportPlan(before, after)
    }

    /** A journal bridges two independent stores. legacyCommit must be an atomic platform transaction. */
    @Synchronized
    fun commitImported(plan: PolicyImportPlan, legacyCommit: (TransferBundle) -> Unit): PolicyWorkspace {
        require(snapshot() == plan.before) { "Рабочая область изменилась после подготовки импорта. Повторите импорт." }
        PolicyWorkspaceCodec.validate(plan.after)
        store.savePendingImport(plan)
        legacyCommit(plan.after.legacy)
        val next = commit(plan.after, allowPendingImport = true)
        store.clearPendingImport()
        return next
    }

    /** Append keeps rule order and the existing default. A foreign default requires explicit replacement. */
    @Synchronized
    fun mergeImported(raw: String): PolicyWorkspace = commit(previewImported(raw).after)

    private fun appendImported(before: PolicyWorkspace, imported: PolicyWorkspace): PolicyWorkspace {
        require(imported.saved.device.defaultTarget == PolicyTarget.Direct && imported.draft.device.defaultTarget == PolicyTarget.Direct) {
            "У импортируемой схемы другой маршрут по умолчанию. Используйте замену рабочей области."
        }
        val copied = PolicyWorkspaceImport.remap(imported, newId)
        val legacy = before.legacy.copy(
            scope = "all",
            groups = before.legacy.groups + copied.legacy.groups,
            profiles = before.legacy.profiles + copied.legacy.profiles,
            rules = before.legacy.rules + copied.legacy.rules,
        )
        val revision = nextRevision(before.saved.revision, before.draft.revision, copied.saved.revision)
        fun append(left: NetworkPolicy, right: NetworkPolicy) = left.copy(
            revision = revision,
            device = left.device.copy(
                nodes = left.device.nodes + right.device.nodes.mapIndexed { index, node ->
                    if (node.parentId == null) node.copy(sortIndex = rootAppendIndex(left.device, index)) else node
                },
                positions = left.device.positions + right.device.positions.filterKeys { it != PolicyCanvasKeys.ROOT },
            ),
            trees = left.trees + right.trees,
            channels = left.channels + right.channels,
            folderPolicies = left.folderPolicies + right.folderPolicies,
            profilePolicies = left.profilePolicies + right.profilePolicies,
        )
        return before.copy(legacy = legacy, saved = append(before.saved, copied.saved), draft = append(before.draft, copied.draft)).also {
            PolicyWorkspaceCodec.validate(it)
        }
    }

    private fun recoverImport(bundle: TransferBundle): PolicyWorkspace? {
        val pending = store.loadPendingImport() ?: return null
        val restored = when {
            equivalent(bundle, pending.after.legacy) -> pending.after.copy(legacy = bundle)
            equivalent(bundle, pending.before.legacy) -> pending.before.copy(legacy = bundle)
            else -> throw IllegalStateException(
                "Импорт не завершён, а список профилей изменился. " +
                    "Сохраните данные и повторите восстановление импорта.",
            )
        }
        val recovered = commit(restored, allowPendingImport = true)
        store.clearPendingImport()
        lastNotes = listOf("Восстановлена согласованность профилей и общей схемы после прерванного импорта.")
        return recovered
    }

    private fun equivalent(left: TransferBundle, right: TransferBundle): Boolean {
        fun canonical(bundle: TransferBundle) = PolicyWorkspaceImport.canonicalLegacy(bundle).let {
            it.copy(
                scope = "all",
                groups = it.groups.sortedBy { group ->
                    group.id
                },
                profiles = it.profiles.sortedBy { profile -> profile.id },
                rules = it.rules.sortedBy { rule -> rule.id }, selectedProfileId = null
            )
        }
        return canonical(left) == canonical(right)
    }

    private fun commit(workspace: PolicyWorkspace, allowPendingImport: Boolean = false): PolicyWorkspace {
        require(allowPendingImport || store.loadPendingImport() == null) {
            "Сначала восстановите прерванный импорт, обновив список профилей."
        }
        store.save(workspace)
        current = workspace
        lastNotes = emptyList()
        return workspace
    }

    private fun rootAppendIndex(tree: PolicyTree, offset: Int): Int {
        val last = tree.nodes.filter { it.parentId == null }.maxOfOrNull { it.sortIndex } ?: -1
        require(last.toLong() + offset + 1 <= Int.MAX_VALUE) { "Слишком много правил в корне схемы" }
        return last + offset + 1
    }
}

data class PolicyImportPlan(val before: PolicyWorkspace, val after: PolicyWorkspace)

internal fun nextRevision(vararg revisions: Long): Long {
    val last = revisions.maxOrNull() ?: 0
    require(last < Long.MAX_VALUE) { "Исчерпан счётчик версий схемы" }
    return last + 1
}

object PolicyWorkspaceImport {
    /** Platform stores share this representation; flattened join/positions become explicit structured data. */
    fun canonicalLegacy(bundle: TransferBundle): TransferBundle {
        val migrated = PolicyMigration.migrate(bundle).saved
        val conditions = migrated.trees.flatMap { it.nodes }.associate { it.id to it.conditions }
        val identities = bundle.rules.associate { it.id to it.id }
        val ownerRules = bundle.rules.groupBy { it.ownerId }
        val layouts = bundle.profiles.associate { profile ->
            profile.id to
                (
                    profile.canvasLayout?.takeIf { it.isNotBlank() }
                        ?: TransferCodec.layoutFromPositions(ownerRules[profile.id].orEmpty(), identities)
                    )
        } + bundle.groups.associate { group ->
            val owner = "grp_${group.id}"
            owner to
                (
                    group.canvasLayout?.takeIf { it.isNotBlank() }
                        ?: TransferCodec.layoutFromPositions(ownerRules[owner].orEmpty(), identities)
                    )
        }
        return bundle.copy(
            profiles = bundle.profiles.map { profile ->
                profile.copy(
                    source = ProfileSource.entries.firstOrNull { it.name == profile.source }?.name ?: ProfileSource.JSON_PASTE.name,
                    dnsPolicy = DnsPolicy.fromStorage(profile.dnsPolicy).name,
                    canvasLayout = layouts[profile.id],
                )
            },
            groups = bundle.groups.map { it.copy(canvasLayout = layouts["grp_${it.id}"]) },
            rules = bundle.rules.map { rule ->
                rule.copy(
                    blocksJson = ConditionCodec.encode(conditions.getValue(rule.id)), join = "AND",
                    position = TransferCodec.pointInLayout(layouts[rule.ownerId], rule.id),
                )
            },
        )
    }

    fun remap(workspace: PolicyWorkspace, newId: () -> String): PolicyWorkspace {
        val policies = listOf(workspace.saved, workspace.draft)
        val profileRefs = workspace.legacy.profiles.map { it.id }.toMutableSet()
        val folderRefs = workspace.legacy.groups.map { it.id }.toMutableSet()
        val channelRefs = mutableSetOf<String>()
        val nodeRefs = workspace.legacy.rules.map { it.id }.toMutableSet()
        fun scope(scope: PolicyScope) {
            when (scope) {
                PolicyScope.Device -> Unit
                is PolicyScope.Profile -> profileRefs += scope.id
                is PolicyScope.Folder -> folderRefs += scope.id
            }
        }
        fun target(target: PolicyTarget) {
            when (target) {
                PolicyTarget.Direct, PolicyTarget.Block, PolicyTarget.CurrentExit -> Unit
                is PolicyTarget.Profile -> {
                    profileRefs += target.id
                    target.routeScope?.let(::scope)
                }
                is PolicyTarget.Folder -> {
                    folderRefs += target.id
                    target.routeScope?.let(::scope)
                }
                is PolicyTarget.Channel -> channelRefs += target.id
            }
        }
        // Invalid drafts remain invalid after transfer. Map missing references into new isolated
        // identities rather than allowing a stale external ID to bind an unrelated local profile.
        policies.forEach { policy ->
            (listOf(policy.device) + policy.trees).forEach { tree ->
                scope(tree.scope)
                target(tree.defaultTarget)
                tree.nodes.forEach { node ->
                    nodeRefs += node.id
                    node.parentId?.let { nodeRefs += it }
                    target(node.target)
                }
                tree.positions.keys.forEach { key ->
                    when {
                        key == PolicyCanvasKeys.ROOT -> Unit
                        key.startsWith("channel:") -> channelRefs += key.removePrefix("channel:")
                        else -> nodeRefs += PolicyCanvasKeys.nodeId(key)
                    }
                }
            }
            policy.channels.forEach { channel ->
                channelRefs += channel.id
                scope(channel.owner)
                target(channel.target)
            }
            policy.profilePolicies.forEach { profileRefs += it.profileId }
            policy.folderPolicies.forEach { folder ->
                folderRefs += folder.folderId
                folder.preferredProfileId?.let { profileRefs += it }
            }
        }
        val mapping = PolicyIdRemap(
            profiles = profileRefs.associateWith { newId() },
            folders = folderRefs.associateWith { newId() },
            nodes = nodeRefs.associateWith { newId() },
            channels = channelRefs.associateWith { newId() },
        )
        val owners = TransferCodec.remapOwnerIds(mapping.profiles, mapping.folders)
        val rules = workspace.legacy.rules.map { rule ->
            rule.copy(
                id = mapping.nodes.getValue(rule.id), ownerId = owners.getValue(rule.ownerId),
                parentId = TransferCodec.remapParentId(rule.parentId, mapping.nodes),
            )
        }
        val legacy = workspace.legacy.copy(
            scope = "all",
            groups = workspace.legacy.groups.map { group ->
                group.copy(
                    id = mapping.folders.getValue(group.id), profileIds = group.profileIds.map(mapping.profiles::getValue),
                    canvasLayout = TransferCodec.remapCanvasLayout(group.canvasLayout, mapping.nodes),
                )
            },
            profiles = workspace.legacy.profiles.map { profile ->
                val outboundIds = profile.outbounds.associate { it.id to newId() }
                profile.copy(
                    id = mapping.profiles.getValue(profile.id),
                    outbounds = profile.outbounds.map { it.copy(id = outboundIds.getValue(it.id)) },
                    selectedOutboundId = outboundIds.getValue(profile.selectedOutboundId),
                    canvasLayout = TransferCodec.remapCanvasLayout(profile.canvasLayout, mapping.nodes),
                )
            },
            rules = rules,
            selectedProfileId = workspace.legacy.selectedProfileId?.let(mapping.profiles::getValue),
        )
        return workspace.copy(legacy = legacy, saved = mapping.apply(workspace.saved), draft = mapping.apply(workspace.draft))
    }
}
