package app.lernet.ui.expert

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.config.transfer.TransferBundle
import app.lernet.engine.text.SchemaWords
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.PolicyTree
import app.lernet.ui.icons.LerNetSymbols
import kotlin.math.ceil
import kotlin.math.roundToInt

private val cameraSaver = listSaver<BranchMapCamera?, Float>(
    save = { camera -> camera?.let { listOf(it.scale, it.center.x, it.center.y) }.orEmpty() },
    restore = { if (it.size == 3) BranchMapCamera(it[0], Offset(it[1], it[2])) else null },
)

/** A branch projection of the draft. Viewport gestures never change stored node positions or policy. */
@Composable
internal fun ExpertBranchMap(
    tree: PolicyTree,
    bundle: TransferBundle,
    policy: NetworkPolicy,
    inactive: Set<String>,
    parentId: String?,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onOpenBranch: (String) -> Unit,
    onEdit: (PolicyNode?) -> Unit,
    modifier: Modifier = Modifier,
    index: ExpertBranchIndex = remember(tree.nodes) { ExpertBranchIndex(tree) },
) {
    val parent = index.byId[parentId]
    val children = index.children(parent?.id)
    val selectedRow = remember(selectedId, children) {
        children.indexOfFirst { it.id == selectedId } + 1
    }
    val selected = children.getOrNull(selectedRow - 1) ?: parent
    val fontScale = LocalDensity.current.fontScale
    val layout = remember(children.size, fontScale) {
        BranchMapLayout(children.size + 1, 24f + 53f * fontScale.coerceAtLeast(1f))
    }
    fun nodeAt(row: Int) = if (row == 0) parent else children[row - 1]
    val colors = MaterialTheme.colorScheme
    val scopeName = scopeTitle(tree.scope, bundle)
    var manual by rememberSaveable(tree.scope, parent?.id, stateSaver = cameraSaver) { mutableStateOf(null) }

    Column(modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(12.dp),
            color = colors.surfaceContainerLow, border = BorderStroke(1.dp, colors.outline)) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(1.dp)) {
                val toolbar = minOf(maxHeight, 54.dp)
                val viewport = Size(maxWidth.value, (maxHeight - toolbar).value.coerceAtLeast(1f))
                val fit = layout.fit(viewport)
                val camera = manual ?: fit
                val minimum = minOf(fit.scale, .25f)
                val compact = layout.nodeHeight * camera.scale < 18f
                val rows = layout.visibleRows(camera.world(Offset.Zero, viewport).y,
                    camera.world(Offset(0f, viewport.height), viewport).y)
                Box(Modifier.fillMaxWidth().height(viewport.height.dp).clipToBounds().testTag("expert-branch-map")
                    .pointerInput(tree.scope, parent?.id, viewport, fit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            manual = (manual ?: fit).gesture(viewport, centroid / density, pan / density, zoom, minimum)
                        }
                    }) {
                    val lastAttached = remember(parent?.id, children) {
                        children.indexOfLast { !it.detached && it.parentId == parent?.id } + 1
                    }
                    // At overview scale the paint cost is bounded by screen pixels, even for very wide branches.
                    val hitOverview = if (compact) Modifier.pointerInput(camera, layout, children, parent) {
                        detectTapGestures { point ->
                            layout.rowAt(camera.world(point / density, viewport))?.let { onSelect(nodeAt(it)?.id) }
                        }
                    } else Modifier
                    Canvas(Modifier.fillMaxSize().then(hitOverview)) {
                        val grid = 18.dp.toPx()
                        var x = grid / 2
                        while (x < size.width) {
                            var y = grid / 2
                            while (y < size.height) {
                                drawCircle(colors.outlineVariant, .75.dp.toPx(), Offset(x, y))
                                y += grid
                            }
                            x += grid
                        }
                        fun screen(x: Float, y: Float) = camera.screen(Offset(x, y), viewport) * density
                        val rail = 13f
                        if (lastAttached > 0) {
                            drawLine(colors.outline, screen(rail, layout.nodeHeight),
                                screen(rail, layout.position(lastAttached).y + layout.nodeHeight / 2), 1.4.dp.toPx())
                        }
                        val step = ceil(1f / (layout.rowStep * camera.scale * density)).toInt().coerceAtLeast(1)
                        for (row in rows step step) {
                            val origin = layout.position(row)
                            val midY = origin.y + layout.nodeHeight / 2
                            if (row > 0 && !children[row - 1].detached && children[row - 1].parentId == parent?.id) {
                                drawLine(if (row == selectedRow) colors.primary else colors.outline,
                                    screen(rail, midY), screen(origin.x, midY), 1.4.dp.toPx())
                            }
                            if (compact) {
                                drawRect(if (row == selectedRow) colors.primary else colors.onSurfaceVariant,
                                    screen(origin.x, origin.y), Size(layout.nodeWidth * camera.scale * density,
                                        (layout.nodeHeight * camera.scale * density).coerceAtLeast(1f)))
                            }
                        }
                    }
                    if (!compact) for (row in rows) {
                        val node = nodeAt(row)
                        key(node?.id) {
                            val world = layout.position(row)
                            val hasChildren = index.children(node?.id).isNotEmpty()
                            val subtitle = when {
                                node == null -> SchemaWords.ROOT_SUBTITLE
                                node.detached -> stringResource(R.string.branch_map_detached)
                                !node.enabled -> stringResource(R.string.status_off)
                                node.id in inactive -> stringResource(R.string.branch_map_inactive)
                                hasChildren -> stringResource(R.string.branch_map_children, index.children(node.id).size)
                                else -> targetTitle(node.target, bundle, policy)
                            }
                            BranchMapNode(
                                title = node?.let { branchRuleTitle(it) } ?: scopeName,
                                subtitle = subtitle, hasChildren = hasChildren, blocking = node?.target == PolicyTarget.Block,
                                selected = selectedRow == row, onClick = { onSelect(node?.id) },
                                modifier = Modifier.offset {
                                    val point = camera.screen(world, viewport) * density
                                    IntOffset(point.x.roundToInt(), point.y.roundToInt())
                                }.graphicsLayer {
                                    transformOrigin = TransformOrigin(0f, 0f)
                                    scaleX = camera.scale
                                    scaleY = camera.scale
                                }.size(layout.nodeWidth.dp, layout.nodeHeight.dp)
                                    .testTag(node?.let { "branch-map-node:" + it.id } ?: "branch-map-root"),
                            )
                        }
                    }
                }
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(toolbar).padding(horizontal = 5.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Box(Modifier.weight(1f)) {
                        if (viewport.width >= 310 && fontScale <= 1.5f) {
                            BranchOverview(layout, selectedRow, viewport, camera, onCenter = { manual = camera.copy(center = it) },
                                onFit = { manual = null })
                        }
                    }
                    IconButton({ manual = camera.gesture(viewport, viewport.middle(), Offset.Zero, .8f, minimum) },
                        Modifier.testTag("branch-map-zoom-out"), enabled = camera.scale > minimum) {
                        Icon(Icons.Outlined.Remove, stringResource(R.string.branch_map_zoom_out))
                    }
                    val percent = camera.scale * 100
                    Text(if (percent < 1) stringResource(R.string.branch_map_low_scale)
                        else stringResource(R.string.branch_map_scale, percent.roundToInt()), Modifier.testTag("branch-map-scale"),
                        style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    IconButton({ manual = camera.gesture(viewport, viewport.middle(), Offset.Zero, 1.25f, minimum) },
                        Modifier.testTag("branch-map-zoom-in"), enabled = camera.scale < 2.25f) {
                        Icon(LerNetSymbols.add(), stringResource(R.string.branch_map_zoom_in))
                    }
                    TextButton({ manual = null }, Modifier.testTag("branch-map-fit")) {
                        Text(stringResource(R.string.branch_map_fit), maxLines = 1)
                    }
                }
            }
        }
        val canOpen = selected != null && selected != parent && index.children(selected.id).isNotEmpty()
        BranchSelection(selected?.let { branchRuleTitle(it) } ?: scopeName,
            selected?.let { branchRuleCondition(it) } ?: SchemaWords.ROOT_SUBTITLE,
            if (canOpen && selected != null) ({ onOpenBranch(selected.id) }) else null,
            selected != null, onEdit = { onEdit(selected) })
    }
}

