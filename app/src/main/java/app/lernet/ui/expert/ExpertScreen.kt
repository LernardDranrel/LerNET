package app.lernet.ui.expert

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lernet.R
import app.lernet.config.policy.ExternalExitRequest
import app.lernet.config.transfer.TransferProfile
import app.lernet.engine.policy.DirectFamilyAvailability
import app.lernet.engine.policy.ExpertConnectionObservation
import app.lernet.engine.policy.ExpertIntent
import app.lernet.engine.policy.ExpertSessionPhase
import app.lernet.engine.policy.ExpertTunnelHealth
import app.lernet.engine.policy.ExpertVpnHandover
import app.lernet.engine.policy.FlowTrafficRate
import app.lernet.engine.policy.tunnelHealth
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyDnsSettings
import app.lernet.routing.policy.PolicyHealthSettings
import app.lernet.ui.components.LerNetLogo
import app.lernet.ui.components.PanelCard
import app.lernet.ui.controls.NetworkLever
import app.lernet.ui.controls.NetworkLeverLamp
import app.lernet.ui.icons.LerNetSymbols
import app.lernet.ui.motion.rememberReduceMotion

internal enum class ExpertSection(val label: Int) {
    OVERVIEW(R.string.expert_section_overview),
    SCHEMA(R.string.expert_section_schema),
    SIMULATION(R.string.expert_section_simulation),
    EXITS(R.string.expert_section_exits),
    JOURNAL(R.string.expert_section_journal),
    SAFETY(R.string.expert_section_safety),
}

