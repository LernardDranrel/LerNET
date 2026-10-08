package app.lernet.ui.expert

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.routing.policy.PolicyDnsMode
import app.lernet.routing.policy.PolicyDnsSettings
import kotlinx.coroutines.launch

@Composable
internal fun ExpertDnsEditor(initial: PolicyDnsSettings, onDismiss: () -> Unit, onCommit: suspend (PolicyDnsSettings) -> String?) {
    var mode by rememberSaveable(initial) { mutableStateOf(initial.mode) }
    var server by rememberSaveable(initial) { mutableStateOf(initial.server) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val chosen = PolicyDnsSettings(mode, server.trim())
    ExpertFormDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.expert_dns_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.expert_dns_system_hint))
                FilterChip(
                    mode == PolicyDnsMode.SYSTEM,
                    { mode = PolicyDnsMode.SYSTEM },
                    label = { Text(stringResource(R.string.expert_dns_system)) },
                    enabled = !busy,
                )
                FilterChip(
                    mode == PolicyDnsMode.CUSTOM,
                    { mode = PolicyDnsMode.CUSTOM },
                    label = { Text(stringResource(R.string.expert_dns_custom)) },
                    enabled = !busy,
                )
                if (mode == PolicyDnsMode.CUSTOM) {
                    OutlinedTextField(
                        server, { server = it }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.expert_dns_server)) }, singleLine = true,
                        isError = !chosen.isValid(), enabled = !busy
                    )
                    Text(stringResource(R.string.expert_dns_custom_hint))
                }
                Text(stringResource(R.string.expert_dns_scope_hint))
                Text(stringResource(R.string.expert_dns_apply_hint))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                busy = true
                scope.launch {
                    try {
                        error = onCommit(chosen)
                        if (error == null) onDismiss()
                    } finally {
                        busy = false
                    }
                }
            }, enabled = chosen.isValid() && !busy) { Text(stringResource(R.string.expert_node_accept)) }
        },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}
