package app.lernet.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.config.model.DnsPolicy
import app.lernet.config.net.LocalProxyAccess
import app.lernet.engine.compile.EngineDefaults
import app.lernet.engine.log.JournalCeiling
import app.lernet.ui.components.PanelCard

@Composable
internal fun DnsDefaultsCard(state: SettingsUiState, onIntent: (SettingsIntent) -> Unit) {
    val saved = state.settings.engineDefaults.directDnsServer
    var draft by remember(saved) { mutableStateOf(saved) }
    PanelCard {
        Text(stringResource(R.string.settings_dns_section), style = MaterialTheme.typography.titleMedium)
        SettingHelp(stringResource(R.string.settings_dns_explained))
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.trim().take(45) },
            label = { Text(stringResource(R.string.settings_direct_dns)) },
            placeholder = { Text("1.1.1.1") },
            singleLine = true,
            isError = draft.isNotEmpty() && !EngineDefaults.validIpv4(draft),
            supportingText = { Text(stringResource(R.string.settings_direct_dns_help)) },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        SettingHelp(stringResource(R.string.settings_dns_multiple_help))
    }
    // Audit S5: applied by itself once the address is valid.
    LaunchedEffect(draft) {
        kotlinx.coroutines.delay(700)
        if (draft != saved && EngineDefaults.validIpv4(draft)) onIntent(SettingsIntent.SetDirectDnsServer(draft))
    }
}

@Composable
internal fun XmuxDefaultsCard(state: SettingsUiState, onIntent: (SettingsIntent) -> Unit) {
    val single = state.settings.engineDefaults.xmuxChannels == app.lernet.engine.compile.XhttpMode.SINGLE_CHANNEL
    PanelCard {
        Text(stringResource(R.string.settings_xmux_default), style = MaterialTheme.typography.titleMedium)
        SettingHelp(stringResource(R.string.settings_xmux_explained))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_xmux_single), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            androidx.compose.material3.Switch(
                checked = single,
                onCheckedChange = { onIntent(SettingsIntent.SetXmuxSingleChannel(it)) },
                modifier = Modifier.testTag("settings_xmux_single"),
            )
        }
        SettingHelp(stringResource(R.string.settings_xmux_tuning))
        SettingHelp(stringResource(R.string.settings_xmux_default_help))
    }
}

@Composable
internal fun MtuDefaultsCard(state: SettingsUiState, onIntent: (SettingsIntent) -> Unit) {
    val saved = state.settings.engineDefaults.tunMtu
    var draft by remember(saved) { mutableStateOf(saved.toString()) }
    val valid = draft.toIntOrNull()?.let { it in 1280..9000 } == true
    PanelCard {
        Text(stringResource(R.string.settings_mtu_title), style = MaterialTheme.typography.titleMedium)
        SettingHelp(stringResource(R.string.settings_mtu_help))
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.filter(Char::isDigit).take(4) },
            label = { Text(stringResource(R.string.cfg_tun_mtu)) },
            suffix = { Text(stringResource(R.string.settings_bytes)) },
            singleLine = true,
            isError = draft.isNotEmpty() && !valid,
            supportingText = { Text(stringResource(R.string.settings_mtu_range)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        SettingHelp(stringResource(R.string.settings_mtu_tuning))
    }
    LaunchedEffect(draft) {
        kotlinx.coroutines.delay(700)
        if (valid && draft.toIntOrNull() != saved) draft.toIntOrNull()?.let { onIntent(SettingsIntent.SetTunMtu(it)) }
    }
}

/** Audit S2: login and password of the local proxy live in the «Режим» row and only when «Прокси» is chosen. */
@Composable
internal fun ProxyCredentialsFields(state: SettingsUiState, onIntent: (SettingsIntent) -> Unit) {
    val savedUser = state.settings.proxyUsername
    val savedPassword = state.settings.proxyPassword
    var user by remember(savedUser) { mutableStateOf(savedUser) }
    var password by remember(savedPassword) { mutableStateOf(savedPassword) }
    val clearing = user.isBlank() && password.isEmpty()
    val valid = clearing || (LocalProxyAccess.valid(user.trim()) && LocalProxyAccess.valid(password))
    Column(Modifier.testTag("settings_proxy_credentials"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.settings_proxy_access), style = MaterialTheme.typography.labelLarge)
        SettingHelp(stringResource(R.string.settings_proxy_password_help))
        OutlinedTextField(
            value = user,
            onValueChange = { user = it.take(64) },
            label = { Text(stringResource(R.string.proxy_login)) },
            singleLine = true,
            isError = !valid && user.isNotEmpty() && !LocalProxyAccess.valid(user.trim()),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it.take(64) },
            label = { Text(stringResource(R.string.proxy_password)) },
            singleLine = true,
            isError = !valid && password.isNotEmpty() && !LocalProxyAccess.valid(password),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        SettingHelp(stringResource(R.string.settings_proxy_password_rules))
    }
    LaunchedEffect(user, password) {
        kotlinx.coroutines.delay(900)
        if (valid && (user.trim() != savedUser || password != savedPassword)) onIntent(SettingsIntent.SetProxyCredentials(user, password))
    }
}

