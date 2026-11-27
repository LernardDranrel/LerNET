package app.lernet.desktop.expert

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lernet.desktop.RouteEditorCanvas
import app.lernet.desktop.RouteEditorPositioned
import app.lernet.desktop.RouteMapOverview
import app.lernet.desktop.routeEditorDrag
import app.lernet.routing.RouteLayoutNode
import app.lernet.routing.RouteTreeLayout
import app.lernet.routing.policy.*

internal class DesktopRuleBranches(tree: PolicyTree) {
    val ordered = tree.nodes.distinctBy { it.id }.sortedWith(compareBy<PolicyNode> { it.sortIndex }.thenBy { it.id })
    val byId = ordered.associateBy { it.id }
    val children = ordered.groupBy { it.parentId?.takeIf(byId::containsKey) }

    fun path(id: String?): List<PolicyNode> {
        val trail = mutableListOf<PolicyNode>()
        val visited = mutableSetOf<String>()
        var node = byId[id]
        while (node != null && visited.add(node.id)) {
            trail += node
            node = byId[node.parentId]
        }
        return trail.asReversed()
    }

    fun visible(origin: String?, expanded: Set<String>): List<PolicyNode> = buildList {
        fun appendChildren(id: String?) {
            for (child in children[id].orEmpty()) {
                add(child)
                if (child.id in expanded) appendChildren(child.id)
            }
        }
        appendChildren(origin)
    }
}

private const val cardWidth = 216f
private const val cardHeight = 62f

@Composable
internal fun RuleBranchMap(
    tree: PolicyTree,
    branches: DesktopRuleBranches,
    targetName: (PolicyNode) -> String,
    inactiveReason: (PolicyNode) -> String?,
    focusId: String?,
    selectedId: String?,
    expanded: Set<String>,
    collapsedOrigin: Boolean,
    cameraRequest: Int,
    select: (PolicyNode?) -> Unit,
    fold: (String?) -> Unit,
    move: (String, PolicyCanvasPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val rootKey = focusId?.let(PolicyCanvasKeys::node) ?: PolicyCanvasKeys.ROOT
    val visible = remember(branches, focusId, expanded, collapsedOrigin) {
        if (collapsedOrigin) emptyList() else branches.visible(focusId, expanded)
    }
    val layout = remember(visible) {
        RouteTreeLayout.vertical(visible.map { RouteLayoutNode(it.id, it.parentId) }, 80f, 274f, 0f, 0f)
    }
    // Stored coordinates also drive Android and the full canvas. Share the displacement from their
    // automatic layout so folding a branch never rewrites an existing manual arrangement.
    val original = remember(branches) {
        RouteTreeLayout.vertical(branches.ordered.map { RouteLayoutNode(it.id, it.parentId) }, 274f, 146f)
    }
    var dragging by remember(tree.scope, focusId) { mutableStateOf<String?>(null) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }
    fun baseline(id: String?) = if (id == null) original.root else original.nodes.getValue(id)
    fun storedOffset(id: String?): Offset {
        val key = id?.let(PolicyCanvasKeys::node) ?: PolicyCanvasKeys.ROOT
        val base = baseline(id)
        val old = tree.positions[key]?.takeIf { it.isValid() } ?: return Offset.Zero
        return Offset(old.y - base.y, old.x - base.x)
    }
    val cards = buildMap<String, Rect> {
        fun card(id: String?, x: Float, y: Float) {
            val key = id?.let(PolicyCanvasKeys::node) ?: PolicyCanvasKeys.ROOT
            val shift = storedOffset(id) + if (key == dragging) dragDelta else Offset.Zero
            put(key, Rect(Offset(x, y) + shift, Size(cardWidth, cardHeight)))
        }
        card(focusId, layout.root.y, layout.root.x)
        visible.forEach { node -> val p = layout.nodes.getValue(node.id); card(node.id, p.y, p.x) }
    }
    val edges = remember(visible, rootKey) {
        visible.mapNotNull { node ->
            if (node.detached || (node.parentId != null && node.parentId !in branches.byId)) return@mapNotNull null
            val parent = node.parentId?.takeIf { it in branches.byId }?.let(PolicyCanvasKeys::node) ?: rootKey
            parent to PolicyCanvasKeys.node(node.id)
        }
    }
    val bounds = Rect(cards.values.minOf { it.left }, cards.values.minOf { it.top },
        cards.values.maxOf { it.right }, cards.values.maxOf { it.bottom })
    val selectedKey = selectedId?.let(PolicyCanvasKeys::node) ?: PolicyCanvasKeys.ROOT
    val trail = remember(branches, selectedId) { branches.path(selectedId).mapTo(mutableSetOf()) { PolicyCanvasKeys.node(it.id) } }
    val overview = RouteMapOverview(cards, edges, rootKey, selectedKey, dragging != null)
    RouteEditorCanvas(
        owner = Triple(tree.scope, focusId, cameraRequest), bounds = bounds, modifier = modifier, overview = overview,
        connections = { transform ->
            for ((from, to) in edges) {
                val parent = cards.getValue(from)
                val child = cards.getValue(to)
                val start = transform.project(Offset(parent.right, parent.center.y), density)
                val end = transform.project(Offset(child.left, child.center.y), density)
                val mid = (start.x + end.x) / 2
                val path = Path().apply { moveTo(start.x, start.y); cubicTo(mid, start.y, mid, end.y, end.x, end.y) }
                val color = if (to in trail) ExpertColors.blue else Color(0xFF586F86)
                drawPath(path, color, style = Stroke((if (to in trail) 2.dp else 1.4.dp).toPx()))
                drawCircle(color, 2.8.dp.toPx(), end)
            }
        },
    ) { transform ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val upper = -transform.pan / (density * transform.zoom)
            val viewport = Rect(upper, Size(maxWidth.value / transform.zoom, maxHeight.value / transform.zoom))
            for ((key, rect) in cards) {
                if (key != dragging && !rect.overlaps(viewport)) continue
                val node = if (key == PolicyCanvasKeys.ROOT) null else branches.byId[PolicyCanvasKeys.nodeId(key)]
                val id = node?.id
                key(key) {
                    val kids = branches.children[id].orEmpty()
                    val title = node?.let(::policyNodeTitle) ?: "Весь трафик"
                    val inactive = node?.let(inactiveReason)
                    val subtitle = when {
                        inactive != null -> inactive
                        node == null -> "Начало схемы"
                        kids.isNotEmpty() -> "Вложенные правила · ${kids.size}"
                        else -> targetName(node)
                    }
                    RouteEditorPositioned(rect.topLeft, transform, if (key == dragging) 1f else 0f) {
                        val isOrigin = key == rootKey
                        val open = if (isOrigin) !collapsedOrigin else id in expanded
                        BranchCard(title, subtitle, kind = when {
                            node == null -> BranchGlyph.DEVICE
                            kids.isNotEmpty() -> BranchGlyph.BRANCH
                            node.target == PolicyTarget.Block -> BranchGlyph.BLOCK
                            else -> BranchGlyph.EXIT
                        }, selected = key == selectedKey, origin = isOrigin, inactive = inactive != null,
                            select = { select(node) }, fold = if (kids.isEmpty()) null else ({ fold(id) }), open = open,
                            drag = Modifier.routeEditorDrag(key, transform.zoom,
                                onStart = { dragging = key; dragDelta = Offset.Zero },
                                onDrag = { dragDelta += transform.canvasDelta(it, density) },
                                onEnd = {
                                    val shift = storedOffset(id) + dragDelta
                                    val base = baseline(id)
                                    val limit = PolicyCanvasPoint.MAX_COORDINATE
                                    move(key, PolicyCanvasPoint((base.x + shift.y).coerceIn(-limit, limit),
                                        (base.y + shift.x).coerceIn(-limit, limit)))
                                    dragging = null
                                },
                                onCancel = { dragging = null },
                            ))
                    }
                }
            }
        }
    }
}

