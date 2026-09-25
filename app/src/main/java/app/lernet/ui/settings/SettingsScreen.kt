package app.lernet.ui.settings

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.lernet.BuildConfig
import app.lernet.R
import app.lernet.ui.components.PanelCard
import app.lernet.ui.icons.LerNetSymbols
import app.lernet.ui.layout.rememberCompactMetrics
import app.lernet.ui.theme.LerNetDimens
import app.lernet.ui.theme.lernetButton
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onIntent: (SettingsIntent) -> Unit,
    onBack: () -> Unit,
    onShareLogs: () -> Unit,
    onOpenDiag: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val metrics = rememberCompactMetrics()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(LerNetSymbols.arrowBack(), contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        SettingsColumn(
            state,
            onIntent,
            snackbar,
            onShareLogs,
            onOpenDiag,
            modifier = Modifier.padding(padding).padding(metrics.gutter),
        )
    }
}

@Composable
private fun SettingsColumn(
    state: SettingsUiState,
    onIntent: (SettingsIntent) -> Unit,
    snackbar: SnackbarHostState,
    onShareLogs: () -> Unit,
    onOpenDiag: () -> Unit,
    modifier: Modifier,
) {
    val reconnect = state.settings.reconnect
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(LerNetDimens.sectionGap),
    ) {
        GlobalDefaultsCard(state, onIntent)
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
        LogsCard(onShareLogs, onOpenDiag)
        PanelCard {
            Text(stringResource(R.string.log_level), style = MaterialTheme.typography.titleMedium)
            listOf("warn" to R.string.log_warn, "info" to R.string.log_info, "debug" to R.string.log_debug).forEach { (level, label) ->
                FilterChip(
                    selected = state.settings.logLevel == level,
                    onClick = { onIntent(SettingsIntent.SetLogLevel(level)) },
                    label = { Text(stringResource(label)) },
                    modifier = Modifier.lernetButton(),
                )
            }
            Text(stringResource(R.string.log_redact_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DebugPipeCard(onIntent)
        AboutCard(state, snackbar)
    }
}

@Composable
private fun DebugPipeCard(onIntent: (SettingsIntent) -> Unit) {
    if (!BuildConfig.DEBUG) return
    PanelCard {
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

@Composable
private fun LogsCard(onShareLogs: () -> Unit, onOpenDiag: () -> Unit) {
    PanelCard {
        Text(stringResource(R.string.settings_logs), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.share_logs_hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onOpenDiag, modifier = Modifier.fillMaxWidth().lernetButton()) {
            Text(stringResource(R.string.diag_title))
        }
        OutlinedButton(onClick = onShareLogs, modifier = Modifier.fillMaxWidth().lernetButton()) {
            Text(stringResource(R.string.share_last_log))
        }
    }
}

@Composable
private fun AboutCard(state: SettingsUiState, snackbar: SnackbarHostState) {
    val clipboard = LocalClipboardManager.current
    val copied = stringResource(R.string.version_copied)
    val scope = rememberCoroutineScope()
    var taps by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }
    Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium)
    ListItem(
        headlineContent = { Text(stringResource(R.string.app_version, state.appVersion)) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.libbox_version, state.engineVersion)) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clickable {
            val now = SystemClock.uptimeMillis()
            taps = if (now - lastTap < 500L) taps + 1 else 1
            lastTap = now
            if (taps >= 3) {
                clipboard.setText(AnnotatedString(state.engineVersion))
                taps = 0
                scope.launch { snackbar.showSnackbar(copied) }
            }
        },
    )
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