@Composable
private fun BranchMapNode(title: String, subtitle: String, hasChildren: Boolean, blocking: Boolean,
    selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(9.dp)
    val tint = if (hasChildren) colors.primary else colors.secondary
    Surface(modifier.clip(shape).selectable(selected, role = Role.Button, onClick = onClick),
        shape = shape, border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outline),
        color = if (selected) colors.primaryContainer else colors.surfaceContainer) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(30.dp).background(tint.copy(alpha = .12f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center) {
                Icon(if (hasChildren) Icons.Outlined.AccountTree else if (blocking) Icons.Outlined.Block else Icons.Outlined.Dns,
                    null, Modifier.size(17.dp), tint = tint)
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun BranchSelection(title: String, condition: String, onOpen: (() -> Unit)?, isRule: Boolean, onEdit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = colors.surfaceContainer,
        border = BorderStroke(1.dp, colors.outline)) {
        BoxWithConstraints(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            val stack = maxWidth < 250.dp || LocalDensity.current.fontScale > 1.45f || onOpen != null
            val details: @Composable (Modifier) -> Unit = { contentModifier ->
                Column(contentModifier) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(condition, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            val edit: @Composable () -> Unit = {
                Button(onEdit, Modifier.testTag("branch-map-edit")) {
                    Text(stringResource(if (isRule) R.string.branch_map_edit else R.string.branch_map_edit_default), maxLines = 1)
                }
            }
            if (stack) {
                Column(Modifier.fillMaxWidth()) {
                    details(Modifier.fillMaxWidth())
                    if (onOpen != null && LocalDensity.current.fontScale > 1.45f) {
                        TextButton(onOpen) { Text(stringResource(R.string.branch_map_open)) }
                        edit()
                    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically) {
                        if (onOpen != null) TextButton(onOpen) { Text(stringResource(R.string.branch_map_open)) }
                        edit()
                    }
                }
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                details(Modifier.weight(1f))
                edit()
            }
        }
    }
}

@Composable
private fun BranchOverview(layout: BranchMapLayout, selectedRow: Int, viewport: Size, camera: BranchMapCamera,
    onCenter: (Offset) -> Unit, onFit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    fun miniScale(w: Float, h: Float) = minOf(w / layout.width, h / layout.height) * .86f
    val overviewLabel = stringResource(R.string.branch_map_overview)
    val fitLabel = stringResource(R.string.branch_map_fit)
    Canvas(Modifier.size(60.dp, 46.dp).clip(RoundedCornerShape(6.dp)).background(colors.surfaceContainer)
        .semantics {
            contentDescription = overviewLabel
            onClick(fitLabel) { onFit(); true }
        }.pointerInput(layout, onCenter) {
            detectTapGestures { point ->
                val scale = miniScale(size.width.toFloat(), size.height.toFloat())
                val origin = Offset((size.width - layout.width * scale) / 2, (size.height - layout.height * scale) / 2)
                val center = (point - origin) / scale
                onCenter(Offset(center.x.coerceIn(0f, layout.width), center.y.coerceIn(0f, layout.height)))
            }
        }) {
        val scale = miniScale(size.width, size.height)
        val origin = Offset((size.width - layout.width * scale) / 2, (size.height - layout.height * scale) / 2)
        val step = ceil(1f / (layout.rowStep * scale)).toInt().coerceAtLeast(1)
        val extent = Size((layout.nodeWidth * scale).coerceAtLeast(1f), (layout.nodeHeight * scale).coerceAtLeast(1f))
        for (row in 0 until layout.count step step) {
            drawRect(colors.onSurfaceVariant, origin + layout.position(row) * scale, extent)
        }
        drawRect(colors.primary, origin + layout.position(selectedRow) * scale, extent)
        val corner = camera.world(Offset.Zero, viewport)
        val opposite = camera.world(Offset(viewport.width, viewport.height), viewport)
        val left = corner.x.coerceAtLeast(0f)
        val top = corner.y.coerceAtLeast(0f)
        val right = opposite.x.coerceAtMost(layout.width)
        val bottom = opposite.y.coerceAtMost(layout.height)
        if (right > left && bottom > top) {
            drawRect(colors.primary, origin + Offset(left, top) * scale,
                Size((right - left) * scale, (bottom - top) * scale), style = Stroke(1.dp.toPx()))
        }
    }
}
