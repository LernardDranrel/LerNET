package app.lernet.routing.policy

import app.lernet.routing.RuleConditions
import java.util.UUID

/** Otherwise uses the same ordered terminal paths as ordinary rules, not a second evaluator. */
object PolicyOtherwise {
    fun isOtherwise(node: PolicyNode): Boolean = node.otherwise

    fun errors(tree: PolicyTree): List<app.lernet.routing.FieldError> = buildList {
        tree.nodes.filterNot { it.detached }.groupBy { it.parentId }.values.forEach { siblings ->
            val ordered = siblings.sortedWith(nodeOrder)
            val fallback = ordered.filter { it.otherwise }
            fallback.forEach { node ->
                fun issue(message: String) {
                    add(app.lernet.routing.FieldError(node.id, "otherwise", message))
                }
                if (fallback.size > 1) issue("На одном уровне может быть только одна ветка «ИНАЧЕ».")
                if (ordered.lastOrNull()?.id != node.id) issue("Ветка «ИНАЧЕ» должна быть последней на своём уровне.")
                if (!node.enabled) issue("Ветка «ИНАЧЕ» должна быть включена.")
                if (node.conditions.blocks.isNotEmpty()) issue("У ветки «ИНАЧЕ» нет собственного условия; добавьте дочерние правила.")
            }
        }
    }
}

internal val nodeOrder = compareBy<PolicyNode> { it.sortIndex }.thenBy { it.id }

/** Shared Windows/Android editing. Viewing a synthetic default never mutates the saved policy. */
object PolicyBranchEditing {
    fun displayTree(tree: PolicyTree): PolicyTree {
        val parents = tree.nodes.mapNotNull { it.parentId }.toSet()
        val legacyIds = tree.nodes.filterNot { it.detached }.groupBy { it.parentId }.values.mapNotNull { siblings ->
            if (siblings.any { it.otherwise }) {
                null
            } else {
                siblings.sortedWith(nodeOrder).lastOrNull()?.takeIf { candidate ->
                    candidate.enabled && candidate.conditions.blocks.isEmpty() && candidate.id !in parents
                }?.id
            }
        }.toSet()
        val displayed = tree.copy(nodes = tree.nodes.map { if (it.id in legacyIds) it.copy(otherwise = true) else it })
        val roots = displayed.nodes.filter { it.parentId == null && !it.detached }.sortedWith(nodeOrder)
        if (roots.any { it.otherwise }) return displayed
        val key = when (val scope = tree.scope) {
            PolicyScope.Device -> "device"
            is PolicyScope.Profile -> "profile:${scope.id}"
            is PolicyScope.Folder -> "folder:${scope.id}"
        }
        val base = UUID.nameUUIDFromBytes("lernet:otherwise:$key".toByteArray(Charsets.UTF_8)).toString()
        var id = base
        while (tree.nodes.any { it.id == id }) id += "-else"
        return appendOtherwise(displayed, null, id, tree.defaultTarget)
    }

    fun putNode(tree: PolicyTree, node: PolicyNode): PolicyTree {
        var updated = displayTree(tree)
        val previous = updated.nodes.firstOrNull { it.id == node.id }
        // Opening the first child must retain the terminal's former exit for all remaining traffic.
        val parent = node.parentId?.let { id -> updated.nodes.firstOrNull { it.id == id } }
        if (parent != null && updated.nodes.none { it.parentId == parent.id && it.enabled && !it.detached }) {
            val physical =
                parent.target is PolicyTarget.Profile || parent.target is PolicyTarget.Folder || parent.target is PolicyTarget.Channel
            val structural = if (inheritedProtection(updated, parent.id)) {
                PolicyTarget.Block
            } else if (tree.scope == PolicyScope.Device) {
                PolicyTarget.Direct
            } else {
                PolicyTarget.CurrentExit
            }
            if (physical) updated = updated.copy(nodes = updated.nodes.map { if (it.id == parent.id) it.copy(target = structural) else it })
            updated = appendOtherwise(updated, parent.id, UUID.randomUUID().toString(), parent.target)
        }
        val replacement = if (previous?.otherwise == true) {
            node.copy(otherwise = true, conditions = RuleConditions(), parentId = previous.parentId, enabled = true, detached = false)
        } else {
            node
        }
        val without = updated.nodes.filterNot { it.id == replacement.id }
        var siblings = without.filter { it.parentId == replacement.parentId }.sortedWith(nodeOrder).toMutableList()
        val previousIndex = updated.nodes.filter { it.parentId == replacement.parentId }.sortedWith(nodeOrder)
            .indexOfFirst { it.id == replacement.id }
        val firstCatchAll = siblings.indexOfFirst { it.otherwise || it.conditions.blocks.isEmpty() }
        val index = if (replacement.otherwise) {
            siblings.size
        } else if (previous != null && previous.parentId == replacement.parentId) {
            minOf(
                previousIndex.takeIf { it >= 0 } ?: siblings.size,
                siblings.indexOfFirst { it.otherwise }.takeIf { it >= 0 } ?: siblings.size
            )
        } else {
            firstCatchAll.takeIf { it >= 0 } ?: siblings.size
        }
        siblings.add(index, replacement)
        if (siblings.none { it.otherwise && !it.detached } && parent != null && inheritedProtection(updated, parent.id)) {
            // Legacy unprotected forks may continue to later outer siblings. Do not intercept
            // that continuation with a new terminal default just because a child was edited.
            siblings += otherwise(UUID.randomUUID().toString(), replacement.parentId, PolicyTarget.Block)
        }
        // Detached nodes retain their data but must not displace the executable last branch.
        siblings = siblings.sortedBy { if (it.otherwise && !it.detached) 1 else 0 }.toMutableList()
        val normalized = siblings.mapIndexed { position, item -> item.copy(sortIndex = position) }.associateBy { it.id }
        val existingIds = without.map { it.id }.toSet()
        updated = updated.copy(
            nodes = (without + replacement).map { normalized[it.id] ?: it } +
                normalized.values.filter { item -> item.id !in existingIds && item.id != replacement.id }
        )
        return synchronizeDefault(updated)
    }

