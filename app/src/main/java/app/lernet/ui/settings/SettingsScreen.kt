package app.lernet.ui.settings

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.lernet.BuildConfig
import app.lernet.R
import app.lernet.config.model.DnsPolicy
import app.lernet.engine.RunMode
import app.lernet.ui.components.PanelCard
import app.lernet.ui.icons.LerNetSymbols
import app.lernet.ui.layout.rememberCompactMetrics
import app.lernet.ui.theme.LerNetDimens
import app.lernet.ui.theme.lernetButton
import kotlinx.coroutines.launch

/**
 * Audit S1–S9 (mockup m2): one list with group headers — Подключение → Защита от утечек → Помощь → Приложение,
 * and «Для опытных» as a separate page instead of a 2000 dp fold. Same order as on Windows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onIntent: (SettingsIntent) -> Unit,
    onBack: () -> Unit,
    onShareLogs: () -> Unit,
    onOpenDiag: () -> Unit = {},
    onOpenNetwork: () -> Unit = {},
    onOpenApps: () -> Unit = {},
    fromExpert: Boolean = false,
) {
    val snackbar = remember { SnackbarHostState() }
    val metrics = rememberCompactMetrics()
    var experts by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = experts) { experts = false }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (experts) R.string.settings_experts_title else R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (experts) experts = false else onBack() }) {
                        Icon(LerNetSymbols.arrowBack(), contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).padding(horizontal = metrics.gutter)
        if (experts) ExpertsPage(state, onIntent, modifier)
        else SettingsList(state, onIntent, snackbar, onShareLogs, onOpenNetwork, onOpenApps, { experts = true }, fromExpert, modifier)
    }
}

@Composable
private fun SettingsList(
    state: SettingsUiState,
    onIntent: (SettingsIntent) -> Unit,
    snackbar: SnackbarHostState,
    onShareLogs: () -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenExperts: () -> Unit,
    fromExpert: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    var dnsDialog by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Audit N5: opened from Expert, the page says these are the app's common settings.
        if (fromExpert) Text(stringResource(R.string.settings_from_expert), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 4.dp).testTag("settings_from_expert"))
        GroupHeader(R.string.settings_group_connection)
        SettingsGroup(Modifier.testTag("settings_run_mode")) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_mode_row), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                app.lernet.ui.expert.LerSegmented(
                    listOf(stringResource(R.string.mode_vpn), stringResource(R.string.mode_proxy)),
                    if (state.settings.mode == RunMode.PROXY) 1 else 0,
                    { onIntent(SettingsIntent.SetMode(if (it == 1) RunMode.PROXY else RunMode.FULL_VPN)) },
                    // 196 dp cut «Полный VPN» to «Полный» (maxLines = 1, no ellipsis).
                    Modifier.width(240.dp).testTag("settings_mode_switch"),
                )
            }
            Text(
                // Same two sentences as on Windows, whether or not a session is running.
                stringResource(R.string.settings_mode_profile_note) + " " + stringResource(R.string.settings_mode_next_connection),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.settings.mode == RunMode.PROXY) {
                if (state.liveProxy != null) app.lernet.ui.home.ProxyAddressRow(state.liveProxy)
                else Text(stringResource(R.string.settings_proxy_not_running), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ProxyCredentialsFields(state, onIntent)
            }
            HorizontalDivider()
            val bypass = state.settings.engineDefaults.bypassApps
            LinkRow(stringResource(R.string.settings_apps_row),
                if (bypass.isEmpty()) stringResource(R.string.settings_apps_all) else stringResource(R.string.settings_apps_some, bypass.size),
                tag = "settings_apps", onClick = onOpenApps)
            HorizontalDivider()
            BootRestoreRow()
            HorizontalDivider()
            LinkRow(stringResource(R.string.settings_dns_row), stringResource(when (state.settings.defaultDnsPolicy) {
                DnsPolicy.TUNNEL -> R.string.settings_dns_tunnel_short
                DnsPolicy.UNDERLAY -> R.string.settings_dns_system_short
                DnsPolicy.PROFILE -> R.string.settings_dns_profile_short
            }), tag = "settings_dns") { dnsDialog = true }
        }
        GroupHeader(R.string.settings_group_leaks)
        SettingsGroup(Modifier.testTag("settings_startup")) {
            LinkRow(stringResource(R.string.settings_leaks_row), stringResource(R.string.settings_leaks_value), tag = "settings_leaks") {
                runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS)) }
            }
            Text(stringResource(R.string.settings_leaks_help), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        }
        GroupHeader(R.string.settings_group_help)
        SettingsGroup {
            LinkRow(stringResource(R.string.settings_share_log_row), "", tag = "settings_share_log", onClick = onShareLogs)
            HorizontalDivider()
            LinkRow(stringResource(R.string.settings_network_row), "", tag = "settings_network", onClick = onOpenNetwork)
        }
        GroupHeader(R.string.settings_group_app)
        SettingsGroup {
            UpdatesRows()
            HorizontalDivider()
            LinkRow(stringResource(R.string.settings_experts_row), "", hint = stringResource(R.string.settings_experts_hint),
                tag = "settings_advanced_toggle", onClick = onOpenExperts)
            HorizontalDivider()
            AboutRows(state, snackbar)
        }
        DebugPipeCard(onIntent)
    }
    if (dnsDialog) DnsPolicyDialog(state.settings.defaultDnsPolicy, { onIntent(SettingsIntent.SetDefaultDnsPolicy(it)); dnsDialog = false }) { dnsDialog = false }
}

@Composable
private fun ExpertsPage(state: SettingsUiState, onIntent: (SettingsIntent) -> Unit, modifier: Modifier) {
    val reconnect = state.settings.reconnect
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp).testTag("settings_experts"),
        verticalArrangement = Arrangement.spacedBy(LerNetDimens.sectionGap),
    ) {
        Text(stringResource(R.string.settings_defaults_scope) + " " + stringResource(R.string.settings_auto_apply),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DnsDefaultsCard(state, onIntent)
        XmuxDefaultsCard(state, onIntent)
        MtuDefaultsCard(state, onIntent)
        TlsHandshakeCard(state, onIntent)
        JournalDefaultsCard(state, onIntent)
        // Only the phone has these numbers today (a question for the owner); they come after the shared cards.
        PanelCard {
            Text(stringResource(R.string.settings_connection), style = MaterialTheme.typography.titleMedium)
            SettingNumberRow(stringResource(R.string.max_attempts), reconnect.maxAttempts, stringResource(R.string.settings_attempts_help)) {
                onIntent(SettingsIntent.SetMaxAttempts(it))
            }
            SettingNumberRow(stringResource(R.string.watchdog_seconds), (reconnect.watchdogTimeoutMs / 1000L).toInt(), stringResource(R.string.settings_watchdog_help)) {
                onIntent(SettingsIntent.SetWatchdogSeconds(it))
            }
            SettingNumberRow(stringResource(R.string.backoff_cap_seconds), (reconnect.backoffCapMs / 1000L).toInt(), stringResource(R.string.settings_backoff_help)) {
                onIntent(SettingsIntent.SetBackoffCapSeconds(it))
            }
        }
    }
}

/** Inside the «Журнал» card, as on Windows: how detailed, what each level means, what is cut out. */
@Composable
internal fun LogLevelRows(state: SettingsUiState, onIntent: (SettingsIntent) -> Unit) {
    Text(stringResource(R.string.log_level), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("warn" to R.string.log_warn, "info" to R.string.log_info, "debug" to R.string.log_debug).forEach { (level, label) ->
            FilterChip(
                selected = state.settings.logLevel == level,
                onClick = { onIntent(SettingsIntent.SetLogLevel(level)) },
                label = { Text(stringResource(label)) },
                modifier = Modifier.lernetButton(),
            )
        }
    }
    Text(stringResource(when (state.settings.logLevel) {
        "info" -> R.string.log_info_help
        "debug" -> R.string.log_debug_help
        else -> R.string.log_warn_help
    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.log_redact_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun GroupHeader(title: Int) {
    Text(
        stringResource(title).uppercase(), style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 2.dp),
    )
}

@Composable
private fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), content = content)
    }
}

