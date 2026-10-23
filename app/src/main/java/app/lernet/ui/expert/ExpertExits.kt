package app.lernet.ui.expert

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.config.policy.ExternalExitKind
import app.lernet.config.policy.ExternalExitProfiles
import app.lernet.config.policy.ExternalExitRequest
import app.lernet.config.transfer.TransferBundle
import app.lernet.config.transfer.TransferGroup
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
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyChannel
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.ProfileExitPolicy
import app.lernet.routing.policy.normalized
import app.lernet.ui.components.PanelCard
import java.util.UUID
import kotlinx.coroutines.launch

private const val SEGMENT_CONFIGS = 0
private const val SEGMENT_CHANNELS = 1

@Composable
internal fun ExpertExits(
    runtime: ExpertRuntimeState,
    bundle: TransferBundle,
    onIntent: (ExpertIntent) -> Unit,
    onSaveExternal: (suspend (ExternalExitRequest, TransferProfile?) -> String?)? = null,
    onSaveDraft: (suspend (NetworkPolicy, NetworkPolicy) -> String?)? = null,
    formMemory: ExpertFormMemory = remember { ExpertFormMemory() },
) {
    var editingChannel by rememberSaveable(stateSaver = ExpertOptionalJsonSaver<PolicyChannel>()) { mutableStateOf<PolicyChannel?>(null) }
    var chooseOwner by remember { mutableStateOf(false) }
    var lifecycleOwner by rememberSaveable(stateSaver = ExpertOptionalJsonSaver<PolicyScope>()) { mutableStateOf<PolicyScope?>(null) }
    var externalEditor by rememberSaveable { mutableStateOf(false) }
    var externalProfile by remember { mutableStateOf(formMemory.externalProfile) }
    var externalProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var externalFingerprint by rememberSaveable { mutableStateOf<String?>(null) }
    var externalDetails by remember { mutableStateOf<TransferProfile?>(null) }
    var liveDetails by remember { mutableStateOf<ExpertExitState?>(null) }
    var folderSettingsId by rememberSaveable { mutableStateOf<String?>(null) }
    var preferredOpen by remember { mutableStateOf(false) }
    var profileDetails by remember { mutableStateOf<TransferProfile?>(null) }
    var sleepingOnly by rememberSaveable { mutableStateOf(false) }
    var segment by rememberSaveable { mutableIntStateOf(SEGMENT_CONFIGS) }
    val unsupportedProfiles = remember(bundle.profiles) {
        bundle.profiles.filter {
            runCatching { ExternalExitProfiles.platformRequirement(it) == RoutePlatform.WINDOWS }
                .getOrDefault(false)
        }.mapTo(HashSet()) { it.id }
    }
    val policy = runtime.draft
    val groupedIds = remember(bundle.groups) { bundle.groups.flatMapTo(HashSet()) { it.profileIds } }
    val shownProfiles = bundle.profiles.filter { profile ->
        !sleepingOnly || runtime.exits.any { it.key.profileId == profile.id && it.phase == ExitPhase.SLEEPING }
    }
    val ungrouped = shownProfiles.filter { it.id !in groupedIds }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (bundle.profiles.isEmpty()) {
            PanelCard(Modifier.testTag("expert_exits_empty")) {
                Text(stringResource(R.string.expert_exits_empty_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.expert_exits_empty_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
        ExpertDraftActions(runtime, onIntent)

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            LerSegmented(
                options = listOf(
                    stringResource(R.string.expert_configs_heading),
                    stringResource(R.string.expert_channels_heading),
                ),
                selected = segment,
                onSelect = { segment = it },
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .fillMaxWidth()
                    .testTag("expert_exits_segments"),
            )
        }

        when (segment) {
            SEGMENT_CHANNELS -> ExpertChannelsSegment(
                runtime = runtime,
                bundle = bundle,
                policy = policy,
                onEditChannel = { editingChannel = it },
                onAddChannel = { chooseOwner = true },
            )
            else -> ExpertConfigsSegment(
                runtime = runtime,
                bundle = bundle,
                policy = policy,
                sleepingOnly = sleepingOnly,
                onSleepingOnly = { sleepingOnly = it },
                shownEmpty = sleepingOnly && shownProfiles.isEmpty(),
                ungrouped = ungrouped,
                unsupportedProfiles = unsupportedProfiles,
                onFolderSettings = { folderSettingsId = it },
                onFolderStrategy = { folderId, mode ->
                    val settings = policy.folderPolicies.firstOrNull { it.folderId == folderId } ?: FolderPolicy(folderId)
                    onIntent(
                        ExpertIntent.EditChecked(
                            policy,
                            policy.copy(
                                folderPolicies = policy.folderPolicies.filterNot { it.folderId == folderId } +
                                    settings.copy(selection = mode),
                            ),
                        ),
                    )
                },
                onProfileTap = { profileDetails = it },
                onSaveExternal = onSaveExternal,
                onOpenExternalEditor = {
                    externalProfile = null
                    formMemory.externalProfile = null
                    externalProfileId = null
                    externalFingerprint = null
                    formMemory.passwords.clear()
                    externalEditor = true
                },
            )
        }
    }

    folderSettingsId?.let { folderId ->
        val folder = bundle.groups.firstOrNull { it.id == folderId }
        if (folder == null) {
            folderSettingsId = null
        } else {
            val settings = policy.folderPolicies.firstOrNull { it.folderId == folder.id } ?: FolderPolicy(folder.id)
            fun update(next: FolderPolicy) {
                onIntent(
                    ExpertIntent.EditChecked(
                        policy,
                        policy.copy(folderPolicies = policy.folderPolicies.filterNot { it.folderId == folder.id } + next),
                    ),
                )
            }
            AlertDialog(
                onDismissRequest = { folderSettingsId = null },
                title = { Text(stringResource(R.string.expert_folder_settings)) },
                text = {
                    Column(
                        Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(folder.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(
                                when (settings.selection.normalized()) {
                                    FolderSelection.PREFERRED -> R.string.expert_folder_preferred_hint
                                    FolderSelection.STABLE, FolderSelection.LOWEST_LATENCY -> R.string.expert_folder_stable_hint
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(stringResource(R.string.expert_folder_preferred), style = MaterialTheme.typography.labelLarge)
                        OutlinedButton(
                            { preferredOpen = true },
                            Modifier.fillMaxWidth().testTag("expert_folder_preferred_pick"),
                        ) {
                            Text(
                                bundle.profiles.firstOrNull { it.id == settings.preferredProfileId }?.name
                                    ?: stringResource(R.string.expert_folder_preferred_none),
                            )
                        }
                        ExpertCheckRow(R.string.expert_folder_swap, settings.autoSwap) { update(settings.copy(autoSwap = it)) }
                        LifecycleSummary(settings.lifecycle) { lifecycleOwner = PolicyScope.Folder(folder.id) }
                        runtime.selectedFolderProfiles[folder.id]?.let { selected ->
                            Text(
                                bundle.profiles.firstOrNull { it.id == selected }?.name ?: selected,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton({ folderSettingsId = null }) { Text(stringResource(R.string.expert_node_accept)) }
                },
            )
            if (preferredOpen) {
                AlertDialog(
                    onDismissRequest = { preferredOpen = false },
                    title = { Text(stringResource(R.string.expert_folder_preferred)) },
                    text = {
                        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                            TextButton({
                                update(settings.copy(preferredProfileId = null))
                                preferredOpen = false
                            }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.expert_folder_preferred_none)) }
                            bundle.profiles.filter { it.id in folder.profileIds }.forEach { profile ->
                                TextButton({
                                    update(settings.copy(preferredProfileId = profile.id))
                                    preferredOpen = false
                                }, Modifier.fillMaxWidth()) { Text(profile.name) }
                            }
                        }
                    },
                    confirmButton = { TextButton({ preferredOpen = false }) { Text(stringResource(R.string.cancel)) } },
                )
            }
        }
    }

    profileDetails?.let { profile ->
        val settings = policy.profilePolicies.firstOrNull { it.profileId == profile.id } ?: ProfileExitPolicy(profile.id)
        val external = remember(profile) { ExternalExitProfiles.describe(profile) }
        val windowsOnly = profile.id in unsupportedProfiles
        val liveExits = runtime.exits.filter { it.key.profileId == profile.id && it.key.channelId == null }
        AlertDialog(
            onDismissRequest = { profileDetails = null },
            title = { Text(profile.name) },
            text = {
                Column(
                    Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (external != null || windowsOnly) {
                        if (windowsOnly) {
                            ExpertHint(R.string.expert_external_corporate_unavailable)
                        } else {
                            val described = requireNotNull(external)
                            Text(
                                stringResource(
                                    if (described.kind == ExternalExitKind.SOCKS5) {
                                        R.string.expert_external_socks
                                    } else {
                                        R.string.expert_external_http
                                    },
                                ),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text("${described.host}:${described.port}", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(
                                onClick = {
                                    externalDetails = profile
                                    profileDetails = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.expert_external_details)) }
                        }
                    }
                    LifecycleSummary(settings.lifecycle) { lifecycleOwner = PolicyScope.Profile(profile.id) }
                    if (liveExits.isEmpty()) {
                        Text(
                            stringResource(R.string.expert_exit_card_idle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    liveExits.forEach { exit ->
                        ExpertExitStatusLine(exit)
                        TextButton({
                            liveDetails = exit
                            profileDetails = null
                        }) { Text(stringResource(R.string.expert_live_exit_details)) }
                        ExpertExitControls(exit, runtime.phase == ExpertSessionPhase.RUNNING, onIntent, windowsOnly)
                    }
                }
            },
            confirmButton = {
                TextButton({ profileDetails = null }) { Text(stringResource(R.string.expert_node_accept)) }
            },
        )
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
        val expected = externalProfile ?: externalProfileId?.let { id -> bundle.profiles.firstOrNull { it.id == id } }
        ExpertExternalExitEditor(expected, onDismiss = {
            externalEditor = false; formMemory.passwords.clear(); formMemory.externalProfile = null
        }, onSave = { request, profile ->
            if (externalProfileId != null && (profile == null || ExternalExitProfiles.fingerprint(profile) != externalFingerprint)) {
                "Конфигурация изменилась. Открой её свойства заново."
            } else onSaveExternal(request, profile)
        }, formMemory = formMemory)
    }
    externalDetails?.let { profile ->
        val editAction: (() -> Unit)? = if (onSaveExternal == null) {
            null
        } else {
            {
                externalProfile = profile
                formMemory.externalProfile = profile
                externalProfileId = profile.id
                externalFingerprint = ExternalExitProfiles.fingerprint(profile)
                formMemory.passwords.clear()
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
        val base by rememberSaveable(owner, stateSaver = ExpertPolicySaver) { mutableStateOf(policy) }
        val initial = when (owner) {
            is PolicyScope.Profile -> base.profilePolicies.firstOrNull { it.profileId == owner.id }?.lifecycle
            is PolicyScope.Folder -> base.folderPolicies.firstOrNull { it.folderId == owner.id }?.lifecycle
            PolicyScope.Device -> null
        } ?: ExitLifecyclePolicy()
        ExpertLifecycleEditor(initial, onDismiss = { lifecycleOwner = null }, onCommit = { lifecycle ->
            val updated = when (owner) {
                is PolicyScope.Profile -> base.copy(
                    profilePolicies = base.profilePolicies.filterNot { it.profileId == owner.id } +
                        ProfileExitPolicy(owner.id, lifecycle)
                )
                is PolicyScope.Folder -> {
                    val settings = base.folderPolicies.firstOrNull { it.folderId == owner.id } ?: FolderPolicy(owner.id)
                    base.copy(
                        folderPolicies = base.folderPolicies.filterNot { it.folderId == owner.id } + settings.copy(lifecycle = lifecycle),
                    )
                }
                PolicyScope.Device -> base
            }
            if (onSaveDraft == null) "Сохранение недоступно" else onSaveDraft(base, updated)
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
        val base by rememberSaveable(initial.id, stateSaver = ExpertPolicySaver) { mutableStateOf(policy) }
        var channel by rememberSaveable(initial.id, stateSaver = androidx.compose.runtime.saveable.Saver<PolicyChannel, String>(
            save = { kotlinx.serialization.json.Json.encodeToString(PolicyChannel.serializer(), it) },
            restore = { kotlinx.serialization.json.Json.decodeFromString(PolicyChannel.serializer(), it) },
        )) { mutableStateOf(initial) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val commitScope = rememberCoroutineScope()
        var seconds by rememberSaveable(initial.id) { mutableStateOf(expertSecondsInput(initial.lifecycle.idleTimeoutMs)) }
        val validMilliseconds = expertIdleAfterEdit(initial.lifecycle.idleTimeoutMs, channel.lifecycle.coldStart, seconds)
        AlertDialog(
            onDismissRequest = { if (!busy) editingChannel = null }, title = { Text(stringResource(R.string.expert_channel_add)) },
            text = {
                Column(
                    Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        channel.name, { if (!busy) channel = channel.copy(name = it) }, label = { Text(stringResource(R.string.expert_channel_name)) },
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
                            busy = true
                            commitScope.launch {
                                try {
                                    error = if (onSaveDraft == null) "Сохранение недоступно" else onSaveDraft(base, ExpertEdits.removeChannel(base, channel.id))
                                    if (error == null) editingChannel = null
                                } finally { busy = false }
                            }
                        }, enabled = !busy) { Text(stringResource(R.string.expert_channel_remove)) }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    ExpertTargetSelector(channel.target, channel.owner, bundle, policy, false, includeChannels = false) {
                        if (!busy) channel = channel.copy(target = it)
                    }
                    ExpertCheckRow(R.string.expert_cold_start, channel.lifecycle.coldStart, enabled = !busy) {
                        if (!busy) channel = channel.copy(lifecycle = channel.lifecycle.copy(coldStart = it))
                    }
                    ExpertHint(R.string.expert_cold_hint)
                    OutlinedTextField(
                        seconds, { if (!busy) seconds = it }, label = { Text(stringResource(R.string.expert_idle_seconds)) },
                        isError = channel.lifecycle.coldStart && validMilliseconds == null,
                        enabled = channel.lifecycle.coldStart && !busy, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            }, confirmButton = {
                Button(onClick = {
                    val complete = channel.copy(
                        name = channel.name.trim(),
                        lifecycle = channel.lifecycle.copy(idleTimeoutMs = requireNotNull(validMilliseconds)),
                    )
                    busy = true
                    commitScope.launch {
                        try {
                            error = if (onSaveDraft == null) "Сохранение недоступно" else
                                onSaveDraft(base, base.copy(channels = base.channels.filterNot { it.id == complete.id } + complete))
                            if (error == null) editingChannel = null
                        } finally { busy = false }
                    }
                }, enabled = !busy && channel.name.isNotBlank() && validMilliseconds != null) {
                    Text(stringResource(R.string.expert_node_accept))
                }
            },
            dismissButton = { TextButton({ editingChannel = null }, enabled = !busy) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun ExpertConfigsSegment(
    runtime: ExpertRuntimeState,
    bundle: TransferBundle,
    policy: NetworkPolicy,
    sleepingOnly: Boolean,
    onSleepingOnly: (Boolean) -> Unit,
    shownEmpty: Boolean,
    ungrouped: List<TransferProfile>,
    unsupportedProfiles: Set<String>,
    onFolderSettings: (String) -> Unit,
    onFolderStrategy: (String, FolderSelection) -> Unit,
    onProfileTap: (TransferProfile) -> Unit,
    onSaveExternal: (suspend (ExternalExitRequest, TransferProfile?) -> String?)?,
    onOpenExternalEditor: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("expert_exits_configs")) {
        Text(
            stringResource(R.string.expert_configs_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (runtime.exits.isNotEmpty()) {
            FilterChip(
                sleepingOnly,
                { onSleepingOnly(!sleepingOnly) },
                label = { Text(stringResource(R.string.expert_sleeping_only)) },
                modifier = Modifier.testTag("expert_sleeping_only"),
            )
        }
        if (shownEmpty) ExpertHint(R.string.expert_sleeping_none)

        bundle.groups.forEach { folder ->
            val settings = policy.folderPolicies.firstOrNull { it.folderId == folder.id } ?: FolderPolicy(folder.id)
            val members = folder.profileIds.mapNotNull { id -> bundle.profiles.firstOrNull { it.id == id } }
                .filter { profile ->
                    !sleepingOnly || runtime.exits.any { it.key.profileId == profile.id && it.phase == ExitPhase.SLEEPING }
                }
            FolderConfigCard(
                folder = folder,
                settings = settings,
                members = members,
                runtime = runtime,
                unsupportedProfiles = unsupportedProfiles,
                onStrategy = { onFolderStrategy(folder.id, it) },
                onSettings = { onFolderSettings(folder.id) },
                onProfileTap = onProfileTap,
            )
        }

        if (ungrouped.isNotEmpty()) {
            PanelCard(Modifier.testTag("expert_folder_ungrouped")) {
                Text(stringResource(R.string.expert_folder_ungrouped), style = MaterialTheme.typography.titleSmall)
                Column {
                    ungrouped.forEachIndexed { index, profile ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        }
                        ConfigDenseRow(
                            profile = profile,
                            exit = primaryExit(runtime, profile.id),
                            windowsOnly = profile.id in unsupportedProfiles,
                            selected = runtime.selectedFolderProfiles.values.any { it == profile.id },
                            onClick = { onProfileTap(profile) },
                        )
                    }
                }
            }
        }

        if (onSaveExternal != null) {
            TextButton(
                onClick = onOpenExternalEditor,
                modifier = Modifier.fillMaxWidth().testTag("expert_external_advanced"),
            ) {
                Text(stringResource(R.string.expert_external_advanced))
            }
        }
    }
}

@Composable
private fun FolderConfigCard(
    folder: TransferGroup,
    settings: FolderPolicy,
    members: List<TransferProfile>,
    runtime: ExpertRuntimeState,
    unsupportedProfiles: Set<String>,
    onStrategy: (FolderSelection) -> Unit,
    onSettings: () -> Unit,
    onProfileTap: (TransferProfile) -> Unit,
) {
    val current = settings.selection.normalized()
    PanelCard(Modifier.testTag("expert_folder_${folder.id}")) {
        Row(
            Modifier.fillMaxWidth().testTag("expert_folder_header_${folder.id}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                folder.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = true).widthIn(min = 72.dp),
            )
            StrategyChip(
                label = stringResource(R.string.expert_folder_strategy_preferred),
                selected = current == FolderSelection.PREFERRED,
                onClick = { onStrategy(FolderSelection.PREFERRED) },
                tag = "expert_folder_chip_preferred_${folder.id}",
            )
            StrategyChip(
                label = stringResource(R.string.expert_folder_strategy_stable),
                selected = current == FolderSelection.STABLE || current == FolderSelection.LOWEST_LATENCY,
                onClick = { onStrategy(FolderSelection.STABLE) },
                tag = "expert_folder_chip_stable_${folder.id}",
            )
            Icon(
                Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.expert_folder_settings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onSettings)
                    .padding(4.dp)
                    .testTag("expert_folder_settings_${folder.id}"),
            )
        }
        if (members.isEmpty()) {
            Text(
                stringResource(R.string.expert_exit_card_idle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column {
                members.forEachIndexed { index, profile ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    }
                    ConfigDenseRow(
                        profile = profile,
                        exit = primaryExit(runtime, profile.id),
                        windowsOnly = profile.id in unsupportedProfiles,
                        selected = runtime.selectedFolderProfiles[folder.id] == profile.id,
                        onClick = { onProfileTap(profile) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpertChannelsSegment(
    runtime: ExpertRuntimeState,
    bundle: TransferBundle,
    policy: NetworkPolicy,
    onEditChannel: (PolicyChannel) -> Unit,
    onAddChannel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("expert_exits_channels")) {
        if (policy.channels.isEmpty()) {
            Text(
                stringResource(R.string.expert_channels_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            PanelCard {
                Column {
                    policy.channels.forEachIndexed { index, channel ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        }
                        ChannelDenseRow(
                            channel = channel,
                            ownerTitle = scopeTitle(channel.owner, bundle),
                            targetTitle = targetTitle(channel.target, bundle, policy),
                            exit = runtime.exits.firstOrNull { it.key.channelId == channel.id },
                            onClick = { onEditChannel(channel) },
                        )
                    }
                }
            }
        }
        if (bundle.profiles.isNotEmpty()) {
            Button({ onAddChannel() }, Modifier.fillMaxWidth().testTag("expert_channel_add")) {
                Text(stringResource(R.string.expert_channel_add))
            }
        }
    }
}

@Composable
private fun StrategyChip(label: String, selected: Boolean, onClick: () -> Unit, tag: String) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .heightIn(min = 28.dp)
            .clip(shape)
            .background(if (selected) scheme.secondaryContainer else scheme.surface)
            .border(1.dp, if (selected) scheme.secondaryContainer else scheme.outlineVariant, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = { if (!selected) onClick() })
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConfigDenseRow(
    profile: TransferProfile,
    exit: ExpertExitState?,
    windowsOnly: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp)
            .testTag("expert_config_row_${profile.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                profile.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (windowsOnly) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (windowsOnly) {
                Text(
                    stringResource(R.string.expert_external_corporate_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            exitShortStatus(exit),
            style = MaterialTheme.typography.labelMedium,
            color = exitStatusColor(exit),
            maxLines = 1,
        )
        Text(
            exit?.latencyMs?.let { stringResource(R.string.expert_latency_ms, it) }
                ?: stringResource(R.string.expert_exit_ping_none),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            stringResource(R.string.expert_exit_requests_short, exit?.activeFlows ?: 0),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun ChannelDenseRow(
    channel: PolicyChannel,
    ownerTitle: String,
    targetTitle: String,
    exit: ExpertExitState?,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp)
            .testTag("expert_channel_row_${channel.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(channel.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "$ownerTitle · $targetTitle",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            exitShortStatus(exit),
            style = MaterialTheme.typography.labelMedium,
            color = exitStatusColor(exit),
            maxLines = 1,
        )
        Text(
            exit?.latencyMs?.let { stringResource(R.string.expert_latency_ms, it) }
                ?: stringResource(R.string.expert_exit_ping_none),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            stringResource(R.string.expert_exit_requests_short, exit?.activeFlows ?: 0),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun exitShortStatus(exit: ExpertExitState?): String = when (exit?.phase) {
    null -> stringResource(R.string.expert_exit_card_idle)
    ExitPhase.SLEEPING -> stringResource(R.string.expert_exit_sleeping)
    ExitPhase.STARTING -> stringResource(R.string.expert_exit_starting_short)
    ExitPhase.READY -> stringResource(R.string.expert_exit_ready_short)
    ExitPhase.DEGRADED -> stringResource(R.string.expert_exit_degraded_short)
    ExitPhase.FAILED -> stringResource(R.string.expert_exit_failed)
    ExitPhase.DRAINING -> stringResource(R.string.expert_exit_draining_short)
}

@Composable
private fun exitStatusColor(exit: ExpertExitState?) = when (exit?.phase) {
    ExitPhase.READY -> MaterialTheme.colorScheme.primary
    ExitPhase.DEGRADED -> MaterialTheme.colorScheme.tertiary
    ExitPhase.FAILED -> MaterialTheme.colorScheme.error
    ExitPhase.SLEEPING, ExitPhase.DRAINING, ExitPhase.STARTING -> MaterialTheme.colorScheme.onSurfaceVariant
    null -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun primaryExit(runtime: ExpertRuntimeState, profileId: String): ExpertExitState? =
    runtime.exits.firstOrNull { it.key.profileId == profileId && it.key.channelId == null }

@Composable
private fun ExpertExitStatusLine(exit: ExpertExitState) {
    Text(stringResource(exit.phase.titleResource()), style = MaterialTheme.typography.titleSmall)
    exit.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Text(
        stringResource(
            R.string.expert_exit_card_latency,
            exit.latencyMs?.let { stringResource(R.string.expert_latency_ms, it) }
                ?: stringResource(R.string.expert_value_unknown),
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        stringResource(R.string.expert_observation_connections, exit.activeFlows, exit.pendingFlows),
        style = MaterialTheme.typography.labelSmall,
    )
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
private fun ExpertLifecycleEditor(initial: ExitLifecyclePolicy, onDismiss: () -> Unit, onCommit: suspend (ExitLifecyclePolicy) -> String?) {
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val commitScope = rememberCoroutineScope()
    var cold by rememberSaveable(initial) { mutableStateOf(initial.coldStart) }
    var seconds by rememberSaveable(initial) { mutableStateOf(expertSecondsInput(initial.idleTimeoutMs)) }
    val valid = expertIdleAfterEdit(initial.idleTimeoutMs, cold, seconds)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(R.string.expert_lifecycle)) },
        text = {
            Column(
                Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                ExpertCheckRow(R.string.expert_cold_start, cold) { if (!busy) cold = it }
                ExpertHint(R.string.expert_cold_hint)
                OutlinedTextField(
                    seconds, { if (!busy) seconds = it }, label = { Text(stringResource(R.string.expert_idle_seconds)) },
                    isError = cold && valid == null, enabled = cold, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
        }, confirmButton = {
            Button(onClick = {
                busy = true
                val next = initial.copy(coldStart = cold, idleTimeoutMs = requireNotNull(valid))
                commitScope.launch {
                    try {
                        error = onCommit(next)
                        if (error == null) onDismiss()
                    } finally { busy = false }
                }
            }, enabled = !busy && (!cold || valid != null)) { Text(stringResource(R.string.expert_node_accept)) }
        },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } }
    )
}
