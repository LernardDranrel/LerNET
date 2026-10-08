package app.lernet.desktop.expert

import app.lernet.routing.ConditionKind
import app.lernet.routing.MatchJoin
import app.lernet.routing.RoutePlatform
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyBranchEditing
import app.lernet.routing.policy.PolicyCanvasKeys
import app.lernet.routing.policy.PolicyChannel
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyOtherwise
import app.lernet.routing.policy.PolicyProgramCompiler
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.PolicyTree

/** Editing preserves unrelated scopes, portable conditions, and stable channel identities. */
object ExpertPolicyEditing {
    fun tree(policy: NetworkPolicy, scope: PolicyScope): PolicyTree =
        if (scope == PolicyScope.Device) {
            policy.device
        } else {
            policy.trees.firstOrNull { it.scope == scope }
                ?: PolicyTree(scope, defaultTarget = PolicyTarget.CurrentExit)
        }

    fun replaceTree(policy: NetworkPolicy, original: PolicyTree): NetworkPolicy {
        val tree = PolicyBranchEditing.synchronizeDefault(original)
        return if (tree.scope == PolicyScope.Device) {
            policy.copy(device = tree)
        } else {
            policy.copy(
                trees = policy.trees.filterNot { it.scope == tree.scope } + tree,
            )
        }
    }

    fun putNode(policy: NetworkPolicy, scope: PolicyScope, node: PolicyNode): NetworkPolicy {
        val updatedTree = PolicyBranchEditing.putNode(tree(policy, scope), node)
        return replaceTree(policy, updatedTree)
    }

    fun ensureTargetTree(policy: NetworkPolicy, target: PolicyTarget): NetworkPolicy {
        val scope = when (target) {
            is PolicyTarget.Profile -> target.routeScope
            is PolicyTarget.Folder -> target.routeScope
            PolicyTarget.Direct, PolicyTarget.Block, PolicyTarget.CurrentExit, is PolicyTarget.Channel -> null
        } ?: return policy
        return if (scope == PolicyScope.Device || policy.trees.any { it.scope == scope }) {
            policy
        } else {
            replaceTree(policy, tree(policy, scope))
        }
    }

    fun putChannel(policy: NetworkPolicy, channel: PolicyChannel): NetworkPolicy {
        val withOwner = replaceTree(policy, tree(policy, channel.owner))
        val updated = withOwner.copy(channels = withOwner.channels.filterNot { it.id == channel.id } + channel)
        return ensureTargetTree(updated, channel.target)
    }