/** A row that opens something: title (and hint), value on the right, chevron. 56 dp, whole row is the target. */
@Composable
private fun LinkRow(title: String, value: String, hint: String? = null, tag: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (value.isEmpty()) "›" else "$value ›", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Same behaviour as «Запускать при входе в систему» on Windows: a VPN left on comes back after a restart. */
@Composable
private fun BootRestoreRow() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(app.lernet.vpn.BootRestorePreference.enabled(context)) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Switch) {
            enabled = !enabled
            app.lernet.vpn.BootRestorePreference.set(context, enabled)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_boot_row), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.settings_boot_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = enabled, onCheckedChange = null, modifier = Modifier.testTag("settings_boot_restore"))
    }
}

@Composable
private fun DnsPolicyDialog(current: DnsPolicy, onSelect: (DnsPolicy) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_dns_row)) },
        text = {
            Column(Modifier.selectableGroup()) {
                listOf(DnsPolicy.TUNNEL to R.string.cfg_dns_tunnel, DnsPolicy.UNDERLAY to R.string.cfg_keep_system,
                    DnsPolicy.PROFILE to R.string.cfg_use_profile).forEach { (policy, label) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.RadioButton) { onSelect(policy) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(current == policy, onClick = null)
                        Text(stringResource(label), Modifier.padding(start = 12.dp))
                    }
                }
                Text(stringResource(R.string.settings_dns_default_help), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.done)) } },
    )
}

