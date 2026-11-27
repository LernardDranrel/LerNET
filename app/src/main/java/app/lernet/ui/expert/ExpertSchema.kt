package app.lernet.ui.expert

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.config.transfer.TransferBundle
import app.lernet.engine.policy.ExpertIntent
import app.lernet.engine.policy.ExpertRuntimeState
import app.lernet.engine.policy.ExpertSessionPhase
import app.lernet.routing.policy.PolicyBranchEditing
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyCanvasKeys
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyOtherwise
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.PolicyTree
import app.lernet.ui.icons.LerNetSymbols
import java.util.UUID

private enum class ExpertBranchView { LIST, MAP, CANVAS }

@Composable
internal fun ExpertSchema(
    runtime: ExpertRuntimeState,
    bundle: TransferBundle,
    onIntent: (ExpertIntent) -> Unit,
    modifier: Modifier = Modifier,
    onSaveDraft: (suspend (NetworkPolicy, NetworkPolicy) -> String?)? = null,
) {
    var scope by rememberSaveable(stateSaver = ExpertScopeSaver) { mutableStateOf<PolicyScope>(PolicyScope.Device) }
    var chooseScope by remember { mutableStateOf(false) }
    var view by rememberSaveable(scope) { mutableStateOf(ExpertBranchView.LIST) }
    var parentId by rememberSaveable(scope) { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable(scope) { mutableStateOf<String?>(null) }
    var revealId by rememberSaveable(scope) { mutableStateOf<String?>(null) }
    var path by rememberSaveable(scope) { mutableStateOf(false) }
    var search by rememberSaveable(scope) { mutableStateOf(false) }
    var changes by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var editing by rememberSaveable(stateSaver = ExpertOptionalNodeSaver) { mutableStateOf<PolicyNode?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var channelDetail by remember { mutableStateOf<String?>(null) }
    val policy = runtime.draft
    val scopedTrees = policy.trees.map { it.scope }
    val scopes = remember(bundle.groups, bundle.profiles, scopedTrees) {
        buildList {
            add(PolicyScope.Device)
            bundle.groups.forEach { folder ->
                add(PolicyScope.Folder(folder.id))
                folder.profileIds.forEach { add(PolicyScope.Profile(it)) }
            }
            bundle.profiles.forEach { add(PolicyScope.Profile(it.id)) }
            addAll(scopedTrees)
        }.distinct()
    }
    LaunchedEffect(scopes) {
        if (scope !in scopes) {
            scope = PolicyScope.Device
            editing = null
            deleting = null
            channelDetail = null
        }
    }
    val tree = remember(policy, scope) { PolicyBranchEditing.displayTree(ExpertEdits.tree(policy, scope)) }
    val index = remember(tree.nodes) { ExpertBranchIndex(tree) }
    val parent = parentId?.let(index.byId::get)
    val rootOtherwise = tree.nodes.first { it.parentId == null && !it.detached && PolicyOtherwise.isOtherwise(it) }
    LaunchedEffect(index) {
        if (parent == null) parentId = null
        if (selectedId != null && selectedId !in index.byId) selectedId = parent?.id
    }
    fun add(parent: PolicyNode?) {
        editing = PolicyNode(
            UUID.randomUUID().toString(), parentId = parent?.id,
            sortIndex = tree.nodes.count { it.parentId == parent?.id },
            target = PolicyBranchEditing.initialChildTarget(tree, parent?.id)
        )
    }
    fun addOtherwise(parent: PolicyNode) {
        editing = PolicyNode(
            UUID.randomUUID().toString(), parentId = parent.id,
            sortIndex = tree.nodes.count { it.parentId == parent.id }, title = "ИНАЧЕ",
            target = if (PolicyBranchEditing.initialChildTarget(tree, parent.id) == PolicyTarget.Block) {
                PolicyTarget.Block
            } else {
                tree.defaultTarget
            },
            otherwise = true,
        )
    }
    val showDraftActions = runtime.hasDraftChanges ||
        runtime.errors.isNotEmpty() ||
        runtime.draftErrors.isNotEmpty() ||
        runtime.appliedPolicy?.let { it != runtime.draft } == true ||
        (runtime.phase == ExpertSessionPhase.RUNNING && runtime.appliedRevision != runtime.saved.revision)
    fun level(target: String?) {
        parentId = target
        selectedId = index.children(target).firstOrNull()?.id ?: target
        revealId = null
    }
    fun back() {
        if (view != ExpertBranchView.LIST) {
            view = ExpertBranchView.LIST
            revealId = selectedId?.takeIf { index.parentId(it) == parent?.id && it != parent?.id }
        } else if (parent != null) {
            level(index.parentId(parent.id))
            selectedId = parent.id
            revealId = parent.id
        }
    }
    fun open(node: PolicyNode) {
        if (index.children(node.id).isNotEmpty()) {
            level(node.id)
        } else {
            selectedId = node.id
            editing = node
        }
    }
    fun move(node: PolicyNode, direction: Int) {
        onIntent(ExpertIntent.EditChecked(policy, ExpertEdits.move(policy, scope, node.id, direction)))
    }
    val nodeMenu: @Composable (PolicyNode?) -> Unit = { node ->
        val channel = node?.target as? PolicyTarget.Channel
        ExpertNodeMenu(node, tree, onAdd = { add(node) }, onOtherwise = { node?.let(::addOtherwise) },
            onMove = { direction -> node?.let { move(it, direction) } }, onDelete = { deleting = node?.id },
            onEdit = { editing = node ?: rootOtherwise }, onChannel = channel?.let { { channelDetail = it.id } })
    }
    val hasOverlay = editing != null || deleting != null || channelDetail != null || chooseScope || help || path || search || changes
    BackHandler(enabled = !hasOverlay && (parent != null || view != ExpertBranchView.LIST), onBack = ::back)
    val listStates = key(scope) { rememberSaveableStateHolder() }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ chooseScope = true }, Modifier.weight(1f)) {
                Text(scopeTitle(scope, bundle), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                Icon(LerNetSymbols.expandMore(), null, Modifier.size(18.dp))
            }
            IconButton({ search = true }, Modifier.testTag("branch-search")) {
                Icon(Icons.Outlined.Search, stringResource(R.string.expert_branch_search))
            }
            IconButton({ help = true }) { Icon(LerNetSymbols.help(), stringResource(R.string.expert_schema_help)) }
        }
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (view != ExpertBranchView.LIST || parent != null) {
                IconButton(::back, Modifier.testTag("branch-list-back")) {
                    Icon(LerNetSymbols.arrowBack(), stringResource(if (view == ExpertBranchView.LIST)
                        R.string.expert_branch_parent else R.string.expert_branch_to_list))
                }
            }
            Text(parent?.let { branchRuleTitle(it) } ?: stringResource(R.string.expert_branch_root_title),
                Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton({ path = true }, Modifier.testTag("branch-path")) {
                Text(stringResource(R.string.expert_branch_path, index.path(parent?.id).size + 1))
            }
            if (view == ExpertBranchView.LIST) {
                TextButton({ view = ExpertBranchView.MAP }, Modifier.testTag("branch-map-open")) {
                    Text(stringResource(R.string.expert_branch_on_map))
                }
            } else if (view == ExpertBranchView.MAP) {
                IconButton({ view = ExpertBranchView.CANVAS }) {
                    Icon(LerNetSymbols.route(), stringResource(R.string.expert_branch_full_canvas))
                }
            }
        }
        HorizontalDivider()
        when (view) {
            ExpertBranchView.LIST -> listStates.SaveableStateProvider(parent?.id?.let(PolicyCanvasKeys::node) ?: PolicyCanvasKeys.ROOT) {
                ExpertBranchList(index, parent, selectedId, bundle, policy, runtime.inactiveNodeIds, ::open, { add(parent) },
                    nodeMenu, revealId, { revealId = null }, Modifier.weight(1f).fillMaxWidth())
            }
            ExpertBranchView.MAP -> ExpertBranchMap(
                tree, bundle, policy, runtime.inactiveNodeIds, parentId = parent?.id, selectedId = selectedId,
                onSelect = { selectedId = it }, onOpenBranch = ::level, onEdit = { editing = it ?: rootOtherwise },
                modifier = Modifier.weight(1f).fillMaxWidth(), index = index,
            )
            ExpertBranchView.CANVAS -> ExpertGraph(
                tree, bundle, policy, runtime.inactiveNodeIds,
                onEdit = { editing = it }, onChannel = { channelDetail = it }, onRoot = { editing = rootOtherwise },
                onPosition = { canvasKey, point -> onIntent(ExpertIntent.UpdateLayout(tree.scope, mapOf(canvasKey to point))) },
                onAlign = { onIntent(ExpertIntent.UpdateLayout(tree.scope, clear = true)) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                selectedKey = selectedId?.let(PolicyCanvasKeys::node) ?: PolicyCanvasKeys.ROOT,
                onSelect = { canvasKey -> selectedId = index.byId.values.firstOrNull { PolicyCanvasKeys.node(it.id) == canvasKey }?.id },
                onAdd = ::add, nodeActions = { node ->
                    ExpertNodeActions(node, tree, onEdit = { editing = node ?: rootOtherwise }, onAdd = { add(node) },
                        onOtherwise = { node?.let(::addOtherwise) }, onMove = { direction -> node?.let { move(it, direction) } },
                        onDelete = { deleting = node?.id })
                },
            )
        }
        if (showDraftActions) {
            val errors = (runtime.errors + runtime.draftErrors).distinct()
            TextButton({ changes = true }, Modifier.fillMaxWidth()) {
                Text(if (errors.isEmpty()) stringResource(R.string.expert_branch_changes)
                    else stringResource(R.string.expert_branch_changes_errors, errors.size))
            }
        }
    }
    if (path) ExpertBranchPath(index, parent?.id, scopeTitle(scope, bundle), onPick = { id ->
        if (id != parent?.id) level(id)
        if (view == ExpertBranchView.CANVAS) view = ExpertBranchView.MAP
        path = false
    }, onDismiss = { path = false })
    if (search) ExpertBranchSearch(index, scopeTitle(scope, bundle), onOpen = { node ->
        search = false
        view = ExpertBranchView.LIST
        level(index.parentId(node.id))
        open(node)
        if (editing != null) revealId = node.id
    }, onDismiss = { search = false })
    if (changes && showDraftActions) {
        AlertDialog(onDismissRequest = { changes = false }, title = { Text(stringResource(R.string.expert_branch_changes)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { ExpertDraftActions(runtime, onIntent) } },
            confirmButton = { TextButton({ changes = false }) { Text(stringResource(R.string.expert_node_accept)) } })
    }
    LaunchedEffect(showDraftActions) { if (!showDraftActions) changes = false }
    if (help) {
        AlertDialog(
            onDismissRequest = { help = false },
            title = { Text(stringResource(R.string.expert_schema_help)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ExpertHint(R.string.expert_priority_hint)
                    ExpertHint(if (view == ExpertBranchView.CANVAS) R.string.expert_canvas_hint else R.string.expert_branch_help)
                    ExpertHint(R.string.expert_otherwise_hint)
                }
            },
            dismissButton = { TextButton({
                help = false
                view = ExpertBranchView.CANVAS
            }) { Text(stringResource(R.string.expert_branch_full_canvas)) } },
            confirmButton = {
                TextButton({ help = false }) { Text(stringResource(R.string.expert_node_accept)) }
            },
        )
    }
    if (chooseScope) {
        AlertDialog(
            onDismissRequest = { chooseScope = false }, title = { Text(stringResource(R.string.expert_choose_scope)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    scopes.forEach { choice ->
                        val nested = choice is PolicyScope.Profile && bundle.groups.any { choice.id in it.profileIds }
                        TextButton({
                            scope = choice
                            chooseScope = false
                        }, Modifier.fillMaxWidth().padding(start = if (nested) 20.dp else 0.dp)) {
                            Text(scopeTitle(choice, bundle))
                            if (choice == scope) Icon(LerNetSymbols.check(), null, Modifier.size(16.dp))
                        }
                    }
                }
            }, confirmButton = { TextButton({ chooseScope = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    editing?.takeIf { scope in scopes }?.let { node ->
        val base by rememberSaveable(node.id, stateSaver = ExpertPolicySaver) { mutableStateOf(policy) }
        ExpertRuleEditor(node, scope, bundle, base, onCommit = { next ->
            if (onSaveDraft == null) return@ExpertRuleEditor "Сохранение недоступно"
            val adding = next.id !in index.byId
            val error = onSaveDraft(base, ExpertEdits.putNode(base, scope, next))
            if (error == null && adding && view == ExpertBranchView.LIST) {
                parentId = next.parentId
                selectedId = next.id
                revealId = next.id
            }
            error
        }, onDismiss = { editing = null })
    }
    deleting?.let { id ->
        val base by rememberSaveable(id, stateSaver = ExpertPolicySaver) { mutableStateOf(policy) }
        var busy by remember { mutableStateOf(false) }
        var error by rememberSaveable(id) { mutableStateOf<String?>(null) }
        val jobs = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { if (!busy) deleting = null },
            title = { Text(stringResource(R.string.expert_remove_title)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.expert_remove_body))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } }, confirmButton = {
                TextButton({
                    if (!busy) jobs.launch {
                        busy = true
                        val containingBranch = index.parentId(id)
                        try {
                            error = if (onSaveDraft == null) "Сохранение недоступно" else {
                                onSaveDraft(base, ExpertEdits.removeNode(base, scope, id))
                            }
                            if (error == null) {
                                if (parent?.id == id) {
                                    parentId = containingBranch
                                    selectedId = containingBranch
                                    revealId = null
                                }
                                deleting = null
                            }
                        } finally { busy = false }
                    }
                }, enabled = !busy) { Text(stringResource(R.string.expert_remove)) }
            },
            dismissButton = { TextButton({ deleting = null }, enabled = !busy) { Text(stringResource(R.string.cancel)) } }
        )
    }
    channelDetail?.let { id ->
        policy.channels.firstOrNull { it.id == id }?.let { channel ->
            AlertDialog(
                onDismissRequest = { channelDetail = null }, title = { Text(stringResource(R.string.expert_channel, channel.name)) },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text(targetTitle(channel.target, bundle, policy), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.expert_channel_incoming), style = MaterialTheme.typography.labelLarge)
                        tree.nodes.filter { (it.target as? PolicyTarget.Channel)?.id == id }.forEach { node ->
                            TextButton({
                                editing = node
                                channelDetail = null
                            }, Modifier.fillMaxWidth()) {
                                Text(node.title.ifBlank { stringResource(R.string.expert_rule_unnamed) })
                            }
                        }
                    }
                }, confirmButton = { TextButton({ channelDetail = null }) { Text(stringResource(R.string.cancel)) } }
            )
        }
    }
}

