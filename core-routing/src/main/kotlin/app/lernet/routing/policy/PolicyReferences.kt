package app.lernet.routing.policy

import app.lernet.routing.FieldError
import app.lernet.routing.RoutePlatform

/** References, protected inheritance and cycles are checked across trees and shared channels. */
internal object PolicyReferences {
    fun validate(policy: NetworkPolicy): List<FieldError> {
        val errors = mutableListOf<FieldError>()
        val trees = (listOf(policy.device) + policy.trees).associateBy { it.scope }
        val channels = policy.channels.associateBy { it.id }
        val nodes = trees.values.flatMap { it.nodes }.associateBy { it.id }
        var inspected = 0
        fun branchLabel(id: String?): String {
            val node = nodes[id]
            return when {
                node == null -> "путь по умолчанию"
                PolicyOtherwise.isOtherwise(node) -> "ИНАЧЕ"
                else -> "правило «${node.title.ifBlank { node.id }}»"
            }
        }
        fun treeLabel(scope: PolicyScope): String = when (scope) {
            PolicyScope.Device -> "Схема устройства"
            is PolicyScope.Profile -> "Дерево профиля"
            is PolicyScope.Folder -> "Дерево папки"
        }
        fun inspect(target: PolicyTarget, protected: Boolean, path: Set<Any>, id: String?, depth: Int, location: String) {
            if (++inspected > 50_000) {
                if (errors.none { it.field == "size" }) errors += FieldError(null, "size", "Слишком сложная сеть ссылок между схемами")
                return
            }
            if (depth > 64) {
                errors += FieldError(id, "reference", "Слишком длинная цепочка схем и каналов")
                return
            }
            when (target) {
                PolicyTarget.Direct -> if (protected) errors += FieldError(
                    id, "protection", "$location: выбран путь «Напрямую», но для всей ветки запрещён прямой выход."
                )
                PolicyTarget.Block, PolicyTarget.CurrentExit -> Unit
                is PolicyTarget.Channel -> {
                    if (target in path) {
                        errors += FieldError(id, "reference", "Цикл ссылок на канал")
                    } else {
                        val channel = channels.getValue(target.id)
                        inspect(channel.target, protected, path + target, id, depth + 1, "$location → канал «${channel.name}»")
                    }
                }
                is PolicyTarget.Profile, is PolicyTarget.Folder -> {
                    val fallback = when (target) {
                        is PolicyTarget.Profile -> target.fallback
                        is PolicyTarget.Folder -> target.fallback
                    }
                    val scope = when (target) {
                        is PolicyTarget.Profile -> target.routeScope
                        is PolicyTarget.Folder -> target.routeScope
                    }
                    if (protected && fallback == UnavailableFallback.DIRECT) {
                        errors += FieldError(id, "protection", "$location: прямой запасной путь противоречит запрету для всей ветки.")
                    }
                    if (scope != null) {
                        if (scope in path) {
                            errors += FieldError(id, "reference", "Цикл ссылок на схему")
                        } else {
                            val tree = trees.getValue(scope)
                            RoutePlatform.entries.forEach { platform ->
                                PolicyPaths.project(tree, platform).paths.forEach { branch ->
                                    inspect(
                                        branch.target, protected || branch.protected, path + scope, branch.nodeIds.lastOrNull(),
                                        depth + 1, "$location → ${treeLabel(scope)} → ${branchLabel(branch.nodeIds.lastOrNull())}"
                                    )
                                }
                            }
                            inspect(tree.defaultTarget, protected, path + scope, id, depth + 1,
                                "$location → ${treeLabel(scope)} → путь по умолчанию")
                        }
                    }
                }
            }
        }
        trees.values.forEach { tree ->
            RoutePlatform.entries.forEach { platform ->
                PolicyPaths.project(tree, platform).paths.forEach { branch ->
                    inspect(branch.target, branch.protected, setOf(tree.scope), branch.nodeIds.lastOrNull(), 0,
                        "${treeLabel(tree.scope)} → ${branchLabel(branch.nodeIds.lastOrNull())}")
                }
            }
            inspect(tree.defaultTarget, false, setOf(tree.scope), null, 0, "${treeLabel(tree.scope)} → путь по умолчанию")
        }
        policy.channels.forEach { channel ->
            inspect(channel.target, false, setOf(PolicyTarget.Channel(channel.id)), channel.id, 0, "Канал «${channel.name}»")
        }
        return errors.distinct()
    }
}
