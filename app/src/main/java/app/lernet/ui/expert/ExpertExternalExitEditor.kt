package app.lernet.ui.expert

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.lernet.R
import app.lernet.config.policy.ExternalExitKind
import app.lernet.config.policy.ExternalExitProfiles
import app.lernet.config.policy.ExternalExitRequest
import app.lernet.config.transfer.TransferProfile
import app.lernet.routing.RoutePlatform
import kotlinx.coroutines.launch

@Composable
internal fun ExpertExternalExitEditor(
    existing: TransferProfile?,
    onDismiss: () -> Unit,
    onSave: suspend (ExternalExitRequest, TransferProfile?) -> String?,
) {
    val initial = remember(existing) { existing?.let(ExternalExitProfiles::describe) }
    var kind by remember(existing) { mutableStateOf(initial?.kind ?: ExternalExitKind.SOCKS5) }
    var name by remember(existing) { mutableStateOf(initial?.name.orEmpty()) }
    var host by remember(existing) { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember(existing) { mutableStateOf(initial?.port?.takeIf { it > 0 }?.toString() ?: "1080") }
    var authenticated by remember(existing) { mutableStateOf(initial?.username?.isNotEmpty() == true) }
    var username by remember(existing) { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember(existing) { mutableStateOf(initial?.password.orEmpty()) }
    var tls by remember(existing) { mutableStateOf(initial?.tls == true) }
    var busy by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val request = ExternalExitRequest(
        kind = kind, name = name, host = host.trim(), port = port.toIntOrNull() ?: 0,
        username = if (authenticated) username else "", password = if (authenticated) password else "",
        tls = kind == ExternalExitKind.HTTP && tls,
    )
    val errors = ExternalExitProfiles.validate(request)
    ExpertFormDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (existing == null) R.string.expert_external_add else R.string.expert_external_edit)) },
        text = {
            Column(
                Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ExpertHint(R.string.expert_external_hint)
                if (existing != null) {
                    Text(
                        stringResource(
                            if (kind == ExternalExitKind.SOCKS5) {
                                R.string.expert_external_socks
                            } else {
                                R.string.expert_external_http
                            }
                        ),
                        style = MaterialTheme.typography.titleSmall
                    )
                    ExpertHint(R.string.expert_external_transport_fixed)
                } else {
                    listOf(ExternalExitKind.SOCKS5, ExternalExitKind.HTTP).forEach { choice ->
                        FilterChip(
                            selected = kind == choice,
                            onClick = {
                                kind = choice
                                saveError = null
                            }, enabled = !busy,
                            label = {
                                Text(
                                    stringResource(
                                        if (choice == ExternalExitKind.SOCKS5) {
                                            R.string.expert_external_socks
                                        } else {
                                            R.string.expert_external_http
                                        }
                                    )
                                )
                            },
                        )
                    }
                }
                OutlinedTextField(
                    name, {
                        name = it
                        saveError = null
                    }, label = { Text(stringResource(R.string.expert_external_name)) },
                    enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    host, {
                        host = it
                        saveError = null
                    }, label = { Text(stringResource(R.string.expert_external_host)) },
                    enabled = !busy, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    port, {
                        port = it
                        saveError = null
                    }, label = { Text(stringResource(R.string.expert_external_port)) },
                    enabled = !busy, singleLine = true, isError = port.toIntOrNull()?.let { it in 1..65535 } != true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()
                )
                if (kind == ExternalExitKind.HTTP) {
                    ExpertCheckRow(R.string.expert_external_tls, tls) { if (!busy) tls = it }
                    ExpertHint(R.string.expert_external_http_hint)
                }
                ExpertCheckRow(R.string.expert_external_auth, authenticated) { if (!busy) authenticated = it }
                if (authenticated) {
                    OutlinedTextField(
                        username, {
                            username = it
                            saveError = null
                        },
                        label = { Text(stringResource(R.string.expert_external_username)) }, enabled = !busy, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        password, {
                            password = it
                            saveError = null
                        },
                        label = { Text(stringResource(R.string.expert_external_password)) }, enabled = !busy, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()
                    )
                }
                if (name.isNotBlank() && host.isNotBlank()) errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                ExpertHint(R.string.expert_external_saved_hint)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            saveError = onSave(request, existing)
                            if (saveError == null) onDismiss()
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && errors.isEmpty() && kind != ExternalExitKind.CORPORATE_INTERFACE,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF275C47), contentColor = Color.White),
            ) { Text(stringResource(if (busy) R.string.expert_external_saving else R.string.expert_node_accept)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Complex forms use bounded regular measurement, without AlertDialog text-slot intrinsic sizing. */
@Composable
internal fun ExpertFormDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 720.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
                Box(Modifier.weight(1f, fill = false).fillMaxWidth()) { text() }
                confirmButton()
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { dismissButton() }
            }
        }
    }
}

@Composable
internal fun ExpertExternalExitDetails(profile: TransferProfile, onDismiss: () -> Unit, onEdit: (() -> Unit)?) {
    val description = remember(profile) { ExternalExitProfiles.describe(profile) }
    val corporate = remember(profile) {
        runCatching { ExternalExitProfiles.platformRequirement(profile) == RoutePlatform.WINDOWS }.getOrDefault(false)
    }
    val binding = remember(profile) { runCatching { ExternalExitProfiles.binding(profile) }.getOrNull() }
    if (description == null && !corporate) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(profile.name) },
        text = {
            Column(
                Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (corporate) {
                    ExpertHint(R.string.expert_external_corporate_unavailable)
                    binding?.let { Text(it.name, style = MaterialTheme.typography.titleSmall) }
                } else {
                    val description = requireNotNull(description)
                    Text(
                        stringResource(
                            if (description.kind == ExternalExitKind.SOCKS5) {
                                R.string.expert_external_socks
                            } else {
                                R.string.expert_external_http
                            }
                        )
                    )
                    Text("${description.host}:${description.port}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(
                            if (description.username.isEmpty()) {
                                R.string.expert_external_no_auth
                            } else {
                                R.string.expert_external_has_auth
                            }
                        )
                    )
                    if (description.tls) Text(stringResource(R.string.expert_external_tls))
                    if (description.kind == ExternalExitKind.HTTP) ExpertHint(R.string.expert_external_http_hint)
                }
                ExpertHint(R.string.expert_external_saved_hint)
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = {
            if (!corporate && onEdit != null) TextButton(onEdit) { Text(stringResource(R.string.expert_external_edit)) }
        },
    )
}
