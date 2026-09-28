package app.lernet.ui.routes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import app.lernet.R
import app.lernet.config.repo.RuleNodeRecord
import app.lernet.ui.icons.LerNetSymbols
import app.lernet.ui.theme.LerNetDimens
import app.lernet.ui.theme.LerNetWarn
import app.lernet.ui.theme.lernetButton
import kotlinx.coroutines.flow.drop
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.roundToInt

private data class RouteRowPlaceholder(val id: String, val bounds: Rect)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RouteFolderList(
    state: RouteEditorUiState,
    onIntent: (RouteEditorIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val folderId = state.listFolderId?.takeIf { id -> state.nodes.any { it.id == id } }
    FolderColumn(state, folderId, onIntent, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrphanHotbar(
    state: RouteEditorUiState,
    onIntent: (RouteEditorIntent) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val orphansOpen by rememberUpdatedState(state.orphansOpen)
    LaunchedEffect(state.orphansOpen) {
        if (state.orphansOpen) {
            if (drawerState.isClosed) drawerState.open()
        } else if (drawerState.isOpen) {
            drawerState.close()
        }
    }
    val drawerOpen = remember(drawerState) {
        snapshotFlow { drawerState.currentValue == DrawerValue.Open }.drop(1)
    }
    LaunchedEffect(drawerOpen) {
        drawerOpen.collect { open ->
            if (open != orphansOpen) onIntent(RouteEditorIntent.SetOrphansOpen(open))
        }
    }
    Column(modifier) {
        TextButton(
            onClick = { onIntent(RouteEditorIntent.SetOrphansOpen(true)) },
            modifier = Modifier.padding(horizontal = LerNetDimens.screenPadding).lernetButton(),
        ) {
            Text(stringResource(R.string.route_orphans, RouteFolders.orphans(state.nodes).size))
        }
        ModalNavigationDrawer(
            modifier = Modifier.weight(1f),
            drawerState = drawerState,
            gesturesEnabled = false,
            drawerContent = {
                ModalDrawerSheet {
                    OrphanDrawer(state, onIntent)
                }
            },
        ) {
            content()
        }
    }
}

@Composable
private fun FolderColumn(
    state: RouteEditorUiState,
    folderId: String?,
    onIntent: (RouteEditorIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val children = RouteFolders.listed(state.nodes, folderId)
    val rowIds = children.map { it.id }
    Column(
        modifier.padding(horizontal = LerNetDimens.screenPadding, vertical = LerNetDimens.itemGap),
        verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
    ) {
        if (folderId == null) {
            Text(
                stringResource(R.string.routes_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FolderHeader(state, folderId, onIntent)
        if (folderId == null) SystemRulesCard()
        ElseRepairNotice(state.fieldErrors, onIntent)
        if (state.terminalRejected || RouteFolders.hasNested(state.nodes)) {
            Text(stringResource(R.string.route_terminal), color = MaterialTheme.colorScheme.error)
        }
        if (state.saved && !state.applyPrompt) {
            Text(stringResource(R.string.routes_saved), color = MaterialTheme.colorScheme.secondary)
        }
        if (children.isEmpty()) {
            Text(
                stringResource(if (folderId == null) R.string.empty_routes else R.string.route_folder_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FolderReorderList(children, rowIds, folderId, state, onIntent, Modifier.weight(1f))
    }
}

@Composable
private fun FolderHeader(state: RouteEditorUiState, folderId: String?, onIntent: (RouteEditorIntent) -> Unit) {
    val title = if (folderId == null) {
        stringResource(R.string.rule_root)
    } else {
        val node = state.nodes.firstOrNull { it.id == folderId }
        if (node == null) stringResource(R.string.rule_root) else ruleHeadline(node)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (folderId != null) {
            IconButton(onClick = { onIntent(RouteEditorIntent.FolderBack) }) {
                Icon(LerNetSymbols.arrowBack(), contentDescription = stringResource(R.string.route_folder_back))
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderReorderList(
    rows: List<RuleNodeRecord>,
    rowIds: List<String>,
    folderId: String?,
    state: RouteEditorUiState,
    onIntent: (RouteEditorIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val channelNames = if (folderId == null) {
        CanvasGraph.pipeNames(RouteFolders.attached(state.nodes), state.extraPipes)
    } else emptyList()
    var openChannel by remember(folderId) { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val rowBounds = remember(folderId) { mutableStateMapOf<String, Rect>() }
    var listOrigin by remember(folderId) { mutableStateOf(Offset.Zero) }
    var placeholder by remember(folderId) { mutableStateOf<RouteRowPlaceholder?>(null) }
    val reorderable = rememberReorderableLazyListState(listState) { from, to ->
        if (RouteFolders.elseMoveBlocked(rows, from.index, to.index)) return@rememberReorderableLazyListState
        haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        onIntent(RouteEditorIntent.ReorderSiblings(folderId, movedIds(rowIds, from.index, to.index)))
    }
    Box(modifier.onGloballyPositioned { listOrigin = it.positionInWindow() }) {
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
            modifier = Modifier.fillMaxSize()) {
            items(rows, key = { it.id }) { node ->
                ReorderableItem(reorderable, key = node.id) { isDragging ->
                    val handle = if (node.isElseRule()) {
                        Modifier
                    } else {
                        rowLongPressDrag(this,
                            onStart = { rowBounds[node.id]?.let { placeholder = RouteRowPlaceholder(node.id, it) } },
                            onStop = {
                                placeholder = null
                                onIntent(RouteEditorIntent.ReorderSiblings(folderId, rowIds))
                            })
                    }
                    FolderRow(
                        node,
                        state,
                        handle,
                        RouteFolders.misplaced(state.nodes, node),
                        RouteFolders.priorityRank(state.nodes, node),
                        state.nodes.any { it.parentId == node.id },
                        onIntent,
                        Modifier.onGloballyPositioned { rowBounds[node.id] = it.boundsInWindow() }
                            .graphicsLayer { shadowElevation = if (isDragging) 16.dp.toPx() else 0f },
                    )
                }
            }
            if (channelNames.isNotEmpty()) {
                item(key = "channel-heading") {
                    Text(stringResource(R.string.route_channels_heading),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = LerNetDimens.cardGap))
                }
                items(channelNames, key = { "channel:$it" }) { name ->
                    val sourceCount = channelSources(state.nodes, name).size
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.combinedClickable(onClick = { openChannel = name }),
                        leadingContent = {
                            if (sourceCount > 1) ChannelPortalMark(sourceCount)
                            else Icon(LerNetSymbols.route(), contentDescription = null,
                                tint = LerNetWarn)
                        },
                        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(stringResource(R.string.route_channel_sources, sourceCount)) },
                    )
                }
            }
        }
        val held = placeholder
        val original = held?.let { drag -> rows.firstOrNull { it.id == drag.id } }
        if (held != null && original != null) {
            Box(
                Modifier.offset {
                    IntOffset((held.bounds.left - listOrigin.x).roundToInt(),
                        (held.bounds.top - listOrigin.y).roundToInt())
                }.size(with(density) { held.bounds.width.toDp() }, with(density) { held.bounds.height.toDp() })
                    .graphicsLayer { alpha = .35f },
            ) {
                FolderRow(original, state, Modifier, RouteFolders.misplaced(state.nodes, original),
                    RouteFolders.priorityRank(state.nodes, original), state.nodes.any { it.parentId == original.id },
                    onIntent = {}, modifier = Modifier.fillMaxSize())
            }
        }
    }
    openChannel?.let { name ->
        ChannelDetailsDialog(name, state.nodes) { openChannel = null }
    }
}

@Composable
private fun rowLongPressDrag(scope: ReorderableCollectionItemScope, onStart: () -> Unit,
    onStop: () -> Unit): Modifier {
    val haptic = LocalHapticFeedback.current
    return with(scope) {
        Modifier.longPressDraggableHandle(
            onDragStarted = {
                haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                onStart()
            },
            onDragStopped = {
                haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                onStop()
            },
        )
    }
}

@Composable
private fun FolderRow(
    node: RuleNodeRecord,
    state: RouteEditorUiState,
    drag: Modifier,
    misplaced: Boolean,
    rank: Int,
    branching: Boolean,
    onIntent: (RouteEditorIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val placing = node.id == state.placingOrphanId
    FolderListItem(
        FolderRow(
            node,
            placing,
            drag,
            misplaced,
            rank,
            branching,
            node.acceptsChildren(state.nodes),
            modifier,
        ),
        onIntent,
    )
}

private data class FolderRow(
    val node: RuleNodeRecord,
    val placing: Boolean,
    val drag: Modifier,
    val misplaced: Boolean,
    val rank: Int,
    val branching: Boolean,
    val opensFolder: Boolean,
    val modifier: Modifier,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderListItem(
    row: FolderRow,
    onIntent: (RouteEditorIntent) -> Unit,
) {
    val node = row.node
    val placing = row.placing
    val drag = row.drag
    val misplaced = row.misplaced
    val opensFolder = row.opensFolder
    val moveUp = stringResource(R.string.move_up)
    val moveDown = stringResource(R.string.move_down)
    val ruleColor = if (row.branching) MaterialTheme.colorScheme.primary else routeTone(node.action, node.pipeName).ink()
    val container = if (placing) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = container),
        modifier = Modifier
            .then(row.modifier)
            .drawBehind {
                val bar = 4.dp.toPx()
                drawRect(ruleColor, size = Size(bar, size.height))
            }
            .then(drag)
            .combinedClickable(onClick = { onIntent(rowClick(opensFolder, node.id)) })
            .semantics {
                if (!node.isElseRule()) {
                    customActions = listOf(
                        CustomAccessibilityAction(moveUp) {
                            onIntent(RouteEditorIntent.Move(node.id, -1))
                            true
                        },
                        CustomAccessibilityAction(moveDown) {
                            onIntent(RouteEditorIntent.Move(node.id, 1))
                            true
                        },
                    )
                }
            },
        headlineContent = {
            Column {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        ruleHeadline(node),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!node.isElseRule()) {
                        Text(
                            row.rank.toString(),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = LerNetDimens.itemGap),
                        )
                    }
                }
                RuleSupport(node, misplaced, row.branching)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onIntent(RouteEditorIntent.AddChild(node.id)) }) {
                    Icon(LerNetSymbols.add(), contentDescription = stringResource(R.string.route_add_child))
                }
                Switch(checked = node.enabled, enabled = !node.isElseRule(),
                    onCheckedChange = { onIntent(RouteEditorIntent.Toggle(node.id)) })
                RuleOverflowMenu(node.id, onIntent)
            }
        },
    )
}

@Composable
private fun RuleOverflowMenu(nodeId: String, onIntent: (RouteEditorIntent) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(LerNetSymbols.more(), contentDescription = stringResource(R.string.more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.canvas_properties)) },
                onClick = {
                    expanded = false
                    onIntent(RouteEditorIntent.Edit(nodeId))
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.duplicate)) },
                onClick = {
                    expanded = false
                    onIntent(RouteEditorIntent.Duplicate(nodeId))
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.route_detach)) },
                onClick = {
                    expanded = false
                    onIntent(RouteEditorIntent.Detach(nodeId))
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete)) },
                onClick = {
                    expanded = false
                    onIntent(RouteEditorIntent.RequestDelete(nodeId))
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OrphanDrawer(state: RouteEditorUiState, onIntent: (RouteEditorIntent) -> Unit) {
    val orphans = RouteFolders.orphans(state.nodes)
    Column(
        Modifier
            .fillMaxHeight()
            .padding(LerNetDimens.contentPadding),
        verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.route_orphans_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onIntent(RouteEditorIntent.SetOrphansOpen(false)) }) {
                Icon(LerNetSymbols.close(), contentDescription = stringResource(R.string.close))
            }
        }
        Text(
            stringResource(R.string.route_orphans_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (orphans.isEmpty()) {
            Text(stringResource(R.string.route_orphans_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap), modifier = Modifier.weight(1f)) {
            items(orphans, key = { it.id }) { node ->
                ListItem(
                    modifier = Modifier.combinedClickable(
                        onClick = { onIntent(RouteEditorIntent.PlaceOrphan(node.id)) },
                        onLongClick = { onIntent(RouteEditorIntent.PlaceOrphan(node.id)) },
                    ),
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    headlineContent = {
                        Column {
                            Text(ruleHeadline(node), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            RuleSupport(node, misplaced = false, branching = false)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun RuleSupport(node: RuleNodeRecord, misplaced: Boolean, branching: Boolean) {
    Column {
        if (node.isElseRule() && node.title.isNotBlank()) {
            Text(
                node.title.trim(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (misplaced) {
            Text(
                stringResource(R.string.route_terminal),
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            RulePreviewLines(node, MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                outcomeCaption(node.action, node.pipeName, branching),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun PriorityControls(
    rank: Int,
    maxRank: Int,
    nodeId: String,
    onIntent: (RouteEditorIntent) -> Unit,
    locked: Boolean = false,
) {
    val up = stringResource(R.string.move_up)
    val down = stringResource(R.string.move_down)
    val title = if (locked) {
        stringResource(R.string.rule_else_name)
    } else {
        stringResource(R.string.route_priority, rank)
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (!locked) {
            IconButton(onClick = { onIntent(RouteEditorIntent.Move(nodeId, -1)) }, enabled = rank > 1) {
                Icon(LerNetSymbols.arrowBack(), contentDescription = up, modifier = Modifier.rotate(90f))
            }
            IconButton(onClick = { onIntent(RouteEditorIntent.Move(nodeId, 1)) }, enabled = rank < maxRank) {
                Icon(LerNetSymbols.arrowBack(), contentDescription = down, modifier = Modifier.rotate(270f))
            }
        }
    }
}

@Composable
internal fun ElseRepairNotice(
    errors: List<String>,
    onIntent: (RouteEditorIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val canvasErrors = errors.filterNot(RuleSheetGate::isSheetOnlyError)
    if (canvasErrors.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap)) {
        canvasErrors.forEach { Text(routeFieldErrorText(it), color = MaterialTheme.colorScheme.error) }
        if (RouteFolders.elseNeedsRepair(canvasErrors)) {
            Button(
                onClick = { onIntent(RouteEditorIntent.RepairElse) },
                modifier = Modifier.fillMaxWidth().lernetButton(),
            ) {
                Text(stringResource(R.string.else_repair))
            }
        }
    }
}

private fun rowClick(opensFolder: Boolean, nodeId: String): RouteEditorIntent = if (opensFolder) {
    RouteEditorIntent.OpenFolder(nodeId)
} else {
    RouteEditorIntent.Edit(nodeId)
}
