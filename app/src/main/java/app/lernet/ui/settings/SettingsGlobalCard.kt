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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.config.model.DnsPolicy
import app.lernet.engine.compile.EngineDefaults
import app.lernet.engine.log.JournalCeiling
import app.lernet.ui.components.PanelCard

@Composable
internal fun GlobalDefaultsCard(state: SettingsUiState, onIntent: (SettingsIntent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.settings_global), style = MaterialTheme.typography.titleMedium)
        SettingHelp(stringResource(R.string.settings_defaults_scope))
    }
    DnsDefaultsCard(state, onIntent)
    XmuxDefaultsCard(state, onIntent)
    MtuDefaultsCard(state, onIntent)
    JournalDefaultsCard(state, onIntent)
}

@Composable
private fun DnsDefaultsCard(state: SettingsUiState, onIntent: (SettingsIntent) -> Unit) {
    val saved = state.settings.engineDefaults.directDnsServer
    var draft by remember(saved) { mutableStateOf(saved) }
    PanelCard {
        Text(stringResource(R.string.settings_dns_section), style = MaterialTheme.typography.titleMedium)
        SettingHelp(stringResource(R.string.settings_dns_explained))
        Text(stringResource(R.string.settings_dns_default), style = MaterialTheme.typography.labelLarge)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(DnsPolicy.UNDERLAY to R.string.cfg_keep_system, DnsPolicy.PROFILE to R.string.cfg_use_profile).forEach { (policy, label) ->
                val selected = state.settings.defaultDnsPolicy == policy
                val scheme = MaterialTheme.colorScheme
                Surface(
                    selected = selected,
                    onClick = { onIntent(SettingsIntent.SetDefaultDnsPolicy(policy)) },
                    modifier = Modifier.fillMaxWidth().semantics { role = Role.RadioButton },
                    shape = MaterialTheme.shapes.medium,
                    color = if (selected) scheme.primaryContainer else scheme.surface,
                    border = BorderStroke(1.dp, if (selected) scheme.primary else scheme.outlineVariant),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        if (selected) Text(stringResource(R.string.home_mode_selected), style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                    }
                }
            }
        }
        SettingHelp(stringResource(R.string.settings_dns_default_help))
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
        FilledTonalButton(
            onClick = { onIntent(SettingsIntent.SetDirectDnsServer(draft)) },
            enabled = draft != saved && EngineDefaults.validIpv4(draft),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_journal_apply)) }
    }
}

@Composable
private fun XmuxDefaultsCard(state: SettingsUiState, onIntent: (SettingsIntent) -> Unit) {
    val bounds = state.settings.engineDefaults.xmuxConcurrency.split('-')
    var expanded by remember { mutableStateOf(false) }
    PanelCard {
        Text(stringResource(R.string.settings_xmux_default), style = MaterialTheme.typography.titleMedium)
        SettingHelp(stringResource(R.string.settings_xmux_explained))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            XmuxBound(bounds.firstOrNull().orEmpty(), stringResource(R.string.settings_xmux_lower), Modifier.weight(1f))
            XmuxBound(bounds.lastOrNull().orEmpty(), stringResource(R.string.settings_xmux_upper), Modifier.weight(1f))
        }
        Box {
            FilledTonalButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_xmux_choose))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                listOf("1-1", "8-8", "16-16", "32-32").forEach { value ->
                    DropdownMenuItem(text = { Text(value) }, onClick = {
                        expanded = false
                        onIntent(SettingsIntent.SetXmuxConcurrency(value))
                    })
                }
            }
        }
        SettingHelp(stringResource(R.string.settings_xmux_tuning))
        SettingHelp(stringResource(R.string.settings_xmux_default_help))
    }
}

@Composable
private fun XmuxBound(value: String, label: String, modifier: Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun MtuDefaultsCard(state: SettingsUiState, onIntent: (SettingsIntent) -> Unit) {
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
        FilledTonalButton(
            onClick = { draft.toIntOrNull()?.let { onIntent(SettingsIntent.SetTunMtu(it)) } },
            enabled = valid && draft.toIntOrNull() != saved,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_journal_apply)) }
    }
}

@Composable
private fun JournalDefaultsCard(state: SettingsUiState, onIntent: (SettingsIntent) -> Unit) {
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
        ) { Text(stringResource(R.string.settings_journal_apply)) }
    }
    val pending = state.pendingJournalMb
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { onIntent(SettingsIntent.DismissJournal) },
            title = { Text(stringResource(R.string.settings_journal_confirm_title)) },
            text = { Text(stringResource(R.string.settings_journal_confirm_body, JournalCeiling.mb(pending))) },
            confirmButton = {
                TextButton(onClick = { onIntent(SettingsIntent.ConfirmJournal) }) { Text(stringResource(R.string.settings_journal_apply)) }
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
