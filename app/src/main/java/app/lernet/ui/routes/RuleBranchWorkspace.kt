package app.lernet.ui.routes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.routing.policy.PolicyCanvasKeys
import app.lernet.routing.policy.PolicyNode
import app.lernet.ui.icons.LerNetSymbols

internal enum class BranchView { LIST, MAP, CANVAS }

@Stable
internal class BranchNavigation(initial: BranchView) {
    var view by mutableStateOf(initial)
    var parentId by mutableStateOf<String?>(null)
    var selectedId by mutableStateOf<String?>(null)
    var revealId by mutableStateOf<String?>(null)

    fun level(id: String?, index: RuleBranchIndex) {
        parentId = id
        selectedId = index.children(id).firstOrNull()?.id ?: id
        revealId = null
    }

    fun reveal(parent: String?, child: String) {
        parentId = parent
        selectedId = child
        revealId = child
    }

    fun back(index: RuleBranchIndex): Boolean {
        if (view != BranchView.LIST) {
            view = BranchView.LIST
            revealId = selectedId?.takeIf { index.parentId(it) == parentId && it != parentId }
            return true
        }
        val previous = parentId ?: return false
        reveal(index.parentId(previous), previous)
        return true
    }
}

private val navigationSaver = listSaver<BranchNavigation, Any?>(
    save = { listOf(it.view.name, it.parentId, it.selectedId, it.revealId) },
    restore = { values ->
        BranchNavigation(BranchView.valueOf(values[0] as String)).apply {
            parentId = values[1] as String?
            selectedId = values[2] as String?
            revealId = values[3] as String?
        }
    },
)

@Composable
internal fun rememberBranchNavigation(owner: Any, initial: BranchView = BranchView.LIST): BranchNavigation =
    rememberSaveable(owner, saver = navigationSaver) { BranchNavigation(initial) }

@Composable
internal fun RuleBranchWorkspace(
    owner: Any,
    ownerTitle: String,
    index: RuleBranchIndex,
    navigation: BranchNavigation,
    inactive: Set<String>,
    targetLabel: @Composable (PolicyNode) -> String,
    canAdd: (PolicyNode?) -> Boolean,
    onAdd: (PolicyNode?) -> Unit,
    onEdit: (PolicyNode?) -> Unit,
    nodeMenu: @Composable (PolicyNode?) -> Unit,
    overlayOpen: Boolean,
    modifier: Modifier = Modifier,
    onReorder: ((PolicyNode, Int) -> Unit)? = null,
    header: @Composable (showSearch: () -> Unit) -> Unit,
    fullMap: @Composable () -> Unit,
) {
    var path by rememberSaveable(owner) { mutableStateOf(false) }
    var search by rememberSaveable(owner) { mutableStateOf(false) }
    val parent = index.byId[navigation.parentId]
    LaunchedEffect(index) {
        if (parent == null) navigation.parentId = null
        if (navigation.selectedId !in index.byId) navigation.selectedId = parent?.id
    }
    fun open(node: PolicyNode) {
        if (index.children(node.id).isNotEmpty()) {
            navigation.level(node.id, index)
        } else {
            navigation.selectedId = node.id
            onEdit(node)
        }
    }
    BackHandler(enabled = !overlayOpen && !path && !search && (parent != null || navigation.view != BranchView.LIST)) {
        navigation.back(index)
    }
    val listStates = key(owner) { rememberSaveableStateHolder() }
    Column(modifier) {
        header { search = true }
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (navigation.view != BranchView.LIST || parent != null) {
                IconButton({ navigation.back(index) }, Modifier.testTag("branch-list-back")) {
                    Icon(
                        LerNetSymbols.arrowBack(),
                        stringResource(
                            if (navigation.view == BranchView.LIST) {
                                R.string.expert_branch_parent
                            } else {
                                R.string.expert_branch_to_list
                            }
                        )
                    )
                }
            }
            Text(
                parent?.let { branchRuleTitle(it) } ?: stringResource(R.string.expert_branch_root_title),
                Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            TextButton({ path = true }, Modifier.testTag("branch-path")) {
                Text(stringResource(R.string.expert_branch_path, index.path(parent?.id).size + 1))
            }
            if (navigation.view == BranchView.LIST) {
                TextButton({ navigation.view = BranchView.MAP }, Modifier.testTag("branch-map-open")) {
                    Text(stringResource(R.string.expert_branch_on_map))
                }
            } else if (navigation.view == BranchView.MAP) {
                IconButton({ navigation.view = BranchView.CANVAS }) {
                    Icon(LerNetSymbols.route(), stringResource(R.string.expert_branch_full_canvas))
                }
            }
        }
        HorizontalDivider()
        when (navigation.view) {
            BranchView.LIST -> listStates.SaveableStateProvider(parent?.id?.let(PolicyCanvasKeys::node) ?: PolicyCanvasKeys.ROOT) {
                RuleBranchList(
                    index, parent, navigation.selectedId, targetLabel, canAdd(parent), inactive,
                    ::open, { onAdd(parent) }, nodeMenu, navigation.revealId, { navigation.revealId = null },
                    Modifier.weight(1f).fillMaxWidth(), onReorder
                )
            }
            BranchView.MAP -> RuleBranchMap(
                owner, ownerTitle, index, targetLabel, inactive, parent?.id, navigation.selectedId,
                onSelect = { navigation.selectedId = it }, onOpenBranch = { navigation.level(it, index) },
                onEdit = onEdit, modifier = Modifier.weight(1f).fillMaxWidth()
            )
            BranchView.CANVAS -> Box(Modifier.weight(1f).fillMaxWidth()) { fullMap() }
        }
    }
    if (path) {
        RuleBranchPath(index, parent?.id, ownerTitle, onPick = { id ->
            if (id != parent?.id) navigation.level(id, index)
            if (navigation.view == BranchView.CANVAS) navigation.view = BranchView.MAP
            path = false
        }, onDismiss = { path = false })
    }
    if (search) {
        RuleBranchSearch(index, ownerTitle, onOpen = { node ->
            search = false
            navigation.view = BranchView.LIST
            navigation.reveal(index.parentId(node.id), node.id)
            open(node)
        }, onDismiss = { search = false })
    }
}

@Composable
internal fun RuleBranchHelp(view: BranchView, close: () -> Unit, openFullMap: () -> Unit) {
    AlertDialog(
        onDismissRequest = close, title = { Text(stringResource(R.string.expert_schema_help)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(
                    R.string.expert_priority_hint,
                    if (view == BranchView.CANVAS) R.string.expert_canvas_hint else R.string.expert_branch_help,
                    R.string.expert_otherwise_hint
                ).forEach {
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        dismissButton = { TextButton(openFullMap) { Text(stringResource(R.string.expert_branch_full_canvas)) } },
        confirmButton = { TextButton(close) { Text(stringResource(R.string.expert_node_accept)) } }
    )
}
