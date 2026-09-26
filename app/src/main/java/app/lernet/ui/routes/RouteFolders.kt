package app.lernet.ui.routes

import app.lernet.config.repo.RuleNodeRecord
import app.lernet.routing.RouteAction
import app.lernet.routing.RouteElse
import app.lernet.routing.RouteTerminal
import app.lernet.routing.RouteTree
import app.lernet.routing.RuleMatch

/**
 * List and schema share one tree. [ORPHAN] is not a folder the compiler walks:
 * a node (and everything under it) stays out of the route until it is placed again.
 */
object RouteFolders {
    const val ORPHAN = RouteTree.ORPHAN

    fun children(nodes: List<RuleNodeRecord>, parentId: String?): List<RuleNodeRecord> =
        nodes.filter { it.parentId == parentId }.sortedWith(compareBy({ it.sortIndex }, { it.id }))

    fun orphans(nodes: List<RuleNodeRecord>): List<RuleNodeRecord> =
        nodes.filter { it.parentId == ORPHAN }.sortedWith(compareBy({ it.sortIndex }, { it.id }))

    fun attached(nodes: List<RuleNodeRecord>): List<RuleNodeRecord> =
        RouteTree.keepAttached(nodes, { it.id }, { it.parentId })

    /**
     * Rows of the open parent, top to bottom. Same [RuleNodeRecord.sortIndex] the compiler walks.
     * There is no second list-order field. Canvas coordinates are not read.
     */
    fun listed(nodes: List<RuleNodeRecord>, folderId: String?): List<RuleNodeRecord> = children(nodes, folderId)

    /** Deleting the sole child «Иначе» simply makes its parent a terminal rule. */
    fun deleteNeedsConfirm(nodes: List<RuleNodeRecord>, id: String): Boolean {
        val node = nodes.firstOrNull { it.id == id } ?: return false
        if (nodes.any { it.parentId == id }) return true
        return node.isElseRule() && !isSoleElseChild(nodes, node)
    }

    fun restoreTerminalAfterElseDeletion(nodes: List<RuleNodeRecord>, id: String): List<RuleNodeRecord> {
        val fallback = nodes.firstOrNull { it.id == id } ?: return nodes
        if (!isSoleElseChild(nodes, fallback)) return nodes
        return nodes.map { node ->
            if (node.id == fallback.parentId) {
                node.copy(action = fallback.action, pipeName = fallback.pipeName)
            } else {
                node
            }
        }
    }

    private fun isSoleElseChild(nodes: List<RuleNodeRecord>, node: RuleNodeRecord): Boolean =
        node.isElseRule() && node.parentId != null && node.parentId != ORPHAN &&
            nodes.any { it.id == node.parentId } &&
            nodes.none { it.parentId == node.parentId && it.id != node.id } &&
            nodes.none { it.parentId == node.id }

    fun misplaced(nodes: List<RuleNodeRecord>, node: RuleNodeRecord): Boolean {
        val parentId = node.parentId ?: return false
        if (parentId == ORPHAN) return false
        val parent = nodes.firstOrNull { it.id == parentId } ?: return false
        return !parent.acceptsChildren(nodes)
    }

    fun hasNested(nodes: List<RuleNodeRecord>): Boolean = nodes.any { misplaced(nodes, it) }

    /**
     * Every selected node can become a parent. The insertion path moves
     * its previous outcome into «Иначе» before attaching the first child.
     */
    fun addParent(nodes: List<RuleNodeRecord>, folderId: String?): String? {
        val folder = nodes.firstOrNull { it.id == folderId } ?: return null
        return folder.id
    }

    /** Preserve traffic behavior when a leaf first becomes a structural fork. */
    fun branchPreservingOutcome(
        nodes: List<RuleNodeRecord>,
        parentId: String?,
        newId: () -> String,
    ): List<RuleNodeRecord> {
        val parent = nodes.firstOrNull { it.id == parentId } ?: return nodes
        if (nodes.any { it.parentId == parentId }) return nodes
        val fallback = parent.copy(
            id = newId(),
            parentId = parent.id,
            enabled = true,
            sortIndex = 0,
            title = "",
            apps = emptyList(),
            domains = emptyList(),
            domainSuffixes = emptyList(),
            ipCidrs = emptyList(),
            geoip = emptyList(),
            processes = emptyList(),
            blocksJson = "",
        )
        return nodes.map { if (it.id == parent.id) it.copy(pipeName = "") else it } + fallback
    }

    /**
     * Every real sibling level, including an empty root, gets one «Иначе».
     * Orphans are not a level. Existing else nodes are kept, then pinned last.
     */
    fun seedMissingElse(
        nodes: List<RuleNodeRecord>,
        ownerId: String,
        newId: () -> String,
        extraParents: Set<String?> = emptySet(),
    ): List<RuleNodeRecord> {
        val additions = levels(nodes, extraParents).mapNotNull { parentId ->
            missingElse(nodes, ownerId, parentId, newId)
        }
        val base = if (additions.isEmpty()) nodes else nodes + additions
        return pinElseLast(base)
    }

    /** Drag and −/+ must not place «Иначе» above a sibling. */
    fun elseMoveBlocked(rows: List<RuleNodeRecord>, from: Int, to: Int): Boolean {
        val moving = rows.getOrNull(from) ?: return true
        val target = rows.getOrNull(to) ?: return true
        return moving.isElseRule() || target.isElseRule()
    }

