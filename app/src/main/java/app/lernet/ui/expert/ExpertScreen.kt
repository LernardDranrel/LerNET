package app.lernet.ui.expert

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import app.lernet.ui.controls.ExpertFrameSpec
import app.lernet.ui.controls.ExpertFramedBox
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.testTag
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

internal enum class ExpertSection(val label: Int, val short: Int = label) {
    OVERVIEW(R.string.expert_section_overview),
    SCHEMA(R.string.expert_section_schema),
    SIMULATION(R.string.expert_section_simulation, R.string.expert_section_simulation_short),
    EXITS(R.string.expert_section_exits),
    JOURNAL(R.string.expert_section_journal),
    SAFETY(R.string.expert_section_safety),
}

@Composable
internal fun ExpertRoute(
    onVpn: () -> Unit, simpleActive: Boolean,
    section: ExpertSection, onSectionChange: (ExpertSection) -> Unit,
    onOpenDrawer: () -> Unit,
    onSettings: () -> Unit = {},
    onNetwork: () -> Unit = {},
) {
    val viewModel: ExpertViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val appSnackbar = app.lernet.ui.nav.LocalAppSnackbar.current
    val snackbar = remember(appSnackbar) { appSnackbar ?: SnackbarHostState() }
    val splash = rememberExpertSplash(context)
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
        onSettings = onSettings,
        onNetwork = onNetwork,
        frameAnimateIn = !splash.firstEntry,
    )
    if (splash.visible) ExpertSplash()
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
    onSettings: () -> Unit = {},
    onNetwork: () -> Unit = {},
    frameAnimateIn: Boolean = false,
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
    // «Защита и DNS» opens from ⋮ and is not in the bottom menu: Back returns to the pult.
    androidx.activity.compose.BackHandler(enabled = section == ExpertSection.SAFETY) { selectSection(ExpertSection.OVERVIEW) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Column {
                // Audit N2–N5 (mockup m4): ☰ = configs and folders, the mode switch sits in the title, everything else in ⋮.
                TopAppBar(
                    navigationIcon = {
                        IconButton(onOpenDrawer) {
                            Icon(LerNetSymbols.menu(), contentDescription = stringResource(R.string.cd_configs))
                        }
                    },
                    title = {
                        AppModeTabs(true, onVpn, {}, Modifier.width(220.dp).testTag("expert_mode_switch"))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    actions = {
                        IconButton(onClick = { archiveMenu = true }, Modifier.testTag("expert_more")) {
                            Icon(LerNetSymbols.more(), contentDescription = stringResource(R.string.expert_more))
                        }
                        DropdownMenu(archiveMenu, onDismissRequest = { archiveMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.expert_section_safety)) },
                                onClick = { archiveMenu = false; selectSection(ExpertSection.SAFETY) },
                                modifier = Modifier.testTag("expert_menu_safety"),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.expert_menu_app_settings)) },
                                onClick = { archiveMenu = false; onSettings() },
                                modifier = Modifier.testTag("expert_menu_settings"),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.expert_menu_network)) },
                                onClick = { archiveMenu = false; onNetwork() },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.expert_import)) },
                                enabled = state.inventory != null,
                                onClick = { archiveMenu = false; onImport() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.expert_export)) },
                                enabled = state.inventory != null,
                                onClick = { archiveMenu = false; onExport() },
                            )
                        }
                    }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { ExpertBottomMenu(section, ::selectSection) },
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
                    // The pult keeps the lever and a short summary; connections live in «События».
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        state.storageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (permissionDenied) {
                            Text(stringResource(R.string.expert_permission_denied), color = MaterialTheme.colorScheme.error)
                        }
                        ExpertStatus(state, onIntent, onStart, reduced)
                        ExpertTiles(runtime, state, ::selectSection)
                    }
                } else if (visible == ExpertSection.JOURNAL) {
                    // Audit X2 (mockup m6): one switch «Соединения · N | Действия LerNET · N», each list in full.
                    var actions by rememberSaveable { mutableStateOf(false) }
                    Column(Modifier.fillMaxSize()) {
                        ExpertEventsSwitch(runtime, actions) { actions = it }
                        Box(Modifier.weight(1f)) {
                            if (actions) ExpertActionsList(runtime)
                            else ExpertActivity(runtime, bundle, onSimulate = { connection, historical ->
                                simulationConnection = connection
                                historicalPath = historical
                                simulationRequest++
                                selectSection(ExpertSection.SIMULATION)
                            }, onClear = { onIntent(ExpertIntent.ClearConnectionHistory) }, header = { rate ->
                                ExpertEventsRate(rate)
                            })
                        }
                    }
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
                                // Audit X7: one page «Защита и DNS» — rows that open the editors, then the protection facts.
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(stringResource(R.string.expert_section_safety), style = MaterialTheme.typography.titleLarge)
                                PanelCard {
                                    ExpertLinkRow(stringResource(R.string.expert_dns_row), stringResource(R.string.expert_dns_row_hint),
                                        enabled = onSaveDns != null, tag = "expert_dns_row") { dnsEditor = runtime.draft.dns }
                                    HorizontalDivider()
                                    ExpertLinkRow(stringResource(R.string.expert_health_row), stringResource(R.string.expert_health_row_hint),
                                        enabled = onSaveHealth != null, tag = "expert_health_row") { healthEditor = runtime.draft.health }
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
                            }
                            ExpertSection.JOURNAL -> Unit
                        }
                    }
                }
            }
            ExpertFramedBox(
                ExpertFrameSpec.Phone,
                Modifier.padding(padding).fillMaxSize(),
                lit = if (runtime?.phase == ExpertSessionPhase.RUNNING) 1f else 0.35f,
                animateIn = frameAnimateIn && !reduced,
            ) {
            Column(Modifier.fillMaxSize()) {
                if (reduced) {
                    sectionStates.SaveableStateProvider(section) { content(section) }
                } else {
                    AnimatedContent(section, label = "expert-section") {
                        sectionStates.SaveableStateProvider(it) { content(it) }
                    }
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
    reducedMotion: Boolean = false,
) {
    val runtime = requireNotNull(state.runtime)
    val health = runtime.tunnelHealth
    // One status drives the lever word, the headline, the counter and the schema line.
    val pult = app.lernet.engine.policy.ExpertPult.status(runtime)
    val problemText = runtime.networkReason ?: when {
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
        else -> null
    }
    val statusText = if (pult == app.lernet.engine.policy.PultStatus.PROBLEM) {
        problemText ?: app.lernet.engine.policy.ExpertPult.headline(pult)
    } else {
        app.lernet.engine.policy.ExpertPult.headline(pult)
    }
    // A network note (e.g. no network) is shown under the headline, never instead of the state.
    val note = if (pult == app.lernet.engine.policy.PultStatus.HOLDING) problemText
        else runtime.networkReason?.takeIf { pult != app.lernet.engine.policy.PultStatus.PROBLEM }
    var details by rememberSaveable { mutableStateOf(false) }
    PanelCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val summary: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Mockup m4: «Своя схема · работает» on one line, the path and «сведения» on the next.
                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                    verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.expert_tunnel_title) + " · ", textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge)
                Text(statusText, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag("expert_pult_headline"),
                    color = when (pult) {
                        app.lernet.engine.policy.PultStatus.PROBLEM -> MaterialTheme.colorScheme.error
                        app.lernet.engine.policy.PultStatus.WORKING -> MaterialTheme.colorScheme.secondary
                        app.lernet.engine.policy.PultStatus.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.tertiary
                    })
                }
                note?.let { Text(it, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                state.inventory?.let { inventory ->
                    val policy = runtime.appliedPolicy ?: runtime.saved
                    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                        itemVerticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.expert_default_path_summary, targetTitle(policy.device.defaultTarget, inventory, policy).replaceFirstChar { it.lowercase() }) + " · ",
                            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center
                        )
                        Text(
                            if (details) app.lernet.engine.text.ExpertWords.HIDE_DETAILS else app.lernet.engine.text.ExpertWords.DETAILS,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.heightIn(min = 48.dp).wrapContentHeight()
                                .clickable(role = androidx.compose.ui.semantics.Role.Button) { details = !details }
                                .testTag("expert_tunnel_details"),
                        )
                    }
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
                stateLabel = app.lernet.engine.policy.ExpertPult.lever(pult),
                reducedMotion = reducedMotion,
            )
            }
            lever()
            ExpertUptimeLine(runtime)
            summary()
        }
        app.lernet.engine.policy.ExpertPult.schemaNote(
            pult,
            unsavedDraft = runtime.hasDraftChanges,
            unapplied = runtime.appliedRevision != null && runtime.appliedRevision != runtime.saved.revision,
            applied = runtime.appliedRevision != null,
        )?.let { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().testTag("expert_pult_schema"),
                textAlign = TextAlign.Center,
            )
        }
        androidx.compose.animation.AnimatedVisibility(details) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                runtime.directNetwork?.takeIf { runtime.phase == ExpertSessionPhase.RUNNING }?.let { facts ->
                    Text(app.lernet.engine.text.ExpertWords.directFamilies(facts), style = MaterialTheme.typography.bodySmall)
                    if (facts.ipv6 == DirectFamilyAvailability.UNAVAILABLE) {
                        Text(app.lernet.engine.text.ExpertWords.NO_IPV6, style = MaterialTheme.typography.bodySmall)
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

/** «Без перерыва»: how long the shared TUN has stayed up; hidden when the core has not confirmed a start. */
@Composable
internal fun ExpertUptimeLine(runtime: app.lernet.engine.policy.ExpertRuntimeState) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(runtime.runningSinceEpochMs, runtime.phase) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(30_000)
        }
    }
    val label = app.lernet.engine.policy.ExpertUptime.label(runtime, now) ?: return
    // Mockup m4: one line «БЕЗ ПЕРЕРЫВА · 14 дн 06 ч».
    Row(Modifier.testTag("expert_uptime"), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.expert_uptime_title).uppercase() + " · ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
    }
}