@Composable
internal fun JournalDefaultsCard(state: SettingsUiState, onIntent: (SettingsIntent) -> Unit) {
    val saved = state.settings.journalMaxMb
    var draft by remember(saved) { mutableStateOf(saved.toString()) }
    PanelCard {
        Text(stringResource(R.string.settings_journal), style = MaterialTheme.typography.titleMedium)
        SettingHelp(stringResource(R.string.settings_journal_help))
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.filter(Char::isDigit).take(3) },
            label = { Text(stringResource(R.string.settings_journal_limit)) },
            suffix = { Text(stringResource(R.string.settings_megabytes)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        FilledTonalButton(
            onClick = { draft.toIntOrNull()?.let { onIntent(SettingsIntent.ProposeJournal(it)) } },
            enabled = draft.toIntOrNull() != null && draft.toIntOrNull() != saved,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.apply)) }
        LogLevelRows(state, onIntent)
    }
    val pending = state.pendingJournalMb
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { onIntent(SettingsIntent.DismissJournal) },
            title = { Text(stringResource(R.string.settings_journal_confirm_title)) },
            text = { Text(stringResource(R.string.settings_journal_confirm_body, JournalCeiling.mb(pending))) },
            confirmButton = {
                TextButton(onClick = { onIntent(SettingsIntent.ConfirmJournal) }) { Text(stringResource(R.string.apply)) }
            },
            dismissButton = {
                TextButton(onClick = { onIntent(SettingsIntent.DismissJournal) }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SettingHelp(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Opt-in handshake tweaks for networks that drop or filter the first TLS message. */
@Composable
internal fun TlsHandshakeCard(state: SettingsUiState, onIntent: (SettingsIntent) -> Unit) {
    val defaults = state.settings.engineDefaults
    val keyShare = app.lernet.engine.compile.RealityKeyShare.fromStorage(defaults.realityKeyShare)
    PanelCard {
        Text(stringResource(R.string.settings_tls_title), style = MaterialTheme.typography.titleMedium)
        SettingHelp(stringResource(R.string.settings_tls_help))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_tls_fragment), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            androidx.compose.material3.Switch(
                checked = defaults.tlsRecordFragment,
                onCheckedChange = { onIntent(SettingsIntent.SetTlsRecordFragment(it)) },
                modifier = Modifier.testTag("settings_tls_fragment"),
            )
        }
        SettingHelp(stringResource(R.string.settings_tls_fragment_help))
        Text(stringResource(R.string.settings_reality_key_share), style = MaterialTheme.typography.labelLarge)
        // Three chips do not fit a 390 dp phone in one row: «Короткий» broke into «Коро/ткий». They wrap instead.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                app.lernet.engine.compile.RealityKeyShare.AUTO to R.string.settings_key_share_auto,
                app.lernet.engine.compile.RealityKeyShare.HYBRID to R.string.settings_key_share_hybrid,
                app.lernet.engine.compile.RealityKeyShare.CLASSICAL to R.string.settings_key_share_classical,
            ).forEach { (value, label) ->
                androidx.compose.material3.FilterChip(
                    selected = keyShare == value,
                    onClick = { onIntent(SettingsIntent.SetRealityKeyShare(value)) },
                    label = { Text(stringResource(label)) },
                )
            }
        }
        SettingHelp(stringResource(R.string.settings_key_share_help))
    }
}
