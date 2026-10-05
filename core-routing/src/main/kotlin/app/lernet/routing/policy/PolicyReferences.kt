package app.lernet.routing.policy

import app.lernet.routing.FieldError
import app.lernet.routing.RoutePlatform

/** References, protected inheritance and cycles are checked across trees and shared channels. */
internal object PolicyReferences {
    fun validate(policy: NetworkPolicy): List<FieldError> {
        val errors = mutableListOf<FieldError>()
        val trees = (listOf(policy.device) + policy.trees).associateBy { it.scope }
        val channels = policy.channels.associateBy { it.id }
        var inspected = 0
        fun inspect(target: PolicyTarget, protected: Boolean, path: Set<Any>, id: String?, depth: Int) {
            if (++inspected > 50_000) {
                if (errors.none { it.field == "size" }) errors += FieldError(null, "size", "Слишком сложная сеть ссылок между схемами")
                return
            }
            if (depth > 64) {
                errors += FieldError(id, "reference", "Слишком длинная цепочка схем и каналов")
                return
            }
            when (target) {
                PolicyTarget.Direct -> if (protected) errors += FieldError(id, "protection", "Защищённая ветка не может выходить напрямую")
                PolicyTarget.Block, PolicyTarget.CurrentExit -> Unit
                is PolicyTarget.Channel -> {
                    if (target in path) {
                        errors += FieldError(id, "reference", "Цикл ссылок на канал")
                    } else {
                        inspect(channels.getValue(target.id).target, protected, path + target, id, depth + 1)
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
                        errors += FieldError(id, "protection", "Защищённая ветка не может иметь прямой запасной выход")
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
                                        depth + 1
                                    )
                                }
                            }
                            inspect(tree.defaultTarget, protected, path + scope, id, depth + 1)
                        }
                    }
                }
            }
        }
        trees.values.forEach { tree ->
            RoutePlatform.entries.forEach { platform ->
                PolicyPaths.project(tree, platform).paths.forEach { branch ->
                    inspect(branch.target, branch.protected, setOf(tree.scope), branch.nodeIds.lastOrNull(), 0)
                }
            }
            inspect(tree.defaultTarget, false, setOf(tree.scope), null, 0)
        }
        policy.channels.forEach { channel -> inspect(channel.target, false, setOf(PolicyTarget.Channel(channel.id)), channel.id, 0) }
        return errors.distinct()
    }
}
