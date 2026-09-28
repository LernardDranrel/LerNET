package app.lernet.ui.routes

import app.lernet.config.repo.RuleNodeRecord
import kotlin.math.abs
import kotlin.math.sqrt

enum class SchemaEdgeKind {
    TREE,
    PIPE,
}

data class SchemaEdge(val kind: SchemaEdgeKind, val fromId: String, val toId: String)

enum class EdgeConfirm {
    NONE,
    ELSE,
    PIPE,
}

data class NodeRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    fun shifted(dx: Float, dy: Float): NodeRect = NodeRect(left + dx, top + dy, right + dx, bottom + dy)
}

/** Same mapping as the canvas library: screen = world * scale + offset. */
internal fun NodeRect.toScreen(scale: Float, offsetX: Float, offsetY: Float): NodeRect = NodeRect(
    left * scale + offsetX,
    top * scale + offsetY,
    right * scale + offsetX,
    bottom * scale + offsetY,
)

data class SchemaSegment(
    val edge: SchemaEdge,
    val x0: Float,
    val y0: Float,
    val c1x: Float,
    val c1y: Float,
    val c2x: Float,
    val c2y: Float,
    val x1: Float,
    val y1: Float,
    val straight: Boolean,
    val orthogonal: Boolean = false,
)

object SchemaEdges {
    fun worldRect(x: Float, y: Float, width: Float, height: Float): NodeRect =
        NodeRect(x, y, x + width, y + height)

    fun screenRects(
        world: Map<String, NodeRect>,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
    ): Map<String, NodeRect> = world.mapValues { (_, rect) -> rect.toScreen(scale, offsetX, offsetY) }

    fun edges(nodes: List<RuleNodeRecord>): List<SchemaEdge> {
        val tree = CanvasGraph.links(nodes).map { SchemaEdge(SchemaEdgeKind.TREE, it.fromId, it.toId) }
        val pipes = CanvasGraph.namedPipeLinks(nodes).map { SchemaEdge(SchemaEdgeKind.PIPE, it.fromId, it.toId) }
        return tree + pipes
    }

    /** Pipe connector is the named «В обход» path. A tree edge takes the child outcome. */
    fun tone(nodes: List<RuleNodeRecord>, edge: SchemaEdge): RouteTone {
        if (edge.kind == SchemaEdgeKind.PIPE) return RouteTone.NAMED
        val id = edge.toId.takeIf(CanvasIds::isRule)?.let(CanvasIds::ruleKey)
        val node = id?.let { key -> nodes.firstOrNull { it.id == key } }
        return if (node == null) RouteTone.VIA else routeTone(node.action, node.pipeName)
    }

    fun segments(
        nodes: List<RuleNodeRecord>,
        rects: Map<String, NodeRect>,
        vertical: Boolean,
        scale: Float,
    ): List<SchemaSegment> {
        val all = edges(nodes)
        val pipeRanks = all.filter { it.kind == SchemaEdgeKind.PIPE }
            .groupBy { it.toId }
            .values.flatMap { incoming ->
                incoming.sortedBy { edge -> rects[edge.fromId]?.let { (it.left + it.right) / 2f } ?: 0f }
                    .mapIndexed { index, edge -> edge to (index to incoming.size) }
            }.toMap()
        return all.mapNotNull { edge ->
            val from = rects[edge.fromId] ?: return@mapNotNull null
            val to = rects[edge.toId] ?: return@mapNotNull null
            when (edge.kind) {
                SchemaEdgeKind.TREE -> curve(edge, from, to, vertical, scale)
                SchemaEdgeKind.PIPE -> {
                    val (index, count) = pipeRanks[edge] ?: (0 to 1)
                    straight(edge, from, to, vertical, index, count)
                }
            }
        }
    }

    fun pick(
        segments: List<SchemaSegment>,
        cards: Collection<NodeRect>,
        x: Float,
        y: Float,
        slop: Float,
    ): SchemaEdge? {
        if (cards.any { it.contains(x, y) }) return null
        var best: SchemaEdge? = null
        var bestDistance = slop
        segments.forEach { segment ->
            val distance = distanceToCurve(segment, x, y)
            if (distance <= bestDistance) {
                bestDistance = distance
                best = segment.edge
            }
        }
        return best
    }

    fun pointAt(segment: SchemaSegment, t: Float): Pair<Float, Float> {
        if (segment.orthogonal) {
            val middleY = (segment.y0 + segment.y1) / 2f
            return when {
                t < 1f / 3f -> segment.x0 to segment.y0 + (middleY - segment.y0) * t * 3f
                t < 2f / 3f -> segment.x0 + (segment.x1 - segment.x0) * (t * 3f - 1f) to middleY
                else -> segment.x1 to middleY + (segment.y1 - middleY) * (t * 3f - 2f)
            }
        }
        if (segment.straight) {
            val x = segment.x0 + (segment.x1 - segment.x0) * t
            val y = segment.y0 + (segment.y1 - segment.y0) * t
            return x to y
        }
        val u = 1f - t
        val uu = u * u
        val tt = t * t
        val x = uu * u * segment.x0 + 3f * uu * t * segment.c1x + 3f * u * tt * segment.c2x + tt * t * segment.x1
        val y = uu * u * segment.y0 + 3f * uu * t * segment.c1y + 3f * u * tt * segment.c2y + tt * t * segment.y1
        return x to y
    }