@Composable
internal fun ExpertRoute(
    onVpn: () -> Unit, simpleActive: Boolean,
    section: ExpertSection, onSectionChange: (ExpertSection) -> Unit,
    onOpenDrawer: () -> Unit,
) {
    val viewModel: ExpertViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snackbar = remember { SnackbarHostState() }
    var switchPrompt by rememberSaveable { mutableStateOf(false) }
    var handover by rememberSaveable(stateSaver = ExpertOptionalJsonSaver<Pair<String, NetworkPolicy>>()) { mutableStateOf<Pair<String, NetworkPolicy>?>(null) }
    var keepVpn by rememberSaveable { mutableStateOf(false) }
    var permissionHandover by rememberSaveable(stateSaver = ExpertOptionalJsonSaver<Pair<String, NetworkPolicy>>()) { mutableStateOf<Pair<String, NetworkPolicy>?>(null) }
    val startConfirmed: () -> Unit = {
        val selected = permissionHandover
        if (selected == null) {
            viewModel.onIntent(ExpertIntent.Start)
        } else {
            viewModel.startKeepingVpn(selected.first, selected.second)
        }
    }
    var exportPrompt by rememberSaveable { mutableStateOf(false) }
    var importPrompt by rememberSaveable { mutableStateOf(false) }
    var replaceImport by rememberSaveable { mutableStateOf(false) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        permissionDenied = result.resultCode != Activity.RESULT_OK
        if (!permissionDenied) startConfirmed()
    }
    val prepareStart: () -> Unit = {
        permissionDenied = false
        val inspected = viewModel.refreshEnvironment()
        if (inspected.anotherVpnVisible) {
            permissionDenied = true
        } else {
            val prepare = VpnService.prepare(context)
            if (prepare == null) startConfirmed() else permission.launch(prepare)
        }
    }
    val export = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let(viewModel::export)
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.import(it, replaceImport) }
    }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshEnvironment()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (switchPrompt) {
        AlertDialog(
            onDismissRequest = { switchPrompt = false },
            title = { Text(stringResource(R.string.expert_start_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.expert_start_confirm_body))
                    handover?.let { selected ->
                        val name = state.inventory?.profiles?.firstOrNull { it.id == selected.first }?.name.orEmpty()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = keepVpn, onCheckedChange = { keepVpn = it })
                            Text(stringResource(R.string.expert_keep_vpn_option, name))
                        }
                        Text(stringResource(if (keepVpn) R.string.expert_keep_vpn_body else R.string.expert_direct_switch_body))
                    }
                    if (simpleActive && handover == null) Text(stringResource(R.string.expert_switch_body))
                    if (state.environment?.anotherVpnVisible == true) Text(stringResource(R.string.expert_platform_other_vpn))
                    ExpertHint(R.string.expert_start_environment_limit)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    switchPrompt = false
                    permissionHandover = handover.takeIf { keepVpn }
                    prepareStart()
                }) {
                    Text(stringResource(if (handover != null && keepVpn) R.string.expert_keep_vpn_start else R.string.expert_tab_expert))
                }
            },
            dismissButton = {
                TextButton(onClick = { switchPrompt = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (exportPrompt) {
        AlertDialog(
            onDismissRequest = { exportPrompt = false },
            title = { Text(stringResource(R.string.expert_export)) },
            text = { Text(stringResource(R.string.expert_export_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    exportPrompt = false
                    export.launch("LerNET-network-workspace.json")
                }) {
                    Text(stringResource(R.string.transfer_save))
                }
            }, dismissButton = {
                TextButton(onClick = { exportPrompt = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    if (importPrompt) {
        AlertDialog(
            onDismissRequest = { importPrompt = false },
            title = { Text(stringResource(R.string.expert_import)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.expert_import_modes))
                    OutlinedButton({
                        replaceImport = false
                        importPrompt = false
                        import.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.expert_import_merge)) }
                    OutlinedButton({
                        replaceImport = true
                        importPrompt = false
                        import.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.expert_import_replace)) }
                }
            }, confirmButton = {
                TextButton({ importPrompt = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    ExpertScreen(
        state, snackbar, onVpn, onIntent = viewModel::onIntent,
        onStart = {
            viewModel.refreshEnvironment()
            handover = viewModel.connectedVpnProfileId()?.let { profileId ->
                state.runtime?.takeIf { ExpertVpnHandover.available(it.saved, it.draft) }
                    ?.let { profileId to it.saved }
            }
            keepVpn = false
            switchPrompt = true
        },
        onExport = { exportPrompt = true },
        onAndroidSettings = { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) },
        permissionDenied = permissionDenied,
        onImport = { importPrompt = true },
        onSaveExternal = viewModel::saveExternalExit,
        onSaveHealth = viewModel::saveHealthSettings,
        onSaveDns = viewModel::saveDnsSettings,
        onSaveDraft = viewModel::saveDraft,
        formMemory = viewModel.formMemory,
        selectedSection = section, onSectionChange = onSectionChange, onOpenDrawer = onOpenDrawer,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExpertScreen(
    state: ExpertUiState,
    snackbar: SnackbarHostState,
    onVpn: () -> Unit,
    onIntent: (ExpertIntent) -> Unit,
    onStart: () -> Unit,
    onExport: () -> Unit,
    onAndroidSettings: () -> Unit,
    permissionDenied: Boolean,
    onImport: () -> Unit = {},
    onSaveExternal: (suspend (ExternalExitRequest, TransferProfile?) -> String?)? = null,
    onSaveHealth: (suspend (PolicyHealthSettings, PolicyHealthSettings) -> String?)? = null,
    onSaveDns: (suspend (PolicyDnsSettings, PolicyDnsSettings) -> String?)? = null,
    onSaveDraft: (suspend (NetworkPolicy, NetworkPolicy) -> String?)? = null,
    formMemory: ExpertFormMemory = remember { ExpertFormMemory() },
    selectedSection: ExpertSection? = null,
    onSectionChange: ((ExpertSection) -> Unit)? = null,
    onOpenDrawer: () -> Unit = {},
) {
    var localSection by rememberSaveable { mutableStateOf(ExpertSection.OVERVIEW) }
    val section = selectedSection ?: localSection
    fun selectSection(value: ExpertSection) {
        if (onSectionChange != null) onSectionChange(value) else localSection = value
    }
    var archiveMenu by remember { mutableStateOf(false) }
    var simulationConnection by rememberSaveable(stateSaver = ExpertObservationSaver) { mutableStateOf<ExpertConnectionObservation?>(null) }
    var simulationRequest by rememberSaveable { mutableStateOf(0L) }
    var historicalPath by rememberSaveable { mutableStateOf(false) }
    val sectionStates = rememberSaveableStateHolder()
    var dnsEditor by rememberSaveable(stateSaver = ExpertOptionalJsonSaver<PolicyDnsSettings>()) { mutableStateOf<PolicyDnsSettings?>(null) }
    var healthEditor by rememberSaveable(stateSaver = ExpertOptionalJsonSaver<PolicyHealthSettings>()) { mutableStateOf<PolicyHealthSettings?>(null) }
    val reduced = rememberReduceMotion()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onOpenDrawer) {
                            Icon(LerNetSymbols.menu(), contentDescription = stringResource(R.string.expert_open_menu))
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LerNetLogo(size = 28.dp)
                            Column {
                                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                                Text(stringResource(section.label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    actions = {
                        IconButton(onClick = { archiveMenu = true }, enabled = state.inventory != null) {
                            Icon(
                                LerNetSymbols.more(),
                                contentDescription = stringResource(R.string.expert_archive_actions),
                            )
                        }
                        DropdownMenu(archiveMenu, onDismissRequest = { archiveMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.expert_import)) },
                                onClick = {
                                    archiveMenu = false
                                    onImport()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.expert_export)) },
                                onClick = {
                                    archiveMenu = false
                                    onExport()
                                },
                            )
                        }
                    }
                )
                AppModeTabs(true, onVpn, {})

            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val runtime = state.runtime
        val bundle = state.inventory
        if (runtime == null || bundle == null) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.storageError == null) CircularProgressIndicator()
                Text(state.storageError ?: stringResource(R.string.expert_loading))
            }
        } else {
            val content: @Composable (ExpertSection) -> Unit = { visible ->
                if (visible == ExpertSection.SCHEMA) {
                    Column(
                        Modifier.fillMaxSize().padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.storageError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                        }
                        if (permissionDenied) {
                            Text(
                                stringResource(
                                    if (state.environment?.anotherVpnVisible == true) {
                                        R.string.expert_platform_other_vpn
                                    } else {
                                        R.string.expert_permission_denied
                                    }
                                ),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                        ExpertSchema(runtime, bundle, onIntent, Modifier.weight(1f), onSaveDraft)
                    }
                } else if (visible == ExpertSection.OVERVIEW) {
                    ExpertActivity(runtime, bundle, onSimulate = { connection, historical ->
                        simulationConnection = connection
                        historicalPath = historical
                        simulationRequest++
                        selectSection(ExpertSection.SIMULATION)
                    }, onClear = { onIntent(ExpertIntent.ClearConnectionHistory) }, header = { rate ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.storageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (permissionDenied) {
                                Text(stringResource(R.string.expert_permission_denied), color = MaterialTheme.colorScheme.error)
                            }
                            ExpertStatus(state, onIntent, onStart, rate, reduced)
                        }
                    })
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (state.storageError != null) {
                            item {
                                Text(state.storageError, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (permissionDenied) {
                            item {
                                Text(
                                    stringResource(
                                        if (state.environment?.anotherVpnVisible == true) {
                                            R.string.expert_platform_other_vpn
                                        } else {
                                            R.string.expert_permission_denied
                                        }
                                    ),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        when (visible) {
                            ExpertSection.OVERVIEW -> Unit
                            ExpertSection.SCHEMA -> Unit
                            ExpertSection.EXITS -> item { ExpertExits(runtime, bundle, onIntent, onSaveExternal, onSaveDraft, formMemory) }
                            ExpertSection.SIMULATION -> item { ExpertSimulation(runtime, bundle, simulationConnection, historicalPath, simulationRequest, formMemory) }
                            ExpertSection.SAFETY -> item {
                                OutlinedButton(
                                    { dnsEditor = runtime.draft.dns }, Modifier.fillMaxWidth(),
                                    enabled = onSaveDns != null,
                                ) { Text(stringResource(R.string.expert_dns_title)) }

                                OutlinedButton(
                                    { healthEditor = runtime.draft.health }, Modifier.fillMaxWidth(),
                                    enabled =
                                    onSaveHealth != null
                                ) {
                                    Text(stringResource(R.string.expert_health_title))
                                }
                                PanelCard {
                                    Text(
                                        stringResource(R.string.expert_safety_title),
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                    Text(
                                        stringResource(
                                            R.string.expert_protected_count,
                                            (runtime.draft.device.nodes + runtime.draft.trees.flatMap { it.nodes })
                                                .count { it.protected },
                                        )
                                    )
                                    Text(stringResource(R.string.expert_inactive_count, runtime.inactiveNodeIds.size))
                                    ExpertInactiveProtections(state)
                                    ExpertHint(R.string.expert_safety_body)
                                    ExpertHint(R.string.expert_domain_identity_limit)
                                    Text(
                                        stringResource(
                                            if (state.environment?.crashProtectionVerified == true) {
                                                R.string.expert_lockdown_verified
                                            } else {
                                                R.string.expert_lockdown_unverified
                                            }
                                        ),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    if (state.environment?.packageAttributionAvailable == false) {
                                        ExpertHint(R.string.expert_package_limit)
                                    }
                                    Button(onAndroidSettings, Modifier.fillMaxWidth()) {
                                        Text(stringResource(R.string.expert_android_settings))
                                    }
                                }
                            }
                            ExpertSection.JOURNAL -> {
                                if (runtime.reasons.isEmpty()) {
                                    item {
                                        Text(stringResource(R.string.expert_journal_empty))
                                    }
                                }
                                runtime.reasons.asReversed().forEach { reason ->
                                    item(key = reason.sequence) {
                                        PanelCard {
                                            Text(reason.message, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Column(Modifier.padding(padding)) {
                if (reduced) {
                    sectionStates.SaveableStateProvider(section) { content(section) }
                } else {
                    AnimatedContent(section, label = "expert-section") {
                        sectionStates.SaveableStateProvider(it) { content(it) }
                    }
                }
            }
            if (dnsEditor != null && onSaveDns != null) {
                val initialDns = requireNotNull(dnsEditor)
                ExpertDnsEditor(initialDns, onDismiss = { dnsEditor = null }, onCommit = { onSaveDns(initialDns, it) })
            }
            if (healthEditor != null && onSaveHealth != null) {
                val initialHealth = requireNotNull(healthEditor)
                ExpertHealthEditor(
                    initialHealth,
                    onDismiss = { healthEditor = null },
                    onCommit = { onSaveHealth(initialHealth, it) },
                )
            }
        }
    }
}

@Composable
private fun ExpertStatus(
    state: ExpertUiState,
    onIntent: (ExpertIntent) -> Unit,
    onStart: () -> Unit,
    rate: FlowTrafficRate? = null,
    reducedMotion: Boolean = false,
) {
    val runtime = requireNotNull(state.runtime)
    val health = runtime.tunnelHealth
    val statusText = runtime.networkReason ?: when {
        health == ExpertTunnelHealth.ERROR -> {
            val failedExit = runtime.exits.firstOrNull {
                it.phase == app.lernet.engine.policy.ExitPhase.FAILED || it.phase == app.lernet.engine.policy.ExitPhase.DEGRADED
            }
            if (runtime.phase == ExpertSessionPhase.FAILED) {
                runtime.errors.firstOrNull() ?: stringResource(R.string.expert_lamp_error)
            } else {
                failedExit?.reason ?: failedExit?.let { stringResource(it.phase.titleResource()) }
                    ?: stringResource(R.string.expert_lamp_error)
            }
        }
        health == ExpertTunnelHealth.PENDING && runtime.phase == ExpertSessionPhase.RUNNING ->
            stringResource(R.string.expert_lamp_pending)
        else -> stringResource(runtime.phase.titleResource())
    }
    var details by rememberSaveable { mutableStateOf(false) }
    PanelCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val summary: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.expert_tunnel_title), textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineSmall)
                Text(statusText, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                    color = when (runtime.tunnelHealth) {
                        ExpertTunnelHealth.ERROR -> MaterialTheme.colorScheme.error
                        ExpertTunnelHealth.PENDING -> MaterialTheme.colorScheme.tertiary
                        ExpertTunnelHealth.HEALTHY -> MaterialTheme.colorScheme.secondary
                        ExpertTunnelHealth.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
                    })
                state.inventory?.let { inventory ->
                    val policy = runtime.appliedPolicy ?: runtime.saved
                    Text(
                        stringResource(R.string.expert_default_path_summary, targetTitle(policy.device.defaultTarget, inventory, policy)),
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center
                    )
                }
                }
            }
            val lever: @Composable () -> Unit = {
            NetworkLever(
                checked = runtime.desiredEnabled,
                enabled = !runtime.applying &&
                    runtime.phase !in setOf(ExpertSessionPhase.STARTING, ExpertSessionPhase.STOPPING),
                onCheckedChange = { if (it) onStart() else onIntent(ExpertIntent.Stop) },
                accessibleName = stringResource(R.string.expert_tunnel_title),
                lamp = when (runtime.tunnelHealth) {
                    ExpertTunnelHealth.OFF -> NetworkLeverLamp.OFF
                    ExpertTunnelHealth.PENDING -> NetworkLeverLamp.PENDING
                    ExpertTunnelHealth.HEALTHY -> NetworkLeverLamp.HEALTHY
                    ExpertTunnelHealth.ERROR -> NetworkLeverLamp.ERROR
                },
                lampDescription = stringResource(
                    when (runtime.tunnelHealth) {
                        ExpertTunnelHealth.OFF -> R.string.expert_lamp_off
                        ExpertTunnelHealth.PENDING -> R.string.expert_lamp_pending
                        ExpertTunnelHealth.HEALTHY -> R.string.expert_lamp_healthy
                        ExpertTunnelHealth.ERROR -> R.string.expert_lamp_error
                    }
                ),
                stateLabel = stringResource(
                    if (runtime.tunnelHealth == ExpertTunnelHealth.ERROR) {
                        R.string.expert_lever_error
                    } else if (runtime.applying) {
                        R.string.expert_lever_apply
                    } else {
                        when (runtime.phase) {
                            ExpertSessionPhase.RUNNING -> R.string.expert_lever_on
                            ExpertSessionPhase.STOPPED -> R.string.expert_lever_off
                            ExpertSessionPhase.STARTING -> R.string.expert_lever_start
                            ExpertSessionPhase.STOPPING -> R.string.expert_lever_stop
                            ExpertSessionPhase.FAILED -> R.string.expert_lever_error
                        }
                    }
                ),
                reducedMotion = reducedMotion,
            )
            }
            lever()
            summary()
        }
        Text(
            stringResource(R.string.expert_flow_active_count, runtime.connections.count { it.active == true }),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            if (rate == null) {
                stringResource(R.string.expert_flow_rate_unknown)
            } else {
                stringResource(R.string.expert_flow_rate, rate.upload, rate.download)
            },
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            stringResource(when {
                runtime.hasDraftChanges || (runtime.appliedRevision != null && runtime.appliedRevision != runtime.saved.revision) -> R.string.expert_schema_pending
                runtime.appliedRevision != null -> R.string.expert_schema_applied
                else -> R.string.expert_schema_saved
            }),
            style = MaterialTheme.typography.bodySmall
        )
        TextButton({ details = !details }, Modifier.fillMaxWidth()) {
            Text(stringResource(if (details) R.string.expert_hide_tunnel_details else R.string.expert_tunnel_details))
        }
        androidx.compose.animation.AnimatedVisibility(details) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                runtime.directNetwork?.takeIf { runtime.phase == ExpertSessionPhase.RUNNING }?.let { facts ->
                    fun familyResource(value: DirectFamilyAvailability): Int = when (value) {
                        DirectFamilyAvailability.AVAILABLE -> R.string.expert_family_available
                        DirectFamilyAvailability.LIMITED -> R.string.expert_family_limited
                        DirectFamilyAvailability.UNAVAILABLE -> R.string.expert_family_unavailable
                        DirectFamilyAvailability.UNKNOWN -> R.string.expert_family_unknown
                    }
                    Text(
                        stringResource(
                            R.string.expert_direct_families, facts.interfaceName ?: stringResource(R.string.expert_family_unknown),
                            stringResource(familyResource(facts.ipv4)), stringResource(familyResource(facts.ipv6)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (facts.ipv6 == DirectFamilyAvailability.UNAVAILABLE) {
                        Text(stringResource(R.string.expert_direct_no_ipv6), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        runtime.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        state.restoreWarning?.let {
            Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }
        ExpertInactiveProtections(state)
        if (runtime.phase == ExpertSessionPhase.STARTING) {
            OutlinedButton({ onIntent(ExpertIntent.Stop) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.expert_cancel_start)) }
        }
        if (runtime.phase == ExpertSessionPhase.FAILED) {
            Button({ onIntent(ExpertIntent.Stop) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.expert_stop)) }
        }
    }
}

@Composable
private fun ExpertInactiveProtections(state: ExpertUiState) {
    val runtime = state.runtime ?: return
    val bundle = state.inventory ?: return
    val names = (listOf(runtime.draft.device) + runtime.draft.trees).flatMap { it.nodes }
        .associateBy { it.id }
    runtime.inactiveProtections.forEach { inactive ->
        Text(
            stringResource(
                R.string.expert_inactive_protection,
                names[inactive.nodeId]?.title?.ifBlank {
                    stringResource(R.string.expert_rule_unnamed)
                } ?: inactive.nodeId,
                scopeTitle(inactive.scope, bundle)
            ),
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