@Composable
private fun DebugPipeCard(onIntent: (SettingsIntent) -> Unit) {
    if (!BuildConfig.DEBUG) return
    PanelCard(Modifier.padding(top = 16.dp)) {
        Text(stringResource(R.string.debug_simulate_pipe_silent), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.debug_simulate_pipe_silent_hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = { onIntent(SettingsIntent.SimulatePipeSilent) },
            modifier = Modifier.fillMaxWidth().lernetButton(),
        ) {
            Text(stringResource(R.string.debug_simulate_pipe_silent))
        }
    }
}

/** Audit S8: version and core with a visible «Скопировать», no hidden triple tap. */
@Composable
private fun AboutRows(state: SettingsUiState, snackbar: SnackbarHostState) {
    val clipboard = LocalClipboardManager.current
    val copied = stringResource(R.string.settings_copied)
    val scope = rememberCoroutineScope()
    val text = "LerNET ${state.appVersion} · ${state.engineVersion}"
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_version_row) + " " + state.appVersion, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.settings_core_row) + " " + state.engineVersion, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(
            onClick = {
                clipboard.setText(AnnotatedString(text))
                scope.launch { snackbar.showSnackbar(copied) }
            },
            modifier = Modifier.heightIn(min = 48.dp).testTag("settings_copy_version"),
        ) { Text(stringResource(R.string.settings_copy)) }
    }
}

@Composable
private fun SettingNumberRow(label: String, value: Int, help: String, onChange: (Int) -> Unit) {
    var draft by remember(label) { mutableStateOf(value.toString()) }
    val commit: () -> Unit = {
        val parsed = draft.toIntOrNull()
        if (parsed == null) {
            draft = value.toString()
        } else if (parsed != value) {
            onChange(parsed)
        }
    }
    LaunchedEffect(value) {
        if (draft.toIntOrNull() != value) draft = value.toString()
    }
    ListItem(
        headlineContent = { Text(label, maxLines = 2) },
        supportingContent = { Text(help, style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            OutlinedTextField(
                value = draft,
                onValueChange = { raw ->
                    if (raw.length <= 6 && raw.all(Char::isDigit)) {
                        draft = raw
                    }
                },
                modifier = Modifier.width(96.dp).onFocusChanged { focus ->
                    if (!focus.isFocused) commit()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                singleLine = true,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
