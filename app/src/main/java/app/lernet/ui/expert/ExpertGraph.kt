package app.lernet.ui.expert

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import app.lernet.ui.icons.LerNetSymbols
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.IntSize
import app.lernet.ui.routes.SchemaCanvas
import app.lernet.ui.routes.SchemaCanvasControls
import app.lernet.ui.routes.rememberSchemaCanvasState
import io.github.xingray.compose.infinitecanvas.CanvasNode
import io.github.xingray.compose.infinitecanvas.CanvasNodeState
import io.github.xingray.compose.infinitecanvas.CanvasMode
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.config.transfer.TransferBundle
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyBranchEditing
import app.lernet.routing.policy.PolicyCanvasKeys
import app.lernet.routing.policy.PolicyCanvasPoint
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyOtherwise
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.PolicyTree
import app.lernet.ui.motion.rememberReduceMotion
import app.lernet.ui.routes.SchemaNodeSurface
import app.lernet.ui.theme.LerNetDimens
import kotlin.math.roundToInt

private const val NODE_WIDTH = 216f
private const val NODE_HEIGHT = 144f
private const val COLUMN_GAP = 50f
private const val ROW_GAP = 80f

@Composable
internal fun ExpertGraph(
    sourceTree: PolicyTree,
    bundle: TransferBundle,
    policy: NetworkPolicy,
    inactive: Set<String>,
    onEdit: (PolicyNode) -> Unit,
    onChannel: (String) -> Unit,
    onRoot: () -> Unit,
    onPosition: (String, PolicyCanvasPoint) -> Unit,
    onAlign: () -> Unit,
    modifier: Modifier = Modifier.height(460.dp),
    selectedKey: String? = null,
    onSelect: ((String) -> Unit)? = null,
    onAdd: ((PolicyNode?) -> Unit)? = null,
    nodeActions: (@Composable (PolicyNode?) -> Unit)? = null,
    highlightedNodes: Set<String> = emptySet(),
    highlightedChannels: Set<String> = emptySet(),
    highlightRoot: Boolean = false,
    readOnly: Boolean = false,
) {
    val tree = remember(sourceTree) { PolicyBranchEditing.displayTree(sourceTree) }
    val shownHighlights = if (highlightRoot && tree.nodes.none { it.id in highlightedNodes }) {
        tree.nodes.filter { it.parentId == null && PolicyOtherwise.isOtherwise(it) }.map { it.id }.toSet()
    } else {
        highlightedNodes
    }
    val nodeHeight = (NODE_HEIGHT + if (readOnly) 0f else 64f) * LocalDensity.current.fontScale.coerceAtLeast(1f)
    val density = LocalDensity.current.density
    val canvasState = rememberSchemaCanvasState(tree.scope)
    // Policy selection and branches belong to the model, not the library's transient connection tool.
    // Prevent a canvas-only edge from looking like a stored routing rule, including read-only previews.
    LaunchedEffect(canvasState) {
        snapshotFlow { canvasState.selectedNodeIds }.collect { ids ->
            if (ids.isNotEmpty()) canvasState.selectedNodeIds = emptySet()
        }
    }
    LaunchedEffect(canvasState) {
        snapshotFlow { canvasState.connections.map { it.id } }.collect { ids ->
            ids.forEach(canvasState::removeConnection)
        }
    }
    val holders = remember(tree.scope) { mutableMapOf<String, CanvasNodeState>() }
    val dragged = remember(tree.scope) { mutableStateMapOf<String, Offset>() }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var centerAfterAlign by remember(tree.scope) { mutableStateOf(false) }
    var centered by rememberSaveable(tree.scope) { mutableStateOf(false) }
    val automatic = remember(tree.nodes, nodeHeight) { graphPositions(tree, nodeHeight) }
    val channelNodes = policy.channels.filter { it.owner == tree.scope }
    val maxDepth = ExpertEdits.ordered(tree).maxOfOrNull { it.second } ?: 0
    val channelY = (maxDepth + 2) * (nodeHeight + ROW_GAP) + 24f
    val base = automatic + channelNodes.mapIndexed { index, channel ->
        PolicyCanvasKeys.channel(channel.id) to Offset(24f + index * (NODE_WIDTH + COLUMN_GAP), channelY)
    } + tree.positions.mapValues { Offset(it.value.x, it.value.y) }
    val latestBase by rememberUpdatedState(base)
    val latestCommit by rememberUpdatedState(onPosition)
    fun world(key: String): Offset = dragged[key] ?: latestBase[key] ?: Offset(24f, 24f)
    LaunchedEffect(tree.positions) {
        dragged.keys.toList().forEach { key ->
            val stored = tree.positions[key]
            if (stored != null && dragged[key] == Offset(stored.x, stored.y)) dragged.remove(key)
        }
    }
    val lineColor = MaterialTheme.colorScheme.outline
    val pathColor = MaterialTheme.colorScheme.primary
    val reducedMotion = rememberReduceMotion()
    val pathProgress = remember { Animatable(1f) }
    LaunchedEffect(highlightedNodes, highlightedChannels, highlightRoot, reducedMotion) {
        if (reducedMotion || !highlightRoot) pathProgress.snapTo(1f) else {
            pathProgress.snapTo(0.3f)
            pathProgress.animateTo(1f, tween(220))
        }
    }
    fun draggable(key: String): Modifier = Modifier.pointerInput(key, density, readOnly, canvasState.canvasMode) {
        if (!readOnly && canvasState.canvasMode != CanvasMode.Pan) detectDragGesturesAfterLongPress(
            onDragEnd = { dragged[key]?.let { latestCommit(key, PolicyCanvasPoint(it.x, it.y)) } },
            onDragCancel = { dragged.remove(key) },
        ) { change, delta ->
            change.consume()
            // Pointer coordinates are local to the scaled node; retain world coordinates in dp.
            val next = world(key) + delta / density
            if (PolicyCanvasPoint(next.x, next.y).isValid()) dragged[key] = next
        }
    }
    fun cardModifier(): Modifier = Modifier.size(NODE_WIDTH.dp, nodeHeight.dp)
    fun canvasNode(key: String, content: @Composable () -> Unit): CanvasNode {
        val position = world(key) * density
        val holder = holders.getOrPut(key) { CanvasNodeState(position.x, position.y, fixed = true) }
        return CanvasNode(key, state = holder, modifier = draggable(key), content = content)
    }
    val nodes = buildList {
        add(canvasNode(PolicyCanvasKeys.ROOT) {
            Card(
                onClick = { onSelect?.invoke(PolicyCanvasKeys.ROOT) ?: onRoot() },
                modifier = cardModifier(),
                border = if (selectedKey == PolicyCanvasKeys.ROOT || highlightRoot) BorderStroke(2.dp, pathColor) else null,
            ) {
                SchemaNodeSurface(Modifier.fillMaxWidth().weight(1f)) {
                    Text(
                        scopeTitle(tree.scope, bundle), style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(stringResource(R.string.expert_branching_root), style = MaterialTheme.typography.bodySmall)
                }
                if (!readOnly) nodeActions?.invoke(null)
            }
        })
        tree.nodes.forEach { node ->
            val inactiveNode = !node.enabled || node.detached || node.id in inactive
            val fill = when {
                inactiveNode -> MaterialTheme.colorScheme.surfaceVariant
                node.target is PolicyTarget.Channel -> MaterialTheme.colorScheme.tertiaryContainer
                node.protected -> MaterialTheme.colorScheme.secondaryContainer
                PolicyOtherwise.isOtherwise(node) -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHighest
            }
            add(canvasNode(PolicyCanvasKeys.node(node.id)) {
                Card(
                    onClick = { onSelect?.invoke(PolicyCanvasKeys.node(node.id)) ?: onEdit(node) },
                    colors = CardDefaults.cardColors(containerColor = fill),
                    modifier = cardModifier(),
                    border = if (selectedKey == PolicyCanvasKeys.node(node.id) || node.id in shownHighlights) {
                        BorderStroke(2.dp, pathColor)
                    } else {
                        null
                    },
                ) {
                    SchemaNodeSurface(Modifier.fillMaxWidth().weight(1f), containerColor = fill) {
                        val nodeTitle = if (PolicyOtherwise.isOtherwise(node)) {
                            stringResource(R.string.expert_otherwise_title)
                        } else {
                            node.title.ifBlank { stringResource(R.string.expert_rule_unnamed) }
                        }
                        Text(
                            nodeTitle,
                            style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (tree.nodes.any { it.parentId == node.id && !it.detached }) {
                                stringResource(R.string.expert_branching_root)
                            } else {
                                targetTitle(node.target, bundle, policy)
                            },
                            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        if (PolicyOtherwise.isOtherwise(node)) {
                            Text(
                                stringResource(R.string.expert_otherwise_hint),
                                style = MaterialTheme.typography.labelSmall, maxLines = 2,
                            )
                        }
                        if (inactiveNode) {
                            Text(
                                stringResource(
                                    when {
                                        node.detached -> R.string.expert_detached
                                        !node.enabled -> R.string.expert_rule_disabled
                                        else -> R.string.expert_inactive_android
                                    }
                                ),
                                style = MaterialTheme.typography.labelSmall, maxLines = 2
                            )
                        }
                    }
                    if (!readOnly) nodeActions?.invoke(node)
                }
            })
        }
        channelNodes.forEach { channel ->
            add(canvasNode(PolicyCanvasKeys.channel(channel.id)) {
                Card(
                    onClick = { onChannel(channel.id) }, modifier = cardModifier(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    border = if (channel.id in highlightedChannels) BorderStroke(2.dp, pathColor) else null,
                ) {
                    SchemaNodeSurface(Modifier.fillMaxWidth().weight(1f), containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                        Text(
                            stringResource(R.string.expert_channel, channel.name),
                            style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            targetTitle(channel.target, bundle, policy), style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(
                                R.string.expert_observation_rule,
                                tree.nodes.count {
                                    (it.target as? PolicyTarget.Channel)?.id ==
                                        channel.id
                                }
                            ),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if (!readOnly) IconButton({ onChannel(channel.id) }) {
                        Icon(LerNetSymbols.more(), contentDescription = stringResource(R.string.expert_node_options))
                    }
                }
            })
        }
    }
    LaunchedEffect(base, dragged.toMap(), density) {
        holders.keys.retainAll(nodes.map { it.id }.toSet())
        nodes.forEach { node ->
            val position = world(node.id) * density
            node.state.x = position.x; node.state.y = position.y
        }
    }
    fun centerRoot() {
        if (viewportSize.width > 0) {
            val root = world(PolicyCanvasKeys.ROOT) * density
            canvasState.viewport.offset = Offset(
                viewportSize.width / 2f - (root.x + NODE_WIDTH * density / 2f) * canvasState.viewport.scale,
                16f * density - root.y * canvasState.viewport.scale,
            )
            centered = true
        }
    }
    LaunchedEffect(tree.scope, viewportSize) { if (!centered) centerRoot() }
    LaunchedEffect(tree.positions) {
        if (centerAfterAlign && tree.positions.isEmpty()) { centerRoot(); centerAfterAlign = false }
    }
    Column(modifier.padding(horizontal = LerNetDimens.screenPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SchemaCanvasControls(canvasState, onCenter = ::centerRoot,
            zoomAnchor = Offset(viewportSize.width / 2f, viewportSize.height / 2f),
            onAlign = if (readOnly) null else ({
                dragged.clear()
                centerAfterAlign = tree.positions.isNotEmpty()
                onAlign()
                if (!centerAfterAlign) centerRoot()
            }))
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds().onSizeChanged { viewportSize = it }) {
            SchemaCanvas(canvasState, nodes, Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val renderDensity = density * canvasState.viewport.scale
                val nodeExtent = tree.nodes.maxOfOrNull { world(PolicyCanvasKeys.node(it.id)).x + NODE_WIDTH } ?: NODE_WIDTH
                fun point(key: String): Offset = world(key) * renderDensity + canvasState.viewport.offset
                fun edge(from: String, to: String) {
                    val start = point(from) + Offset(NODE_WIDTH * renderDensity / 2, nodeHeight * renderDensity)
                    val end = point(to) + Offset(NODE_WIDTH * renderDensity / 2, 0f)
                    val middle = (start.y + end.y) / 2f
                    drawPath(
                        Path().apply {
                            moveTo(start.x, start.y)
                            lineTo(start.x, middle)
                            lineTo(end.x, middle)
                            lineTo(end.x, end.y)
                        },
                        if (to.removePrefix("node:") in shownHighlights ||
                            tree.nodes.any { PolicyCanvasKeys.node(it.id) == to && it.id in shownHighlights }
                        ) {
                            pathColor.copy(alpha = pathProgress.value)
                        } else {
                            lineColor
                        },
                        style = Stroke(
                            if (tree.nodes.any { PolicyCanvasKeys.node(it.id) == to && it.id in shownHighlights }) {
                                3.dp.toPx()
                            } else {
                                1.5.dp.toPx()
                            }
                        ),
                    )
                }
                fun portal(from: String, channelId: String) {
                    val start = point(from) + Offset(NODE_WIDTH * renderDensity, nodeHeight * renderDensity / 2)
                    val end = point(PolicyCanvasKeys.channel(channelId)) +
                        Offset(NODE_WIDTH * renderDensity, nodeHeight * renderDensity / 2)
                    val lane =
                        (nodeExtent + 40f + channelNodes.indexOfFirst { it.id == channelId } * 16f) * renderDensity + canvasState.viewport.offset.x
                    drawPath(
                        Path().apply {
                            moveTo(start.x, start.y)
                            lineTo(start.x + 20f * renderDensity, start.y)
                            lineTo(start.x + 20f * renderDensity, start.y + nodeHeight * renderDensity / 2 + 20f * renderDensity)
                            lineTo(lane, start.y + nodeHeight * renderDensity / 2 + 20f * renderDensity)
                            lineTo(lane, end.y)
                            lineTo(end.x, end.y)
                        },
                        if (channelId in highlightedChannels) pathColor.copy(alpha = pathProgress.value) else lineColor,
                        style = Stroke(if (channelId in highlightedChannels) 3.dp.toPx() else 1.5.dp.toPx()),
                    )
                }
                tree.nodes.forEach { node ->
                    if (!node.detached) {
                        edge(
                            node.parentId?.let(PolicyCanvasKeys::node)?.takeIf { it in automatic }
                                ?: PolicyCanvasKeys.ROOT,
                            PolicyCanvasKeys.node(node.id)
                        )
                    }
                    (node.target as? PolicyTarget.Channel)?.id?.takeIf { id -> channelNodes.any { it.id == id } }
                        ?.let { portal(PolicyCanvasKeys.node(node.id), it) }
                }
            }
            if (!readOnly && onAdd != null && selectedKey != null) {
                val selectedNode = tree.nodes.firstOrNull { PolicyCanvasKeys.node(it.id) == selectedKey }
                if (selectedKey == PolicyCanvasKeys.ROOT || selectedNode != null && ExpertEdits.canAddChild(selectedNode)) {
                    val bottom = canvasState.viewport.worldToScreen(
                        (world(selectedKey) + Offset(NODE_WIDTH / 2f, nodeHeight)) * density,
                    )
                    val point = bottom + Offset(-24f * density, 8f * density)
                    FilledIconButton({ onAdd(selectedNode) }, Modifier
                        .offset { IntOffset(point.x.roundToInt(), point.y.roundToInt()) }.size(48.dp)) {
                        Icon(app.lernet.ui.icons.LerNetSymbols.add(), contentDescription = stringResource(R.string.expert_add_child))
                    }
                }
            }
        }
    }
}

/** Top-down layout centers each parent over its children; root and channels have their own rows. */
private fun graphPositions(tree: PolicyTree, nodeHeight: Float): Map<String, Offset> {
    val children = tree.nodes.groupBy { it.parentId }
    val result = linkedMapOf<String, Offset>()
    val visiting = mutableSetOf<String>()
    var nextLeaf = 0
    fun place(node: PolicyNode, depth: Int): Float {
        if (!visiting.add(node.id)) return nextLeaf.toFloat()
        val descendants = children[node.id].orEmpty().sortedWith(compareBy<PolicyNode> { it.sortIndex }.thenBy { it.id })
        val center = if (descendants.isEmpty()) {
            nextLeaf++.toFloat()
        } else {
            descendants.map { place(it, depth + 1) }.average().toFloat()
        }
        result[PolicyCanvasKeys.node(node.id)] = Offset(
            24f + center * (NODE_WIDTH + COLUMN_GAP), 24f + depth * (nodeHeight + ROW_GAP),
        )
        return center
    }
    val roots = children[null].orEmpty().sortedWith(compareBy<PolicyNode> { it.sortIndex }.thenBy { it.id })
    val centers = roots.map { place(it, 1) }
    tree.nodes.filterNot { PolicyCanvasKeys.node(it.id) in result }.forEach { place(it, 1) }
    result[PolicyCanvasKeys.ROOT] =
        Offset(24f + (centers.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f) * (NODE_WIDTH + COLUMN_GAP), 24f)
    return result
}