    fun descendants(tree: PolicyTree, nodeId: String): Set<String> {
        val removed = mutableSetOf<String>()
        val children = tree.nodes.groupBy { it.parentId }
        val pending = ArrayDeque<String>()
        pending.add(nodeId)
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (removed.add(id)) children[id].orEmpty().forEach { pending.add(it.id) }
        }
        return removed
    }

    fun graphProblem(tree: PolicyTree): String? {
        if (tree.nodes.size > 2000) return "В этом дереве больше 2000 правил. Для устойчивой работы используйте список."
        val byId = tree.nodes.associateBy { it.id }
        if (byId.size !=
            tree.nodes.size
        ) {
            return "В черновике повторяются идентификаторы правил. Схему нельзя применить; отмените черновик или исправьте архив."
        }
        for (node in tree.nodes) {
            val visited = mutableSetOf<String>()
            var cursor: PolicyNode? = node
            while (cursor != null) {
                if (!visited.add(cursor.id)) return "В черновике замкнутая связь. Перенесите родителя правила, чтобы разомкнуть её."
                if (visited.size > 64) return "Дерево глубже 64 уровней. Перенесите часть правил ближе к корню."
                cursor = cursor.parentId?.let { byId[it] }
            }
        }
        return null
    }

    fun deleteNode(policy: NetworkPolicy, scope: PolicyScope, nodeId: String): NetworkPolicy {
        val updatedTree = PolicyBranchEditing.removeNode(tree(policy, scope), nodeId)
        return replaceTree(policy, updatedTree)
    }

    fun deleteChannel(policy: NetworkPolicy, channelId: String): NetworkPolicy {
        val compatibilityReference = (listOf(policy.device) + policy.trees).any {
            it.defaultTarget == PolicyTarget.Channel(channelId)
        }
        if (channelReferenceCount(policy, channelId) > 0 || compatibilityReference) return policy
        val key = PolicyCanvasKeys.channel(channelId)
        return policy.copy(
            channels = policy.channels.filterNot { it.id == channelId },
            device = policy.device.copy(positions = policy.device.positions - key),
            trees = policy.trees.map { it.copy(positions = it.positions - key) }
        )
    }

    fun moveNode(policy: NetworkPolicy, scope: PolicyScope, nodeId: String, delta: Int): NetworkPolicy {
        val updatedTree = PolicyBranchEditing.moveNode(tree(policy, scope), nodeId, delta)
        return replaceTree(policy, updatedTree)
    }

    fun validation(state: ExpertUiState): List<String> =
        PolicyProgramCompiler.compile(state.draft, state.inventory, RoutePlatform.WINDOWS).errors.map { it.message }.distinct()

    fun channelReferenceCount(policy: NetworkPolicy, channelId: String): Int {
        val target = PolicyTarget.Channel(channelId)
        return (listOf(policy.device) + policy.trees).sumOf { tree ->
            val explicitDefault = tree.nodes.any { it.parentId == null && !it.detached && PolicyOtherwise.isOtherwise(it) }
            val defaultReference = if (tree.defaultTarget == target && !explicitDefault) 1 else 0
            tree.nodes.count { it.target == target } + defaultReference
        } + policy.channels.count { it.target == target }
    }

    fun inheritedProtection(tree: PolicyTree, parentId: String?): Boolean =
        PolicyBranchEditing.inheritedProtection(tree, parentId)

    fun inactiveReason(node: PolicyNode, tree: PolicyTree, state: ExpertUiState): String? {
        state.inactiveReasons[node.id]?.let { return it }
        val visited = mutableSetOf<String>()
        var cursor: PolicyNode? = node
        while (cursor != null && visited.add(cursor.id)) {
            if (!cursor.enabled) {
                return if (cursor.id == node.id) {
                    "Ветка выключена пользователем."
                } else {
                    "Родительская ветка выключена. Условия ребёнка сохраняются, но сейчас не выполняются."
                }
            }
            if (cursor.detached) {
                return if (cursor.id == node.id) {
                    "Правило отсоединено от исполняемого дерева."
                } else {
                    "Родительское правило отсоединено от дерева. Эта ветка хранится для редактирования."
                }
            }
            if (cursor.conditions.blocks.any { it.kind == ConditionKind.APP }) {
                return "Эта ветка зависит от пакета Android. В Windows она и её дочерние правила не выполняются; " +
                    "данные сохраняются для Android. Добавьте отдельную ветку с программой Windows."
            }
            cursor = cursor.parentId?.let { id -> tree.nodes.firstOrNull { it.id == id } }
        }
        return null
    }

    fun scopeName(scope: PolicyScope, state: ExpertUiState): String = when (scope) {
        PolicyScope.Device -> "Всё устройство"
        is PolicyScope.Profile -> state.profiles.firstOrNull { it.id == scope.id }?.name ?: "Удалённый профиль"
        is PolicyScope.Folder -> state.folders.firstOrNull { it.id == scope.id }?.name ?: "Удалённая папка"
    }

    fun targetName(target: PolicyTarget, state: ExpertUiState): String = when (target) {
        PolicyTarget.Direct -> "Напрямую"
        PolicyTarget.Block -> "Блокировать трафик"
        PolicyTarget.CurrentExit -> "Выход этой схемы"
        is PolicyTarget.Profile -> "Профиль: ${state.profiles.firstOrNull { it.id == target.id }?.name ?: "недоступен"}"
        is PolicyTarget.Folder -> "Папка: ${state.folders.firstOrNull { it.id == target.id }?.name ?: "недоступна"}"
        is PolicyTarget.Channel -> "Канал: ${state.draft.channels.firstOrNull { it.id == target.id }?.name ?: "недоступен"}"
    }

    fun conditionSummary(node: PolicyNode): String {
        if (PolicyOtherwise.isOtherwise(node)) return "Трафик, которому не подошли предыдущие ветки"
        if (node.conditions.blocks.isEmpty()) return "Весь трафик этой ветки"
        val join = if (node.conditions.join == MatchJoin.AND) " И " else " ИЛИ "
        return node.conditions.blocks.joinToString(join) { block ->
            "${kindName(block.kind)}: ${block.values.joinToString(", ")}"
        }
    }

    fun kindName(kind: ConditionKind): String = when (kind) {
        ConditionKind.DOMAIN -> "Сайт"
        ConditionKind.GEOIP -> "Страна IP"
        ConditionKind.PRIVATE -> "Локальная сеть"
        ConditionKind.CIDR -> "IP / подсеть"
        ConditionKind.APP -> "Приложение Android"
        ConditionKind.PROCESS -> "Программа Windows"
    }
}