internal enum class BranchGlyph { DEVICE, BRANCH, EXIT, BLOCK }

@Composable
private fun BranchCard(
    title: String, subtitle: String, kind: BranchGlyph, selected: Boolean, origin: Boolean, inactive: Boolean,
    select: () -> Unit, fold: (() -> Unit)?, open: Boolean, drag: Modifier,
) {
    val tone = when {
        inactive -> ExpertColors.muted
        kind == BranchGlyph.BLOCK -> ExpertColors.amber
        kind == BranchGlyph.EXIT -> ExpertColors.green
        else -> ExpertColors.blue
    }
    val fill = when {
        selected -> Color(0xFF253650)
        origin -> Color(0xFF24364A)
        kind == BranchGlyph.BRANCH -> Color(0xFF1B2839)
        else -> Color(0xFF182532)
    }
    Surface(Modifier.size(cardWidth.dp, cardHeight.dp), color = fill, shape = RoundedCornerShape(8.dp),
        border = BorderStroke(if (selected) 1.7.dp else 1.dp, if (selected) ExpertColors.blue else Color(0xFF43566C))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).fillMaxHeight().then(drag).selectable(selected, role = Role.Button, onClick = select)
                .padding(start = 8.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BranchIcon(kind, tone)
                Column {
                    Text(title, color = if (inactive) ExpertColors.muted else ExpertColors.text, fontSize = 14.sp,
                        lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, color = if (kind == BranchGlyph.EXIT && !inactive) ExpertColors.green else ExpertColors.muted,
                        fontSize = 12.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (fold != null) Box(Modifier.padding(end = 7.dp).size(26.dp).clip(RoundedCornerShape(5.dp))
                .background(Color(0xFF304158)).clickable(role = Role.Button, onClick = fold)
                .semantics { contentDescription = (if (open) "Свернуть" else "Раскрыть") + " · " + title },
                contentAlignment = Alignment.Center) { Text(if (open) "−" else "+", color = ExpertColors.blue, fontSize = 16.sp) }
        }
    }
}

@Composable
internal fun BranchIcon(kind: BranchGlyph, tone: Color) {
    Box(Modifier.size(28.dp).background(tone.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(15.dp)) {
            val u = size.width / 16f
            fun at(x: Int, y: Int) = Offset(x * u, y * u)
            val stroke = Stroke(1.3f * u)
            when (kind) {
                BranchGlyph.DEVICE -> {
                    drawRect(tone, at(1, 2), Size(14 * u, 10 * u), style = stroke)
                    drawLine(tone, at(8, 12), at(8, 15), stroke.width)
                    drawLine(tone, at(5, 15), at(11, 15), stroke.width)
                }
                BranchGlyph.BRANCH -> {
                    drawLine(tone, at(4, 3), at(4, 13), stroke.width)
                    drawLine(tone, at(4, 8), at(12, 3), stroke.width)
                    listOf(at(4, 3), at(4, 13), at(12, 3)).forEach { drawCircle(tone, 2*u, it) }
                }
                BranchGlyph.EXIT -> listOf(2, 9).forEach { y ->
                    drawRect(tone, at(1, y), Size(14*u, 5*u), style = stroke)
                    drawCircle(tone, u, at(12, y + 2))
                }
                BranchGlyph.BLOCK -> {
                    drawCircle(tone, 6.5f*u, center, style = stroke)
                    drawLine(tone, at(3, 13), at(13, 3), stroke.width)
                }
            }
        }
    }
}
