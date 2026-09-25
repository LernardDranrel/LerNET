package app.lernet.ui.routes

import app.lernet.config.repo.RuleNodeRecord
import app.lernet.routing.RouteTree
import app.lernet.routing.RouteLayoutNode
import app.lernet.routing.RouteTreeLayout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CanvasPoint(val x: Float, val y: Float)

data class CanvasLink(val fromId: String, val toId: String)

data class GraphApply(
    val nodes: List<RuleNodeRecord>,
    val rejectedCycle: Boolean,
    val rejectedTerminal: Boolean = false,
)

object CanvasIds {
    const val ROOT = "node-root"
    const val SYSTEM = "node-system"

    /** Kept for reading older saved layouts; the current diagram is always vertical. */
    const val AXIS = "axis"

    fun rule(id: String): String = "rule:$id"

    fun pipe(name: String): String = "pipe:$name"

    fun isRule(id: String): Boolean = id.startsWith("rule:")

    fun ruleKey(id: String): String = id.removePrefix("rule:")

    fun isPipe(id: String): Boolean = id.startsWith("pipe:")

    fun pipeKey(id: String): String = id.removePrefix("pipe:")
}

object CanvasGraph {
    private val json = Json { ignoreUnknownKeys = true }
    private const val COLUMN = 280f
    private const val ROW = 170f

    fun pipeNames(nodes: List<RuleNodeRecord>, extra: List<String>): List<String> {
        val named = nodes.map { it.pipeName }.filter { it.isNotBlank() }
        return (named + extra.filter { it.isNotBlank() }).distinct()
    }

    fun savedPipeNames(layout: Map<String, CanvasPoint>): List<String> =
        layout.keys.filter(CanvasIds::isPipe).map(CanvasIds::pipeKey).filter(String::isNotBlank)

    /**
     * Tree edges only. Action color is drawn on the node.
     * A blank pipe is the auto yellow stub, not a canvas node.
     * Positions from [layout] are visual. [RuleNodeRecord.sortIndex] is the priority.
     */
    fun links(nodes: List<RuleNodeRecord>): Set<CanvasLink> = buildSet {
        RouteFolders.attached(nodes).forEach { node ->
            val parent = node.parentId?.let { CanvasIds.rule(it) } ?: CanvasIds.ROOT
            add(CanvasLink(parent, CanvasIds.rule(node.id)))
        }
    }

    /** Yellow edge only for a leaf «В обход» with a named pipe. A fan-out is not that edge. */
    fun namedPipeLinks(nodes: List<RuleNodeRecord>): List<CanvasLink> {
        val withKids = nodes.mapNotNull { it.parentId }.toSet()
        return RouteFolders.attached(nodes).mapNotNull { node ->
            val named = node.action.equals("proxy", ignoreCase = true) && node.pipeName.isNotBlank()
            if (!named || node.id in withKids) {
                null
            } else {
                CanvasLink(CanvasIds.rule(node.id), CanvasIds.pipe(node.pipeName))
            }
        }
    }

    fun applyLinks(nodes: List<RuleNodeRecord>, links: Set<CanvasLink>): GraphApply {
        if (links.isEmpty()) return GraphApply(nodes, rejectedCycle = false, rejectedTerminal = false)
        var rejectedCycle = false
        var rejectedTerminal = false
        val parents = nodes.associate { it.id to it.parentId }.toMutableMap()
        val byId = nodes.associateBy { it.id }
        val next = nodes.map { node ->
            val requested = requestedParent(node, links)
            val choice = chooseParent(node, requested, parents, byId[requested])
            if (choice.cycle) rejectedCycle = true
            if (choice.terminal) rejectedTerminal = true
            parents[node.id] = choice.parentId
            val id = CanvasIds.rule(node.id)
            val pipeLink = links.lastOrNull { it.fromId == id && CanvasIds.isPipe(it.toId) }
            val pipe = pipeLink?.let { CanvasIds.pipeKey(it.toId) } ?: node.pipeName
            node.copy(parentId = choice.parentId, pipeName = pipe)
        }
        return GraphApply(next, rejectedCycle, rejectedTerminal)
    }

    private fun requestedParent(node: RuleNodeRecord, links: Set<CanvasLink>): String? {
        val id = CanvasIds.rule(node.id)
        val incoming = links.filter { link ->
            link.toId == id && (link.fromId == CanvasIds.ROOT || CanvasIds.isRule(link.fromId))
        }
        return when (val from = incoming.lastOrNull()?.fromId) {
            null -> node.parentId
            CanvasIds.ROOT -> null
            else -> CanvasIds.ruleKey(from)
        }
    }

    private fun chooseParent(
        node: RuleNodeRecord,
        requested: String?,
        parents: Map<String, String?>,
        requestedNode: RuleNodeRecord?,
    ): ParentChoice {
        if (RouteTree.wouldCycle(parents, node.id, requested)) {
            return ParentChoice(node.parentId, cycle = true, terminal = false)
        }
        val blocked = requested != node.parentId &&
            requested != null &&
            requestedNode?.canAdoptChild() != true
        return if (blocked) {
            ParentChoice(node.parentId, cycle = false, terminal = true)
        } else {
            ParentChoice(requested, cycle = false, terminal = false)
        }
    }

    private data class ParentChoice(val parentId: String?, val cycle: Boolean, val terminal: Boolean)

    fun isVertical(@Suppress("UNUSED_PARAMETER") saved: Map<String, CanvasPoint>): Boolean = true

    fun layout(
        nodes: List<RuleNodeRecord>,
        pipes: List<String>,
        @Suppress("UNUSED_PARAMETER") saved: Map<String, CanvasPoint>,
        column: Float = COLUMN,
        row: Float = ROW,
    ): Map<String, CanvasPoint> {
        val tree = RouteTreeLayout.vertical(
            nodes.sortedWith(compareBy({ it.sortIndex }, { it.id }))
                .map { RouteLayoutNode(it.id, it.parentId) },
            column = column,
            row = row,
            left = 40f,
            top = 36f,
        )
        val placed = LinkedHashMap<String, CanvasPoint>()
        placed[CanvasIds.AXIS] = CanvasPoint(1f, 0f)
        placed[CanvasIds.SYSTEM] = CanvasPoint(40f, 8f)
        placed[CanvasIds.ROOT] = CanvasPoint(tree.root.x, tree.root.y)
        tree.nodes.forEach { (id, point) ->
            placed[CanvasIds.rule(id)] = CanvasPoint(point.x, point.y)
        }
        val pipeX = (tree.nodes.values.maxOfOrNull { it.x } ?: tree.root.x) + column
        pipes.forEachIndexed { index, name ->
            val id = CanvasIds.pipe(name)
            placed[id] = CanvasPoint(pipeX, 36f + row * (index + 1))
        }
        return placed
    }

    fun encode(layout: Map<String, CanvasPoint>): String = json.encodeToString(layout)

    fun decode(raw: String?): Map<String, CanvasPoint> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, CanvasPoint>>(raw) }.getOrDefault(emptyMap())
    }
}
