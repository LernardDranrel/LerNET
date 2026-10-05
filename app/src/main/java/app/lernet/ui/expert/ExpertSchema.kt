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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import app.lernet.ui.components.PanelCard
import app.lernet.ui.icons.LerNetSymbols
import java.util.UUID

@Composable
internal fun ExpertSchema(
    runtime: ExpertRuntimeState,
    bundle: TransferBundle,
    onIntent: (ExpertIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scope by remember { mutableStateOf<PolicyScope>(PolicyScope.Device) }
    var chooseScope by remember { mutableStateOf(false) }
    var graph by remember { mutableStateOf(true) }
    var help by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PolicyNode?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var channelDetail by remember { mutableStateOf<String?>(null) }
    var rootTarget by remember { mutableStateOf<PolicyTarget?>(null) }
    val policy = runtime.draft
    val scopes = listOf(PolicyScope.Device) + policy.trees.map { it.scope }
    LaunchedEffect(scopes) {
        if (scope !in scopes) {
            scope = PolicyScope.Device
            editing = null
            deleting = null
            channelDetail = null
            rootTarget = null
        }
    }
    val tree = if (scope in scopes) ExpertEdits.tree(policy, scope) else policy.device
    fun add(parent: PolicyNode?) {
        editing = PolicyNode(
            UUID.randomUUID().toString(), parentId = parent?.id,
            sortIndex = (tree.nodes.filter { it.parentId == parent?.id }.maxOfOrNull { it.sortIndex } ?: -1) + 1,
            target = parent?.target ?: tree.defaultTarget
        )
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (runtime.hasDraftChanges ||
            runtime.errors.isNotEmpty() ||
            runtime.draftErrors.isNotEmpty() ||
            runtime.appliedPolicy?.let { it != runtime.draft } == true ||
            (runtime.phase == ExpertSessionPhase.RUNNING && runtime.appliedRevision != runtime.saved.revision)
        ) {
            Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                ExpertDraftActions(runtime, onIntent)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ chooseScope = true }, Modifier.weight(1f)) {
                Text(scopeTitle(scope, bundle), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(" ▾")
            }
            TextButton({ help = true }) { Text(stringResource(R.string.expert_schema_help)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(graph, { graph = true }, label = { Text(stringResource(R.string.expert_graph)) })
            FilterChip(!graph, { graph = false }, label = { Text(stringResource(R.string.expert_list)) })
            IconButton({ add(null) }) { Icon(LerNetSymbols.add(), stringResource(R.string.expert_add_rule)) }
        }
        if (graph) {
            ExpertGraph(
                tree, bundle, policy, runtime.inactiveNodeIds,
                onEdit = { editing = it }, onChannel = { channelDetail = it },
                onRoot = { rootTarget = tree.defaultTarget },
                onPosition = { key, point ->
                    onIntent(ExpertIntent.Edit(ExpertEdits.position(policy, tree.scope, key, point)))
                },
                onAlign = { onIntent(ExpertIntent.Edit(ExpertEdits.align(policy, tree.scope))) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
        if (!graph) {
            Column(
                Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (tree.nodes.isEmpty()) Text(stringResource(R.string.expert_empty_schema))
                ExpertEdits.ordered(tree).forEach { (node, depth) ->
                    if (!graph) {
                        Card(Modifier.fillMaxWidth().padding(start = (depth.coerceAtMost(3) * 12).dp)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    node.title.ifBlank { stringResource(R.string.expert_rule_unnamed) },
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(targetTitle(node.target, bundle, policy), style = MaterialTheme.typography.bodySmall)
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
                                Row {
                                    IconButton({ editing = node }) {
                                        Icon(LerNetSymbols.edit(), stringResource(R.string.expert_rule_editor))
                                    }
                                    val up = stringResource(R.string.expert_move_up)
                                    val down = stringResource(R.string.expert_move_down)
                                    IconButton(
                                        { onIntent(ExpertIntent.Edit(ExpertEdits.move(policy, scope, node.id, -1))) },
                                        Modifier.semantics { contentDescription = up }
                                    ) {
                                        Text("↑", modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.titleLarge)
                                    }
                                    IconButton(
                                        { onIntent(ExpertIntent.Edit(ExpertEdits.move(policy, scope, node.id, 1))) },
                                        Modifier.semantics { contentDescription = down }
                                    ) {
                                        Text("↓", modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.titleLarge)
                                    }
                                    IconButton({ add(node) }, enabled = ExpertEdits.canAddChild(node.target)) {
                                        Icon(LerNetSymbols.add(), stringResource(R.string.expert_add_child))
                                    }
                                    IconButton({ deleting = node.id }) {
                                        Icon(LerNetSymbols.delete(), stringResource(R.string.expert_remove))
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
                PanelCard {
                    Text(stringResource(R.string.expert_default_path), style = MaterialTheme.typography.titleMedium)
                    ExpertTargetSelector(tree.defaultTarget, scope, bundle, policy, false) { target ->
                        onIntent(ExpertIntent.Edit(ExpertEdits.replaceTree(policy, tree.copy(defaultTarget = target))))
                    }
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
        ExpertRuleEditor(node, scope, bundle, policy, onCommit = {
            onIntent(ExpertIntent.Edit(ExpertEdits.putNode(policy, scope, it)))
            editing = null
        }, onDismiss = { editing = null })
    }
    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.expert_remove_title)) },
            text = { Text(stringResource(R.string.expert_remove_body)) }, confirmButton = {
                TextButton({
                    onIntent(ExpertIntent.Edit(ExpertEdits.removeNode(policy, scope, id)))
                    deleting = null
                }) { Text(stringResource(R.string.expert_remove)) }
            },
            dismissButton = { TextButton({ deleting = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    rootTarget?.takeIf { scope in scopes }?.let { target ->
        AlertDialog(
            onDismissRequest = { rootTarget = null },
            title = { Text(stringResource(R.string.expert_default_path)) },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    ExpertTargetSelector(target, scope, bundle, policy, false) { rootTarget = it }
                }
            }, confirmButton = {
                TextButton({
                    onIntent(ExpertIntent.Edit(ExpertEdits.replaceTree(policy, tree.copy(defaultTarget = target))))
                    rootTarget = null
                }) { Text(stringResource(R.string.expert_node_accept)) }
            },
            dismissButton = { TextButton({ rootTarget = null }) { Text(stringResource(R.string.cancel)) } }
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
