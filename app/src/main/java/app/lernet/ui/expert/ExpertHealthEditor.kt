package app.lernet.ui.expert

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.routing.policy.PolicyHealthSettings
import kotlinx.coroutines.launch

@Composable
internal fun ExpertHealthEditor(
    initial: PolicyHealthSettings,
    onDismiss: () -> Unit,
    onCommit: suspend (PolicyHealthSettings) -> String?,
) {
    var minimum by remember(initial) { mutableStateOf(expertSecondsInput(initial.minimumIntervalMs)) }
    var maximum by remember(initial) { mutableStateOf(expertSecondsInput(initial.maximumIntervalMs)) }
    var timeout by remember(initial) { mutableStateOf(expertSecondsInput(initial.activeTimeoutMs)) }
    var failures by remember(initial) { mutableStateOf(initial.failedChecksBeforeRecovery.toString()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val next = expertHealthAfterEdit(minimum, maximum, timeout, failures)
    ExpertFormDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.expert_health_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ExpertHint(R.string.expert_health_hint)
                OutlinedTextField(
                    minimum, { minimum = it }, Modifier.fillMaxWidth(),
                    enabled = !busy,
                    label = { Text(stringResource(R.string.expert_health_minimum)) },
                )
                OutlinedTextField(
                    maximum, { maximum = it }, Modifier.fillMaxWidth(),
                    enabled = !busy,
                    label = { Text(stringResource(R.string.expert_health_maximum)) },
                )
                OutlinedTextField(
                    timeout, { timeout = it }, Modifier.fillMaxWidth(),
                    enabled = !busy,
                    label = { Text(stringResource(R.string.expert_health_timeout)) },
                )
                OutlinedTextField(
                    failures, { failures = it }, Modifier.fillMaxWidth(),
                    enabled = !busy,
                    label = { Text(stringResource(R.string.expert_health_failures)) },
                )
                if (next == null) {
                    Text(
                        stringResource(R.string.expert_health_invalid),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val requested = requireNotNull(next)
                    busy = true
                    error = null
                    scope.launch {
                        error = onCommit(requested)
                        busy = false
                        if (error == null) onDismiss()
                    }
                },
                enabled = next != null && !busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
            ) {
                Text(stringResource(if (busy) R.string.expert_external_saving else R.string.expert_node_accept))
            }
        },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}

internal fun expertHealthAfterEdit(
    minimum: String,
    maximum: String,
    timeout: String,
    failures: String,
): PolicyHealthSettings? {
    val settings = PolicyHealthSettings(
        expertIdleMilliseconds(minimum) ?: return null,
        expertIdleMilliseconds(maximum) ?: return null,
        expertIdleMilliseconds(timeout) ?: return null,
        failures.toIntOrNull() ?: return null,
    )
    return settings.takeIf { it.isValid() }
}
