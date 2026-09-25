package app.lernet.desktop

import app.lernet.routing.RouteTree
import app.lernet.routing.ConditionCodec
import app.lernet.routing.RuleMatch
import java.util.UUID

/** One rule list backs both the tree and the diagram. Coordinates never affect routing order. */
object DesktopRouteTree {
    data class Edit(val rules: List<StoredRule>, val error: String? = null)

    fun siblings(rules: List<StoredRule>, profileId: String, parentId: String?): List<StoredRule> =
        rules.filter { it.profileId == profileId && it.parentId == parentId }
            .sortedWith(compareBy<StoredRule> { it.sortIndex }.thenBy { it.id })

    fun isElse(rule: StoredRule): Boolean =
        rule.domains.isEmpty() && rule.domainSuffixes.isEmpty() && rule.cidrs.isEmpty() &&
            rule.countries.isEmpty() && rule.processes.isEmpty() && rule.apps.isEmpty() &&
            (rule.blocksJson.isBlank() || ConditionCodec.decode(rule.blocksJson, RuleMatch()).blocks.all { it.values.isEmpty() })

    fun save(all: List<StoredRule>, rule: StoredRule): Edit {
        val profile = all.filter { it.profileId == rule.profileId }
        val old = profile.firstOrNull { it.id == rule.id }
        val parent = rule.parentId?.let { id -> profile.firstOrNull { it.id == id } }
        if (rule.parentId != null && parent == null) return Edit(all, "Родительское правило не найдено")
        if (RouteTree.wouldCycle(profile.associate { it.id to it.parentId }, rule.id, rule.parentId)) {
            return Edit(all, "Нельзя перенести правило внутрь самого себя")
        }
        if (isElse(rule) && siblings(profile, rule.profileId, rule.parentId).any { it.id != rule.id && isElse(it) }) {
            return Edit(all, "На одном уровне может быть только одно правило «Иначе»")
        }
        if (old != null && profile.any { it.parentId == rule.id } && rule.pipeName.isNotBlank()) {
            return Edit(all, "У развилки нельзя указать отдельный канал")
        }
        val firstChild = parent != null && profile.none { it.parentId == parent.id && it.id != rule.id }
        // Converting a terminal rule into a fork preserves its former outcome in
        // the mandatory child-level Else. The parent itself no longer emits an action.
        val base = if (firstChild && parent?.pipeName?.isNotBlank() == true) {
            all.map { if (it.id == parent.id) it.copy(pipeName = "") else it }
        } else all
        val withoutRule = base.filterNot { it.id == rule.id }
        val siblingsAtDestination = siblings(withoutRule, rule.profileId, rule.parentId).filterNot(::isElse).toMutableList()
        siblingsAtDestination.add(rule.sortIndex.coerceIn(0, siblingsAtDestination.size), rule)
        val siblingOrder = siblingsAtDestination.mapIndexed { index, item -> item.id to index }.toMap()
        val updated = (withoutRule + rule).map { item ->
            siblingOrder[item.id]?.let { item.copy(sortIndex = it) } ?: item
        }
        val withElse = if (!isElse(rule) && siblings(updated, rule.profileId, rule.parentId).none(::isElse)) {
            updated + StoredRule(UUID.randomUUID().toString(), rule.profileId, rule.parentId, Int.MAX_VALUE,
                title = "Иначе", action = if (firstChild) parent?.action ?: "PROXY" else "PROXY",
                pipeName = if (firstChild) parent?.pipeName.orEmpty() else "")
        } else updated
        return Edit(normalize(withElse))
    }

    fun delete(all: List<StoredRule>, ruleId: String): Edit {
        val rule = all.firstOrNull { it.id == ruleId } ?: return Edit(all)
        if (isElse(rule) && siblings(all, rule.profileId, rule.parentId).size > 1) {
            return Edit(all, "Правило «Иначе» нужно для завершения ветки")
        }
        val removed = mutableSetOf(ruleId)
        do {
            val before = removed.size
            all.filter { it.parentId in removed }.forEach { removed += it.id }
        } while (removed.size != before)
        return Edit(normalize(all.filterNot { it.id in removed }))
    }

    fun move(all: List<StoredRule>, ruleId: String, delta: Int): Edit {
        val rule = all.firstOrNull { it.id == ruleId } ?: return Edit(all)
        if (isElse(rule)) return Edit(all, "«Иначе» всегда остаётся последним")
        val siblings = siblings(all, rule.profileId, rule.parentId).filterNot(::isElse).toMutableList()
        val from = siblings.indexOfFirst { it.id == ruleId }
        val to = from + delta
        if (from < 0 || to !in siblings.indices) return Edit(all)
        val moved = siblings.removeAt(from)
        siblings.add(to, moved)
        val order = siblings.mapIndexed { index, node -> node.id to index }.toMap()
        return Edit(normalize(all.map { node ->
            if (node.id in order) node.copy(sortIndex = order.getValue(node.id)) else node
        }))
    }

    fun reorder(all: List<StoredRule>, ruleId: String, targetId: String, after: Boolean): Edit {
        val source = all.firstOrNull { it.id == ruleId } ?: return Edit(all)
        val target = all.firstOrNull { it.id == targetId } ?: return Edit(all)
        if (source.id == target.id || source.profileId != target.profileId || source.parentId != target.parentId ||
            isElse(source) || isElse(target)) return Edit(all)
        val siblings = siblings(all, source.profileId, source.parentId).filterNot(::isElse).toMutableList()
        siblings.removeAll { it.id == ruleId }
        val index = siblings.indexOfFirst { it.id == targetId }
        siblings.add((index + if (after) 1 else 0).coerceIn(0, siblings.size), source)
        val order = siblings.mapIndexed { position, node -> node.id to position }.toMap()
        return Edit(normalize(all.map { node -> order[node.id]?.let { node.copy(sortIndex = it) } ?: node }))
    }

    private fun normalize(all: List<StoredRule>): List<StoredRule> {
        val order = all.groupBy { it.profileId to it.parentId }.values.flatMap { group ->
            group.sortedWith(compareBy<StoredRule> { isElse(it) }.thenBy { it.sortIndex }.thenBy { it.id })
                .mapIndexed { index, node -> node.id to index }
        }.toMap()
        return all.map { it.copy(sortIndex = order.getValue(it.id)) }
    }
}
