package app.lernet.ui.routes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import app.lernet.R
import app.lernet.config.repo.RuleNodeRecord
import app.lernet.ui.icons.LerNetSymbols
import app.lernet.ui.theme.LerNetDimens
import app.lernet.ui.theme.lernetButton
import kotlinx.coroutines.flow.drop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrphanHotbar(
    state: RouteEditorUiState, parentId: String?,
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
        // «Без папки (0)» was shown on every screen; Windows has no such line. Only when there is something to place.
        val orphans = RouteFolders.orphans(state.nodes).size
        if (orphans > 0 || orphansOpen) TextButton(
            onClick = { onIntent(RouteEditorIntent.SetOrphansOpen(true)) },
            modifier = Modifier.padding(horizontal = LerNetDimens.screenPadding).lernetButton(),
        ) {
            Text(stringResource(R.string.route_orphans, orphans))
        }
        ModalNavigationDrawer(
            modifier = Modifier.weight(1f),
            drawerState = drawerState,
            gesturesEnabled = false,
            drawerContent = {
                ModalDrawerSheet {
                    OrphanDrawer(state, parentId, onIntent)
                }
            },
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OrphanDrawer(state: RouteEditorUiState, parentId: String?, onIntent: (RouteEditorIntent) -> Unit) {
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
                        onClick = { onIntent(RouteEditorIntent.PlaceOrphan(node.id, parentId)) },
                        onLongClick = { onIntent(RouteEditorIntent.PlaceOrphan(node.id, parentId)) },
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