/** Audit X1 (mockup m4): three tiles that lead to Конфиги, События and Защита instead of a long text link. */
@Composable
private fun ExpertTiles(runtime: app.lernet.engine.policy.ExpertRuntimeState, state: ExpertUiState, open: (ExpertSection) -> Unit) {
    val exits = runtime.exits
    val working = exits.count { it.phase == app.lernet.engine.policy.ExitPhase.READY }
    val active = runtime.connections.count { it.active == true }
    val protectionOk = runtime.inactiveNodeIds.isEmpty()
    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Same values as on Windows (ExpertWords): «2 из 3», «3 активных».
        ExpertTile(stringResource(R.string.expert_tile_exits), app.lernet.engine.text.ExpertWords.exitsValue(working, exits.size),
            Modifier.weight(1f).testTag("expert_tile_exits")) { open(ExpertSection.EXITS) }
        ExpertTile(stringResource(R.string.expert_tile_connections), app.lernet.engine.text.ExpertWords.connectionsValue(active),
            Modifier.weight(1f).testTag("expert_open_events")) { open(ExpertSection.JOURNAL) }
        ExpertTile(stringResource(R.string.expert_tile_protection),
            stringResource(if (protectionOk) R.string.expert_tile_protection_on else R.string.expert_tile_protection_check),
            Modifier.weight(1f).testTag("expert_tile_safety"), warn = !protectionOk) { open(ExpertSection.SAFETY) }
    }
}

