package app.lernet.ui.expert

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.config.policy.ExternalExitKind
import app.lernet.config.policy.ExternalExitProfiles
import app.lernet.config.policy.ExternalExitRequest
import app.lernet.config.transfer.TransferBundle
import app.lernet.config.transfer.TransferProfile
import app.lernet.engine.policy.ExitPhase
import app.lernet.engine.policy.ExpertExitState
import app.lernet.engine.policy.ExpertIntent
import app.lernet.engine.policy.ExpertRuntimeState
import app.lernet.engine.policy.ExpertSessionPhase
import app.lernet.routing.RoutePlatform
import app.lernet.routing.policy.ExitLifecyclePolicy
import app.lernet.routing.policy.FolderPolicy
import app.lernet.routing.policy.FolderSelection
import app.lernet.routing.policy.PolicyChannel
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.ProfileExitPolicy
import app.lernet.ui.components.PanelCard
import java.util.UUID

@Composable
internal fun ExpertExits(
    runtime: ExpertRuntimeState,
    bundle: TransferBundle,
    onIntent: (ExpertIntent) -> Unit,
    onSaveExternal: (suspend (ExternalExitRequest, TransferProfile?) -> String?)? = null,
) {
    var editingChannel by remember { mutableStateOf<PolicyChannel?>(null) }
    var chooseOwner by remember { mutableStateOf(false) }
    var lifecycleOwner by remember { mutableStateOf<PolicyScope?>(null) }
    var externalEditor by remember { mutableStateOf(false) }
    var externalProfile by remember { mutableStateOf<TransferProfile?>(null) }
    var externalDetails by remember { mutableStateOf<TransferProfile?>(null) }
    var liveDetails by remember { mutableStateOf<ExpertExitState?>(null) }
    val unsupportedProfiles = remember(bundle.profiles) {
        bundle.profiles.filter {
            runCatching { ExternalExitProfiles.platformRequirement(it) == RoutePlatform.WINDOWS }
                .getOrDefault(false)
        }.mapTo(HashSet()) { it.id }
    }
    val policy = runtime.draft
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ExpertDraftActions(runtime, onIntent)
        if (onSaveExternal != null) {
            Button(onClick = {
                externalProfile = null
                externalEditor = true
            }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.expert_external_add))
            }
        }
        ExpertHint(R.string.expert_folder_hint)
        bundle.groups.forEach { folder ->
            val settings = policy.folderPolicies.firstOrNull { it.folderId == folder.id } ?: FolderPolicy(folder.id)
            var preferredOpen by remember(folder.id) { mutableStateOf(false) }
            fun update(next: FolderPolicy) {
                onIntent(
                    ExpertIntent.Edit(
                        policy.copy(folderPolicies = policy.folderPolicies.filterNot { it.folderId == folder.id } + next),
                    )
                )
            }
            PanelCard {
                Text(folder.name, style = MaterialTheme.typography.titleMedium)
                FolderSelection.entries.forEach { mode ->
                    OutlinedButton(onClick = { update(settings.copy(selection = mode)) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(
                                if (mode == FolderSelection.PREFERRED) R.string.expert_folder_preferred else R.string.expert_folder_fastest,
                            )
                        )
                        if (settings.selection == mode) Text(" ✓")
                    }
                }
                OutlinedButton({ preferredOpen = true }, Modifier.fillMaxWidth()) {
                    Text(
                        bundle.profiles.firstOrNull { it.id == settings.preferredProfileId }?.name
                            ?: stringResource(R.string.expert_folder_preferred),
                    )
                }
                ExpertCheckRow(R.string.expert_folder_swap, settings.autoSwap) { update(settings.copy(autoSwap = it)) }
                LifecycleSummary(settings.lifecycle) { lifecycleOwner = PolicyScope.Folder(folder.id) }
                runtime.selectedFolderProfiles[folder.id]?.let { selected ->
                    Text(bundle.profiles.firstOrNull { it.id == selected }?.name ?: selected, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (preferredOpen) {
                AlertDialog(
                    onDismissRequest = { preferredOpen = false }, title = { Text(folder.name) },
                    text = {
                        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                            bundle.profiles.filter { it.id in folder.profileIds }.forEach { profile ->
                                TextButton({
                                    update(settings.copy(preferredProfileId = profile.id))
                                    preferredOpen = false
                                }, Modifier.fillMaxWidth()) { Text(profile.name) }
                            }
                        }
                    }, confirmButton = { TextButton({ preferredOpen = false }) { Text(stringResource(R.string.cancel)) } }
                )
            }
        }
        ExpertHint(R.string.expert_lifecycle_priority)
        bundle.profiles.forEach { profile ->
            val settings = policy.profilePolicies.firstOrNull { it.profileId == profile.id } ?: ProfileExitPolicy(profile.id)
            val external = remember(profile) { ExternalExitProfiles.describe(profile) }
            val windowsOnly = profile.id in unsupportedProfiles
            PanelCard {
                Text(
                    profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (windowsOnly) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (external != null || windowsOnly) {
                    if (windowsOnly) {
                        ExpertHint(R.string.expert_external_corporate_unavailable)
                    } else {
                        val external = requireNotNull(external)
                        Text(
                            stringResource(
                                if (external.kind == ExternalExitKind.SOCKS5) {
                                    R.string.expert_external_socks
                                } else {
                                    R.string.expert_external_http
                                }
                            ),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text("${external.host}:${external.port}", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { externalDetails = profile }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.expert_external_details))
                    }
                }
                LifecycleSummary(settings.lifecycle) { lifecycleOwner = PolicyScope.Profile(profile.id) }
                runtime.exits.filter { it.key.profileId == profile.id && it.key.channelId == null }.forEach { exit ->
                    Text(stringResource(exit.phase.titleResource()), style = MaterialTheme.typography.titleSmall)
                    exit.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    TextButton({ liveDetails = exit }) { Text(stringResource(R.string.expert_live_exit_details)) }
                    ExpertExitControls(exit, runtime.phase == ExpertSessionPhase.RUNNING, onIntent, windowsOnly)
                }
            }
        }
        policy.channels.forEach { channel ->
            PanelCard {
                Text(stringResource(R.string.expert_channel, channel.name), style = MaterialTheme.typography.titleMedium)
                Text(scopeTitle(channel.owner, bundle), style = MaterialTheme.typography.labelSmall)
                Text(targetTitle(channel.target, bundle, policy), style = MaterialTheme.typography.bodySmall)
                val live = runtime.exits.filter { it.key.channelId == channel.id }
                if (live.isEmpty()) ExpertHint(R.string.expert_exit_no_live)
                live.forEach { exit ->
                    Text(stringResource(exit.phase.titleResource()), style = MaterialTheme.typography.titleSmall)
                    exit.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    TextButton({ liveDetails = exit }) { Text(stringResource(R.string.expert_live_exit_details)) }
                    ExpertExitControls(
                        exit,
                        runtime.phase == ExpertSessionPhase.RUNNING,
                        onIntent,
                        exit.key.profileId in unsupportedProfiles,
                    )
                }
                OutlinedButton({ editingChannel = channel }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.expert_rule_editor)) }
            }
        }
        Button({ chooseOwner = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.expert_channel_add)) }
    }
    liveDetails?.let { initial ->
        val current = runtime.exits.firstOrNull { it.key == initial.key }
        val exit = current ?: initial
        AlertDialog(
            onDismissRequest = { liveDetails = null },
            title = { Text(exitTitle(exit.key, bundle, runtime.appliedPolicy)) },
            text = {
                Column(
                    Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (current == null) ExpertHint(R.string.expert_exit_snapshot_stale)
                    Text(stringResource(exit.phase.titleResource()), style = MaterialTheme.typography.titleSmall)
                    Text(exitIdentity(exit.key), style = MaterialTheme.typography.labelSmall)
                    Text(
                        stringResource(
                            R.string.expert_exit_latency,
                            exit.latencyMs?.let { stringResource(R.string.expert_latency_ms, it) }
                                ?: stringResource(R.string.expert_value_unknown),
                        ),
                    )
                    Text(stringResource(R.string.expert_exit_last_check, observationTimeTitle(exit.lastCheckMs)))
                    Text(stringResource(R.string.expert_observation_connections, exit.activeFlows, exit.pendingFlows))
                    exit.reason?.let { Text(it) }
                    Text(stringResource(R.string.expert_exit_users), style = MaterialTheme.typography.titleSmall)
                    val users = runtime.connections.filter { it.exit == exit.key }
                    if (users.isEmpty()) ExpertHint(R.string.expert_exit_users_empty)
                    users.asReversed().forEach { connection ->
                        Text(connection.application ?: stringResource(R.string.expert_unknown_app))
                        Text(connection.destination, style = MaterialTheme.typography.bodySmall)
                        Text(connection.decision, style = MaterialTheme.typography.bodySmall)
                        Text(connectionStateTitle(connection.active), style = MaterialTheme.typography.labelSmall)
                        Text(observationTimeTitle(connection.startedAtMs), style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                TextButton({ liveDetails = null }) { Text(stringResource(R.string.expert_node_accept)) }
            },
        )
    }
    if (externalEditor && onSaveExternal != null) {
        ExpertExternalExitEditor(externalProfile, onDismiss = { externalEditor = false }, onSave = onSaveExternal)
    }
    externalDetails?.let { profile ->
        val editAction: (() -> Unit)? = if (onSaveExternal == null) {
            null
        } else {
            {
                externalProfile = profile
                externalDetails = null
                externalEditor = true
            }
        }
        ExpertExternalExitDetails(
            profile, onDismiss = { externalDetails = null },
            onEdit = editAction,
        )
    }
    lifecycleOwner?.let { owner ->
        val initial = when (owner) {
            is PolicyScope.Profile -> policy.profilePolicies.firstOrNull { it.profileId == owner.id }?.lifecycle
            is PolicyScope.Folder -> policy.folderPolicies.firstOrNull { it.folderId == owner.id }?.lifecycle
            PolicyScope.Device -> null
        } ?: ExitLifecyclePolicy()
        ExpertLifecycleEditor(initial, onDismiss = { lifecycleOwner = null }, onCommit = { lifecycle ->
            val updated = when (owner) {
                is PolicyScope.Profile -> policy.copy(
                    profilePolicies = policy.profilePolicies.filterNot { it.profileId == owner.id } +
                        ProfileExitPolicy(owner.id, lifecycle)
                )
                is PolicyScope.Folder -> {
                    val settings = policy.folderPolicies.firstOrNull { it.folderId == owner.id } ?: FolderPolicy(owner.id)
                    policy.copy(
                        folderPolicies = policy.folderPolicies.filterNot { it.folderId == owner.id } + settings.copy(lifecycle = lifecycle),
                    )
                }
                PolicyScope.Device -> policy
            }
            onIntent(ExpertIntent.Edit(updated))
            lifecycleOwner = null
        })
    }
    if (chooseOwner) {
        AlertDialog(
            onDismissRequest = { chooseOwner = false }, title = { Text(stringResource(R.string.expert_choose_scope)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    (listOf(PolicyScope.Device) + policy.trees.map { it.scope }).forEach { owner ->
                        TextButton({
                            editingChannel = PolicyChannel(
                                UUID.randomUUID().toString(), "", owner,
                                if (owner == PolicyScope.Device) PolicyTarget.Block else PolicyTarget.CurrentExit
                            )
                            chooseOwner = false
                        }, Modifier.fillMaxWidth()) { Text(scopeTitle(owner, bundle)) }
                    }
                }
            }, confirmButton = { TextButton({ chooseOwner = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    editingChannel?.let { initial ->
        var channel by remember(initial.id) { mutableStateOf(initial) }
        var seconds by remember(initial.id) { mutableStateOf(expertSecondsInput(initial.lifecycle.idleTimeoutMs)) }
        val validMilliseconds = expertIdleMilliseconds(seconds)
        AlertDialog(
            onDismissRequest = { editingChannel = null }, title = { Text(stringResource(R.string.expert_channel_add)) },
            text = {
                Column(
                    Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        channel.name, { channel = channel.copy(name = it) }, label = { Text(stringResource(R.string.expert_channel_name)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    ExpertHint(R.string.expert_channel_hint)
                    Text(stringResource(R.string.expert_channel_incoming), style = MaterialTheme.typography.labelLarge)
                    val references = (listOf(policy.device) + policy.trees).flatMap { it.nodes }
                        .filter { (it.target as? PolicyTarget.Channel)?.id == channel.id }
                    references.forEach { node -> Text(node.title.ifBlank { stringResource(R.string.expert_rule_unnamed) }) }
                    if (references.isEmpty() &&
                        channel.id in policy.channels.map { it.id } &&
                        (listOf(policy.device) + policy.trees).none { (it.defaultTarget as? PolicyTarget.Channel)?.id == channel.id } &&
                        policy.channels.none { it.id != channel.id && (it.target as? PolicyTarget.Channel)?.id == channel.id }
                    ) {
                        TextButton({
                            onIntent(ExpertIntent.Edit(ExpertEdits.removeChannel(policy, channel.id)))
                            editingChannel = null
                        }) { Text(stringResource(R.string.expert_channel_remove)) }
                    }
                    ExpertTargetSelector(channel.target, channel.owner, bundle, policy, false, includeChannels = false) {
                        channel = channel.copy(target = it)
                    }
                    ExpertCheckRow(R.string.expert_cold_start, channel.lifecycle.coldStart) {
                        channel = channel.copy(lifecycle = channel.lifecycle.copy(coldStart = it))
                    }
                    ExpertHint(R.string.expert_cold_hint)
                    OutlinedTextField(
                        seconds, { seconds = it }, label = { Text(stringResource(R.string.expert_idle_seconds)) },
                        isError = validMilliseconds == null, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            }, confirmButton = {
                Button(onClick = {
                    val complete = channel.copy(
                        name = channel.name.trim(),
                        lifecycle = channel.lifecycle.copy(idleTimeoutMs = requireNotNull(validMilliseconds)),
                    )
                    onIntent(ExpertIntent.Edit(policy.copy(channels = policy.channels.filterNot { it.id == complete.id } + complete)))
                    editingChannel = null
                }, enabled = channel.name.isNotBlank() && validMilliseconds != null) {
                    Text(stringResource(R.string.expert_node_accept))
                }
            },
            dismissButton = { TextButton({ editingChannel = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun ExpertExitControls(
    exit: ExpertExitState,
    running: Boolean,
    onIntent: (ExpertIntent) -> Unit,
    unsupported: Boolean = false,
) {
    val awake = exit.phase in setOf(ExitPhase.READY, ExitPhase.DEGRADED)
    if (unsupported) {
        ExpertHint(R.string.expert_external_corporate_unavailable)
    } else if (exit.phase == ExitPhase.READY && exit.latencyMs == null) {
        ExpertHint(R.string.expert_exit_ready_unverified)
    }
    OutlinedButton(
        onClick = {
            onIntent(if (awake) ExpertIntent.SleepExit(exit.key) else ExpertIntent.WakeExit(exit.key))
        }, enabled = running && !unsupported && exit.phase !in setOf(ExitPhase.STARTING, ExitPhase.DRAINING),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(if (awake) R.string.expert_sleep_exit else R.string.expert_wake_exit))
    }
    Text(
        stringResource(R.string.expert_observation_connections, exit.activeFlows, exit.pendingFlows),
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun LifecycleSummary(lifecycle: ExitLifecyclePolicy, onEdit: () -> Unit) {
    Text(
        stringResource(if (lifecycle.coldStart) R.string.expert_cold_start else R.string.expert_lifecycle_warm),
        style = MaterialTheme.typography.bodySmall
    )
    if (lifecycle.coldStart) {
        Text(
            stringResource(R.string.expert_lifecycle_cold, idleDurationTitle(lifecycle.idleTimeoutMs)),
            style = MaterialTheme.typography.bodySmall
        )
    }
    OutlinedButton(onEdit, Modifier.fillMaxWidth()) { Text(stringResource(R.string.expert_lifecycle)) }
}

@Composable
private fun ExpertLifecycleEditor(initial: ExitLifecyclePolicy, onDismiss: () -> Unit, onCommit: (ExitLifecyclePolicy) -> Unit) {
    var cold by remember(initial) { mutableStateOf(initial.coldStart) }
    var seconds by remember(initial) { mutableStateOf(expertSecondsInput(initial.idleTimeoutMs)) }
    val valid = expertIdleAfterEdit(initial.idleTimeoutMs, cold, seconds)
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(stringResource(R.string.expert_lifecycle)) },
        text = {
            Column(
                Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ExpertCheckRow(R.string.expert_cold_start, cold) { cold = it }
                ExpertHint(R.string.expert_cold_hint)
                OutlinedTextField(
                    seconds, { seconds = it }, label = { Text(stringResource(R.string.expert_idle_seconds)) },
                    isError = cold && valid == null, enabled = cold, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
        }, confirmButton = {
            Button(onClick = {
                onCommit(initial.copy(coldStart = cold, idleTimeoutMs = requireNotNull(valid)))
            }, enabled = !cold || valid != null) { Text(stringResource(R.string.expert_node_accept)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