internal fun canAddMissingOtherwise(tree: PolicyTree, parent: PolicyNode): Boolean {
    val children = tree.nodes.filter { it.parentId == parent.id && !it.detached }
    return children.isNotEmpty() && children.none { PolicyOtherwise.isOtherwise(it) }
}

@Composable
internal fun ExpertDraftActions(runtime: ExpertRuntimeState, onIntent: (ExpertIntent) -> Unit) {
    var restoreApplied by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val editingBusy = runtime.applying || runtime.phase in setOf(ExpertSessionPhase.STARTING, ExpertSessionPhase.STOPPING)
        if (runtime.hasDraftChanges) {
            ExpertHint(R.string.expert_draft_hint)
            Button({ onIntent(ExpertIntent.SaveDraft) }, Modifier.fillMaxWidth(), enabled = !editingBusy && runtime.draftErrors.isEmpty()) {
                Text(stringResource(R.string.expert_save))
            }
            OutlinedButton({ onIntent(ExpertIntent.DiscardDraft) }, Modifier.fillMaxWidth(), enabled = !editingBusy) {
                Text(stringResource(R.string.expert_discard))
            }
        }
        val running = runtime.phase == ExpertSessionPhase.RUNNING
        val capabilities = runtime.capabilities
        val live = capabilities.preservesTun && capabilities.atomicRules && capabilities.independentExits
        if (running && runtime.appliedRevision != runtime.saved.revision) {
            Button(
                { onIntent(ExpertIntent.ApplySaved) }, Modifier.fillMaxWidth(),
                enabled = live && !runtime.applying && !runtime.hasDraftChanges,
            ) {
                Text(stringResource(if (runtime.applying) R.string.expert_applying else R.string.expert_apply))
            }
            if (!live) ExpertHint(R.string.expert_hot_unavailable)
        }
        if (runtime.appliedPolicy != null && runtime.draft != runtime.appliedPolicy) {
            OutlinedButton({ restoreApplied = true }, Modifier.fillMaxWidth(), enabled = !editingBusy) {
                Text(stringResource(R.string.expert_restore_applied))
            }
        }
        (runtime.errors + runtime.draftErrors).distinct().forEach {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (restoreApplied) {
        AlertDialog(
            onDismissRequest = { restoreApplied = false },
            title = { Text(stringResource(R.string.expert_restore_applied)) },
            text = { Text(stringResource(R.string.expert_restore_applied_body)) },
            confirmButton = {
                TextButton({
                    onIntent(ExpertIntent.RestoreAppliedToDraft)
                    restoreApplied = false
                }) {
                    Text(stringResource(R.string.expert_restore_applied))
                }
            }, dismissButton = { TextButton({ restoreApplied = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}
