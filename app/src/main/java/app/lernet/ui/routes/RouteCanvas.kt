package app.lernet.ui.routes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.config.repo.RuleNodeRecord
import app.lernet.ui.icons.LerNetSymbols
import app.lernet.ui.theme.LerNetAccent
import app.lernet.ui.theme.LerNetBlack
import app.lernet.ui.theme.LerNetDimens
import app.lernet.ui.theme.LerNetWarn
import app.lernet.ui.theme.lernetButton
import io.github.xingray.compose.infinitecanvas.AnchorPosition
import io.github.xingray.compose.infinitecanvas.CanvasMode
import io.github.xingray.compose.infinitecanvas.CanvasNode
import io.github.xingray.compose.infinitecanvas.CanvasNodeState
import io.github.xingray.compose.infinitecanvas.Connection
import io.github.xingray.compose.infinitecanvas.InfiniteCanvasState
import kotlin.math.roundToInt

internal class LayoutCapture {
    var latest: Map<String, CanvasPoint> = emptyMap()
}

private data class RuleDragGhost(val id: String, val delta: Offset)

@Composable
internal fun RouteCanvas(
    state: RouteEditorUiState,
    capture: LayoutCapture,
    onIntent: (RouteEditorIntent) -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canvasState = rememberSchemaCanvasState(state.ownerId)
    var centerRequest by remember { mutableStateOf(0) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val holders = remember { mutableMapOf<String, CanvasNodeState>() }
    val visible = remember(state.nodes) { RouteFolders.attached(state.nodes) }
    val pipes = remember(visible, state.extraPipes) { CanvasGraph.pipeNames(visible, state.extraPipes) }
    val density = LocalDensity.current
    val column = with(density) { 246.dp.toPx() }
    val row = with(density) { 300.dp.toPx() }
    val ruleWidth = with(density) { 210.dp.toPx() }
    val pipeWidth = with(density) { 180.dp.toPx() }
    val points = remember(visible, pipes, state.layout, column, row, ruleWidth, pipeWidth) {
        CanvasGraph.layout(visible, pipes, state.layout, column, row, ruleWidth, pipeWidth)
    }
    val board = remember { AnchorBoard() }
    var dragGhost by remember { mutableStateOf<RuleDragGhost?>(null) }
    var openChannel by remember(state.ownerId) { mutableStateOf<String?>(null) }
    val freePipes = state.extraPipes.filter { name -> state.nodes.none { it.pipeName == name } }.toSet()
    val nodes = canvasNodes(
        visible, pipes, freePipes, points, holders, board, state.canvasSelection,
        canvasState.viewport.scale, state.routesLocked, canvasState.canvasMode == CanvasMode.Pan,
        onIntent, onDragGhost = { dragGhost = it },
        onOpenChannel = { name ->
            onIntent(RouteEditorIntent.SelectCanvas(CanvasIds.pipe(name)))
            openChannel = name
        }
    )
    SyncCanvasLinks(
        canvasState,
        state.nodes,
        CanvasGraph.isVertical(state.layout),
        state.linkEpoch,
        onIntent,
    )
    LaunchedEffect(canvasState) {
        snapshotFlow { canvasState.selectedNodeIds.firstOrNull() }.collect { id ->
            onIntent(RouteEditorIntent.SelectCanvas(id))
        }
    }
    val knownEdges = remember(visible) { SchemaEdges.edges(visible) }
    LaunchedEffect(knownEdges, state.selectedEdge) {
        val selected = state.selectedEdge ?: return@LaunchedEffect
        if (knownEdges.none { it == selected }) onIntent(RouteEditorIntent.SelectEdge(null))
    }
    capture.latest = points
    LaunchedEffect(points) {
        points.forEach { (id, point) ->
            holders[id]?.let { node ->
                node.x = point.x
                node.y = point.y
            }
        }
    }
    Column(modifier.fillMaxSize()) {
        SchemaCanvasControls(canvasState, onHelp = onHelp, onCenter = { centerRequest++ },
            modifier = Modifier.padding(horizontal = LerNetDimens.screenPadding),
            zoomAnchor = Offset(canvasSize.width / 2f, canvasSize.height / 2f))
        CanvasNotices(state, onIntent)
        CanvasBoard(canvasState, nodes, board, points, visible, canvasState.canvasMode == CanvasMode.Pan, state, dragGhost, centerRequest, canvasSize, { canvasSize = it }, onIntent)
    }
    openChannel?.let { name ->
        ChannelDetailsDialog(name, state.nodes) {
            openChannel = null
            onIntent(RouteEditorIntent.SelectCanvas(null))
        }
    }
}

@Composable
private fun ColumnScope.CanvasBoard(
    canvasState: InfiniteCanvasState,
    nodes: List<CanvasNode>,
    board: AnchorBoard,
    points: Map<String, CanvasPoint>,
    visible: List<RuleNodeRecord>,
    panning: Boolean,
    state: RouteEditorUiState,
    dragGhost: RuleDragGhost?,
    centerRequest: Int,
    canvasSize: IntSize,
    onSize: (IntSize) -> Unit,
    onIntent: (RouteEditorIntent) -> Unit,
) {
    val vertical = CanvasGraph.isVertical(state.layout)
    val viewport = canvasState.viewport
    val scale = viewport.scale
    val offset = viewport.offset
    val rects = canvasScreenRects(board, scale, offset.x, offset.y)
    var handledCenterRequest by remember { mutableStateOf(0) }
    var centered by remember(state.ownerId) { mutableStateOf(false) }
    val density = LocalDensity.current
    LaunchedEffect(canvasSize, state.loading, points[CanvasIds.ROOT], centerRequest) {
        val root = points[CanvasIds.ROOT] ?: return@LaunchedEffect
        if (!centered && viewport.offset != Offset.Zero) centered = true
        if (!state.loading && (!centered || centerRequest != handledCenterRequest) && canvasSize.width > 0) {
            val halfCard = with(density) { 105.dp.toPx() }
            viewport.offset = Offset(
                canvasSize.width / 2f - (root.x + halfCard) * viewport.scale,
                22f - root.y * viewport.scale
            )
            centered = true
            handledCenterRequest = centerRequest
        }
    }
    Box(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .clipToBounds()
            .onSizeChanged(onSize)
            .schemaEdgeInput(
                rects = rects,
                nodes = visible,
                vertical = vertical,
                scale = scale,
                panning = panning,
                selected = state.selectedEdge,
                onSelect = { edge -> onIntent(RouteEditorIntent.SelectEdge(edge)) },
            ),
    ) {
        SchemaCanvas(
            modifier = Modifier.fillMaxSize(),
            state = canvasState,
            nodes = nodes,
        )
        SchemaEdgeLayer(
            rects = rects,
            nodes = visible,
            vertical = vertical,
            scale = scale,
            selected = state.selectedEdge,
            selectedChannel = state.canvasSelection?.takeIf(CanvasIds::isPipe),
            canBreak = !state.routesLocked,
            onBreak = { edge -> onIntent(RouteEditorIntent.RequestBreakEdge(edge)) },
        )
        val draggedNode = dragGhost?.let { ghost -> visible.firstOrNull { it.id == ghost.id } }
        val draggedRect = draggedNode?.let { rects[CanvasIds.rule(it.id)] }
        if (dragGhost != null && draggedNode != null && draggedRect != null) {
            val branching = visible.any { it.parentId == draggedNode.id }
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (draggedRect.left + dragGhost.delta.x * scale).roundToInt(),
                            (draggedRect.top + dragGhost.delta.y * scale).roundToInt(),
                        )
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0f)
                        shadowElevation = 16.dp.toPx()
                    }
                    .width(210.dp)
                    .border(3.dp, LerNetAccent, RoundedCornerShape(8.dp)),
            ) {
                RuleNodeContent(
                    draggedNode, RouteFolders.priorityRank(visible, draggedNode), branching,
                    unavailable = draggedNode.id in androidInactiveRuleIds(visible),
                    onEdit = {}, onDelete = {}
                )
            }
        }
        val selectedNode = visible.firstOrNull { CanvasIds.rule(it.id) == state.canvasSelection }
        val selectedRect = state.canvasSelection?.let { rects[it] }
        val buttonSize = with(density) { LerNetDimens.iconButtonSize.toPx() }
        val buttonGap = with(density) { 12.dp.toPx() }
        val edgeGap = with(density) { 12.dp.toPx() }
        LaunchedEffect(state.canvasSelection, selectedRect != null, canvasSize.height) {
            if (selectedNode != null && selectedRect != null && canvasSize.height > 0) {
                val overflow = selectedRect.bottom + buttonGap + buttonSize + edgeGap - canvasSize.height
                if (overflow > 0f) {
                    viewport.offset = Offset(viewport.offset.x, viewport.offset.y - overflow)
                }
            }
        }
        if (!state.routesLocked && dragGhost == null && selectedNode != null && selectedRect != null) {
            val buttonTop = selectedRect.bottom + buttonGap
            if (selectedRect.bottom > 0f &&
                selectedRect.right > 0f &&
                selectedRect.left < canvasSize.width &&
                buttonTop + buttonSize <= canvasSize.height
            ) {
                IconButton(
                    onClick = { onIntent(RouteEditorIntent.AddChild(selectedNode.id)) },
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                ((selectedRect.left + selectedRect.right - buttonSize) / 2f)
                                    .coerceIn(0f, (canvasSize.width - buttonSize).coerceAtLeast(0f)).roundToInt(),
                                buttonTop.roundToInt(),
                            )
                        }
                        .size(LerNetDimens.iconButtonSize)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                ) {
                    Icon(
                        LerNetSymbols.add(), contentDescription = stringResource(R.string.route_add_child),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

private fun canvasScreenRects(
    board: AnchorBoard,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
): Map<String, NodeRect> {
    val world = mutableMapOf<String, NodeRect>()
    board.sizes.forEach { (id, size) ->
        val node = board.states[id] ?: return@forEach
        world[id] = SchemaEdges.worldRect(node.x, node.y, size.first, size.second)
    }
    return SchemaEdges.screenRects(world, scale, offsetX, offsetY)
}

@Composable
private fun CanvasNotices(state: RouteEditorUiState, onIntent: (RouteEditorIntent) -> Unit) {
    val pad = Modifier.padding(horizontal = LerNetDimens.screenPadding)
    ElseRepairNotice(state.fieldErrors, onIntent, pad)
    if (state.cycleRejected) {
        Text(stringResource(R.string.route_cycle), color = MaterialTheme.colorScheme.error, modifier = pad)
    }
    if (state.terminalRejected || RouteFolders.hasNested(state.nodes)) {
        Text(stringResource(R.string.route_terminal), color = MaterialTheme.colorScheme.error, modifier = pad)
    }
}

@Composable
private fun canvasNodes(
    nodes: List<RuleNodeRecord>,
    pipes: List<String>,
    freePipes: Set<String>,
    points: Map<String, CanvasPoint>,
    holders: MutableMap<String, CanvasNodeState>,
    board: AnchorBoard,
    selectedId: String?,
    scale: Float,
    locked: Boolean,
    panning: Boolean,
    onIntent: (RouteEditorIntent) -> Unit,
    onDragGhost: (RuleDragGhost?) -> Unit,
    onOpenChannel: (String) -> Unit,
): List<CanvasNode> {
    val inactive = androidInactiveRuleIds(nodes)
    val rootState = holder(holders, CanvasIds.ROOT, points, fixed = true)
    board.bind(CanvasIds.ROOT, rootState)
    val root = CanvasNode(
        id = CanvasIds.ROOT,
        modifier = Modifier
            .width(210.dp)
            .then(selectionBorder(selectedId == CanvasIds.ROOT))
            .clickable { onIntent(RouteEditorIntent.SelectCanvas(CanvasIds.ROOT)) }
            .trackNode(board, CanvasIds.ROOT),
        state = rootState,
        content = { RootNode() },
    )
    val rules = nodes.map { node ->
        ruleNode(
            node,
            node.id in inactive,
            RouteFolders.priorityRank(nodes, node),
            nodes.any { it.parentId == node.id },
            board,
            holder(holders, CanvasIds.rule(node.id), points, fixed = true),
            onIntent,
            selected = selectedId == CanvasIds.rule(node.id),
            siblings = RouteFolders.children(nodes, node.parentId),
            points = points,
            scale = scale,
            locked = locked,
            panning = panning,
            onDragGhost = onDragGhost,
        )
    }
    val pipeNodes = pipes.map { name ->
        val sourceCount = channelSources(nodes, name).size
        val pipeState = holder(holders, CanvasIds.pipe(name), points, fixed = true)
        board.bind(CanvasIds.pipe(name), pipeState)
        CanvasNode(
            id = CanvasIds.pipe(name),
            modifier = Modifier
                .width(180.dp)
                .border(2.dp, LerNetWarn, RoundedCornerShape(8.dp))
                .then(selectionBorder(selectedId == CanvasIds.pipe(name)))
                .trackNode(board, CanvasIds.pipe(name)),
            state = pipeState,
            content = {
                SchemaNodeSurface(modifier = Modifier.clickable { onOpenChannel(name) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            name.ifBlank { stringResource(R.string.route_pipe_default) },
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (sourceCount > 1) ChannelPortalMark(sourceCount, Modifier.padding(start = 6.dp))
                        if (name in freePipes) {
                            IconButton(onClick = { onIntent(RouteEditorIntent.RemovePipe(name)) }) {
                                Icon(
                                    LerNetSymbols.delete(),
                                    contentDescription = stringResource(R.string.route_pipe_delete),
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.route_channel_sources, sourceCount),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            },
        )
    }
    return listOf(root) + rules + pipeNodes
}

@Composable
private fun RootNode() {
    SchemaNodeSurface {
        Text(
            stringResource(R.string.route_parent_root),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
        SystemBadge(Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.route_system_why),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun SystemBadge(modifier: Modifier = Modifier) {
    FilterChip(
        selected = true,
        onClick = {},
        enabled = false,
        label = { Text(stringResource(R.string.cfg_overridden)) },
        modifier = modifier.lernetButton(),
        colors = FilterChipDefaults.filterChipColors(
            disabledSelectedContainerColor = LerNetWarn,
            disabledLabelColor = LerNetBlack,
        ),
    )
}

@Composable
internal fun SystemRulesCard(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = LerNetDimens.cardGap),
        verticalArrangement = Arrangement.spacedBy(LerNetDimens.cardGap),
    ) {
        Text(stringResource(R.string.route_system_strip), style = MaterialTheme.typography.titleSmall)
        SystemBadge()
        Text(
            stringResource(R.string.route_system_why),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun selectionBorder(selected: Boolean): Modifier {
    if (!selected) return Modifier
    return Modifier.border(3.dp, LerNetAccent, RoundedCornerShape(8.dp))
}

@Composable
private fun ruleNode(
    node: RuleNodeRecord,
    unavailable: Boolean,
    rank: Int,
    branching: Boolean,
    board: AnchorBoard,
    nodeState: CanvasNodeState,
    onIntent: (RouteEditorIntent) -> Unit,
    selected: Boolean,
    siblings: List<RuleNodeRecord>,
    points: Map<String, CanvasPoint>,
    scale: Float,
    locked: Boolean,
    panning: Boolean,
    onDragGhost: (RuleDragGhost?) -> Unit,
): CanvasNode {
    var dragging by remember(node.id) { mutableStateOf(false) }
    var dragDelta by remember(node.id) { mutableStateOf(Offset.Zero) }
    val latestIntent by rememberUpdatedState(onIntent)
    val latestGhost by rememberUpdatedState(onDragGhost)
    val siblingIds = siblings.filterNot { it.isElseRule() }.map { it.id }
    val drag = if (locked || panning || node.isElseRule()) {
        Modifier
    } else {
        Modifier.pointerInput(node.id, siblingIds, scale) {
            var origin = Offset.Zero
            detectDragGestures(
                onDragStart = {
                    origin = Offset(nodeState.x, nodeState.y)
                    dragDelta = Offset.Zero
                    dragging = true
                    latestGhost(RuleDragGhost(node.id, dragDelta))
                    latestIntent(RouteEditorIntent.SelectCanvas(CanvasIds.rule(node.id)))
                },
                onDrag = { change, amount ->
                    change.consume()
                    dragDelta += amount
                    latestGhost(RuleDragGhost(node.id, dragDelta))
                },
                onDragEnd = {
                    val landedX = origin.x + dragDelta.x
                    val to = siblingIds.filterNot { it == node.id }.count { id ->
                        (points[CanvasIds.rule(id)]?.x ?: Float.MAX_VALUE) < landedX
                    }
                    dragging = false
                    latestGhost(null)
                    val from = siblingIds.indexOf(node.id)
                    if (from != to) {
                        latestIntent(RouteEditorIntent.ReorderSiblings(node.parentId, movedIds(siblingIds, from, to)))
                    }
                },
                onDragCancel = {
                    dragging = false
                    latestGhost(null)
                },
            )
        }
    }
    board.bind(CanvasIds.rule(node.id), nodeState)
    return CanvasNode(
        id = CanvasIds.rule(node.id),
        modifier = Modifier
            .width(210.dp)
            .graphicsLayer { alpha = if (dragging) .35f else 1f }
            .border(
                2.dp,
                if (unavailable ||
                    !node.enabled
                ) {
                    MaterialTheme.colorScheme.outline
                } else if (branching) {
                    LerNetAccent
                } else {
                    routeTone(node.action, node.pipeName).ink()
                },
                RoundedCornerShape(8.dp)
            )
            .then(selectionBorder(selected || dragging))
            .then(drag)
            .clickable { onIntent(RouteEditorIntent.SelectCanvas(CanvasIds.rule(node.id))) }
            .trackNode(board, CanvasIds.rule(node.id)),
        state = nodeState,
        content = {
            RuleNodeContent(
                node, rank, branching,
                unavailable = unavailable,
                onEdit = { onIntent(RouteEditorIntent.Edit(node.id)) },
                onDelete = { onIntent(RouteEditorIntent.RequestDelete(node.id)) }
            )
        },
    )
}

@Composable
private fun RuleNodeContent(
    node: RuleNodeRecord,
    rank: Int,
    branching: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    unavailable: Boolean = false
) {
    SchemaNodeSurface {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                ruleHeadline(node),
                color = if (unavailable ||
                    !node.enabled
                ) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!node.isElseRule()) {
                Text(
                    rank.toString(),
                    color = if (unavailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        if (node.isElseRule() && node.title.isNotBlank()) {
            Text(
                node.title.trim(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RulePreviewLines(node, MaterialTheme.colorScheme.onSurfaceVariant)
        if (unavailable) {
            Text(
                stringResource(R.string.route_windows_only),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
            )
        } else {
            OutcomeStub(node.action, node.pipeName, branching)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onEdit,
                modifier = Modifier.weight(1f).lernetButton(),
            ) {
                Icon(LerNetSymbols.edit(), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.canvas_properties))
            }
            IconButton(onClick = onDelete) {
                Icon(
                    LerNetSymbols.delete(),
                    contentDescription = stringResource(R.string.rule_delete_node),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun holder(
    holders: MutableMap<String, CanvasNodeState>,
    id: String,
    points: Map<String, CanvasPoint>,
    fixed: Boolean,
): CanvasNodeState {
    val point = points[id] ?: CanvasPoint(80f, 80f)
    return holders.getOrPut(id) { CanvasNodeState(point.x, point.y, fixed) }
}

@Composable
private fun SyncCanvasLinks(
    canvasState: InfiniteCanvasState,
    nodes: List<RuleNodeRecord>,
    vertical: Boolean,
    linkEpoch: Int,
    onIntent: (RouteEditorIntent) -> Unit,
) {
    val latest by rememberUpdatedState(nodes)
    val desired = remember(nodes) { CanvasGraph.links(nodes) }
    var seeded by remember { mutableStateOf(false) }
    var seededVertical by remember { mutableStateOf(vertical) }
    LaunchedEffect(desired, vertical, linkEpoch) {
        val current = canvasState.connections.map { CanvasLink(it.fromElementId, it.toElementId) }.toSet()
        if (seeded && current == desired && seededVertical == vertical) return@LaunchedEffect
        seeded = false
        canvasState.connections.toList().forEach { canvasState.removeConnection(it.id) }
        desired.forEach { link ->
            val toPipe = CanvasIds.isPipe(link.toId)
            val parentChild = vertical && !toPipe
            canvasState.addConnection(
                Connection(
                    fromElementId = link.fromId,
                    fromAnchor = if (parentChild) AnchorPosition.Bottom else AnchorPosition.Right,
                    toElementId = link.toId,
                    toAnchor = if (parentChild) AnchorPosition.Top else AnchorPosition.Left,
                ),
            )
        }
        seededVertical = vertical
        seeded = true
    }
    LaunchedEffect(canvasState) {
        snapshotFlow { canvasState.connections.map { CanvasLink(it.fromElementId, it.toElementId) }.toSet() }
            .collect { links ->
                if (!seeded || links.isEmpty()) return@collect
                val applied = CanvasGraph.applyLinks(latest, links)
                val rejected = applied.rejectedCycle || applied.rejectedTerminal
                if (applied.nodes != latest || rejected) {
                    onIntent(
                        RouteEditorIntent.ApplyGraph(
                            applied.nodes,
                            applied.rejectedCycle,
                            applied.rejectedTerminal,
                        ),
                    )
                }
            }
    }
}
