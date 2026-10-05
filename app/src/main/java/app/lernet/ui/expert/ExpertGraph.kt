package app.lernet.ui.expert

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.config.transfer.TransferBundle
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyCanvasKeys
import app.lernet.routing.policy.PolicyCanvasPoint
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.PolicyTree
import kotlin.math.roundToInt

private const val NODE_WIDTH = 216f
private const val NODE_HEIGHT = 132f
private const val COLUMN_GAP = 50f
private const val ROW_GAP = 80f

@Composable
internal fun ExpertGraph(
    tree: PolicyTree,
    bundle: TransferBundle,
    policy: NetworkPolicy,
    inactive: Set<String>,
    onEdit: (PolicyNode) -> Unit,
    onChannel: (String) -> Unit,
    onRoot: () -> Unit,
    onPosition: (String, PolicyCanvasPoint) -> Unit,
    onAlign: () -> Unit,
    modifier: Modifier = Modifier.height(460.dp),
) {
    val density = LocalDensity.current.density
    val dragged = remember(tree.scope) { mutableStateMapOf<String, Offset>() }
    var zoom by remember(tree.scope) { mutableStateOf(1f) }
    var viewportWidth by remember { mutableStateOf(0) }
    var centerRequest by remember(tree.scope) { mutableStateOf(0) }
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    val automatic = remember(tree.nodes) { graphPositions(tree) }
    val channelNodes = policy.channels.filter { it.owner == tree.scope }
    val maxDepth = ExpertEdits.ordered(tree).maxOfOrNull { it.second } ?: 0
    val channelY = (maxDepth + 2) * (NODE_HEIGHT + ROW_GAP) + 24f
    val base = automatic + channelNodes.mapIndexed { index, channel ->
        PolicyCanvasKeys.channel(channel.id) to Offset(24f + index * (NODE_WIDTH + COLUMN_GAP), channelY)
    } + tree.positions.mapValues { Offset(it.value.x, it.value.y) }
    val latestBase by rememberUpdatedState(base)
    val latestCommit by rememberUpdatedState(onPosition)
    LaunchedEffect(tree.positions) {
        dragged.keys.toList().forEach { key ->
            val stored = tree.positions[key]
            if (stored != null && dragged[key] == Offset(stored.x, stored.y)) dragged.remove(key)
        }
    }
    fun world(key: String): Offset = dragged[key] ?: latestBase[key] ?: Offset(24f, 24f)
    val keys =
        listOf(PolicyCanvasKeys.ROOT) + tree.nodes.map { PolicyCanvasKeys.node(it.id) } +
            channelNodes.map { PolicyCanvasKeys.channel(it.id) }
    val points = keys.map(::world)
    val minimum = Offset(minOf(0f, base.values.minOfOrNull { it.x } ?: 0f), minOf(0f, base.values.minOfOrNull { it.y } ?: 0f))
    val origin = Offset(24f - minimum.x, 24f - minimum.y)
    val nodeExtent = tree.nodes.maxOfOrNull { world(PolicyCanvasKeys.node(it.id)).x + NODE_WIDTH } ?: NODE_WIDTH
    val width = (points.maxOfOrNull { it.x } ?: 24f) + origin.x + NODE_WIDTH + 200f + channelNodes.size * 16f
    val height = (points.maxOfOrNull { it.y } ?: 24f) + origin.y + NODE_HEIGHT + 100f
    val lineColor = MaterialTheme.colorScheme.outline
    val maximum = Offset(
        (4800f - origin.x - NODE_WIDTH - channelNodes.size * 16f).coerceAtLeast(minimum.x),
        (4800f - origin.y - NODE_HEIGHT).coerceAtLeast(minimum.y)
    )
    fun draggable(key: String): Modifier = Modifier.pointerInput(key, density, minimum, maximum) {
        detectDragGesturesAfterLongPress(
            onDragEnd = { dragged[key]?.let { latestCommit(key, PolicyCanvasPoint(it.x, it.y)) } },
            onDragCancel = { dragged.remove(key) },
        ) { change, delta ->
            change.consume()
            val next = world(key) + delta / density
            dragged[key] = Offset(next.x.coerceIn(minimum.x, maximum.x), next.y.coerceIn(minimum.y, maximum.y))
        }
    }
    fun cardModifier(key: String): Modifier {
        val point = (world(key) + origin) * density
        return Modifier.offset { IntOffset(point.x.roundToInt(), point.y.roundToInt()) }
            .size(NODE_WIDTH.dp, NODE_HEIGHT.dp).then(draggable(key))
    }
    LaunchedEffect(tree.scope, viewportWidth, zoom, centerRequest) {
        if (viewportWidth > 0) {
            val root = world(PolicyCanvasKeys.ROOT) + origin
            horizontal.scrollTo(((root.x + NODE_WIDTH / 2) * density * zoom - viewportWidth / 2).roundToInt())
            vertical.scrollTo((root.y * density * zoom - 16 * density).roundToInt())
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = {
                dragged.clear()
                onAlign()
                centerRequest++
            }) { Text(stringResource(R.string.expert_align_graph)) }
            TextButton(onClick = { centerRequest++ }) { Text(stringResource(R.string.expert_graph_root)) }
            val zoomOut = stringResource(R.string.expert_graph_zoom_out)
            val zoomIn = stringResource(R.string.expert_graph_zoom_in)
            TextButton(
                onClick = { zoom = (zoom - 0.25f).coerceAtLeast(0.5f) },
                enabled = zoom > 0.5f, modifier = Modifier.semantics { contentDescription = zoomOut },
            ) { Text("−") }
            TextButton(
                onClick = { zoom = (zoom + 0.25f).coerceAtMost(1.5f) },
                enabled = zoom < 1.5f, modifier = Modifier.semantics { contentDescription = zoomIn },
            ) { Text("+") }
        }
        if (width > 5000f || height > 5000f) {
            ExpertHint(R.string.expert_graph_large)
        } else {
            Box(
                Modifier.fillMaxWidth().weight(1f).onSizeChanged { viewportWidth = it.width }
                    .horizontalScroll(horizontal).verticalScroll(vertical),
            ) {
                Box(Modifier.size((width * zoom).dp, (height * zoom).dp)) {
                    Box(
                        Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(width.dp, height.dp)
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                transformOrigin = TransformOrigin(0f, 0f)
                            }.background(MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Canvas(Modifier.size(width.dp, height.dp)) {
                            fun point(key: String): Offset = (world(key) + origin) * density
                            fun edge(from: String, to: String) {
                                val start = point(from) + Offset(NODE_WIDTH * density / 2, NODE_HEIGHT * density)
                                val end = point(to) + Offset(NODE_WIDTH * density / 2, 0f)
                                val middle = (start.y + end.y) / 2f
                                drawPath(
                                    Path().apply {
                                        moveTo(start.x, start.y)
                                        lineTo(start.x, middle)
                                        lineTo(end.x, middle)
                                        lineTo(end.x, end.y)
                                    },
                                    lineColor, style = Stroke(1.5.dp.toPx())
                                )
                            }
                            fun portal(from: String, channelId: String) {
                                val start = point(from) + Offset(NODE_WIDTH * density, NODE_HEIGHT * density / 2)
                                val end = point(PolicyCanvasKeys.channel(channelId)) +
                                    Offset(NODE_WIDTH * density, NODE_HEIGHT * density / 2)
                                val lane =
                                    (nodeExtent + origin.x + 40f + channelNodes.indexOfFirst { it.id == channelId } * 16f) * density
                                drawPath(
                                    Path().apply {
                                        moveTo(start.x, start.y)
                                        lineTo(start.x + 20f * density, start.y)
                                        lineTo(start.x + 20f * density, start.y + NODE_HEIGHT * density / 2 + 20f * density)
                                        lineTo(lane, start.y + NODE_HEIGHT * density / 2 + 20f * density)
                                        lineTo(lane, end.y)
                                        lineTo(end.x, end.y)
                                    },
                                    lineColor, style = Stroke(1.5.dp.toPx())
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
                            (tree.defaultTarget as? PolicyTarget.Channel)?.id?.takeIf { id -> channelNodes.any { it.id == id } }
                                ?.let { portal(PolicyCanvasKeys.ROOT, it) }
                        }
                        Card(onClick = onRoot, modifier = cardModifier(PolicyCanvasKeys.ROOT)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(scopeTitle(tree.scope, bundle), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.expert_default_path), style = MaterialTheme.typography.labelSmall)
                                Text(targetTitle(tree.defaultTarget, bundle, policy), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        tree.nodes.forEach { node ->
                            val inactiveNode = !node.enabled || node.detached || node.id in inactive
                            val fill = when {
                                inactiveNode -> MaterialTheme.colorScheme.surfaceVariant
                                node.target is PolicyTarget.Channel -> MaterialTheme.colorScheme.tertiaryContainer
                                node.protected -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.surfaceContainerHighest
                            }
                            Card(
                                onClick = { onEdit(node) }, colors = CardDefaults.cardColors(containerColor = fill),
                                modifier = cardModifier(PolicyCanvasKeys.node(node.id))
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        node.title.ifBlank {
                                            stringResource(R.string.expert_rule_unnamed)
                                        },
                                        style = MaterialTheme.typography.titleSmall, maxLines = 2
                                    )
                                    Text(
                                        targetTitle(node.target, bundle, policy),
                                        style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                    )
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
                            }
                        }
                        channelNodes.forEach { channel ->
                            Card(
                                onClick = { onChannel(channel.id) }, modifier = cardModifier(PolicyCanvasKeys.channel(channel.id)),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        stringResource(R.string.expert_channel, channel.name),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(targetTitle(channel.target, bundle, policy), style = MaterialTheme.typography.bodySmall)
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
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Top-down layout centers each parent over its children; root and channels have their own rows. */
private fun graphPositions(tree: PolicyTree): Map<String, Offset> {
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
            24f + center * (NODE_WIDTH + COLUMN_GAP), 24f + depth * (NODE_HEIGHT + ROW_GAP),
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