    /** Keep the compatibility default aligned with the explicit final path, including nested ELSE. */
    fun synchronizeDefault(tree: PolicyTree): PolicyTree {
        var node = tree.nodes.firstOrNull { it.parentId == null && it.otherwise && it.enabled && !it.detached } ?: return tree
        val visited = mutableSetOf<String>()
        while (visited.add(node.id)) {
            val children = tree.nodes.filter { it.parentId == node.id && it.enabled && !it.detached }.sortedWith(nodeOrder)
            if (children.isEmpty()) return tree.copy(defaultTarget = node.target)
            node = children.lastOrNull()?.takeIf { it.conditions.blocks.isEmpty() } ?: return tree
        }
        return tree
    }

    fun initialChildTarget(tree: PolicyTree, parentId: String?): PolicyTarget =
        if (parentId != null && inheritedProtection(tree, parentId)) {
            PolicyTarget.Block
        } else if (tree.scope == PolicyScope.Device) {
            PolicyTarget.Direct
        } else {
            PolicyTarget.CurrentExit
        }

    fun canMoveNode(tree: PolicyTree, id: String, delta: Int): Boolean {
        val node = tree.nodes.firstOrNull { it.id == id } ?: return false
        if (node.otherwise) return false
        val siblings = tree.nodes.filter { it.parentId == node.parentId }.sortedWith(nodeOrder)
        val next = siblings.getOrNull(siblings.indexOfFirst { it.id == id } + delta) ?: return false
        return !next.otherwise
    }

    fun moveNode(tree: PolicyTree, id: String, delta: Int): PolicyTree {
        val node = tree.nodes.firstOrNull { it.id == id } ?: return tree
        if (node.otherwise) return tree
        val siblings = tree.nodes.filter { it.parentId == node.parentId }.sortedWith(nodeOrder).toMutableList()
        val from = siblings.indexOfFirst { it.id == id }
        val to = from + delta
        if (to !in siblings.indices || siblings[to].otherwise) return tree
        siblings.add(to, siblings.removeAt(from))
        val indices = siblings.mapIndexed { index, item -> item.id to index }.toMap()
        return tree.copy(nodes = tree.nodes.map { item -> indices[item.id]?.let { item.copy(sortIndex = it) } ?: item })
    }

    fun removeNode(tree: PolicyTree, id: String): PolicyTree {
        if (tree.nodes.firstOrNull { it.id == id }?.otherwise == true) return tree
        val doomed = mutableSetOf<String>()
        val children = tree.nodes.groupBy { it.parentId }
        val pending = ArrayDeque<String>().apply { add(id) }
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (doomed.add(next)) children[next].orEmpty().forEach { pending.add(it.id) }
        }
        return tree.copy(
            nodes = tree.nodes.filterNot { it.id in doomed },
            positions =
            tree.positions - doomed.map(PolicyCanvasKeys::node).toSet()
        )
    }

    /** Checks this ancestor and its parents; editors pass the edited node's parentId. */
    fun inheritedProtection(tree: PolicyTree, id: String?): Boolean {
        val visited = mutableSetOf<String>()
        var cursor = tree.nodes.firstOrNull { it.id == id }
        while (cursor != null && visited.add(cursor.id)) {
            if (cursor.protected) return true
            cursor = cursor.parentId?.let { parentId -> tree.nodes.firstOrNull { it.id == parentId } }
        }
        return false
    }

    private fun appendOtherwise(tree: PolicyTree, parentId: String?, id: String, target: PolicyTarget): PolicyTree {
        val siblings = tree.nodes.filter { it.parentId == parentId }.sortedWith(nodeOrder)
        val indices = siblings.mapIndexed { index, item -> item.id to index }.toMap()
        return tree.copy(
            nodes = tree.nodes.map { item -> indices[item.id]?.let { item.copy(sortIndex = it) } ?: item } +
                otherwise(id, parentId, target).copy(sortIndex = siblings.size)
        )
    }

    private fun otherwise(id: String, parentId: String?, target: PolicyTarget) =
        PolicyNode(id, parentId = parentId, title = "ИНАЧЕ", target = target, otherwise = true)
}
