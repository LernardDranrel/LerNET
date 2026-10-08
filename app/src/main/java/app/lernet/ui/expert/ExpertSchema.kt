package app.lernet.ui.expert

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import app.lernet.ui.routes.RouteEditorWorkspace
import app.lernet.ui.theme.LerNetDimens
import java.util.UUID

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    var graph by rememberSaveable { mutableStateOf(true) }
    var help by remember { mutableStateOf(false) }
    var editing by rememberSaveable(stateSaver = ExpertOptionalNodeSaver) { mutableStateOf<PolicyNode?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var channelDetail by remember { mutableStateOf<String?>(null) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(PolicyCanvasKeys.ROOT) }
    val policy = runtime.draft
    val scopes = (
        listOf(PolicyScope.Device) + policy.trees.map { it.scope } +
            bundle.groups.map { PolicyScope.Folder(it.id) } + bundle.profiles.map { PolicyScope.Profile(it.id) }
        ).distinct()
    LaunchedEffect(scopes) {
        if (scope !in scopes) {
            scope = PolicyScope.Device
            editing = null
            deleting = null
            channelDetail = null
        }
    }
    val tree = PolicyBranchEditing.displayTree(if (scope in scopes) ExpertEdits.tree(policy, scope) else policy.device)
    val rootOtherwise = tree.nodes.first { it.parentId == null && !it.detached && PolicyOtherwise.isOtherwise(it) }
    LaunchedEffect(scope, tree.nodes) {
        if (tree.nodes.none { PolicyCanvasKeys.node(it.id) == selectedKey }) selectedKey = PolicyCanvasKeys.ROOT
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
    RouteEditorWorkspace(
        asList = !graph,
        onListChange = { graph = !it },
        modifier = modifier,
        header = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = LerNetDimens.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton({ chooseScope = true }, Modifier.weight(1f)) {
                    Text(scopeTitle(scope, bundle), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(LerNetSymbols.expandMore(), contentDescription = null)
                }
                IconButton({ help = true }) {
                    Icon(LerNetSymbols.help(), contentDescription = stringResource(R.string.expert_schema_help))
                }
            }
        },
        floatingAction = {
            FloatingActionButton(onClick = { add(null) }) {
                Icon(LerNetSymbols.add(), stringResource(R.string.expert_add_rule))
            }
        },
        bottomBar = {
            if (showDraftActions) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())
                        .padding(horizontal = LerNetDimens.screenPadding, vertical = 8.dp),
                ) { ExpertDraftActions(runtime, onIntent) }
            }
        },
    ) {
        if (graph) {
            ExpertGraph(
                tree, bundle, policy, runtime.inactiveNodeIds,
                onEdit = { editing = it }, onChannel = { channelDetail = it },
                onRoot = { editing = rootOtherwise },
                onPosition = { key, point ->
                    onIntent(ExpertIntent.UpdateLayout(tree.scope, mapOf(key to point)))
                },
                onAlign = { onIntent(ExpertIntent.UpdateLayout(tree.scope, clear = true)) },
                modifier = Modifier.fillMaxSize(),
                selectedKey = selectedKey,
                onSelect = { selectedKey = it },
                onAdd = { add(it) },
                nodeActions = { node ->
                    ExpertNodeActions(
                        node = node, tree = tree,
                        onEdit = { editing = node ?: rootOtherwise },
                        onAdd = { add(node) },
                        onOtherwise = { node?.let(::addOtherwise) },
                        onMove = { direction -> node?.let {
                            onIntent(ExpertIntent.EditChecked(policy, ExpertEdits.move(policy, scope, it.id, direction)))
                        } },
                        onDelete = { deleting = node?.id },
                    )
                },
            )
        }
        if (!graph) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LerNetDimens.screenPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(onClick = { selectedKey = PolicyCanvasKeys.ROOT }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(scopeTitle(scope, bundle), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.expert_branching_root))
                        TextButton({ editing = rootOtherwise }) { Text(stringResource(R.string.expert_selected_properties)) }
                        TextButton({
                            selectedKey = PolicyCanvasKeys.ROOT
                            add(null)
                        }) { Text(stringResource(R.string.expert_add_rule)) }
                    }
                }
                ExpertEdits.ordered(tree).forEach { (node, depth) ->
                    if (!graph) {
                        Card(
                            onClick = { selectedKey = PolicyCanvasKeys.node(node.id) },
                            modifier = Modifier.fillMaxWidth().padding(start = (depth.coerceAtMost(3) * 12).dp)
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    if (PolicyOtherwise.isOtherwise(node)) {
                                        stringResource(R.string.expert_otherwise_title)
                                    } else {
                                        node.title.ifBlank { stringResource(R.string.expert_rule_unnamed) }
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                if (PolicyOtherwise.isOtherwise(node)) ExpertHint(R.string.expert_otherwise_hint)
                                Text(
                                    if (tree.nodes.any { it.parentId == node.id && !it.detached }) {
                                        stringResource(R.string.expert_branching_root)
                                    } else {
                                        targetTitle(node.target, bundle, policy)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                (node.target as? PolicyTarget.Channel)?.let { target ->
                                    val links = tree.nodes.count { (it.target as? PolicyTarget.Channel)?.id == target.id }
                                    if (links > 1) {
                                        TextButton({ channelDetail = target.id }) {
                                            Icon(LerNetSymbols.route(), null)
                                            Text(stringResource(R.string.expert_portal_hint, links))
                                        }
                                    }
                                }
                                if (node.id in runtime.inactiveNodeIds) ExpertHint(R.string.expert_inactive_android)
                                if (node.detached) ExpertHint(R.string.expert_detached)
                                if (node.protected) ExpertHint(R.string.expert_rule_protected)
                                androidx.compose.foundation.layout.FlowRow {
                                    IconButton({ editing = node }) {
                                        Icon(LerNetSymbols.edit(), stringResource(R.string.expert_rule_editor))
                                    }
                                    if (!PolicyOtherwise.isOtherwise(node)) {
                                        val up = stringResource(R.string.expert_move_up)
                                        val down = stringResource(R.string.expert_move_down)
                                        IconButton(
                                            { onIntent(ExpertIntent.EditChecked(policy, ExpertEdits.move(policy, scope, node.id, -1))) },
                                            Modifier.semantics { contentDescription = up },
                                            enabled = PolicyBranchEditing.canMoveNode(tree, node.id, -1),
                                        ) {
                                            Icon(Icons.Outlined.ArrowUpward, contentDescription = null)
                                        }
                                        IconButton(
                                            { onIntent(ExpertIntent.EditChecked(policy, ExpertEdits.move(policy, scope, node.id, 1))) },
                                            Modifier.semantics { contentDescription = down },
                                            enabled = PolicyBranchEditing.canMoveNode(tree, node.id, 1),
                                        ) {
                                            Icon(Icons.Outlined.ArrowDownward, contentDescription = null)
                                        }
                                    }
                                    IconButton({ add(node) }, enabled = ExpertEdits.canAddChild(node)) {
                                        Icon(LerNetSymbols.add(), stringResource(R.string.expert_add_child))
                                    }
                                    if (canAddMissingOtherwise(tree, node)) {
                                        TextButton({ addOtherwise(node) }) { Text(stringResource(R.string.expert_add_otherwise)) }
                                    }
                                    if (!PolicyOtherwise.isOtherwise(node)) {
                                        IconButton({ deleting = node.id }) {
                                            Icon(LerNetSymbols.delete(), stringResource(R.string.expert_remove))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Button({ add(null) }, Modifier.fillMaxWidth()) {
                    Icon(LerNetSymbols.add(), null)
                    Text(stringResource(R.string.expert_add_rule))
                }
            }
        }
    }
    if (help) {
        AlertDialog(
            onDismissRequest = { help = false },
            title = { Text(stringResource(R.string.expert_schema_help)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ExpertHint(R.string.expert_priority_hint)
                    ExpertHint(R.string.expert_canvas_hint)
                    ExpertHint(R.string.expert_schema_root_hint)
                    ExpertHint(R.string.expert_otherwise_hint)
                }
            },
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
                    TextButton({
                        scope = PolicyScope.Device
                        chooseScope = false
                    }, Modifier.fillMaxWidth()) {
                        Text(scopeTitle(PolicyScope.Device, bundle))
                    }
                    bundle.groups.forEach { folder ->
                        val owner = PolicyScope.Folder(folder.id)
                        TextButton({
                            scope = owner
                            chooseScope = false
                        }, Modifier.fillMaxWidth()) { Text(scopeTitle(owner, bundle)) }
                        folder.profileIds.forEach { id ->
                            val profile = PolicyScope.Profile(id)
                            TextButton({
                                scope = profile
                                chooseScope = false
                            }, Modifier.fillMaxWidth().padding(start = 20.dp)) {
                                Text(scopeTitle(profile, bundle))
                            }
                        }
                    }
                    bundle.profiles.filter { profile -> bundle.groups.none { profile.id in it.profileIds } }.forEach { profile ->
                        val owner = PolicyScope.Profile(profile.id)
                        TextButton({
                            scope = owner
                            chooseScope = false
                        }, Modifier.fillMaxWidth()) { Text(scopeTitle(owner, bundle)) }
                    }
                }
            }, confirmButton = { TextButton({ chooseScope = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    editing?.takeIf { scope in scopes }?.let { node ->
        val base by rememberSaveable(node.id, stateSaver = ExpertPolicySaver) { mutableStateOf(policy) }
        ExpertRuleEditor(node, scope, bundle, base, onCommit = { next ->
            if (onSaveDraft == null) "Сохранение недоступно" else onSaveDraft(base, ExpertEdits.putNode(base, scope, next))
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
                        try {
                            error = if (onSaveDraft == null) "Сохранение недоступно" else {
                                onSaveDraft(base, ExpertEdits.removeNode(base, scope, id))
                            }
                            if (error == null) deleting = null
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