    fun confirmBeforeBreak(nodes: List<RuleNodeRecord>, edge: SchemaEdge): EdgeConfirm = when (edge.kind) {
        SchemaEdgeKind.PIPE -> EdgeConfirm.PIPE
        SchemaEdgeKind.TREE -> {
            val child = ruleId(edge.toId)?.let { id -> nodes.firstOrNull { it.id == id } }
            if (child?.isElseRule() == true) EdgeConfirm.ELSE else EdgeConfirm.NONE
        }
    }

    fun afterTreeBreak(
        nodes: List<RuleNodeRecord>,
        ownerId: String,
        childId: String,
        newId: () -> String,
    ): List<RuleNodeRecord> {
        val current = nodes.firstOrNull { it.id == childId }
        val moved = if (current == null) {
            nodes
        } else {
            nodes.map { node -> if (node.id == childId) node.copy(parentId = RouteFolders.ORPHAN) else node }
        }
        val extra = if (current?.isElseRule() == true) setOf(current.parentId) else emptySet()
        return RouteFolders.seedMissingElse(moved, ownerId, newId, extra)
    }

    fun afterPipeBreak(nodes: List<RuleNodeRecord>, ruleId: String): List<RuleNodeRecord> =
        nodes.map { node -> if (node.id == ruleId) node.copy(pipeName = "") else node }

    private fun ruleId(canvasId: String): String? =
        canvasId.takeIf(CanvasIds::isRule)?.let(CanvasIds::ruleKey)

    private fun curve(
        edge: SchemaEdge,
        from: NodeRect,
        to: NodeRect,
        vertical: Boolean,
        scale: Float,
    ): SchemaSegment {
        val fromSide = if (vertical) AnchorSide.BOTTOM else AnchorSide.RIGHT
        val toSide = if (vertical) AnchorSide.TOP else AnchorSide.LEFT
        val start = anchor(from, fromSide)
        val end = anchor(to, toSide)
        val gap = if (vertical) end.second - start.second else end.first - start.first
        val pull = bezierPull(start.first, start.second, end.first, end.second, scale)
            .coerceAtMost((gap / 2f).coerceAtLeast(0f))
        val c1 = control(start.first, start.second, fromSide, pull)
        val c2 = control(end.first, end.second, toSide, pull)
        return SchemaSegment(
            edge,
            start.first,
            start.second,
            c1.first,
            c1.second,
            c2.first,
            c2.second,
            end.first,
            end.second,
            straight = false,
        )
    }

    private fun straight(edge: SchemaEdge, from: NodeRect, to: NodeRect, vertical: Boolean,
        inletIndex: Int, inletCount: Int): SchemaSegment {
        val start = anchor(from, if (vertical) AnchorSide.BOTTOM else AnchorSide.RIGHT)
        val end = if (vertical) {
            val inletX = to.left + (to.right - to.left) * (inletIndex + 1) / (inletCount + 1)
            inletX to to.top
        } else anchor(to, AnchorSide.LEFT)
        return SchemaSegment(
            edge,
            start.first,
            start.second,
            start.first,
            start.second,
            end.first,
            end.second,
            end.first,
            end.second,
            straight = true,
            orthogonal = vertical && abs(start.first - end.first) > 1f,
        )
    }

    private fun anchor(rect: NodeRect, side: AnchorSide): Pair<Float, Float> {
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        return when (side) {
            AnchorSide.TOP -> cx to rect.top
            AnchorSide.BOTTOM -> cx to rect.bottom
            AnchorSide.LEFT -> rect.left to cy
            AnchorSide.RIGHT -> rect.right to cy
        }
    }

    private fun control(x: Float, y: Float, side: AnchorSide, pull: Float): Pair<Float, Float> = when (side) {
        AnchorSide.TOP -> x to y - pull
        AnchorSide.BOTTOM -> x to y + pull
        AnchorSide.LEFT -> x - pull to y
        AnchorSide.RIGHT -> x + pull to y
    }

    private fun bezierPull(x0: Float, y0: Float, x1: Float, y1: Float, scale: Float): Float {
        val span = abs(x1 - x0) + abs(y1 - y0)
        return (span * 0.4f).coerceIn(30f * scale, 200f * scale)
    }

    private fun distanceToCurve(segment: SchemaSegment, x: Float, y: Float): Float {
        if (segment.orthogonal) {
            val middleY = (segment.y0 + segment.y1) / 2f
            return minOf(
                distanceToSegment(x, y, segment.x0, segment.y0, segment.x0, middleY),
                distanceToSegment(x, y, segment.x0, middleY, segment.x1, middleY),
                distanceToSegment(x, y, segment.x1, middleY, segment.x1, segment.y1),
            )
        }
        if (segment.straight) {
            return distanceToSegment(x, y, segment.x0, segment.y0, segment.x1, segment.y1)
        }
        var best = Float.MAX_VALUE
        var previous = pointAt(segment, 0f)
        val steps = 32
        for (step in 1..steps) {
            val next = pointAt(segment, step / steps.toFloat())
            val distance = distanceToSegment(x, y, previous.first, previous.second, next.first, next.second)
            if (distance < best) best = distance
            previous = next
        }
        return best
    }

    private fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lengthSq = dx * dx + dy * dy
        val raw = if (lengthSq == 0f) 0f else ((px - ax) * dx + (py - ay) * dy) / lengthSq
        val t = raw.coerceIn(0f, 1f)
        val x = ax + dx * t
        val y = ay + dy * t
        val ox = px - x
        val oy = py - y
        return sqrt(ox * ox + oy * oy)
    }

    private enum class AnchorSide {
        TOP,
        BOTTOM,
        LEFT,
        RIGHT,
    }
}
