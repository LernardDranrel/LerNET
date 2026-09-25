package app.lernet.ui.config

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import app.lernet.R
import app.lernet.config.model.DnsPolicy
import app.lernet.engine.compile.DnsDraft
import app.lernet.engine.compile.DnsServerDraft
import app.lernet.engine.compile.FieldView
import app.lernet.engine.compile.TruthFieldId
import app.lernet.ui.components.PanelCard
import app.lernet.ui.icons.LerNetSymbols
import app.lernet.ui.layout.rememberCompactMetrics
import app.lernet.ui.layout.rememberThumbZoneBottomPadding
import app.lernet.ui.theme.LerNetDimens
import app.lernet.ui.theme.lernetButton
import app.lernet.ui.theme.lernetPrimaryAction
import kotlinx.coroutines.flow.SharedFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigEditorScreen(
    state: ConfigEditorUiState,
    onIntent: (ConfigEditorIntent) -> Unit,
    onBack: () -> Unit,
    onOpenRoutes: () -> Unit,
    events: SharedFlow<ConfigEditorEvent>,
) {
    var pendingExit by remember { mutableStateOf<ConfigExit?>(null) }
    val requestExit: (ConfigExit) -> Unit = { destination ->
        if (state.saving) {
            Unit
        } else if (state.dirty) {
            pendingExit = destination
        } else {
            when (destination) {
                ConfigExit.BACK -> onBack()
                ConfigExit.ROUTES -> onOpenRoutes()
            }
        }
    }
    BackHandler(enabled = state.saving || state.dirty) { requestExit(ConfigExit.BACK) }
    pendingExit?.let { destination ->
        AlertDialog(
            onDismissRequest = { pendingExit = null },
            title = { Text(stringResource(R.string.cfg_unsaved_title)) },
            text = { Text(stringResource(R.string.cfg_unsaved_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingExit = null
                    if (destination == ConfigExit.BACK) onBack() else onOpenRoutes()
                }) { Text(stringResource(R.string.routes_leave)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingExit = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    val snackbar = remember { SnackbarHostState() }
    val saved = stringResource(R.string.cfg_saved)
    val saveFailed = stringResource(R.string.cfg_save_failed)
    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                ConfigEditorEvent.Saved -> snackbar.showSnackbar(saved)
                ConfigEditorEvent.SaveFailed -> snackbar.showSnackbar(saveFailed)
            }
        }
    }
    if (state.confirmProfileDns) {
        ProfileDnsDialog(onIntent)
    }
    val metrics = rememberCompactMetrics()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(state.profileName.ifBlank { stringResource(R.string.cfg_title) }) },
                navigationIcon = {
                    IconButton(onClick = { requestExit(ConfigExit.BACK) }, enabled = !state.saving) {
                        Icon(LerNetSymbols.arrowBack(), contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (!state.loading && !state.missing) {
                Button(
                    onClick = { onIntent(ConfigEditorIntent.Save) },
                    enabled = !state.saving,
                    modifier = Modifier
                        .padding(horizontal = metrics.gutter)
                        .padding(bottom = rememberThumbZoneBottomPadding())
                        .lernetPrimaryAction(),
                ) {
                    Text(stringResource(if (state.saving) R.string.saving else R.string.cfg_save))
                }
            }
        },
    ) { padding ->
        when {
            state.loading -> Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
            state.missing -> Text(
                stringResource(R.string.cfg_missing),
                modifier = Modifier.padding(padding).padding(metrics.gutter),
            )
            else -> ConfigBody(state, onIntent, { requestExit(ConfigExit.ROUTES) }, Modifier.padding(padding))
        }
    }
}

private enum class ConfigExit { BACK, ROUTES }

@Composable
private fun ProfileDnsDialog(onIntent: (ConfigEditorIntent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onIntent(ConfigEditorIntent.DismissProfileDns) },
        title = { Text(stringResource(R.string.cfg_dns_warning_title)) },
        text = { Text(stringResource(R.string.cfg_dns_warning_body)) },
        confirmButton = {
            TextButton(onClick = { onIntent(ConfigEditorIntent.ConfirmProfileDns) }) {
                Text(stringResource(R.string.cfg_dns_warning_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { onIntent(ConfigEditorIntent.DismissProfileDns) }) {
                Text(stringResource(R.string.cfg_keep_system))
            }
        },
    )
}

@Composable
private fun ConfigBody(
    state: ConfigEditorUiState,
    onIntent: (ConfigEditorIntent) -> Unit,
    onOpenRoutes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = rememberCompactMetrics()
    val byId = state.fields.associateBy { it.id }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(metrics.gutter),
        verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
    ) {
        Text(stringResource(R.string.cfg_local_section), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.cfg_local_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
        DnsCard(state, onIntent, byId[TruthFieldId.DNS])
        RoutesCard(byId[TruthFieldId.ROUTE_PREFIX], onOpenRoutes)
        LocalDefaultsCard(state, onIntent, byId)
        Text(stringResource(R.string.cfg_remote_section), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.cfg_remote_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
        NodeCard(state, onIntent)
        DisguiseCard(state, onIntent, byId[TruthFieldId.REALITY])
        EngineCard(state, onIntent)
    }
}

@Composable
private fun NodeCard(state: ConfigEditorUiState, onIntent: (ConfigEditorIntent) -> Unit) {
    PanelCard {
        SectionTitle(stringResource(R.string.cfg_node), stringResource(R.string.cfg_node_why))
        OutlinedTextField(
            value = state.server,
            onValueChange = { onIntent(ConfigEditorIntent.Server(it)) },
            label = { Text(stringResource(R.string.cfg_server)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = state.fieldError == ConfigFieldError.SERVER,
            supportingText = errorText(state.fieldError, ConfigFieldError.SERVER, R.string.cfg_server_error),
        )
        OutlinedTextField(
            value = state.port,
            onValueChange = { onIntent(ConfigEditorIntent.Port(it)) },
            label = { Text(stringResource(R.string.cfg_port)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = state.fieldError == ConfigFieldError.PORT,
            supportingText = errorText(state.fieldError, ConfigFieldError.PORT, R.string.cfg_port_error),
        )
    }
}

@Composable
private fun DisguiseCard(
    state: ConfigEditorUiState,
    onIntent: (ConfigEditorIntent) -> Unit,
    reality: FieldView?,
) {
    PanelCard {
        SectionTitle(stringResource(R.string.cfg_disguise), stringResource(R.string.cfg_disguise_why))
        OutlinedTextField(
            value = state.sni,
            onValueChange = { onIntent(ConfigEditorIntent.Sni(it)) },
            label = { Text(stringResource(R.string.cfg_sni)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        if (reality != null) {
            ConfigOverrideRow(reality, stringResource(R.string.cfg_reality))
        }
        if (state.hasTransport) {
            OutlinedTextField(
                value = state.path,
                onValueChange = { onIntent(ConfigEditorIntent.Path(it)) },
                label = { Text(stringResource(R.string.cfg_path)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
}

@Composable
private fun DnsCard(
    state: ConfigEditorUiState,
    onIntent: (ConfigEditorIntent) -> Unit,
    dns: FieldView?,
) {
    PanelCard {
        SectionTitle(stringResource(R.string.cfg_dns), stringResource(R.string.cfg_dns_why))
        if (dns != null) {
            ConfigOverrideRow(dns, stringResource(R.string.cfg_dns_servers))
        }
        PolicyChoice(state.dnsPolicy, onIntent)
        if (state.dnsPolicy == DnsPolicy.PROFILE) {
            state.dnsServers.forEach { draft ->
                DnsEditor(draft, state.fieldError, onIntent)
            }
        }
    }
}

@Composable
private fun PolicyChoice(policy: DnsPolicy, onIntent: (ConfigEditorIntent) -> Unit) {
    RadioLine(
        selected = policy == DnsPolicy.UNDERLAY,
        label = stringResource(R.string.cfg_keep_system),
        onClick = { onIntent(ConfigEditorIntent.KeepSystemDns) },
    )
    RadioLine(
        selected = policy == DnsPolicy.PROFILE,
        label = stringResource(R.string.cfg_use_profile),
        onClick = { onIntent(ConfigEditorIntent.RequestProfileDns) },
    )
}

@Composable
private fun RadioLine(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DnsEditor(
    draft: DnsServerDraft,
    fieldError: ConfigFieldError?,
    onIntent: (ConfigEditorIntent) -> Unit,
) {
    val title = draft.tag.ifBlank { stringResource(R.string.cfg_dns_servers) }
    Text(title, style = MaterialTheme.typography.labelLarge)
    ChoiceField(
        label = stringResource(R.string.cfg_dns_type),
        value = draft.type,
        options = (listOf("udp", "tcp", "tls", "https", "quic", "h3", "local") + draft.type).distinct(),
        onSelect = { onIntent(ConfigEditorIntent.DnsType(draft.index, it)) },
        hint = stringResource(R.string.cfg_dns_type_help),
    )
    OutlinedTextField(
        value = draft.server,
        onValueChange = { onIntent(ConfigEditorIntent.DnsServer(draft.index, it)) },
        label = { Text(stringResource(R.string.cfg_dns_address)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = fieldError == ConfigFieldError.DNS && DnsDraft.needsAddress(draft) && draft.server.isBlank(),
        supportingText = errorText(fieldError, ConfigFieldError.DNS, R.string.cfg_dns_error),
    )
}

@Composable
private fun RoutesCard(prefix: FieldView?, onOpenRoutes: () -> Unit) {
    PanelCard {
        SectionTitle(stringResource(R.string.cfg_routes), stringResource(R.string.cfg_routes_why))
        if (prefix != null) {
            ConfigOverrideRow(prefix, stringResource(R.string.cfg_route_prefix))
        }
        TextButton(onClick = onOpenRoutes, modifier = Modifier.lernetButton()) {
            Text(stringResource(R.string.cfg_routes_open))
        }
    }
}

@Composable
private fun LocalDefaultsCard(
    state: ConfigEditorUiState,
    onIntent: (ConfigEditorIntent) -> Unit,
    byId: Map<TruthFieldId, FieldView>,
) {
    PanelCard {
        SectionTitle(stringResource(R.string.cfg_advanced), stringResource(R.string.cfg_local_defaults_help))
        if (state.transportType == "xhttp" || state.transportType == "splithttp") {
            ChoiceField(
                label = stringResource(R.string.cfg_mode),
                value = state.mode,
                options = (listOf("", "auto", "packet-up", "stream-up", "stream-one", "stream-on") + state.mode).distinct(),
                onSelect = { onIntent(ConfigEditorIntent.Mode(it)) },
                hint = stringResource(R.string.cfg_mode_help),
            )
        }
        TruthLine(byId, TruthFieldId.XMUX, R.string.cfg_xmux)
        TruthLine(byId, TruthFieldId.TUN_STACK, R.string.cfg_tun_stack)
        TruthLine(byId, TruthFieldId.TUN_MTU, R.string.cfg_tun_mtu)
        TruthLine(byId, TruthFieldId.LOG_LEVEL, R.string.cfg_log)
    }
}

@Composable
private fun TruthLine(byId: Map<TruthFieldId, FieldView>, id: TruthFieldId, label: Int) {
    val field = byId[id] ?: return
    ConfigOverrideRow(field, stringResource(label))
}

@Composable
private fun EngineCard(state: ConfigEditorUiState, onIntent: (ConfigEditorIntent) -> Unit) {
    val clipboard = LocalClipboardManager.current
    PanelCard {
        Text(stringResource(R.string.cfg_engine), style = MaterialTheme.typography.titleMedium)
        state.assembleError?.let { error ->
            Text(
                stringResource(R.string.cfg_assemble_error, error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = { onIntent(ConfigEditorIntent.ToggleEngine) }, modifier = Modifier.lernetButton()) {
            val label = if (state.showEngine) R.string.cfg_engine_hide else R.string.cfg_engine_show
            Text(stringResource(label))
        }
        OutlinedButton(
            onClick = { clipboard.setText(AnnotatedString(state.fullEngineJson)) },
            enabled = state.fullEngineJson.isNotBlank() && state.assembleError == null
        ) {
            Icon(LerNetSymbols.copy(), contentDescription = null)
            Text(stringResource(R.string.cfg_copy_json))
        }
        Text(
            stringResource(R.string.cfg_json_secret_hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
        )
        if (state.showEngine) {
            SelectionContainer {
                Text(
                    state.fullEngineJson.ifBlank { stringResource(R.string.cfg_unset) },
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun ChoiceField(label: String, value: String, options: List<String>, onSelect: (String) -> Unit, hint: String) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(value.ifBlank { stringResource(R.string.cfg_system_defined) }, modifier = Modifier.weight(1f))
                Icon(LerNetSymbols.expandMore(), contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.ifBlank { stringResource(R.string.cfg_system_defined) }) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        }
                    )
                }
            }
        }
        Text(
            hint, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionTitle(title: String, why: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun errorText(actual: ConfigFieldError?, expected: ConfigFieldError, message: Int): (@Composable () -> Unit)? {
    if (actual != expected) return null
    return { Text(stringResource(message)) }
}
