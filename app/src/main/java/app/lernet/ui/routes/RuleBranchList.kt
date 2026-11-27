package app.lernet.ui.routes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.routing.policy.PolicyNode
import app.lernet.ui.controls.ExpertFormDialog
import app.lernet.ui.expert.ExpertHint
import app.lernet.ui.icons.LerNetSymbols
import kotlinx.coroutines.flow.first
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
internal fun RuleBranchList(
    index: RuleBranchIndex,
    parent: PolicyNode?,
    selectedId: String?,
    targetLabel: @Composable (PolicyNode) -> String,
    canAdd: Boolean,
    inactive: Set<String>,
    onOpen: (PolicyNode) -> Unit,
    onAdd: () -> Unit,
    nodeMenu: @Composable (PolicyNode?) -> Unit,
    revealId: String?,
    onRevealed: () -> Unit,
    modifier: Modifier = Modifier,
    onReorder: ((PolicyNode, Int) -> Unit)? = null,
) {
    val children = index.children(parent?.id)
    val state = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(state) { from, to ->
        val source = children.firstOrNull { it.id == from.key } ?: return@rememberReorderableLazyListState
        val destination = children.indexOfFirst { it.id == to.key }
        if (source.otherwise || destination < 0 || children[destination].otherwise) return@rememberReorderableLazyListState
        if (source.parentId == children[destination].parentId) {
            onReorder?.invoke(source, destination - children.indexOf(source))
        }
    }
    LaunchedEffect(revealId, children) {
        if (revealId != null) {
            val position = children.indexOfFirst { it.id == revealId }
            if (position >= 0) {
                val visible = snapshotFlow { state.layoutInfo.visibleItemsInfo }.first { it.isNotEmpty() }
                if (visible.none { it.index == position + 1 }) state.scrollToItem(position + 1)
                onRevealed()
            }
        }
    }
    LazyColumn(state = state, modifier = modifier, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
        item {
            if (parent != null) {
                Text(
                    branchRuleCondition(parent), Modifier.padding(bottom = 8.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.expert_branch_list_title, children.size), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall
                )
                if (canAdd) {
                    IconButton(onAdd) { Icon(LerNetSymbols.add(), stringResource(R.string.expert_add_rule)) }
                }
                nodeMenu(parent)
            }
            HorizontalDivider()
        }
        itemsIndexed(children, key = { _, node -> node.id }) { position, node ->
            ReorderableItem(reorder, key = node.id) {
                val handle = if (onReorder != null && !node.otherwise) Modifier.longPressDraggableHandle() else Modifier
                val count = index.children(node.id).size
                val chosen = selectedId == node.id
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f).testTag("branch-row:${node.id}").semantics { selected = chosen }
                            .clickable(role = Role.Button) { onOpen(node) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            (position + 1).toString().padStart(2, '0'), modifier = handle, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                branchRuleTitle(node), style = MaterialTheme.typography.titleSmall,
                                color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                branchRuleCondition(node), style = MaterialTheme.typography.bodySmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (count > 0) stringResource(R.string.expert_branch_children, count) else targetLabel(node),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary
                            )
                            if (!node.enabled) ExpertHint(R.string.expert_rule_disabled)
                            if (node.id in inactive) ExpertHint(R.string.expert_inactive_android)
                            if (node.detached) ExpertHint(R.string.expert_detached)
                            if (node.protected) ExpertHint(R.string.expert_rule_protected)
                        }
                        Icon(LerNetSymbols.chevronRight(), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    nodeMenu(node)
                }
            }
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RuleBranchPath(
    index: RuleBranchIndex,
    parentId: String?,
    rootTitle: String,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val path = remember(index, parentId) { listOf(null) + index.path(parentId) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            stringResource(R.string.expert_branch_path_title), Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.titleLarge
        )
        LazyColumn(
            Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("branch-ancestors"),
            state = rememberLazyListState(initialFirstVisibleItemIndex = path.lastIndex),
        ) {
            items(path) { node ->
                val current = node?.id == parentId
                Row(
                    Modifier.fillMaxWidth().testTag(node?.let { "branch-ancestor:${it.id}" } ?: "branch-ancestor-root")
                        .semantics { selected = current }.clickable(role = Role.Button) { onPick(node?.id) }
                        .heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(node?.let { branchRuleTitle(it) } ?: rootTitle, style = MaterialTheme.typography.titleSmall)
                        node?.let {
                            Text(
                                branchRuleCondition(it), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        if (current) LerNetSymbols.check() else LerNetSymbols.chevronRight(), null,
                        Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
internal fun RuleBranchSearch(index: RuleBranchIndex, rootTitle: String, onOpen: (PolicyNode) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val unnamed = stringResource(R.string.expert_rule_unnamed)
    val otherwise = stringResource(R.string.expert_otherwise_title)
    val focus = remember { FocusRequester() }
    val listState = key(query) { rememberLazyListState() }
    val results = remember(index, query, unnamed, otherwise) {
        val term = query.trim()
        index.byId.values.filter { node ->
            val label = if (node.otherwise) otherwise else node.title.ifBlank { unnamed }
            label.contains(term, ignoreCase = true) || node.title.contains(term, ignoreCase = true) ||
                node.conditions.blocks.any { block -> block.values.any { it.contains(term, ignoreCase = true) } }
        }
    }
    ExpertFormDialog(
        onDismissRequest = onDismiss, title = { Text(stringResource(R.string.expert_branch_search)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth().focusRequester(focus).testTag("branch-search-field"),
                    label = { Text(stringResource(R.string.expert_branch_search_query)) }, singleLine = true
                )
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    focus.requestFocus()
                }
                if (results.isEmpty()) Text(stringResource(R.string.search_profiles_empty))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), state = listState) {
                    items(results, key = { it.id }) { node ->
                        val trail = listOf(rootTitle) + index.path(node.id).dropLast(1).map { branchRuleTitle(it) }
                        var fullPath by remember(node.id, query) { mutableStateOf(false) }
                        val longPath = trail.size > 5
                        val shown = if (longPath && !fullPath) trail.take(2) + "…" + trail.takeLast(2) else trail
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                Modifier.weight(1f).testTag("branch-search-result:${node.id}")
                                    .clickable(role = Role.Button) { onOpen(node) }.padding(vertical = 10.dp)
                            ) {
                                Text(branchRuleTitle(node), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    shown.joinToString(" › "), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (longPath) {
                                IconButton({ fullPath = !fullPath }) {
                                    Icon(
                                        if (fullPath) LerNetSymbols.close() else LerNetSymbols.expandMore(),
                                        stringResource(
                                            if (fullPath) R.string.expert_branch_collapse_path else R.string.expert_branch_expand_path,
                                        )
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }, confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
        dismissButton = {},
    )
}