    /** Save can offer a one-tap repair only for a missing or out-of-order «Иначе». */
    fun elseNeedsRepair(errors: List<String>): Boolean = errors.any { line ->
        val message = line.substringAfter(": ", "")
        message == RouteElse.MISSING || message == RouteElse.NOT_LAST
    }

    /** «Иначе» stays the last sibling. Canvas coordinates are not read. */
    fun pinElseLast(nodes: List<RuleNodeRecord>): List<RuleNodeRecord> {
        val rank = HashMap<String, Int>()
        nodes.groupBy { it.parentId }.forEach { (_, siblings) ->
            val ordered = siblings.sortedWith(compareBy({ it.sortIndex }, { it.id }))
            val body = ordered.filter { !it.isElseRule() }
            val tails = ordered.filter { it.isElseRule() }
            (body + tails).forEachIndexed { index, node -> rank[node.id] = index }
        }
        return nodes.map { node ->
            val next = rank[node.id] ?: return@map node
            if (next == node.sortIndex) node else node.copy(sortIndex = next)
        }
    }

    /** 1-based sibling rank among non-Else rules. «Иначе» is not in that race. */
    fun priorityRank(nodes: List<RuleNodeRecord>, node: RuleNodeRecord): Int {
        if (node.isElseRule()) return nodes.count { it.parentId == node.parentId && !it.isElseRule() } + 1
        val siblings = nodes
            .filter { it.parentId == node.parentId && !it.isElseRule() }
            .sortedWith(compareBy({ it.sortIndex }, { it.id }))
        val index = siblings.indexOfFirst { it.id == node.id }
        return index.coerceAtLeast(0) + 1
    }

    fun selectedParent(
        nodes: List<RuleNodeRecord>,
        asList: Boolean,
        listFolderId: String?,
        canvasSelection: String?,
    ): String? {
        val raw = if (asList) {
            listFolderId
        } else {
            canvasSelection?.takeIf(CanvasIds::isRule)?.let(CanvasIds::ruleKey)
        }
        return addParent(nodes, raw)
    }

    /** Long-press from the hotbar. A non-orphan is left alone. The node lands before «Иначе». */
    fun adopt(
        nodes: List<RuleNodeRecord>,
        id: String,
        parentId: String?,
        ownerId: String,
        newId: () -> String,
    ): List<RuleNodeRecord> {
        val node = nodes.firstOrNull { it.id == id } ?: return nodes
        if (node.parentId != ORPHAN) return nodes
        val base = if (node.isElseRule()) nodes else branchPreservingOutcome(nodes, parentId, newId)
        val sortIndex = nodes.count { it.parentId == parentId && !it.isElseRule() }
        val placed = base.map { item ->
            if (item.id == id) item.copy(parentId = parentId, sortIndex = sortIndex) else item
        }
        return seedMissingElse(placed, ownerId, newId)
    }

    private fun levels(nodes: List<RuleNodeRecord>, extraParents: Set<String?>): Set<String?> = buildSet {
        add(null)
        nodes.forEach { node ->
            if (node.parentId != ORPHAN) add(node.parentId)
        }
        extraParents.forEach { parentId ->
            if (parentId != ORPHAN) add(parentId)
        }
    }

    private fun missingElse(
        nodes: List<RuleNodeRecord>,
        ownerId: String,
        parentId: String?,
        newId: () -> String,
    ): RuleNodeRecord? {
        val siblings = nodes.filter { it.parentId == parentId }
        if (siblings.any { it.isElseRule() }) return null
        return RuleNodeRecord(
            id = newId(),
            profileId = ownerId,
            parentId = parentId,
            enabled = true,
            sortIndex = siblings.size,
            action = nodes.firstOrNull { it.id == parentId }?.action ?: "proxy",
            apps = emptyList(),
            domains = emptyList(),
            domainSuffixes = emptyList(),
            ipCidrs = emptyList(),
            geoip = emptyList(),
            pipeName = "",
            blocksJson = "",
        )
    }
}

internal fun RuleNodeRecord.acceptsChildren(nodes: List<RuleNodeRecord>): Boolean {
    val parsed = RouteAction.entries.firstOrNull { it.name.equals(action, ignoreCase = true) } ?: return false
    val kids = nodes.count { it.parentId == id }
    return RouteTerminal.acceptsChildren(parsed, pipeName, kids)
}

internal fun RuleNodeRecord.canAdoptChild(): Boolean {
    val parsed = RouteAction.entries.firstOrNull { it.name.equals(action, ignoreCase = true) } ?: return false
    return RouteTerminal.canAdoptChild(parsed, pipeName)
}

internal fun RuleNodeRecord.isElseRule(): Boolean {
    val match = RuleMatch(apps, domains, domainSuffixes, ipCidrs, geoip, processes)
    if (!match.isCatchAll()) return false
    // Canonical «Иначе» keeps blank blocksJson. Encoded empty blocks are a draft rule, not Else.
    return blocksJson.isBlank()
}

internal fun movedIds(ids: List<String>, from: Int, to: Int): List<String> {
    if (from !in ids.indices || to !in ids.indices) return ids
    val next = ids.toMutableList()
    val moved = next.removeAt(from)
    next.add(to, moved)
    return next
}
