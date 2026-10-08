package app.lernet.routing.policy

import app.lernet.routing.FieldError
import app.lernet.routing.RouteCompiler

/** Validate before projection. A broken or unavailable reference must never become a direct route. */
object PolicyValidator {
    private const val MAX_NODES = 10_000
    private const val MAX_DEPTH = 64

    fun validate(policy: NetworkPolicy, inventory: PolicyInventory): List<FieldError> {
        val errors = mutableListOf<FieldError>()
        fun issue(id: String?, field: String, message: String) {
            errors += FieldError(id, field, message)
        }
        if (policy.schemaVersion != NetworkPolicy.VERSION) issue(null, "version", "Неподдерживаемая версия схемы")
        if (policy.revision < 0) issue(null, "revision", "Отрицательная версия правил")
        if (!policy.health.isValid()) {
            issue(
                null, "health",
                "Интервал проверки: 1–60 секунд, максимум не меньше минимума. " +
                    "Ожидание активного выхода: 1–15 секунд. Неудач перед восстановлением: 1–10."
            )
        }
        if (!policy.dns.isValid()) issue(null, "dns", "DNS: укажите корректный IPv4-адрес сервера")
        if (policy.device.scope != PolicyScope.Device) issue(null, "scope", "Основная схема должна принадлежать устройству")
        val trees = listOf(policy.device) + policy.trees
        if (trees.map { it.scope }.distinct().size != trees.size) issue(null, "scope", "Повторяющийся владелец схемы")
        if (trees.sumOf { it.nodes.size } > MAX_NODES ||
            policy.channels.size > MAX_NODES ||
            trees.size > MAX_NODES ||
            policy.profilePolicies.size > MAX_NODES ||
            policy.folderPolicies.size > MAX_NODES
        ) {
            issue(null, "size", "В схеме слишком много элементов")
            return errors
        }
        val nodeIds = trees.flatMap { it.nodes }.map { it.id }
        if (nodeIds.distinct().size != nodeIds.size) issue(null, "id", "Идентификатор правила повторяется в разных схемах")
        if (policy.channels.map { it.id }.distinct().size !=
            policy.channels.size
        ) {
            issue(null, "channel", "Повторяющийся идентификатор канала")
        }
        if (policy.folderPolicies.map { it.folderId }.distinct().size !=
            policy.folderPolicies.size
        ) {
            issue(null, "folder", "Повторяющаяся политика папки")
        }
        if (policy.profilePolicies.map { it.profileId }.distinct().size != policy.profilePolicies.size) {
            issue(null, "profile", "Повторяющаяся политика профиля")
        }
        val treeByScope = trees.associateBy { it.scope }
        val channels = policy.channels.associateBy { it.id }
        fun validScope(scope: PolicyScope): Boolean = when (scope) {
            PolicyScope.Device -> true
            is PolicyScope.Profile -> scope.id in inventory.profileIds
            is PolicyScope.Folder -> scope.id in inventory.folderMembers
        }
        inventory.folderMembers.forEach { (_, members) ->
            if (members.distinct().size != members.size || members.any { it !in inventory.profileIds }) {
                issue(null, "inventory", "Папка ссылается на отсутствующий или повторный профиль")
            }
        }
        if (inventory.profileIds.any { it.isBlank() } || inventory.folderMembers.keys.any { it.isBlank() }) {
            issue(null, "inventory", "Профиль или папка без идентификатора")
        }
        if (inventory.profilePlatformRequirements.keys.any { it !in inventory.profileIds }) {
            issue(null, "inventory", "Платформенные возможности относятся к отсутствующему профилю")
        }
        fun targetErrors(target: PolicyTarget, scope: PolicyScope, id: String?) {
            when (target) {
                PolicyTarget.Direct, PolicyTarget.Block -> Unit
                PolicyTarget.CurrentExit -> if (scope ==
                    PolicyScope.Device
                ) {
                    issue(id, "target", "У схемы устройства нет текущего VPN-выхода")
                }
                is PolicyTarget.Channel -> {
                    val channel = channels[target.id]
                    if (channel == null) {
                        issue(id, "channel", "Канал отсутствует")
                    } else if (channel.owner != scope) {
                        issue(id, "channel", "Канал принадлежит другой схеме")
                    }
                }
                is PolicyTarget.Profile -> {
                    if (target.id !in inventory.profileIds) issue(id, "profile", "Профиль отсутствует")
                    target.routeScope?.let { if (it !in treeByScope) issue(id, "scope", "Дочерняя схема отсутствует") }
                    val scopeFits = when (val routeScope = target.routeScope) {
                        null -> true
                        PolicyScope.Device -> false
                        is PolicyScope.Profile -> routeScope.id == target.id
                        is PolicyScope.Folder -> target.id in inventory.folderMembers[routeScope.id].orEmpty()
                    }
                    if (!scopeFits) issue(id, "scope", "Схема не принадлежит выбранному профилю или его папке")
                }
                is PolicyTarget.Folder -> {
                    if (target.id !in inventory.folderMembers) issue(id, "folder", "Папка отсутствует")
                    target.routeScope?.let { if (it !in treeByScope) issue(id, "scope", "Дочерняя схема отсутствует") }
                    if (target.routeScope != null && target.routeScope != PolicyScope.Folder(target.id)) {
                        issue(id, "scope", "Папка может использовать только собственную схему")
                    }
                }
            }
        }
        trees.forEach { tree ->
            errors += PolicyOtherwise.errors(tree)
            if (!validScope(tree.scope)) issue(null, "scope", "Владелец схемы отсутствует")
            if (tree.nodes.map { it.id }.distinct().size != tree.nodes.size) issue(null, "id", "Повторяющийся идентификатор правила")
            val byId = tree.nodes.associateBy { it.id }
            val positionKeys = byId.keys.map(PolicyCanvasKeys::node) + PolicyCanvasKeys.ROOT +
                policy.channels.filter { it.owner == tree.scope }.map { PolicyCanvasKeys.channel(it.id) }
            tree.positions.forEach { (key, point) ->
                if (key !in positionKeys) issue(key, "position", "Позиция относится к отсутствующему элементу схемы")
                if (!point.isValid()) issue(key, "position", "Координаты элемента схемы выходят за допустимые границы")
            }
            val structuralIds = tree.nodes.filter { it.enabled && !it.detached }.mapNotNull { it.parentId }.toSet()
            tree.nodes.forEach { node ->
                if (node.id.isBlank()) issue(node.id, "id", "Правило без идентификатора")
                val ancestors = mutableSetOf<String>()
                var cursor: PolicyNode? = node
                while (cursor != null) {
                    if (!ancestors.add(cursor.id) || ancestors.size > MAX_DEPTH) {
                        issue(node.id, "tree", "Цикл или слишком большая глубина дерева")
                        break
                    }
                    val parent = cursor.parentId ?: break
                    cursor = byId[parent]
                    if (cursor == null) issue(node.id, "parent", "Родительское правило отсутствует")
                }
                if (node.conditions.blocks.isNotEmpty()) {
                    errors += RouteCompiler.validateConditions(node.id, node.conditions)
                }
                targetErrors(node.target, tree.scope, node.id)
                val physicalTarget = node.target is PolicyTarget.Profile ||
                    node.target is PolicyTarget.Folder ||
                    node.target is PolicyTarget.Channel
                if (node.id in structuralIds && physicalTarget) {
                    val reason = "Профиль, папка и канал должны быть конечной целью. " +
                        "Добавьте ветви в схему самой цели."
                    issue(node.id, "target", reason)
                }
                node.redirect?.let { redirect ->
                    if (redirect.address == null && redirect.port == null) issue(node.id, "redirect", "Задайте адрес или порт назначения")
                    if (redirect.port != null && redirect.port !in 1..65535) issue(node.id, "redirect", "Порт должен быть от 1 до 65535")
                    if (redirect.address != null && PolicyDestinationAddress.normalize(redirect.address) == null) {
                        issue(node.id, "redirect", "Некорректный адрес назначения")
                    }
                }
            }
            targetErrors(tree.defaultTarget, tree.scope, null)
        }
        fun lifecycleErrors(id: String, life: ExitLifecyclePolicy) {
            if (life.idleTimeoutMs !in 1_000..86_400_000 ||
                life.firstFlowTimeoutMs !in 1_000..45_000 ||
                life.startupTimeoutMs !in 1_000..45_000 ||
                life.maxPendingFlows !in 1..1_000
            ) {
                issue(id, "lifecycle", "Недопустимые ограничения ожидания или простоя")
            }
        }
        policy.channels.forEach { channel ->
            if (channel.id.isBlank() || channel.name.isBlank()) issue(channel.id, "channel", "Канал без идентификатора или названия")
            if (channel.owner !in treeByScope) issue(channel.id, "scope", "Схема канала отсутствует")
            targetErrors(channel.target, channel.owner, channel.id)
            lifecycleErrors(channel.id, channel.lifecycle)
        }
        policy.profilePolicies.forEach { profile ->
            if (profile.profileId !in inventory.profileIds) issue(profile.profileId, "profile", "Профиль отсутствует")
            lifecycleErrors(profile.profileId, profile.lifecycle)
        }
        policy.folderPolicies.forEach { folder ->
            if (folder.folderId !in inventory.folderMembers) issue(folder.folderId, "folder", "Папка отсутствует")
            if (folder.preferredProfileId != null && folder.preferredProfileId !in inventory.folderMembers[folder.folderId].orEmpty()) {
                issue(folder.folderId, "preferred", "Предпочтительный профиль не принадлежит папке")
            }
            if (folder.freshnessMs !in 1_000..300_000 ||
                folder.cooldownMs !in 0..300_000
            ) {
                issue(folder.folderId, "health", "Недопустимые сроки проверки")
            }
            lifecycleErrors(folder.folderId, folder.lifecycle)
        }
        if (errors.isNotEmpty()) return errors.distinct()
        errors += PolicyReferences.validate(policy)
        return errors.distinct()
    }
}
