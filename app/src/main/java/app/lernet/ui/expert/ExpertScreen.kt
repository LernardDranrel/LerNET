package app.lernet.ui.expert

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lernet.R
import app.lernet.config.policy.ExternalExitRequest
import app.lernet.config.transfer.TransferProfile
import app.lernet.engine.policy.ExpertIntent
import app.lernet.engine.policy.ExpertSessionPhase
import app.lernet.routing.policy.PolicyHealthSettings
import app.lernet.ui.components.PanelCard
import app.lernet.ui.icons.LerNetSymbols
import app.lernet.ui.motion.rememberReduceMotion

internal enum class ExpertSection(val label: Int) {
    OVERVIEW(R.string.expert_section_overview),
    SCHEMA(R.string.expert_section_schema),
    EXITS(R.string.expert_section_exits),
    SAFETY(R.string.expert_section_safety),
    ACTIVITY(R.string.expert_section_activity),
    JOURNAL(R.string.expert_section_journal),
    SIMULATION(R.string.expert_section_simulation),
}

@Composable
fun ExpertRoute(onVpn: () -> Unit, simpleActive: Boolean) {
    val viewModel: ExpertViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snackbar = remember { SnackbarHostState() }
    var switchPrompt by remember { mutableStateOf(false) }
    var exportPrompt by remember { mutableStateOf(false) }
    var importPrompt by remember { mutableStateOf(false) }
    var replaceImport by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        permissionDenied = result.resultCode != Activity.RESULT_OK
        if (!permissionDenied) viewModel.onIntent(ExpertIntent.Start)
    }
    val prepareStart: () -> Unit = {
        permissionDenied = false
        val inspected = viewModel.refreshEnvironment()
        if (inspected.anotherVpnVisible) {
            permissionDenied = true
        } else {
            val prepare = VpnService.prepare(context)
            if (prepare == null) viewModel.onIntent(ExpertIntent.Start) else permission.launch(prepare)
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
            title = { Text(stringResource(R.string.expert_switch_title)) },
            text = { Text(stringResource(R.string.expert_switch_body)) },
            confirmButton = {
                TextButton(onClick = {
                    switchPrompt = false
                    prepareStart()
                }) {
                    Text(stringResource(R.string.expert_tab_expert))
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
        onStart = { if (simpleActive) switchPrompt = true else prepareStart() },
        onExport = { exportPrompt = true },
        onAndroidSettings = { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) },
        permissionDenied = permissionDenied,
        onImport = { importPrompt = true },
        onSaveExternal = viewModel::saveExternalExit,
        onSaveHealth = viewModel::saveHealthSettings,
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
) {
    var section by remember { mutableStateOf(ExpertSection.OVERVIEW) }
    var archiveMenu by remember { mutableStateOf(false) }
    var healthEditor by remember { mutableStateOf<PolicyHealthSettings?>(null) }
    val reduced = rememberReduceMotion()
    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text(stringResource(R.string.app_name)) }, actions = {
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
                })
                AppModeTabs(true, onVpn, {})
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExpertSection.entries.forEach { item ->
                        FilterChip(
                            section == item, onClick = { section = item },
                            label = { Text(stringResource(item.label)) },
                        )
                    }
                }
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
                        Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.storageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
                            )
                        }
                        ExpertSchema(runtime, bundle, onIntent, Modifier.weight(1f))
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
                            ExpertSection.OVERVIEW -> {
                                item { ExpertStatus(state, onIntent, onStart) }
                                item {
                                    OutlinedButton(
                                        { healthEditor = runtime.draft.health },
                                        Modifier.fillMaxWidth(), enabled = onSaveHealth != null,
                                    ) {
                                        Text(stringResource(R.string.expert_health_title))
                                    }
                                }
                                item {
                                    PanelCard {
                                        Text(
                                            stringResource(R.string.expert_title),
                                            style = MaterialTheme.typography.titleLarge,
                                        )
                                        ExpertHint(R.string.expert_subtitle)
                                        Text(
                                            stringResource(
                                                R.string.expert_overview_default,
                                                targetTitle(runtime.saved.device.defaultTarget, bundle, runtime.saved)
                                            ),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        ExpertHint(R.string.expert_direct_hint)
                                    }
                                }
                                item {
                                    OutlinedButton(
                                        { section = ExpertSection.SCHEMA }, Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(LerNetSymbols.route(), contentDescription = null)
                                        Text(stringResource(R.string.expert_section_schema))
                                    }
                                }
                                state.inventoryNotes.forEach { note ->
                                    item {
                                        Text(note, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            ExpertSection.SCHEMA -> Unit
                            ExpertSection.EXITS -> item { ExpertExits(runtime, bundle, onIntent, onSaveExternal) }
                            ExpertSection.SIMULATION -> item { ExpertSimulation(runtime, bundle) }
                            ExpertSection.SAFETY -> item {
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
                            ExpertSection.ACTIVITY -> item { ExpertActivity(runtime, bundle) }
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
                    content(section)
                } else {
                    AnimatedContent(section, label = "expert-section") {
                        content(it)
                    }
                }
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
private fun ExpertStatus(state: ExpertUiState, onIntent: (ExpertIntent) -> Unit, onStart: () -> Unit) {
    val runtime = requireNotNull(state.runtime)
    PanelCard {
        Text(stringResource(runtime.phase.titleResource()), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(
                R.string.expert_revision, runtime.saved.revision,
                runtime.appliedRevision?.toString() ?: stringResource(R.string.expert_no_revision)
            ),
            style = MaterialTheme.typography.bodySmall
        )
        runtime.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        state.restoreWarning?.let {
            Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }
        ExpertInactiveProtections(state)
        val active = runtime.phase != ExpertSessionPhase.STOPPED
        Button(
            onClick = { if (active) onIntent(ExpertIntent.Stop) else onStart() },
            enabled = runtime.phase != ExpertSessionPhase.STOPPING, modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(if (active) R.string.expert_stop else R.string.expert_start))
        }
        ExpertDraftActions(runtime, onIntent)
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