@Composable
private fun ExpertTile(title: String, value: String, modifier: Modifier, warn: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(R.string.expert_tile_open, title)
    Column(
        modifier.fillMaxHeight().heightIn(min = 64.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(scheme.surfaceVariant.copy(alpha = .55f))
            .clickable(onClickLabel = label, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        Text("$value ›", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = if (warn) scheme.tertiary else scheme.onSurface, maxLines = 1)
    }
}

/** A list row that opens a page or editor: title, one-line hint, chevron. 56 dp, the whole row is the target. */
@Composable
internal fun ExpertLinkRow(title: String, hint: String, enabled: Boolean = true, tag: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Top of «События»: the two lists side by side as one segmented switch, with their sizes. */
@Composable
private fun ExpertEventsSwitch(runtime: app.lernet.engine.policy.ExpertRuntimeState, actions: Boolean, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .border(1.dp, scheme.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(50)).padding(3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(false to stringResource(R.string.expert_events_connections, runtime.connections.size),
            true to stringResource(R.string.expert_events_actions, runtime.reasons.size)).forEach { (value, label) ->
            val selected = actions == value
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .background(if (selected) scheme.secondaryContainer else scheme.background)
                    .selectable(selected, role = androidx.compose.ui.semantics.Role.Tab) { onChange(value) }
                    .testTag(if (value) "expert_events_actions" else "expert_events_connections"),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant)
            }
        }
    }
}

/** Audit X4: speed in KB/s like everywhere else, one line. */
@Composable
private fun ExpertEventsRate(rate: FlowTrafficRate?) {
    Text(
        // Shared with Windows; «новые сверху» is said once, under the list.
        app.lernet.engine.text.ExpertWords.eventsRate(rate?.upload, rate?.download),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal fun humanBytes(bytes: Long): String = app.lernet.engine.text.TrafficUnits.bytes(bytes)

/** «Действия LerNET»: every note of the app, newest first (before, only the last five were shown). */
@Composable
private fun ExpertActionsList(runtime: app.lernet.engine.policy.ExpertRuntimeState) {
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { ExpertHint(R.string.expert_actions_hint) }
        if (runtime.reasons.isEmpty()) item { Text(stringResource(R.string.expert_journal_empty)) }
        items(runtime.reasons.asReversed(), key = { it.sequence }) { reason ->
            PanelCard {
                Text(observationTimeTitle(reason.atMs), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(reason.message, style = MaterialTheme.typography.bodyMedium,
                    color = when (reason.level) {
                        app.lernet.engine.policy.ExpertReasonLevel.ERROR -> MaterialTheme.colorScheme.error
                        app.lernet.engine.policy.ExpertReasonLevel.WARNING -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurface
                    })
            }
        }
    }
}
